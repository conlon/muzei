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
 * Partitions [source] into segments by assigning each pixel a Long id via [segmentIdOf],
 * then samples one base-image colour per segment at the segment's centroid. This produces
 * a crisp tiled / stained-glass result without alpha-blending: every segment gets the
 * single true colour from the photo at its centre.
 */
private fun segmentSampledBitmap(source: Bitmap, segmentIdOf: (x: Int, y: Int) -> Long): Bitmap {
    val w = source.width
    val h = source.height
    val sampler = PixelSampler(source)

    // Pass 1: assign every pixel an id and accumulate centroid sums per segment.
    val ids = LongArray(w * h)
    val sumX = HashMap<Long, Long>()
    val sumY = HashMap<Long, Long>()
    val count = HashMap<Long, Int>()
    for (y in 0 until h) {
        for (x in 0 until w) {
            val id = segmentIdOf(x, y)
            ids[y * w + x] = id
            sumX[id] = (sumX[id] ?: 0L) + x
            sumY[id] = (sumY[id] ?: 0L) + y
            count[id] = (count[id] ?: 0) + 1
        }
    }

    // Sample one colour per segment at its centroid.
    val colorOf = HashMap<Long, Int>(count.size * 2)
    for ((id, n) in count) {
        val cx = (sumX[id]!! / n).toFloat()
        val cy = (sumY[id]!! / n).toFloat()
        colorOf[id] = sampler.sample(cx, cy)
    }

    // Pass 2: fill output pixels.
    val pixels = IntArray(w * h) { colorOf[ids[it]]!! }
    val config = source.config ?: Bitmap.Config.ARGB_8888
    val out = Bitmap.createBitmap(w, h, config)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
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
 * Mixed1: recursive square subdivision. Each tile cell is assigned a subdivision
 * factor n ∈ {1, 2, 3} by a spatial hash, then drawn as n×n sub-squares each filled
 * with the base colour at its own centre. Sub-squares with n ≥ 2 may split one level
 * deeper into 2×2 (a secondary hash), so some regions read as smaller squares composed
 * of even smaller squares.
 */
private fun mixed1Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val sW = source.width
    val sH = source.height
    val tileF = tile.toFloat()
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1

    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val x0 = col * tileF
            val y0 = row * tileF
            val hash = (col * 1664525L + row * 1013904223L).toInt()
            val n = when (((hash % 3) + 3) % 3) { 0 -> 1; 1 -> 2; else -> 3 }
            val subW = tileF / n
            val subH = tileF / n
            for (sr in 0 until n) {
                for (sc in 0 until n) {
                    val sx0 = x0 + sc * subW
                    val sy0 = y0 + sr * subH
                    // Secondary hash: occasionally split this sub-square into 2×2.
                    val subHash = ((col * 73L + sc) * 1664525L + (row * 73L + sr) * 1013904223L).toInt()
                    if (n >= 2 && ((subHash % 3) + 3) % 3 == 0) {
                        val ssW = subW / 2f
                        val ssH = subH / 2f
                        for (ssr in 0..1) {
                            for (ssc in 0..1) {
                                val ssx0 = sx0 + ssc * ssW
                                val ssy0 = sy0 + ssr * ssH
                                paint.color = sampler.sample(ssx0 + ssW / 2f, ssy0 + ssH / 2f)
                                canvas.drawRect(ssx0, ssy0, ssx0 + ssW, ssy0 + ssH, paint)
                            }
                        }
                    } else {
                        paint.color = sampler.sample(sx0 + subW / 2f, sy0 + subH / 2f)
                        canvas.drawRect(sx0, sy0, sx0 + subW, sy0 + subH, paint)
                    }
                }
            }
        }
    }
    return out
}

/**
 * Mixed2: overlapping circles, segment-sampled. Circle centres are arranged on a square
 * grid with spacing [tile]; radius is 62% of [tile] so adjacent circles overlap.
 * Every pixel is assigned to a segment identified by the set of circles covering it
 * (gap pixels use their nearest circle centre). One base-image colour is sampled per
 * segment at its centroid — producing crisp overlapping bubbles where every interior,
 * lens overlap, and gap is its own true colour.
 */
private fun mixed2Mosaic(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val r = tileF * 0.62f
    val r2 = r * r

    return segmentSampledBitmap(source) { x, y ->
        val xf = x.toFloat()
        val yf = y.toFloat()
        val baseCol = floor(xf / tileF).toInt()
        val baseRow = floor(yf / tileF).toInt()
        // Test the 3×3 neighbourhood of circle centres.
        var key = 1L
        var covered = false
        for (dRow in -1..1) {
            for (dCol in -1..1) {
                val col = baseCol + dCol
                val row = baseRow + dRow
                val cx = col * tileF + tileF * 0.5f
                val cy = row * tileF + tileF * 0.5f
                val dx = xf - cx; val dy = yf - cy
                if (dx * dx + dy * dy <= r2) {
                    key = key * 1_000_003L + col.toLong() * 10_000L + row.toLong()
                    covered = true
                }
            }
        }
        if (!covered) {
            // Gap: assign to nearest circle's Voronoi cell.
            val nearCol = ((xf / tileF) + 0.5f).toInt()
            val nearRow = ((yf / tileF) + 0.5f).toInt()
            -(nearCol.toLong() * 100_000L + nearRow.toLong() + 1_000_000_000L)
        } else {
            key
        }
    }
}

