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

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.Keep
import androidx.core.graphics.scale
import com.google.android.apps.muzei.ArtDetailOpen
import com.google.android.apps.muzei.ArtDetailViewport
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.util.ImageBlurrer
import com.google.android.apps.muzei.util.TickingFloatAnimator
import com.google.android.apps.muzei.util.constrain
import com.google.android.apps.muzei.util.floorEven
import com.google.android.apps.muzei.util.interpolate
import com.google.android.apps.muzei.util.MosaicShape
import com.google.android.apps.muzei.util.mosaicBitmap
import com.google.android.apps.muzei.util.roundMult4
import com.google.android.apps.muzei.util.uninterpolate
import kotlinx.coroutines.flow.MutableStateFlow
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

sealed class SwitchingPhotos(val viewportId: Int)
data class SwitchingPhotosInProgress(private val currentId: Int) : SwitchingPhotos(currentId)
data class SwitchingPhotosDone(private val currentId: Int) : SwitchingPhotos(currentId)

val SwitchingPhotosStateFlow = MutableStateFlow<SwitchingPhotos?>(null)

data class ArtworkSize(val width: Int, val height: Int)

val ArtworkSizeStateFlow = MutableStateFlow<ArtworkSize?>(null)

class MuzeiBlurRenderer(
        private val context: Context,
        private val callbacks: Callbacks,
        private val demoMode: Boolean = false,
        private val preview: Boolean = false
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MuzeiBlurRenderer"

        private const val CROSSFADE_ANIMATION_DURATION = 750
        private const val BLUR_ANIMATION_DURATION = 750

        const val DEFAULT_BLUR = 250 // max 500
        const val DEFAULT_GREY = 0 // max 500
        const val DEFAULT_MAX_DIM = 128 // technical max 255
        const val DEFAULT_MOSAIC = 100 // max 500
        const val DEFAULT_MOSAIC_OPACITY = 500 // max 500; 500 = full mosaic (preserves existing look)
        const val DEFAULT_EFFECT_MODE = Prefs.EFFECT_MODE_BLUR
        const val DEFAULT_MOSAIC_SHAPE = Prefs.MOSAIC_SHAPE_SQUARE
        private const val DEMO_BLUR = 250
        private const val DEMO_DIM = 64
        private const val DEMO_GREY = 0
        private const val DIM_RANGE = 0.5f // percent of max dim
        // At max amount, mosaic tile size = this fraction of source bitmap height.
        // ~0.20 => ~5 tiles vertically at max, which reads as obviously mosaic'd
        // without becoming unrecognisable.
        private const val MOSAIC_MAX_TILE_FRACTION = 0.20f
    }

    private val blurKeyframes: Int
    private var maxPrescaledBlurPixels: Int = 0
    private var blurredSampleSize: Int = 0
    private var maxDim: Int = 0
    private var maxGrey: Int = 0
    private var mosaicAmount: Int = DEFAULT_MOSAIC
    private var mosaicOpacity: Int = DEFAULT_MOSAIC_OPACITY
    private var currentEffectMode: String = DEFAULT_EFFECT_MODE
    private var currentMosaicShape: MosaicShape = MosaicShape.SQUARE
    private var mosaicRandom: Boolean = false

    // Model and view matrices. Projection and MVP stored in picture set
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var aspectRatio: Float = 0f
    fun getAspectRatio(): Float = aspectRatio
    private var currentHeight: Int = 0

    private var currentGLPictureSet: GLPictureSet
    private var nextGLPictureSet: GLPictureSet
    private lateinit var colorOverlay: GLColorOverlay

    private var queuedNextImageLoader: ImageLoader? = null

    private var surfaceCreated: Boolean = false

    @Volatile
    private var normalOffsetX: Float = 0f
    @Volatile
    private var zoomAmount: Float = 1f
    @Volatile
    var pendingSavedViewport: RectF? = null
    private val currentViewport = RectF() // [-1, -1] to [1, 1], flipped

    var isBlurred = true
        private set
    private var blurPreferenceName = Prefs.PREF_BLUR_AMOUNT
    private var dimPreferenceName = Prefs.PREF_DIM_AMOUNT
    private var greyPreferenceName = Prefs.PREF_GREY_AMOUNT
    private var mosaicPreferenceName = Prefs.PREF_MOSAIC_AMOUNT
    private var mosaicOpacityPreferenceName = Prefs.PREF_MOSAIC_OPACITY
    private var effectModePreferenceName = Prefs.PREF_EFFECT_MODE
    private var mosaicShapePreferenceName = Prefs.PREF_MOSAIC_SHAPE
    private var blurRelatedToArtDetailMode = false
    private val blurInterpolator = AccelerateDecelerateInterpolator()
    private val blurAnimator = TickingFloatAnimator(BLUR_ANIMATION_DURATION * if (demoMode) 5 else 1)
    private val crossfadeAnimator = TickingFloatAnimator(CROSSFADE_ANIMATION_DURATION)

    init {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        blurKeyframes = if (activityManager.isLowRamDevice) 1 else 2
        blurAnimator.currentValue = blurKeyframes.toFloat()

        currentGLPictureSet = GLPictureSet(0)
        nextGLPictureSet = GLPictureSet(1) // for transitioning to next pictures
        setNormalOffsetX(0f)
        setZoom(1f)
        recomputeMaxPrescaledBlurPixels()
        recomputeMaxDimAmount()
        recomputeGreyAmount()
        recomputeMosaicAmount()
        recomputeMosaicOpacity()
        recomputeEffectMode()
        recomputeMosaicShape()
    }

    fun recomputeMaxPrescaledBlurPixels(
            newBlurPreferenceName: String = blurPreferenceName
    ) {
        blurPreferenceName = newBlurPreferenceName
        // Compute blur sizes
        val blurAmount = if (demoMode)
            DEMO_BLUR
        else
            Prefs.getSharedPreferences(context)
                    .getInt(blurPreferenceName, DEFAULT_BLUR)
        val maxBlurRadiusOverScreenHeight = blurAmount * 0.0001f
        val dm = context.resources.displayMetrics
        val maxBlurPx = (dm.heightPixels * maxBlurRadiusOverScreenHeight).toInt()
        blurredSampleSize = 4
        while (maxBlurPx / blurredSampleSize > ImageBlurrer.MAX_SUPPORTED_BLUR_PIXELS) {
            blurredSampleSize = blurredSampleSize shl 1
        }
        maxPrescaledBlurPixels = maxBlurPx / blurredSampleSize
    }

    fun recomputeMaxDimAmount(
            newDimPreferenceName: String = dimPreferenceName
    ) {
        dimPreferenceName = newDimPreferenceName
        maxDim = Prefs.getSharedPreferences(context).getInt(
                dimPreferenceName, DEFAULT_MAX_DIM)
    }

    fun recomputeGreyAmount(
            newGreyPreferenceName: String = greyPreferenceName
    ) {
        greyPreferenceName = newGreyPreferenceName
        maxGrey = if (demoMode)
            DEMO_GREY
        else
            Prefs.getSharedPreferences(context)
                    .getInt(greyPreferenceName, DEFAULT_GREY)
    }

    fun recomputeMosaicAmount(
            newMosaicPreferenceName: String = mosaicPreferenceName
    ) {
        mosaicPreferenceName = newMosaicPreferenceName
        mosaicAmount = Prefs.getSharedPreferences(context)
                .getInt(mosaicPreferenceName, DEFAULT_MOSAIC)
                .coerceIn(0, 500)
    }

    fun recomputeMosaicOpacity(
            newMosaicOpacityPreferenceName: String = mosaicOpacityPreferenceName
    ) {
        mosaicOpacityPreferenceName = newMosaicOpacityPreferenceName
        mosaicOpacity = Prefs.getSharedPreferences(context)
                .getInt(mosaicOpacityPreferenceName, DEFAULT_MOSAIC_OPACITY)
                .coerceIn(0, 500)
    }

    fun recomputeEffectMode(
            newEffectModePreferenceName: String = effectModePreferenceName
    ) {
        effectModePreferenceName = newEffectModePreferenceName
        currentEffectMode = Prefs.getSharedPreferences(context)
                .getString(effectModePreferenceName, DEFAULT_EFFECT_MODE)
                ?: DEFAULT_EFFECT_MODE
    }

    fun recomputeMosaicShape(
            newMosaicShapePreferenceName: String = mosaicShapePreferenceName
    ) {
        mosaicShapePreferenceName = newMosaicShapePreferenceName
        val raw = Prefs.getSharedPreferences(context)
                .getString(mosaicShapePreferenceName, DEFAULT_MOSAIC_SHAPE)
                ?: DEFAULT_MOSAIC_SHAPE
        mosaicRandom = raw == Prefs.MOSAIC_SHAPE_RANDOM
        currentMosaicShape = when (raw) {
            Prefs.MOSAIC_SHAPE_TRIANGLE -> MosaicShape.TRIANGLE
            Prefs.MOSAIC_SHAPE_HEXAGON -> MosaicShape.HEXAGON
            Prefs.MOSAIC_SHAPE_MIXED1 -> MosaicShape.MIXED1
            Prefs.MOSAIC_SHAPE_MIXED2 -> MosaicShape.MIXED2
            Prefs.MOSAIC_SHAPE_MIXED3 -> MosaicShape.MIXED3
            Prefs.MOSAIC_SHAPE_MIXED4 -> MosaicShape.MIXED4
            Prefs.MOSAIC_SHAPE_GLITCH -> MosaicShape.GLITCH
            else -> MosaicShape.SQUARE
        }
    }

    private fun mosaicTilePixelsAtFrame(
            scaledHeight: Int,
            f: Int,
            visibleImageHeightFraction: Float
    ): Int {
        if (mosaicAmount <= 0) return 1
        val frameFraction = f.toFloat() / blurKeyframes
        val amountFraction = mosaicAmount / 500f
        // Multiply by the fraction of the source image height that's actually visible
        // on screen (smaller when framing zooms in, or when the image is narrower than
        // the screen). This keeps the on-screen tile size constant regardless of
        // per-image framing/zoom.
        val tile = amountFraction * MOSAIC_MAX_TILE_FRACTION *
                scaledHeight * visibleImageHeightFraction * frameFraction
        return max(1, tile.toInt())
    }

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        surfaceCreated = false
        GLES20.glEnable(GLES20.GL_BLEND)
        //        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glClearColor(0f, 0f, 0f, 0f)

        // Set the camera position (View matrix)
        Matrix.setLookAtM(viewMatrix, 0,
                0f, 0f, 1f,
                0f, 0f, -1f,
                0f, 1f, 0f)

        GLColorOverlay.initGl()
        GLPicture.initGl()

        colorOverlay = GLColorOverlay()

        surfaceCreated = true
        val loader = queuedNextImageLoader
        if (loader != null) {
            queuedNextImageLoader = null
            setAndConsumeImageLoader(loader)
        }
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        hintViewportSize(width, height)
        if (!demoMode && !preview) {
            // Reset art detail viewports
            ArtDetailViewport.setViewport(0, 0f, 0f, 0f, 0f)
            ArtDetailViewport.setViewport(1, 0f, 0f, 0f, 0f)
        }
        currentGLPictureSet.recomputeTransformMatrices()
        nextGLPictureSet.recomputeTransformMatrices()
        recomputeMaxPrescaledBlurPixels()
    }

    fun hintViewportSize(width: Int, height: Int) {
        currentHeight = height
        aspectRatio = width * 1f / height
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        Matrix.setIdentityM(modelMatrix, 0)

        val stillAnimating = crossfadeAnimator.tick() or blurAnimator.tick()

        if (blurRelatedToArtDetailMode) {
            currentGLPictureSet.recomputeTransformMatrices()
            nextGLPictureSet.recomputeTransformMatrices()
        }

        var dimAmount = currentGLPictureSet.dimAmount.toFloat()
        currentGLPictureSet.drawFrame(1f)
        if (crossfadeAnimator.isRunning) {
            dimAmount = interpolate(dimAmount, nextGLPictureSet.dimAmount.toFloat(),
                    crossfadeAnimator.currentValue)
            nextGLPictureSet.drawFrame(crossfadeAnimator.currentValue)
        }

        colorOverlay.color = Color.argb((dimAmount * blurAnimator.currentValue / blurKeyframes).toInt(), 0, 0, 0)
        colorOverlay.draw(modelMatrix) // don't need any perspective or anything for color overlay

        if (stillAnimating) {
            callbacks.requestRender()
        }
    }

    @Keep
    fun setNormalOffsetX(x: Float) {
        normalOffsetX = x.constrain(0f, 1f)
        onViewportChanged()
    }

    fun setZoom(zoom: Float) {
        zoomAmount = interpolate(1f, 1.1f, 1 - zoom.constrain(0f, 1f))
        onViewportChanged()
    }

    private fun onViewportChanged() {
        currentGLPictureSet.recomputeTransformMatrices()
        nextGLPictureSet.recomputeTransformMatrices()
        if (surfaceCreated) {
            callbacks.requestRender()
        }
    }

    private fun blurRadiusAtFrame(f: Float): Float {
        return maxPrescaledBlurPixels * blurInterpolator.getInterpolation(f / blurKeyframes)
    }

    fun setAndConsumeImageLoader(imageLoader: ImageLoader, immediate: Boolean = false) {
        if (!surfaceCreated) {
            queuedNextImageLoader = imageLoader
            return
        }

        if (crossfadeAnimator.isRunning && !immediate) {
            queuedNextImageLoader = imageLoader
            return
        }

        val (width, height) = imageLoader.getSize()
        if (width == 0 || height == 0) {
            return
        }

        if (immediate) {
            // Stop any running cross fade if we're immediately switching to this new image
            crossfadeAnimator.finish()
        }

        if (!demoMode && !preview) {
            SwitchingPhotosStateFlow.value = SwitchingPhotosInProgress(nextGLPictureSet.id)
            ArtworkSizeStateFlow.value = ArtworkSize(width, height)
            val savedViewport = pendingSavedViewport
            nextGLPictureSet.savedViewport = savedViewport
            if (savedViewport != null) {
                ArtDetailViewport.setViewport(nextGLPictureSet.id, savedViewport)
            } else {
                ArtDetailViewport.setDefaultViewport(nextGLPictureSet.id,
                        width * 1f / height,
                        aspectRatio)
            }
        }

        nextGLPictureSet.load(imageLoader)

        crossfadeAnimator.start(if (immediate) 1 else 0, 1) {
            // swap current and next picturesets
            val oldGLPictureSet = currentGLPictureSet
            currentGLPictureSet = nextGLPictureSet
            nextGLPictureSet = GLPictureSet(oldGLPictureSet.id)
            callbacks.requestRender()
            oldGLPictureSet.destroyPictures()
            if (!demoMode) {
                SwitchingPhotosStateFlow.value = SwitchingPhotosDone(currentGLPictureSet.id)
            }
            System.gc()
            val loader = queuedNextImageLoader
            if (loader != null) {
                queuedNextImageLoader = null
                setAndConsumeImageLoader(loader, immediate)
            }
        }
        callbacks.requestRender()
    }

    private inner class GLPictureSet(val id: Int) {
        private val projectionMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val pictures = arrayOfNulls<GLPicture>(blurKeyframes + 1)
        private var hasBitmap = false
        private var bitmapAspectRatio = 1f
        var dimAmount = 0
        var savedViewport: RectF? = null

        fun load(imageLoader: ImageLoader) {
            val (width, height) = imageLoader.getSize()
            hasBitmap = width != 0 && height != 0
            bitmapAspectRatio = if (hasBitmap)
                width * 1f / height
            else
                1f

            dimAmount = DEFAULT_MAX_DIM

            destroyPictures()

            if (hasBitmap) {
                // Calculate image darkness to determine dim amount
                var tempBitmap = imageLoader.decode(64)
                val darkness = tempBitmap.darkness()
                dimAmount = if (demoMode)
                    DEMO_DIM
                else
                    (maxDim * (1 - DIM_RANGE + DIM_RANGE * sqrt(darkness.toDouble()))).toInt()
                tempBitmap?.recycle()

                // Create the GLPicture objects
                var success = false
                var sampleSize = 1
                do {
                    val attemptedWidth = (bitmapAspectRatio * currentHeight / sampleSize).toInt()
                    val attemptedHeight = currentHeight / sampleSize
                    try {
                        val image = imageLoader.decode(
                                attemptedWidth,
                                attemptedHeight)
                        pictures[0] = image?.toGLPicture()
                        success = true
                    } catch (_: OutOfMemoryError) {
                        sampleSize = sampleSize shl 1
                        Log.d(TAG, "Decoding image at ${attemptedWidth}x$attemptedHeight " +
                                "was too large, trying a sample size of $sampleSize")
                    }
                } while (!success)
                val mosaicActive = currentEffectMode == Prefs.EFFECT_MODE_MOSAIC &&
                        mosaicAmount > 0
                val blurActive = currentEffectMode == Prefs.EFFECT_MODE_BLUR &&
                        maxPrescaledBlurPixels > 0
                if (!mosaicActive && !blurActive && maxGrey == 0) {
                    for (f in 1..blurKeyframes) {
                        pictures[f] = pictures[0]
                    }
                } else {
                    val sampleSizeTargetHeight: Int = if (blurActive) {
                        currentHeight / blurredSampleSize
                    } else {
                        currentHeight
                    }
                    // Note that image width should be a multiple of 4 to avoid
                    // issues with RenderScript allocations.
                    val scaledHeight = max(2, sampleSizeTargetHeight.floorEven())
                    val scaledWidth = max(4, (scaledHeight * bitmapAspectRatio).toInt().roundMult4())

                    // Load the entire bitmap region at a sample size appropriate for the
                    // final effect (blurred or mosaic'd) image.
                    tempBitmap = imageLoader.decode(scaledWidth, scaledHeight)

                    if (tempBitmap != null
                            && tempBitmap.width != 0 && tempBitmap.height != 0) {
                        // Note that image width should be a multiple of 4 to avoid
                        // issues with RenderScript allocations.
                        val scaledBitmap = tempBitmap.scale(scaledWidth, scaledHeight)
                        if (tempBitmap != scaledBitmap) {
                            tempBitmap.recycle()
                        }

                        if (mosaicActive) {
                            val effectiveShape = if (mosaicRandom) {
                                val allShapes = MosaicShape.values()
                                allShapes[Random(imageLoader.seed).nextInt(allShapes.size)]
                            } else {
                                currentMosaicShape
                            }
                            generateMosaicKeyframes(scaledBitmap, scaledHeight, effectiveShape)
                        } else {
                            generateBlurKeyframes(scaledBitmap)
                        }

                        scaledBitmap.recycle()
                    } else {
                        Log.e(TAG, "ImageLoader failed to decode the image")
                        for (f in 1..blurKeyframes) {
                            pictures[f] = null
                        }
                    }
                }
            }

            recomputeTransformMatrices()
            callbacks.requestRender()
        }

        fun recomputeTransformMatrices() {
            val screenToBitmapAspectRatio = aspectRatio / bitmapAspectRatio
            if (screenToBitmapAspectRatio == 0f) {
                return
            }

            val saved = savedViewport
            if (saved != null) {
                // Use the saved viewport as the parallax center: at normalOffsetX = 0.5 the
                // viewport sits exactly at the saved framing; otherwise slide it horizontally,
                // symmetrically clamped so it never runs off the image.
                val panExtent = min(saved.left, 1f - saved.right)
                val shift = (normalOffsetX - 0.5f) * 2f * panExtent
                currentViewport.apply {
                    left = interpolate(-1f, 1f, saved.left + shift)
                    right = interpolate(-1f, 1f, saved.right + shift)
                    top = interpolate(1f, -1f, saved.top)
                    bottom = interpolate(1f, -1f, saved.bottom)
                }
            } else {
                // Ensure the bitmap is as wide as the screen by applying zoom if necessary
                // ignoring any system wide zoom requests while the Art Detail screen is open
                val zoom = max(1f, screenToBitmapAspectRatio) *
                        (if (ArtDetailOpen.value) 1f else zoomAmount)

                // Total scale factors in both zoom and scale due to aspect ratio.
                val scaledBitmapToScreenAspectRatio = zoom / screenToBitmapAspectRatio

                // At most pan across 1.8 screenfuls (2 screenfuls + some parallax)
                // TODO: if we know the number of home screen pages, use that number here
                val maxPanScreenWidths = min(1.8f, scaledBitmapToScreenAspectRatio)

                currentViewport.apply {
                    left = interpolate(-1f, 1f,
                            interpolate(
                                    (1 - maxPanScreenWidths / scaledBitmapToScreenAspectRatio) / 2,
                                    (1 + (maxPanScreenWidths - 2) / scaledBitmapToScreenAspectRatio) / 2,
                                    normalOffsetX))
                    right = left + 2f / scaledBitmapToScreenAspectRatio
                    bottom = -1f / zoom
                    top = 1f / zoom
                }
            }

            val focusAmount = (blurKeyframes - blurAnimator.currentValue) / blurKeyframes
            if (blurRelatedToArtDetailMode && focusAmount > 0) {
                val artDetailViewport = ArtDetailViewport.getViewport(id)
                if (artDetailViewport.width() == 0f || artDetailViewport.height() == 0f) {
                    if (!demoMode && !preview) {
                        // reset art detail viewport
                        ArtDetailViewport.setViewport(id,
                                uninterpolate(-1f, 1f, currentViewport.left),
                                uninterpolate(1f, -1f, currentViewport.top),
                                uninterpolate(-1f, 1f, currentViewport.right),
                                uninterpolate(1f, -1f, currentViewport.bottom))
                    }
                } else {
                    // interpolate
                    currentViewport.apply {
                        left = interpolate(
                                left,
                                interpolate(-1f, 1f, artDetailViewport.left),
                                focusAmount)
                        top = interpolate(
                                top,
                                interpolate(1f, -1f, artDetailViewport.top),
                                focusAmount)
                        right = interpolate(
                                right,
                                interpolate(-1f, 1f, artDetailViewport.right),
                                focusAmount)
                        bottom = interpolate(
                                bottom,
                                interpolate(1f, -1f, artDetailViewport.bottom),
                                focusAmount)
                    }
                }
            }

            Matrix.orthoM(projectionMatrix, 0,
                    currentViewport.left, currentViewport.right,
                    currentViewport.bottom, currentViewport.top,
                    1f, 10f)
        }

        fun drawFrame(globalAlpha: Float) {
            if (!hasBitmap) {
                return
            }

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            val blurFrame = blurAnimator.currentValue
            val lo = floor(blurFrame.toDouble()).toInt()
            val hi = ceil(blurFrame.toDouble()).toInt()

            val localHiAlpha = blurFrame - lo
            when {
                globalAlpha <= 0 -> {
                    // Nothing to draw
                }
                lo == hi -> {
                    // Just draw one
                    if (pictures[lo] == null) {
                        return
                    }

                    pictures[lo]?.draw(mvpMatrix, globalAlpha)
                }
                globalAlpha == 1f -> {
                    // Simple drawing
                    if (pictures[lo] == null || pictures[hi] == null) {
                        return
                    }

                    pictures[lo]?.draw(mvpMatrix, 1f)
                    pictures[hi]?.draw(mvpMatrix, localHiAlpha)
                }
                else -> {
                    // If there's both a global and local alpha, re-compose alphas, to
                    // effectively compose hi and lo before composing the result
                    // with the background.
                    //
                    // The math, where a1,a2 are previous alphas and b1,b2 are new alphas:
                    //   b1 = a1 * (a2 - 1) / (a1 * a2 - 1)
                    //   b2 = a1 * a2
                    if (pictures[lo] == null || pictures[hi] == null) {
                        return
                    }

                    val newLocalLoAlpha = globalAlpha * (localHiAlpha - 1) / (globalAlpha * localHiAlpha - 1)
                    val newLocalHiAlpha = globalAlpha * localHiAlpha
                    pictures[lo]?.draw(mvpMatrix, newLocalLoAlpha)
                    pictures[hi]?.draw(mvpMatrix, newLocalHiAlpha)
                }
            }
        }

        private fun generateBlurKeyframes(scaledBitmap: android.graphics.Bitmap) {
            val blurrer = ImageBlurrer(context, scaledBitmap)
            for (f in 1..blurKeyframes) {
                val desaturateAmount = maxGrey / 500f * f / blurKeyframes
                val blurRadius = if (maxPrescaledBlurPixels > 0) {
                    blurRadiusAtFrame(f.toFloat())
                } else {
                    0f
                }
                val blurredBitmap = blurrer.blurBitmap(blurRadius, desaturateAmount)
                pictures[f] = blurredBitmap?.toGLPicture()
                blurredBitmap?.recycle()
            }
            blurrer.destroy()
        }

        /**
         * The fraction of the source image height that is visible on screen,
         * computed from data known at bake time (saved viewport, or bitmap/screen
         * aspect ratios). Multiplying source-pixel tile sizes by this fraction
         * cancels out the projection zoom so that on-screen tile size is constant
         * regardless of per-image framing.
         *
         * Dynamic zooms (live art-detail pinch up to 5×, home-screen parallax 1.0–1.1×)
         * are NOT compensated here — the mosaic is baked once per image and those zooms
         * apply after the fact. In practice this is invisible because the mosaic is only
         * shown while the wallpaper is blurred; the sharp original is displayed during
         * interactive framing.
         */
        private fun staticVisibleHeightFraction(): Float {
            val saved = savedViewport
            return if (saved != null) {
                saved.height().coerceIn(0.01f, 1f)
            } else {
                val screenToBitmapAspectRatio = if (bitmapAspectRatio > 0f)
                    aspectRatio / bitmapAspectRatio else 1f
                1f / max(1f, screenToBitmapAspectRatio)
            }
        }

        private fun generateMosaicKeyframes(
            scaledBitmap: android.graphics.Bitmap,
            scaledHeight: Int,
            effectiveShape: MosaicShape,
        ) {
            val visibleImageHeightFraction = staticVisibleHeightFraction()
            for (f in 1..blurKeyframes) {
                val tilePx = mosaicTilePixelsAtFrame(scaledHeight, f, visibleImageHeightFraction)
                val pixelated = mosaicBitmap(scaledBitmap, tilePx, effectiveShape)

                // Blend the mosaic over the (original) scaled photo when opacity < 500.
                // This respects grey (desaturate step below) and dim (draw-time overlay).
                val blended = if (mosaicOpacity < 500 && pixelated != null) {
                    val config = scaledBitmap.config ?: Bitmap.Config.ARGB_8888
                    val composite = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, config)
                    val compositeCanvas = Canvas(composite)
                    compositeCanvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                    val alphaPaint = Paint().apply {
                        // Logarithmic response: ln(1+k·x)/ln(1+k), x∈[0,1].
                        // Concave curve — low slider positions already produce visible
                        // mosaic; the top of the range fine-tunes toward full opacity.
                        val x = mosaicOpacity / 500f
                        val k = 9f
                        val frac = ln(1f + k * x) / ln(1f + k)
                        alpha = (frac * 255).toInt().coerceIn(0, 255)
                    }
                    compositeCanvas.drawBitmap(pixelated, 0f, 0f, alphaPaint)
                    pixelated.recycle()
                    composite
                } else {
                    pixelated
                }

                val desaturateAmount = maxGrey / 500f * f / blurKeyframes
                val finalBitmap = if (desaturateAmount > 0f && blended != null) {
                    val blurrer = ImageBlurrer(context, blended)
                    val out = blurrer.blurBitmap(0f, desaturateAmount)
                    blurrer.destroy()
                    blended.recycle()
                    out
                } else {
                    blended
                }
                pictures[f] = finalBitmap?.toGLPicture()
                finalBitmap?.recycle()
            }
        }

        fun destroyPictures() {
            for (i in pictures.indices) {
                if (pictures[i] != null) {
                    pictures[i]?.destroy()
                    pictures[i] = null
                }
            }
        }
    }

    fun destroy() {
        currentGLPictureSet.destroyPictures()
        nextGLPictureSet.destroyPictures()
    }

    fun setIsBlurred(isBlurred: Boolean, artDetailMode: Boolean) {
        if (artDetailMode && !isBlurred && !demoMode && !preview) {
            // Reset art detail viewport
            ArtDetailViewport.setViewport(0, 0f, 0f, 0f, 0f)
            ArtDetailViewport.setViewport(1, 0f, 0f, 0f, 0f)
        }

        blurRelatedToArtDetailMode = artDetailMode
        this.isBlurred = isBlurred
        blurAnimator.start(endValue = if (isBlurred) blurKeyframes else 0) {
            if (isBlurred && artDetailMode) {
                System.gc()
            }
        }
        callbacks.requestRender()
    }

    fun interface Callbacks {
        fun requestRender()
    }
}