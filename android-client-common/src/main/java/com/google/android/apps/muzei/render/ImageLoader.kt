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

import android.content.ContentResolver
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

fun InputStream.isValidImage(): Boolean {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeStream(this, null, options)
    return with(options) {
        outWidth != 0 && outHeight != 0 &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        outConfig == Bitmap.Config.ARGB_8888)
    }
}

/**
 * Base class for loading images with the correct rotation
 */
sealed class ImageLoader {
    open val seed: Long = 0L

    companion object {
        private const val TAG = "ImageLoader"

        suspend fun decode(
                contentResolver: ContentResolver,
                uri: Uri,
                targetWidth: Int = 0,
                targetHeight: Int = targetWidth
        ) = withContext(Dispatchers.IO) {
            ContentUriImageLoader(contentResolver, uri)
                    .decode(targetWidth, targetHeight)
        }
    }

    open fun getSize(): Pair<Int, Int> {
        return try {
            val (originalWidth, originalHeight) = openInputStream()?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                options.outWidth to options.outHeight
            } ?: return 0 to 0
            val rotation = getRotation()
            val width = if (rotation == 90 || rotation == 270) originalHeight else originalWidth
            val height = if (rotation == 90 || rotation == 270) originalWidth else originalHeight
            return width to height
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error decoding ${toString()}: ${e.message}")
            }
            0 to 0
        }
    }

    open fun decode(
            targetWidth: Int = 0,
            targetHeight: Int = targetWidth
    ) : Bitmap? {
        return try {
            val (originalWidth, originalHeight) = openInputStream()?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                Pair(options.outWidth, options.outHeight)
            } ?: return null
            val rotation = getRotation()
            val width = if (rotation == 90 || rotation == 270) originalHeight else originalWidth
            val height = if (rotation == 90 || rotation == 270) originalWidth else originalHeight
            openInputStream()?.use { input ->
                BitmapFactory.decodeStream(input, null,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            if (targetWidth != 0) {
                                inSampleSize = max(
                                        width.sampleSize(targetWidth),
                                        height.sampleSize(targetHeight))
                            }
                        })
            }?.run {
                when (rotation) {
                    0 -> this
                    else -> {
                        val rotateMatrix = Matrix().apply {
                            postRotate(rotation.toFloat())
                        }
                        Bitmap.createBitmap(
                                this, 0, 0,
                                this.width, this.height,
                                rotateMatrix, true).also { rotatedBitmap ->
                            if (rotatedBitmap != this) {
                                recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Error decoding ${toString()}: ${e.message}")
            }
            null
        }
    }

    open fun getRotation(): Int = try {
        openInputStream()?.use { input ->
            val exifInterface = ExifInterface(input)
            when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "Couldn't open EXIF interface for ${toString()}", e)
        }
        0
    }

    abstract fun openInputStream() : InputStream?
}

/**
 * An [ImageLoader] capable of loading images from a [ContentResolver].
 *
 * Reads the source stream **once** into a [ByteArray] and serves every subsequent
 * [getSize], [getRotation], and [decode] call from that in-memory buffer. This
 * eliminates the ~8 slow content-provider opens that would otherwise hit the
 * original MediaStore/SAF/cloud URI on every artwork load.
 */
class ContentUriImageLoader(
        private val contentResolver: ContentResolver,
        private val uri: Uri,
        override val seed: Long = 0L,
) : ImageLoader() {

    // Read-once buffer: filled on the first call that needs image data.
    @Volatile private var bytes: ByteArray? = null

    @Throws(FileNotFoundException::class)
    override fun openInputStream(): InputStream? = contentResolver.openInputStream(uri)

    // Call on an IO thread before handing the loader to the GL thread; subsequent
    // getSize/getRotation/decode calls then return without any blocking I/O.
    fun prefetch() { loadBytes() }

    private fun loadBytes(): ByteArray? {
        bytes?.let { return it }
        return try {
            openInputStream()?.use { it.readBytes() }?.also { bytes = it }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("ImageLoader", "Error reading $uri into buffer: ${e.message}")
            }
            null
        }
    }

    override fun getSize(): Pair<Int, Int> {
        val b = loadBytes() ?: return super.getSize()
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, options)
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW == 0 || origH == 0) return 0 to 0
            val rotation = getRotation()
            val width = if (rotation == 90 || rotation == 270) origH else origW
            val height = if (rotation == 90 || rotation == 270) origW else origH
            width to height
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("ImageLoader", "Error sizing $uri: ${e.message}")
            }
            0 to 0
        }
    }

    override fun getRotation(): Int {
        val b = loadBytes() ?: return super.getRotation()
        return try {
            val exif = ExifInterface(ByteArrayInputStream(b))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("ImageLoader", "Couldn't read EXIF from $uri: ${e.message}")
            }
            0
        }
    }

    override fun decode(targetWidth: Int, targetHeight: Int): Bitmap? {
        val b = loadBytes() ?: return super.decode(targetWidth, targetHeight)
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, boundsOptions)
            val rotation = getRotation()
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            val width = if (rotation == 90 || rotation == 270) origH else origW
            val height = if (rotation == 90 || rotation == 270) origW else origH
            BitmapFactory.decodeByteArray(b, 0, b.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        if (targetWidth != 0) {
                            inSampleSize = max(
                                    width.sampleSize(targetWidth),
                                    height.sampleSize(targetHeight))
                        }
                    })?.run {
                when (rotation) {
                    0 -> this
                    else -> {
                        val rotateMatrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(this, 0, 0,
                                this.width, this.height,
                                rotateMatrix, true).also { rotated ->
                            if (rotated != this) recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("ImageLoader", "Error decoding $uri: ${e.message}")
            }
            null
        }
    }

    override fun toString(): String = uri.toString()
}

/**
 * An [ImageLoader] capable of loading images from [AssetManager]
 */
class AssetImageLoader(
        private val assetManager: AssetManager,
        private val fileName: String
) : ImageLoader() {

    @Throws(IOException::class)
    override fun openInputStream(): InputStream =
            assetManager.open(fileName)

    override fun toString(): String {
        return fileName
    }
}