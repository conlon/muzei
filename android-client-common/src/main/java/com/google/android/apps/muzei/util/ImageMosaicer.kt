/*
 * Copyright 2026 Google Inc.
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

package com.google.android.apps.muzei.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.core.graphics.scale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MosaicShape { SQUARE, TRIANGLE, HEXAGON, MIXED1, MIXED2, MIXED3, MIXED4 }

/**
 * Produce a mosaic of [source] using tiles of [shape], each roughly [tileSizePx] pixels
 * across. Square tiles use a bilinear downscale + nearest-neighbour upscale fast path;
 * triangle and hexagon tiles enumerate polygons and fill each with the source colour
 * sampled at the tile centroid.
 *
 * Returns null if [source] is null or has zero area. Callers own the returned bitmap.
 */
fun mosaicBitmap(
    source: Bitmap?,
    tileSizePx: Int,
    shape: MosaicShape = MosaicShape.SQUARE,
): Bitmap? {
    if (source == null || source.width == 0 || source.height == 0) return null
    val tile = max(1, tileSizePx)
    if (tile <= 1) {
        return source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
    }
    return when (shape) {
        MosaicShape.SQUARE -> squareMosaic(source, tile)
        MosaicShape.TRIANGLE -> triangleMosaic(source, tile)
        MosaicShape.HEXAGON -> hexagonMosaic(source, tile)
        MosaicShape.MIXED1 -> mixed1Mosaic(source, tile)
        MosaicShape.MIXED2 -> mixed2Mosaic(source, tile)
        MosaicShape.MIXED3 -> mixed3Mosaic(source, tile)
        MosaicShape.MIXED4 -> mixed4Mosaic(source, tile)
    }
}

private fun squareMosaic(source: Bitmap, tile: Int): Bitmap {
    val smallW = max(1, source.width / tile)
    val smallH = max(1, source.height / tile)
    val small = source.scale(smallW, smallH, filter = true)
    val config = source.config ?: Bitmap.Config.ARGB_8888
    val out = Bitmap.createBitmap(source.width, source.height, config)
    val canvas = Canvas(out)
    val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    canvas.drawBitmap(
        small,
        Rect(0, 0, smallW, smallH),
        Rect(0, 0, source.width, source.height),
        paint
    )
    if (small != source) {
        small.recycle()
    }
    return out
}

private class PixelSampler(source: Bitmap) {
    private val w = source.width
    private val h = source.height
    private val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }

    fun sample(x: Float, y: Float): Int {
        val xi = x.toInt().coerceIn(0, w - 1)
        val yi = y.toInt().coerceIn(0, h - 1)
        return pixels[yi * w + xi]
    }
}

private fun newCanvasBitmap(source: Bitmap): Pair<Bitmap, Canvas> {
    val config = source.config ?: Bitmap.Config.ARGB_8888
    val out = Bitmap.createBitmap(source.width, source.height, config)
    return out to Canvas(out)
}

/**
 * Equilateral triangle tessellation. Each rhombus cell of side [tile] contains two
 * mirrored equilateral triangles. Tile colour is sampled at each triangle's centroid.
 */
private fun triangleMosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val path = Path()
    val sW = source.width.toFloat()
    val sH = source.height.toFloat()
    val rowH = tile * sqrt(3f) / 2f
    val jMin = -1
    val jMax = ceil(sH / rowH).toInt() + 1
    val tileF = tile.toFloat()

    for (j in jMin..jMax) {
        val rowOffsetX = j * (tileF / 2f)
        val y0 = j * rowH
        val y1 = (j + 1) * rowH
        // Compute the i-range that covers [0, sW] at this row's offset.
        val iMin = floor(-rowOffsetX / tileF).toInt() - 1
        val iMax = ceil((sW - rowOffsetX) / tileF).toInt() + 1
        for (i in iMin..iMax) {
            val v00x = i * tileF + rowOffsetX
            val v10x = v00x + tileF
            val v01x = v00x + tileF / 2f
            val v11x = v01x + tileF

            // Triangle A: v00, v10, v01
            val cax = (v00x + v10x + v01x) / 3f
            val cay = (y0 + y0 + y1) / 3f
            paint.color = sampler.sample(cax, cay)
            path.rewind()
            path.moveTo(v00x, y0)
            path.lineTo(v10x, y0)
            path.lineTo(v01x, y1)
            path.close()
            canvas.drawPath(path, paint)

            // Triangle B: v10, v11, v01
            val cbx = (v10x + v11x + v01x) / 3f
            val cby = (y0 + y1 + y1) / 3f
            paint.color = sampler.sample(cbx, cby)
            path.rewind()
            path.moveTo(v10x, y0)
            path.lineTo(v11x, y1)
            path.lineTo(v01x, y1)
            path.close()
            canvas.drawPath(path, paint)
        }
    }
    return out
}