/**
 * Returns a compact Int identifying which triangle in an equilateral tessellation
 * covers pixel ([px], [py]). The grid uses [tileF] as the base size, [rowH] as the
 * row height (`tile * sqrt(3) / 2`), and is globally shifted by ([offsetX], [offsetY]).
 * Encodes (col, row, isTriangleB) for the rhombus cell the pixel falls in.
 */
private fun triangleIndexAt(
    px: Float, py: Float,
    tileF: Float, rowH: Float,
    offsetX: Float, offsetY: Float
): Int {
    val localY = py - offsetY
    val j = floor(localY / rowH).toInt()
    val rowOffsetX = j * tileF / 2f + offsetX
    val lx = px - rowOffsetX
    val i = floor(lx / tileF).toInt()
    // Which of the two triangles in rhombus (i,j)?
    // Shared edge: v10=(i*tileF+tileF, 0) → v01=(i*tileF+tileF/2, rowH) in local coords.
    // Cross product (edge direction) × (v10 → pixel):
    val lxRel = lx - i * tileF
    val lyRel = localY - j * rowH
    val cross = (-tileF / 2f) * lyRel - rowH * (lxRel - tileF)
    val isB = cross < 0f
    // Pack (i+1000, j+1000, isB) — safe for images where |i|,|j| < 32000.
    return ((i + 1000) shl 16) or ((j + 1000) shl 1) or (if (isB) 1 else 0)
}

/**
 * Mixed3: overlapping equilateral triangle grids, segment-sampled. Two triangle
 * tessellations are overlaid with a half-tile horizontal and half-row-height vertical
 * offset so their edges intersect at different angles. Every crossing region formed by
 * the union of both grids is its own segment, sampling one true base-image colour at
 * its centroid — crisp stained-glass polygons with no alpha-blending.
 */
private fun mixed3Mosaic(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val rowH = tile * sqrt(3f) / 2f
    val bOffsetX = tileF * 0.5f
    val bOffsetY = rowH * 0.5f

    return segmentSampledBitmap(source) { x, y ->
        val xf = x.toFloat()
        val yf = y.toFloat()
        val idA = triangleIndexAt(xf, yf, tileF, rowH, 0f, 0f)
        val idB = triangleIndexAt(xf, yf, tileF, rowH, bOffsetX, bOffsetY)
        idA.toLong() shl 32 or (idB.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * Mixed4: recursive equilateral triangle subdivision. Uses the same tessellation as
 * [triangleMosaic] but each base triangle is randomly either drawn solid (centroid-
 * sampled) or split into 4 sub-triangles — 3 corner triangles plus 1 central inverted
 * triangle — up to 2 levels deep. The result reads as triangles composed of smaller
 * triangles, with the depth and split pattern varying per cell.
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

    fun drawTri(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float,
        depth: Int,
        seed: Long
    ) {
        // ~50% chance to split at each level; stop at depth 2.
        val doSplit = depth < 2 && ((seed * 2654435761L).toInt() and 0x1) == 0
        if (doSplit) {
            val mabx = (ax + bx) / 2f; val maby = (ay + by) / 2f
            val mbcx = (bx + cx) / 2f; val mbcy = (by + cy) / 2f
            val mcax = (cx + ax) / 2f; val mcay = (cy + ay) / 2f
            val s = seed * 6364136223846793005L + 1442695040888963407L
            drawTri(ax, ay, mabx, maby, mcax, mcay, depth + 1, s)
            drawTri(mabx, maby, bx, by, mbcx, mbcy, depth + 1, s + 1L)
            drawTri(mcax, mcay, mbcx, mbcy, cx, cy, depth + 1, s + 2L)
            // Central inverted triangle.
            drawTri(mabx, maby, mbcx, mbcy, mcax, mcay, depth + 1, s + 3L)
        } else {
            paint.color = sampler.sample((ax + bx + cx) / 3f, (ay + by + cy) / 3f)
            path.rewind()
            path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    val jMin = -1
    val jMax = ceil(sH / rowH).toInt() + 1
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
            val baseSeed = i * 1664525L + j * 1013904223L
            drawTri(v00x, y0, v10x, y0, v01x, y1, 0, baseSeed)
            drawTri(v10x, y0, v11x, y1, v01x, y1, 0, baseSeed xor 0xDEAD_BEEFL)
        }
    }
    return out
}
