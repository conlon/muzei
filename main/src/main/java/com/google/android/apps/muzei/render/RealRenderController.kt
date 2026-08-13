/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei.render

import android.content.ContentUris
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.ArtDetailViewport
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.util.collectIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class RealRenderController(
        context: Context,
        renderer: MuzeiBlurRenderer,
        callbacks: Callbacks
) : RenderController(context, renderer, callbacks) {

    /**
     * If there's no artwork yet (as is the case when in Direct Boot), then we
     * use [MuzeiContract.Artwork.CONTENT_URI].
     */
    private var currentArtworkUri = MuzeiContract.Artwork.CONTENT_URI

    /**
     * Debounce job for async auto-framing + GLITCH2 face detection.
     * Cancelled when a new image arrives (so rapid Next taps only run ML Kit for the
     * image the user settles on) and when the lifecycle stops.
     */
    private var autoFrameJob: Job? = null

    /**
     * True once the user has manually panned/zoomed the current image. When set,
     * [launchAutoFrameJob] will not override their framing even after ML Kit finishes.
     * Reset to false whenever [currentArtworkUri] changes (new image).
     */
    @Volatile private var userTouchedViewport = false

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        reloadCurrentArtwork()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // Track user-initiated viewport adjustments so the async auto-frame job doesn't
        // snap back an in-progress manual pan after ML Kit finishes (~5-10s).
        ArtDetailViewport.getChanges().collectIn(owner) { fromUser ->
            if (fromUser) userTouchedViewport = true
        }
        val database = MuzeiDatabase.getInstance(context)
        database.artworkDao().getCurrentArtworkFlow().filterNotNull().collectIn(owner) { artwork ->
            val newUri = artwork.contentUri
            // Only reload (re-bake) when the image itself changes.
            // Mutations like setFavorite / updateSavedViewport / updateDateAdded also
            // re-emit this flow; without this guard they would each trigger a full
            // reload, resetting the viewport and re-running expensive GL work.
            if (newUri == currentArtworkUri) {
                // Same image — refresh the pending saved viewport (fast DB read) in case
                // the user just saved or cleared a manual framing.
                renderer.pendingSavedViewport = savedViewportFor(artwork.imageUri)
                // Re-launch auto-framing if the viewport is unset and the job isn't active,
                // e.g. when returning to this tab after the lifecycle was stopped.
                if (renderer.pendingSavedViewport == null && autoFrameJob?.isActive != true) {
                    autoFrameJob?.cancel()
                    autoFrameJob = owner.lifecycleScope.launch {
                        launchAutoFrameJob(currentArtworkUri, artwork.imageUri)
                    }
                }
                return@collectIn
            }
            val tFlow = SystemClock.elapsedRealtime()
            Log.d("NextTiming", "RC emission ${artwork.imageUri.lastPathSegment}")
            currentArtworkUri = newUri
            userTouchedViewport = false  // new image — auto-framing is allowed again

            // --- Fast path: DB-only viewport, then show the photo immediately ---
            // No ML Kit on the critical path — the photo appears at the saved framing
            // (if the user set one) or at the default centered crop, within milliseconds.
            renderer.pendingSavedViewport = savedViewportFor(artwork.imageUri)
            Log.d("NextTiming", "RC savedViewport +${SystemClock.elapsedRealtime() - tFlow}ms")
            reloadCurrentArtwork()
            Log.d("NextTiming", "RC reloadCurrentArtwork +${SystemClock.elapsedRealtime() - tFlow}ms")

            // --- Async path: cancel any in-flight detection, launch for the new image ---
            // Rapid Next taps cancel the stale job; only the settled image runs ML Kit.
            autoFrameJob?.cancel()
            autoFrameJob = owner.lifecycleScope.launch {
                // ArtDetailViewport.changes has extraBufferCapacity=1: a fromUser=true event
                // from the previous image can sit in the buffer and arrive after the
                // userTouchedViewport=false reset, causing a spurious skipped-touched.
                // yield() gives the changes collector one turn to drain the buffer; the
                // re-reset that follows clears any stale effect before real tracking begins.
                yield()
                userTouchedViewport = false
                launchAutoFrameJob(newUri, artwork.imageUri)
            }
        }

        // Live-apply manual framing saves and clears. saveFraming/clearFraming write to the
        // image_metadata table, which does NOT invalidate the artwork flow above, so without
        // this collector the already-running wallpaper never re-reads a newly saved viewport —
        // it keeps the original crop and appears to "revert" the moment the user leaves the app.
        // We watch the current image's metadata row and push changes straight to the GL thread.
        var trackedUri: Uri? = null
        var appliedViewport: RectF? = null
        database.artworkDao().getCurrentArtworkFlow()
                .filterNotNull()
                .flatMapLatest { artwork ->
                    database.imageMetadataDao().getByImageUriFlow(artwork.imageUri)
                            .map { meta -> artwork to meta }
                }
                .collectIn(owner) { (artwork, meta) ->
                    // Only react to the image on screen; new-image loads are baked above.
                    if (artwork.contentUri != currentArtworkUri) return@collectIn
                    val vp = meta?.takeIf { it.hasSavedViewport }?.let {
                        RectF(it.savedViewportLeft!!, it.savedViewportTop!!,
                                it.savedViewportRight!!, it.savedViewportBottom!!)
                    }
                    // The first emission for an image is just the load-time state the artwork
                    // flow already handled — record it as the baseline and don't re-apply.
                    if (trackedUri != artwork.contentUri) {
                        trackedUri = artwork.contentUri
                        appliedViewport = vp
                        return@collectIn
                    }
                    if (vp == appliedViewport) return@collectIn  // no change
                    appliedViewport = vp
                    renderer.pendingSavedViewport = vp
                    if (vp != null) {
                        // Newly saved framing — apply it to the live picture set immediately.
                        queueEventOnGlThread { renderer.applyAutoFramedViewport(vp) }
                    } else {
                        // Framing was cleared — re-bake at the default crop and let auto-framing
                        // run again so the live wallpaper reverts instead of freezing the crop.
                        reloadCurrentArtwork()
                        autoFrameJob?.cancel()
                        autoFrameJob = owner.lifecycleScope.launch {
                            launchAutoFrameJob(currentArtworkUri, artwork.imageUri)
                        }
                    }
                }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        autoFrameJob?.cancel()
        autoFrameJob = null
    }

    /**
     * Fast DB-only viewport lookup. Returns the user's saved framing for [imageUri], or null
     * if none exists. Never runs ML Kit — auto-framing is handled by [launchAutoFrameJob].
     */
    private suspend fun savedViewportFor(imageUri: Uri): RectF? {
        val meta = MuzeiDatabase.getInstance(context).imageMetadataDao().getByImageUri(imageUri)
        if (meta != null && meta.hasSavedViewport) {
            return RectF(meta.savedViewportLeft!!, meta.savedViewportTop!!,
                    meta.savedViewportRight!!, meta.savedViewportBottom!!)
        }
        return null
    }

    /**
     * Async auto-framing + GLITCH2 face detection for [capturedContentUri] / [imageUri].
     *
     * Runs entirely off the critical path: the photo is already visible at its saved or
     * default viewport before this job starts. On completion, applies the auto-framed
     * viewport and/or face regions to the current picture set on the GL thread — only
     * when [currentArtworkUri] still matches [capturedContentUri] (stale-image guard).
     *
     * Face regions feed [MuzeiBlurRenderer.GLPictureSet.bakeDeferredEffects], which fires
     * when the renderer transitions from sharp (Art Detail open) to blurred (wallpaper
     * visible). Since the browse tab is always sharp, regions are guaranteed to be in place
     * before the bake runs.
     */
    private suspend fun launchAutoFrameJob(capturedContentUri: Uri, imageUri: Uri) {
        // Bail early if the user has already moved to a different image.
        if (currentArtworkUri != capturedContentUri) return

        val autoFramingEnabled = Prefs.getSharedPreferences(context)
                .getBoolean(Prefs.PREF_AUTO_FRAMING, Prefs.DEFAULT_AUTO_FRAMING)
        val screenAspectRatio = renderer.getAspectRatio()
        // Don't override a viewport the user manually saved — saved framing always wins.
        val hasSavedViewport = savedViewportFor(imageUri) != null
        val needsAutoFrame = autoFramingEnabled && screenAspectRatio > 0f && !hasSavedViewport
        val needsFaces = renderer.wantsSubjectRegions()

        if (!needsAutoFrame && !needsFaces) return

        val viewport: RectF?
        val faceRegions: List<RectF>?

        when {
            needsAutoFrame && needsFaces -> {
                // Combined path: auto-framing viewport + GLITCH2 face bias in one decode.
                val (vp, faces) = AutoFramingEngine.computeFramingAndFaceRegions(
                        context.contentResolver, imageUri, screenAspectRatio)
                viewport = vp
                faceRegions = faces
            }
            needsAutoFrame -> {
                viewport = AutoFramingEngine.computeFraming(
                        context.contentResolver, imageUri, screenAspectRatio)
                faceRegions = null
            }
            else -> {
                // GLITCH2 face bias only (auto-framing off).
                viewport = null
                faceRegions = AutoFramingEngine.detectFaceRegions(
                        context.contentResolver, imageUri)
            }
        }

        // Bail if the user has moved on while ML Kit was running.
        if (currentArtworkUri != capturedContentUri) return

        // Apply face regions on the GL thread (GLPictureSet.faceRegions is a GL-thread field).
        if (needsFaces) {
            queueEventOnGlThread {
                renderer.applyAsyncFaceRegions(faceRegions)
            }
        }

        // Apply the auto-framed viewport on the GL thread (updates GLPictureSet.savedViewport).
        // Re-check the DB and the user-touched flag in case the user manually adjusted the
        // framing while ML Kit was running — their choice always wins over auto-framing.
        // Log the decision so we can diagnose unexpected skips (e.g. face-out-of-frame reports).
        val applyReason = when {
            viewport == null -> "viewport-null"
            currentArtworkUri != capturedContentUri -> "skipped-stale"
            userTouchedViewport -> "skipped-touched"
            savedViewportFor(imageUri) != null -> "skipped-saved"
            else -> "applied"
        }
        Log.d("AutoFramingEngine", "autoframe ${imageUri.lastPathSegment} → $applyReason" +
                (if (viewport != null) " vp=$viewport" else ""))
        if (applyReason == "applied") {
            queueEventOnGlThread {
                renderer.applyAutoFramedViewport(viewport!!)
            }
        }
    }

    override suspend fun openDownloadedCurrentArtwork(): ContentUriImageLoader {
        val t0 = SystemClock.elapsedRealtime()
        Log.d("NextTiming", "RC openDownloaded entry ${currentArtworkUri.lastPathSegment}")
        val seed = try {
            ContentUris.parseId(currentArtworkUri)
        } catch (_: Exception) {
            currentArtworkUri.hashCode().toLong()
        }
        val loader = ContentUriImageLoader(context.contentResolver, currentArtworkUri, seed)
        // Face regions are applied asynchronously by launchAutoFrameJob — clear any stale
        // regions from the previous image so the GL thread doesn't inherit them.
        renderer.pendingFaceRegions = null
        // Fill the byte buffer on IO before handing the loader to the GL thread, so
        // getSize()/decode() read from memory and never block on the slow provider.
        withContext(Dispatchers.IO) {
            Log.d("NextTiming", "RC prefetch start")
            loader.prefetch()
            Log.d("NextTiming", "RC prefetch done +${SystemClock.elapsedRealtime() - t0}ms")
        }
        Log.d("NextTiming", "RC openDownloaded done +${SystemClock.elapsedRealtime() - t0}ms")
        return loader
    }
}