/**
 * Flat-top regular hexagons. [tile] is the hexagon's **flat-to-flat height**
 * (the distance between two parallel sides), matching the vertical pitch used by
 * [squareMosaic] and [triangleMosaic] so all three shapes appear the same size
 * on screen for the same slider value. The circumradius is derived internally as
 * `r = tile / sqrt(3)`.
 * Columns are spaced by `1.5 * r` horizontally; alternating columns are offset
 * by `tile / 2` vertically. Tile colour is sampled at the hexagon centre.
 */
private fun hexagonMosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val path = Path()
    val sW = source.width.toFloat()
    val sH = source.height.toFloat()
    val tileF = tile.toFloat()
    // Circumradius derived from the flat-to-flat extent (tile = sqrt(3) * r).
    val r = tileF / sqrt(3f)
    val colSpacing = 1.5f * r
    val rowSpacing = tileF          // = sqrt(3) * r — same vertical pitch as square/triangle
    val colCount = ceil(sW / colSpacing).toInt() + 2
    val rowCount = ceil(sH / rowSpacing).toInt() + 2

    // Pre-compute the six vertex offsets for a flat-top hex (angles 0°, 60°, 120°, ...).
    val vx = FloatArray(6)
    val vy = FloatArray(6)
    for (k in 0..5) {
        val angle = (k * 60f) * PI.toFloat() / 180f
        vx[k] = r * cos(angle)
        vy[k] = r * sin(angle)
    }

    for (col in -1..colCount) {
        val cx = col * colSpacing
        val yOffset = if (col and 1 == 0) 0f else rowSpacing / 2f
        for (row in -1..rowCount) {
            val cy = row * rowSpacing + yOffset
            paint.color = sampler.sample(cx, cy)
            path.rewind()
            path.moveTo(cx + vx[0], cy + vy[0])
            for (k in 1..5) {
                path.lineTo(cx + vx[k], cy + vy[k])
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }
    return out
}

/**
 * Mixed1: per-cell random shape. Each cell in a square tile grid is independently
 * assigned one of three cell styles via a spatial hash:
 *  - type 0: solid square (one color per cell)
 *  - type 1: two diagonal triangles (upper-left / lower-right)
 *  - type 2: four triangles meeting at the cell center (cross-split)
 */
private fun mixed1Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val path = Path()
    val sW = source.width
    val sH = source.height
    val tileF = tile.toFloat()
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1

    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val x0 = col * tileF
            val y0 = row * tileF
            val x1 = x0 + tileF
            val y1 = y0 + tileF
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            val hash = (col * 1664525L + row * 1013904223L).toInt()
            val cellType = ((hash % 3) + 3) % 3

            when (cellType) {
                0 -> {
                    paint.color = sampler.sample(cx, cy)
                    canvas.drawRect(x0, y0, x1, y1, paint)
                }
                1 -> {
                    paint.color = sampler.sample((x0 * 2 + x1) / 3f, (y0 * 2 + y1) / 3f)
                    path.rewind(); path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x0, y1)
                    path.close(); canvas.drawPath(path, paint)
                    paint.color = sampler.sample((x0 + x1 * 2) / 3f, (y0 + y1 * 2) / 3f)
                    path.rewind(); path.moveTo(x1, y0); path.lineTo(x1, y1); path.lineTo(x0, y1)
                    path.close(); canvas.drawPath(path, paint)
                }
                else -> {
                    paint.color = sampler.sample((x0 + x1 + cx) / 3f, (y0 + y0 + cy) / 3f)
                    path.rewind(); path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(cx, cy)
                    path.close(); canvas.drawPath(path, paint)
                    paint.color = sampler.sample((x1 + x1 + cx) / 3f, (y0 + y1 + cy) / 3f)
                    path.rewind(); path.moveTo(x1, y0); path.lineTo(x1, y1); path.lineTo(cx, cy)
                    path.close(); canvas.drawPath(path, paint)
                    paint.color = sampler.sample((x1 + x0 + cx) / 3f, (y1 + y1 + cy) / 3f)
                    path.rewind(); path.moveTo(x1, y1); path.lineTo(x0, y1); path.lineTo(cx, cy)
                    path.close(); canvas.drawPath(path, paint)
                    paint.color = sampler.sample((x0 + x0 + cx) / 3f, (y1 + y0 + cy) / 3f)
                    path.rewind(); path.moveTo(x0, y1); path.lineTo(x0, y0); path.lineTo(cx, cy)
                    path.close(); canvas.drawPath(path, paint)
                }
            }
        }
    }
    return out
}

/**
 * Mixed2: regional patches quilt. Image divided into coarse regions (4× tile size);
 * each region gets a hashed shape assignment (square/triangle/hexagon). The three
 * full-image mosaics are baked once and composited by region, so within each patch
 * the sub-tile detail of its assigned shape is visible.
 */
