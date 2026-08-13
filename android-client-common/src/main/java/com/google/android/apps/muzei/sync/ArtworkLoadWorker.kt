/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.sync

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.BaseColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.internal.ProtocolConstants
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD
import com.google.android.apps.muzei.api.internal.getRecentIds
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.render.isValidImage
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.ImageMetadata
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.getLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Worker responsible for loading artwork from a [MuzeiArtProvider] and inserting it into
 * the [MuzeiDatabase].
 */
class ArtworkLoadWorker(
        context: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ArtworkLoad"
        private const val PERIODIC_TAG = "ArtworkLoadPeriodic"
        private const val ARTWORK_LOAD_THROTTLE = 250L // quarter second
        private const val KEY_TARGET_IMAGE_URI = "target_image_uri"

        internal fun enqueueNext(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ArtworkLoadWorker>().build())
        }

        /**
         * Load one specific image (identified by its `content://<authority>/<id>` [imageUri])
         * and make it the current artwork. Used by the favorites-boost feature to surface a
         * chosen favorite even when it is not in the recent-history window. If the image can no
         * longer be loaded (deleted, invalid, provider error) this falls back to a normal load.
         */
        internal fun enqueueFavorite(context: Context, imageUri: Uri) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ArtworkLoadWorker>()
                            .setInputData(Data.Builder()
                                    .putString(KEY_TARGET_IMAGE_URI, imageUri.toString())
                                    .build())
                            .build())
        }

        internal fun enqueuePeriodic(
                context: Context,
                loadFrequencySeconds: Long,
                loadOnWifi: Boolean
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(PERIODIC_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    PeriodicWorkRequestBuilder<ArtworkLoadWorker>(
                            loadFrequencySeconds, TimeUnit.SECONDS,
                            loadFrequencySeconds / 10, TimeUnit.SECONDS)
                            .setConstraints(Constraints.Builder()
                                    .setRequiredNetworkType(if (loadOnWifi) {
                                        NetworkType.UNMETERED
                                    } else {
                                        NetworkType.CONNECTED
                                    })
                                    .build())
                            .build())
        }

        fun cancelPeriodic(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_TAG)
        }
    }

    override suspend fun doWork() = withContext(syncSingleThreadContext) {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("NextTiming", "Worker entry")
        // Throttle artwork loads
        delay(ARTWORK_LOAD_THROTTLE)
        Log.d("NextTiming", "Worker post-throttle +${SystemClock.elapsedRealtime() - t0}ms")
        // If this load targets a specific favorite, load that image directly rather than
        // running the normal provider-driven selection.
        inputData.getString(KEY_TARGET_IMAGE_URI)?.let { targetUriString ->
            return@withContext loadTargetArtwork(Uri.parse(targetUriString))
        }
        // Now actually load the artwork
        val database = MuzeiDatabase.getInstance(applicationContext)
        val (authority) = database.providerDao()
                .getCurrentProvider() ?: return@withContext Result.failure()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Artwork Load for $authority")
        }
        val loadOrdering = ProviderManager.getInstance(applicationContext).loadOrdering
        val contentUri = ProviderContract.getContentUri(authority)
        try {
            ContentProviderClientCompat.getClient(applicationContext, contentUri)?.use { client ->
                Log.d("NextTiming", "Worker getLoadInfo +${SystemClock.elapsedRealtime() - t0}ms")
                val result = client.call(METHOD_GET_LOAD_INFO)
                        ?: return@withContext Result.failure()
                Log.d("NextTiming", "Worker getLoadInfo done +${SystemClock.elapsedRealtime() - t0}ms")
                val maxLoadedArtworkId = result.getLong(KEY_MAX_LOADED_ARTWORK_ID, 0L)
                val recentArtworkIds = result.getRecentIds(KEY_RECENT_ARTWORK_IDS)
                val startingArtworkId = when (loadOrdering) {
                    // IN_ORDER means we always start with the last artwork we loaded
                    ProviderManager.LoadOrdering.IN_ORDER ->recentArtworkIds.lastOrNull() ?: maxLoadedArtworkId
                    // NEW_IN_ORDER means that we load new artwork starting with the max loaded
                    ProviderManager.LoadOrdering.NEW_IN_ORDER -> maxLoadedArtworkId
                    // RANDOM means we never care about new artwork
                    ProviderManager.LoadOrdering.RANDOM -> Int.MAX_VALUE
                }
                Log.d("NextTiming", "Worker query-new +${SystemClock.elapsedRealtime() - t0}ms")
                client.query(
                        contentUri,
                        selection = "_id > ?",
                        selectionArgs = arrayOf(startingArtworkId.toString()),
                        sortOrder = ProviderContract.Artwork._ID
                )?.use { newArtwork ->
                    Log.d("NextTiming", "Worker query-new done (${newArtwork.count}) +${SystemClock.elapsedRealtime() - t0}ms")
                    Log.d("NextTiming", "Worker query-all +${SystemClock.elapsedRealtime() - t0}ms")
                    client.query(
                        contentUri,
                        sortOrder = ProviderContract.Artwork._ID
                    )?.use { allArtwork ->
                        Log.d("NextTiming", "Worker query-all done (${allArtwork.count}) +${SystemClock.elapsedRealtime() - t0}ms")
                        // First prioritize new artwork
                        while (newArtwork.moveToNext()) {
                            Log.d("NextTiming", "Worker checkValid ${newArtwork.position}/${newArtwork.count} +${SystemClock.elapsedRealtime() - t0}ms")
                            val validArtwork = checkForValidArtwork(client, contentUri, newArtwork)
                            Log.d("NextTiming", "Worker checkValid done ${if (validArtwork != null) "OK" else "skip"} +${SystemClock.elapsedRealtime() - t0}ms")
                            if (validArtwork != null) {
                                validArtwork.providerAuthority = authority
                                val artworkId = database.artworkDao().insert(validArtwork)
                                database.imageMetadataDao().ensureRow(
                                        ImageMetadata(validArtwork.imageUri, authority))
                                Log.d("NextTiming", "Worker inserted artwork ${validArtwork.imageUri.lastPathSegment} +${SystemClock.elapsedRealtime() - t0}ms")
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "Loaded ${validArtwork.imageUri} into id $artworkId")
                                }
                                client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString())
                                // If we just loaded the last new artwork, we should request that they load another
                                // in preparation for the next load
                                if (!newArtwork.moveToNext()) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Out of new artwork, requesting load from $authority")
                                    }
                                    client.call(METHOD_REQUEST_LOAD)
                                }
                                return@withContext Result.success()
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            if (loadOrdering == ProviderManager.LoadOrdering.RANDOM) {
                                Log.d(TAG, "Loading in random order, requesting load from $authority")
                            } else {
                                Log.d(TAG, "Could not find any new artwork, requesting load from $authority")
                            }
                        }
                        // No new artwork, request that they load another in preparation for the next load
                        Log.d("NextTiming", "Worker requestLoad +${SystemClock.elapsedRealtime() - t0}ms")
                        client.call(METHOD_REQUEST_LOAD)
                        Log.d("NextTiming", "Worker requestLoad done +${SystemClock.elapsedRealtime() - t0}ms")
                        // Is there any artwork at all?
                        if (allArtwork.count == 0) {
                            Log.w(TAG, "Unable to find any artwork for $authority")
                            return@withContext Result.failure()
                        }
                        // Okay so there's at least some artwork.
                        // Is it just the one artwork we're already showing?
                        val currentArtwork = database.artworkDao().getCurrentArtwork()
                        if (allArtwork.count == 1 && allArtwork.moveToFirst()) {
                            val artworkId = allArtwork.getLong(BaseColumns._ID)
                            val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
                            if (artworkUri == currentArtwork?.imageUri) {
                                if (BuildConfig.DEBUG) {
                                    Log.i(TAG, "Provider $authority only has one artwork")
                                }
                                return@withContext Result.failure()
                            }
                        }
                        // We've loaded every artwork IN_ORDER, so we need to loop back around
                        // to the first artwork again to continue loading in order
                        if (loadOrdering == ProviderManager.LoadOrdering.IN_ORDER) {
                            if (allArtwork.moveToPosition(0)) {
                                checkForValidArtwork(client, contentUri, allArtwork)?.apply {
                                    providerAuthority = authority
                                    val artworkId = database.artworkDao().insert(this)
                                    database.imageMetadataDao().ensureRow(
                                            ImageMetadata(imageUri, authority))
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Loaded $imageUri into id $artworkId")
                                    }
                                    client.call(METHOD_MARK_ARTWORK_LOADED, imageUri.toString())
                                    return@withContext Result.success()
                                }
                            }
                        }
                        // At this point, we know there must be some artwork that isn't the current
                        // artwork. We want to avoid showing artwork we've recently loaded, so
                        // we'll generate two sequences - the first being made up of
                        // non recent artwork, the second being made up of only recent artwork
                        // Build a lambda that checks whether the given position
                        // represents the current artwork
                        val isCurrentArtwork: (position: Int) -> Boolean = { position ->
                            if (allArtwork.moveToPosition(position)) {
                                val artworkId = allArtwork.getLong(BaseColumns._ID)
                                val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
                                artworkUri == currentArtwork?.imageUri
                            } else {
                                false
                            }
                        }
                        // Build a lambda that checks whether the given position
                        // represents an artwork in the recentArtworkIds
                        val isRecentArtwork: (position: Int) -> Boolean = { position ->
                            if (allArtwork.moveToPosition(position)) {
                                val artworkId = allArtwork.getLong(BaseColumns._ID)
                                recentArtworkIds.contains(artworkId)
                            } else {
                                false
                            }
                        }
                        // Now generate a random sequence for non recent artwork
                        val nonRecentArtworkSequence = generateSequence {
                            Random.nextInt(allArtwork.count)
                        }.distinct().take(allArtwork.count)
                            .filterNot(isCurrentArtwork)
                            .filterNot(isRecentArtwork)
                        // Now generate another sequence for recent artwork
                        val recentArtworkSequence = generateSequence {
                            Random.nextInt(allArtwork.count)
                        }.distinct().take(allArtwork.count)
                            .filterNot(isCurrentArtwork)
                            .filter(isRecentArtwork)
                        // And build the final sequence that iterates first through
                        // non recent artwork, then recent artwork
                        val randomSequence = nonRecentArtworkSequence + recentArtworkSequence
                        val iterator = randomSequence.iterator()
                        while (iterator.hasNext()) {
                            val position = iterator.next()
                            if (allArtwork.moveToPosition(position)) {
                                checkForValidArtwork(client, contentUri, allArtwork)?.apply {
                                    providerAuthority = authority
                                    val artworkId = database.artworkDao().insert(this)
                                    database.imageMetadataDao().ensureRow(
                                            ImageMetadata(imageUri, authority))
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Loaded $imageUri into id $artworkId")
                                    }
                                    client.call(METHOD_MARK_ARTWORK_LOADED, imageUri.toString())
                                    return@withContext Result.success()
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "Unable to find any other valid artwork for $authority")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                else -> Log.i(TAG, "Provider $authority crashed while retrieving artwork: ${e.message}")
            }
        }
        Result.retry()
    }

    /**
     * Loads the single artwork identified by [targetUri] from its provider and inserts it as
     * the current artwork. Falls back to a normal [enqueueNext] load if the image can no longer
     * be retrieved (e.g. it was removed from the source or the provider errors out).
     */
    private suspend fun loadTargetArtwork(targetUri: Uri): Result {
        val database = MuzeiDatabase.getInstance(applicationContext)
        val authority = targetUri.authority
        val artworkId = try {
            ContentUris.parseId(targetUri)
        } catch (e: NumberFormatException) {
            -1L
        }
        if (authority != null && artworkId >= 0) {
            val contentUri = ProviderContract.getContentUri(authority)
            try {
                ContentProviderClientCompat.getClient(applicationContext, contentUri)?.use { client ->
                    client.query(
                            contentUri,
                            selection = "${BaseColumns._ID} = ?",
                            selectionArgs = arrayOf(artworkId.toString())
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            checkForValidArtwork(client, contentUri, cursor)?.let { validArtwork ->
                                validArtwork.providerAuthority = authority
                                val id = database.artworkDao().insert(validArtwork)
                                database.imageMetadataDao().ensureRow(
                                        ImageMetadata(validArtwork.imageUri, authority))
                                client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString())
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "Loaded favorite ${validArtwork.imageUri} into id $id")
                                }
                                return Result.success()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    else -> Log.i(TAG, "Provider $authority crashed loading favorite " +
                            "$targetUri: ${e.message}")
                }
            }
        }
        // Couldn't load the requested favorite — fall back to a normal advance so that a "Next"
        // still results in a rotation.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Favorite $targetUri unavailable; falling back to a normal load")
        }
        enqueueNext(applicationContext)
        return Result.success()
    }

    private suspend fun checkForValidArtwork(
            client: ContentProviderClientCompat,
            contentUri: Uri,
            data: Cursor
    ): Artwork? {
        val providerArtwork = com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data)
        val artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.id)
        try {
            client.openInputStream(artworkUri)?.use { inputStream ->
                if (inputStream.isValidImage()) {
                    return Artwork(artworkUri).apply {
                        title = providerArtwork.title
                        byline = providerArtwork.byline
                        attribution = providerArtwork.attribution
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Artwork $artworkUri is not a valid image")
                    }
                    // Tell the client that the artwork is invalid
                    client.call(ProtocolConstants.METHOD_MARK_ARTWORK_INVALID, artworkUri.toString())
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Unable to preload artwork $artworkUri: ${e.message}")
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                else -> Log.i(TAG, "Provider ${contentUri.authority} crashed preloading artwork " +
                        "$artworkUri: ${e.message}")
            }
        }

        return null
    }
}