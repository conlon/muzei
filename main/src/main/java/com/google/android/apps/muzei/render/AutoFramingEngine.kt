/*
 * Copyright 2025 Google Inc.
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

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object AutoFramingEngine {

    private const val TAG = "AutoFramingEngine"
    private const val DECODE_SIZE = 512
    private const val PADDING_FACTOR = 0.15f

    /**
     * Detects faces in the artwork at [artworkUri] and returns their bounding boxes
     * as normalised [RectF] values (coordinates in 0..1 relative to image size).
     * Returns an empty list when no faces are detected or detection fails.
     * Intended for use with GLITCH2 filter to bias displacement chunks toward faces.
     */
    suspend fun detectFaceRegions(
            contentResolver: ContentResolver,
            artworkUri: Uri
    ): List<RectF> = withContext(Dispatchers.IO) {
        try {
            val bitmap = ContentUriImageLoader(contentResolver, artworkUri)
                    .decode(DECODE_SIZE) ?: return@withContext emptyList()
            val imageWidth = bitmap.width.toFloat()
            val imageHeight = bitmap.height.toFloat()
            if (imageWidth == 0f || imageHeight == 0f) {
                bitmap.recycle()
                return@withContext emptyList()
            }
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faceOptions = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            val faceDetector = FaceDetection.getClient(faceOptions)
            val faces = try {
                faceDetector.process(inputImage).await()
            } catch (_: Exception) {
                emptyList()
            }
            faceDetector.close()
            bitmap.recycle()
            faces.map { face ->
                val b = face.boundingBox
                RectF(b.left / imageWidth, b.top / imageHeight,
                        b.right / imageWidth, b.bottom / imageHeight)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face detection failed", e)
            emptyList()
        }
    }

    suspend fun computeFraming(
            contentResolver: ContentResolver,
            artworkUri: Uri,
            screenAspectRatio: Float
    ): RectF? = withContext(Dispatchers.IO) {
        try {
            val bitmap = ContentUriImageLoader(contentResolver, artworkUri)
                    .decode(DECODE_SIZE) ?: return@withContext null
            val result = detectSubjects(bitmap, screenAspectRatio)
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Auto-framing failed", e)
            null
        }
    }

    /**
     * Combined single-decode path for when both auto-framing and GLITCH2 face-biasing are
     * needed for the same image. Decodes once, runs the face detector once, and returns both
     * the viewport and the face regions so callers avoid a redundant decode + detection pass.
     *
     * @return A pair of (viewport RectF or null, face regions list — may be empty).
     */
    suspend fun computeFramingAndFaceRegions(
            contentResolver: ContentResolver,
            artworkUri: Uri,
            screenAspectRatio: Float
    ): Pair<RectF?, List<RectF>> = withContext(Dispatchers.IO) {
        try {
            val bitmap = ContentUriImageLoader(contentResolver, artworkUri)
                    .decode(DECODE_SIZE) ?: return@withContext Pair(null, emptyList())
            val imageWidth = bitmap.width.toFloat()
            val imageHeight = bitmap.height.toFloat()
            if (imageWidth == 0f || imageHeight == 0f) {
                bitmap.recycle()
                return@withContext Pair(null, emptyList())
            }
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Run face detection once
            val faceOptions = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            val faceDetector = FaceDetection.getClient(faceOptions)
            val faces = try {
                faceDetector.process(inputImage).await()
            } catch (_: Exception) {
                emptyList()
            }
            faceDetector.close()
            val faceBounds = faces.map { face ->
                val b = face.boundingBox
                RectF(b.left / imageWidth, b.top / imageHeight,
                        b.right / imageWidth, b.bottom / imageHeight)
            }
            // Run object detection for auto-framing (heavier pass)
            val objectOptions = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build()
            val objectDetector = ObjectDetection.getClient(objectOptions)
            val objects = try {
                objectDetector.process(inputImage).await()
            } catch (_: Exception) {
                emptyList()
            }
            objectDetector.close()
            val objectBounds = objects.map { obj ->
                val b = obj.boundingBox
                RectF(b.left / imageWidth, b.top / imageHeight,
                        b.right / imageWidth, b.bottom / imageHeight)
            }
            bitmap.recycle()
            val allBounds = faceBounds + objectBounds
            val viewport = if (allBounds.isEmpty()) {
                null
            } else {
                val subjectUnion = RectF(
                        allBounds.minOf { it.left }, allBounds.minOf { it.top },
                        allBounds.maxOf { it.right }, allBounds.maxOf { it.bottom })
                val faceUnion = if (faceBounds.isNotEmpty()) {
                    RectF(faceBounds.minOf { it.left }, faceBounds.minOf { it.top },
                            faceBounds.maxOf { it.right }, faceBounds.maxOf { it.bottom })
                } else null
                fitViewport(subjectUnion, faceUnion, screenAspectRatio, imageWidth / imageHeight)
            }
            Pair(viewport, faceBounds)
        } catch (e: Exception) {
            Log.w(TAG, "Combined framing+face detection failed", e)
            Pair(null, emptyList())
        }
    }

    private suspend fun detectSubjects(bitmap: Bitmap, screenAspectRatio: Float): RectF? {
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()
        if (imageWidth == 0f || imageHeight == 0f) return null

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val faceOptions = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        val faceDetector = FaceDetection.getClient(faceOptions)

        val objectOptions = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        val objectDetector = ObjectDetection.getClient(objectOptions)

        val faces = try { faceDetector.process(inputImage).await() } catch (_: Exception) { emptyList() }
        val objects = try { objectDetector.process(inputImage).await() } catch (_: Exception) { emptyList() }

        faceDetector.close()
        objectDetector.close()

        val faceBounds = faces.map { face ->
            val b = face.boundingBox
            RectF(b.left / imageWidth, b.top / imageHeight,
                    b.right / imageWidth, b.bottom / imageHeight)
        }

        val objectBounds = objects.map { obj ->
            val b = obj.boundingBox
            RectF(b.left / imageWidth, b.top / imageHeight,
                    b.right / imageWidth, b.bottom / imageHeight)
        }

        val allBounds = faceBounds + objectBounds
        if (allBounds.isEmpty()) return null

        val subjectUnion = RectF(
                allBounds.minOf { it.left }, allBounds.minOf { it.top },
                allBounds.maxOf { it.right }, allBounds.maxOf { it.bottom })

        val faceUnion = if (faceBounds.isNotEmpty()) {
            RectF(faceBounds.minOf { it.left }, faceBounds.minOf { it.top },
                    faceBounds.maxOf { it.right }, faceBounds.maxOf { it.bottom })
        } else null

        return fitViewport(subjectUnion, faceUnion, screenAspectRatio,
                imageWidth / imageHeight)
    }

    private fun fitViewport(
            subjectArea: RectF,
            faceArea: RectF?,
            screenAspectRatio: Float,
            imageAspectRatio: Float
    ): RectF {
        val viewportAspect = screenAspectRatio / imageAspectRatio

        val padded = RectF(
                max(0f, subjectArea.left - subjectArea.width() * PADDING_FACTOR),
                max(0f, subjectArea.top - subjectArea.height() * PADDING_FACTOR),
                min(1f, subjectArea.right + subjectArea.width() * PADDING_FACTOR),
                min(1f, subjectArea.bottom + subjectArea.height() * PADDING_FACTOR))

        var vpWidth: Float
        var vpHeight: Float

        if (viewportAspect >= 1f) {
            vpWidth = max(padded.width(), padded.height() * viewportAspect)
            vpHeight = vpWidth / viewportAspect
        } else {
            vpHeight = max(padded.height(), padded.width() / viewportAspect)
            vpWidth = vpHeight * viewportAspect
        }

        val fitsInImage = vpWidth <= 1f && vpHeight <= 1f

        if (fitsInImage) {
            var left = padded.centerX() - vpWidth / 2f
            var top = padded.centerY() - vpHeight / 2f
            left = left.coerceIn(0f, max(0f, 1f - vpWidth))
            top = top.coerceIn(0f, max(0f, 1f - vpHeight))
            return RectF(left, top, left + vpWidth, top + vpHeight)
        }

        vpWidth = min(1f, vpWidth)
        vpHeight = vpWidth / viewportAspect
        if (vpHeight > 1f) {
            vpHeight = 1f
            vpWidth = vpHeight * viewportAspect
        }

        if (faceArea == null) {
            var left = padded.centerX() - vpWidth / 2f
            var top = padded.centerY() - vpHeight / 2f
            left = left.coerceIn(0f, max(0f, 1f - vpWidth))
            top = top.coerceIn(0f, max(0f, 1f - vpHeight))
            return RectF(left, top, left + vpWidth, top + vpHeight)
        }

        val thirdW = vpWidth / 3f
        val thirdH = vpHeight / 3f

        var left = padded.centerX() - vpWidth / 2f

        if (left + vpWidth < faceArea.right) {
            left = faceArea.right - vpWidth + thirdW
        }
        if (left > faceArea.left) {
            left = faceArea.left - thirdW
        }

        var top = padded.centerY() - vpHeight / 2f

        if (top + vpHeight < faceArea.bottom) {
            top = faceArea.bottom - vpHeight + thirdH
        }
        if (top > faceArea.top) {
            top = faceArea.top - thirdH
        }

        left = left.coerceIn(0f, max(0f, 1f - vpWidth))
        top = top.coerceIn(0f, max(0f, 1f - vpHeight))

        return RectF(left, top, left + vpWidth, top + vpHeight)
    }
}