private fun mixed2Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sq = squareMosaic(source, tile)
    val tr = triangleMosaic(source, tile)
    val hx = hexagonMosaic(source, tile)
    val config = source.config ?: Bitmap.Config.ARGB_8888
    val out = Bitmap.createBitmap(source.width, source.height, config)
    val canvas = Canvas(out)
    val paint = Paint()
    val regionSize = tile * 4
    val numCols = ceil(source.width.toFloat() / regionSize).toInt() + 1
    val numRows = ceil(source.height.toFloat() / regionSize).toInt() + 1

    for (row in 0..numRows) {
        for (col in 0..numCols) {
            val x0 = col * regionSize
            val y0 = row * regionSize
            val x1 = min(x0 + regionSize, source.width)
            val y1 = min(y0 + regionSize, source.height)
            if (x0 >= source.width || y0 >= source.height) continue
            val hash = (col * 1664525L + row * 1013904223L).toInt()
            val srcMosaic = when (((hash % 3) + 3) % 3) { 0 -> sq; 1 -> tr; else -> hx }
            val src = Rect(x0, y0, x1, y1)
            canvas.drawBitmap(srcMosaic, src, src, paint)
        }
    }

    sq.recycle(); tr.recycle(); hx.recycle()
    return out
}

/**
 * Mixed3: overlapping translucent layers. All three shape mosaics are composited
 * with partial alpha and spatial offsets so tile boundaries don't all coincide.
 * Where misaligned boundaries cross, colors blend — creating a stained-glass feel.
 */
private fun mixed3Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sq = squareMosaic(source, tile)
    val tr = triangleMosaic(source, tile)
    val hx = hexagonMosaic(source, tile)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint()
    val offset = tile / 3f

    // Layer 1: squares as the base
    paint.alpha = 255
    canvas.drawBitmap(sq, 0f, 0f, paint)
    // Layer 2: triangles at 60% opacity, shifted right and down
    paint.alpha = 153
    canvas.drawBitmap(tr, offset, offset, paint)
    // Layer 3: hexagons at 40% opacity, shifted left and down
    paint.alpha = 102
    canvas.drawBitmap(hx, -offset, offset, paint)

    sq.recycle(); tr.recycle(); hx.recycle()
    return out
}

/**
 * Mixed4: triangle pinwheel / rosette. Uses the same equilateral triangle
 * tessellation as [triangleMosaic] but samples each triangle's color from a
 * point rotated 30° around the triangle's apex vertex instead of its centroid.
 * This makes the color in each triangle appear to "spin" around the rosette
 * vertex shared by six triangles, producing a stained-glass pinwheel pattern.
 */
private fun mixed4Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val path = Path()
    val sW = source.width.toFloat()
    val sH = source.height.toFloat()
    val tileF = tile.toFloat()
    val rowH = tile * sqrt(3f) / 2f
    val jMin = -1
    val jMax = ceil(sH / rowH).toInt() + 1
    // 30° rotation for the pinwheel sample offset
    val rotAngle = PI.toFloat() / 6f
    val cosR = cos(rotAngle)
    val sinR = sin(rotAngle)

    for (j in jMin..jMax) {
        val rowOffsetX = j * (tileF / 2f)
        val y0 = j * rowH
        val y1 = (j + 1) * rowH
        val iMin = floor(-rowOffsetX / tileF).toInt() - 1
        val iMax = ceil((sW - rowOffsetX) / tileF).toInt() + 1
        for (i in iMin..iMax) {
            val v00x = i * tileF + rowOffsetX
            val v10x = v00x + tileF
            val v01x = v00x + tileF / 2f
            val v11x = v01x + tileF

            // Triangle A: v00, v10, v01 — apex = v01
            val cax = (v00x + v10x + v01x) / 3f
            val cay = (y0 + y0 + y1) / 3f
            val dax = cax - v01x; val day = cay - y1
            paint.color = sampler.sample(v01x + dax * cosR - day * sinR, y1 + dax * sinR + day * cosR)
            path.rewind(); path.moveTo(v00x, y0); path.lineTo(v10x, y0); path.lineTo(v01x, y1)
            path.close(); canvas.drawPath(path, paint)

            // Triangle B: v10, v11, v01 — apex = v10
            val cbx = (v10x + v11x + v01x) / 3f
            val cby = (y0 + y1 + y1) / 3f
            val dbx = cbx - v10x; val dby = cby - y0
            paint.color = sampler.sample(v10x + dbx * cosR - dby * sinR, y0 + dbx * sinR + dby * cosR)
            path.rewind(); path.moveTo(v10x, y0); path.lineTo(v11x, y1); path.lineTo(v01x, y1)
            path.close(); canvas.drawPath(path, paint)
        }
    }
    return out
}
