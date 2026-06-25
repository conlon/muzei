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
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.util.collectIn
import kotlinx.coroutines.flow.filterNotNull

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

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        reloadCurrentArtwork()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        val database = MuzeiDatabase.getInstance(context)
        database.artworkDao().getCurrentArtworkFlow().filterNotNull().collectIn(owner) { artwork ->
            val newUri = artwork.contentUri
            // Only reload (re-bake + ML Kit) when the image itself changes.
            // Mutations like setFavorite / updateSavedViewport / updateDateAdded also
            // re-emit this flow; without this guard they would each trigger a full
            // reload, resetting the viewport and re-running expensive GL work.
            if (newUri == currentArtworkUri) {
                // Same image — just refresh the pending saved viewport in case the
                // user saved/cleared framing, without triggering a re-bake.
                renderer.pendingSavedViewport = pendingSavedViewportFor(artwork.imageUri)
                return@collectIn
            }
            val tFlow = SystemClock.elapsedRealtime()
            Log.d("NextTiming", "RC emission ${artwork.imageUri.lastPathSegment}")
            currentArtworkUri = newUri
            val tVp = SystemClock.elapsedRealtime()
            renderer.pendingSavedViewport = pendingSavedViewportFor(artwork.imageUri)
            Log.d("NextTiming", "RC pendingSavedViewport +${SystemClock.elapsedRealtime() - tVp}ms (total +${SystemClock.elapsedRealtime() - tFlow}ms)")
            reloadCurrentArtwork()
            Log.d("NextTiming", "RC reloadCurrentArtwork +${SystemClock.elapsedRealtime() - tFlow}ms")
        }
    }

    /**
     * Cached face regions computed during the current artwork load. Populated by
     * [pendingSavedViewportFor] when both auto-framing and GLITCH2 are active, so that
     * [openDownloadedCurrentArtwork] can reuse the result without a second ML Kit pass.
     */
    private var cachedFaceRegions: List<RectF>? = null

    /**
     * Looks up the saved viewport for [imageUri] from the per-image metadata table, falling
     * back to [AutoFramingEngine.computeFraming] when auto-framing is enabled and the image
     * has no saved viewport.
     *
     * When both auto-framing and GLITCH2 face-biasing are needed, uses the combined single-
     * decode path and caches the face regions so [openDownloadedCurrentArtwork] avoids a
     * redundant detection pass.
     */
    private suspend fun pendingSavedViewportFor(imageUri: Uri): RectF? {
        cachedFaceRegions = null
        val database = MuzeiDatabase.getInstance(context)
        val meta = database.imageMetadataDao().getByImageUri(imageUri)
        if (meta != null && meta.hasSavedViewport) {
            return RectF(meta.savedViewportLeft!!, meta.savedViewportTop!!,
                    meta.savedViewportRight!!, meta.savedViewportBottom!!)
        }
        val autoFramingEnabled = Prefs.getSharedPreferences(context)
                .getBoolean(Prefs.PREF_AUTO_FRAMING, Prefs.DEFAULT_AUTO_FRAMING)
        val screenAspectRatio = renderer.getAspectRatio()
        if (!autoFramingEnabled || screenAspectRatio <= 0f) return null

        return if (renderer.wantsSubjectRegions()) {
            // Both auto-framing and GLITCH2 need a face pass — run them together in a
            // single decode to avoid paying for two separate ML Kit face detections.
            val (viewport, faceRegions) = AutoFramingEngine.computeFramingAndFaceRegions(
                    context.contentResolver, currentArtworkUri, screenAspectRatio)
            cachedFaceRegions = faceRegions
            viewport
        } else {
            AutoFramingEngine.computeFraming(
                    context.contentResolver, currentArtworkUri, screenAspectRatio)
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
        // Ferry face regions to the renderer for GLITCH2 displacement biasing.
        // Re-use any regions already computed in pendingSavedViewportFor (combined path)
        // so we never decode + run face detection twice for the same image load.
        renderer.pendingFaceRegions = if (renderer.wantsSubjectRegions()) {
            val cached = cachedFaceRegions
            if (cached != null) {
                Log.d("NextTiming", "RC faceRegions cache-hit +${SystemClock.elapsedRealtime() - t0}ms")
                cached
            } else {
                Log.d("NextTiming", "RC faceRegions cache-miss, running detectFaceRegions")
                val result = AutoFramingEngine.detectFaceRegions(
                        context.contentResolver, currentArtworkUri)
                Log.d("NextTiming", "RC detectFaceRegions done +${SystemClock.elapsedRealtime() - t0}ms")
                result
            }
        } else {
            null
        }
        cachedFaceRegions = null
        val loader = ContentUriImageLoader(context.contentResolver, currentArtworkUri, seed)
        Log.d("NextTiming", "RC openDownloaded done +${SystemClock.elapsedRealtime() - t0}ms")
        return loader
    }
}
