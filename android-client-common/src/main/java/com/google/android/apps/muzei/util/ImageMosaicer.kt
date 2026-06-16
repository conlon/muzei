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
 * then samples one base-image colour per segment at the segment's centroid. Uses a
 * primitive open-addressing Long→Int map (zero boxing) so this is fast even at full
 * screen resolution.
 */
private fun segmentSampledBitmap(source: Bitmap, segmentIdOf: (x: Int, y: Int) -> Long): Bitmap {
    val w = source.width
    val h = source.height
    val pixelCount = w * h
    // Long.MIN_VALUE is the empty-slot sentinel; callers must not produce this key.
    val EMPTY = Long.MIN_VALUE

    // Primitive open-addressing Long→Int map; load factor kept ≤ 0.5.
    var cap = 64
    var hashKeys = LongArray(cap) { EMPTY }
    var hashVals = IntArray(cap)
    var mapSize = 0

    // Per-slot centroid accumulators (grown alongside the map).
    var aLen = 32
    var sumX = LongArray(aLen)
    var sumY = LongArray(aLen)
    var cnt  = IntArray(aLen)
    var slotCount = 0

    fun growAccumulators() { aLen *= 2; sumX = sumX.copyOf(aLen); sumY = sumY.copyOf(aLen); cnt = cnt.copyOf(aLen) }

    fun rehash() {
        val newCap = cap * 2; val mask = newCap - 1
        val nk = LongArray(newCap) { EMPTY }; val nv = IntArray(newCap)
        for (i in hashKeys.indices) {
            val k = hashKeys[i]
            if (k != EMPTY) {
                var p = (k * -7046029254386353131L).ushr(32).toInt() and mask
                while (nk[p] != EMPTY) p = (p + 1) and mask
                nk[p] = k; nv[p] = hashVals[i]
            }
        }
        cap = newCap; hashKeys = nk; hashVals = nv
    }

    fun intern(id: Long): Int {
        if (mapSize * 2 >= cap) { rehash(); if (aLen < cap / 2) growAccumulators() }
        val mask = cap - 1
        var p = (id * -7046029254386353131L).ushr(32).toInt() and mask
        while (true) {
            val k = hashKeys[p]
            if (k == EMPTY) {
                if (slotCount >= aLen) growAccumulators()
                hashKeys[p] = id; hashVals[p] = slotCount; mapSize++
                return slotCount++
            }
            if (k == id) return hashVals[p]
            p = (p + 1) and mask
        }
    }

    // Pass 1: assign segment slots and accumulate centroids.
    val slotPerPixel = IntArray(pixelCount)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val slot = intern(segmentIdOf(x, y))
            val idx = y * w + x
            slotPerPixel[idx] = slot
            sumX[slot] += x; sumY[slot] += y; cnt[slot]++
        }
    }

    // Sample one colour per segment at its centroid.
    val sampler = PixelSampler(source)
    val colors = IntArray(slotCount)
    for (s in 0 until slotCount) {
        colors[s] = sampler.sample((sumX[s] / cnt[s]).toFloat(), (sumY[s] / cnt[s]).toFloat())
    }

    // Pass 2: map pixels to colours.
    val pixels = IntArray(pixelCount) { colors[slotPerPixel[it]] }
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
 * Mixed1: sparse recursive square subdivision. 25% of cells subdivide into 2×2 or
 * 3×3 (chosen by hash); within those, 25% of sub-squares split once more into 2×2.
 * Most cells (75%) stay whole, so subdivision reads as occasional scattered detail
 * rather than a uniform pattern.
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
            // Natural Int overflow is fine here — we just need good bit distribution.
            val hash = col * 1664525 + row * 1013904223
            // 25% chance to subdivide (bottom 2 bits both zero).
            if (hash and 0x3 != 0) {
                paint.color = sampler.sample(x0 + tileF / 2f, y0 + tileF / 2f)
                canvas.drawRect(x0, y0, x0 + tileF, y0 + tileF, paint)
                continue
            }
            // Subdivide: 2×2 or 3×3 (bit 2 of hash).
            val n = if (hash ushr 2 and 0x1 == 0) 2 else 3
            val subW = tileF / n
            val subH = tileF / n
            for (sr in 0 until n) {
                for (sc in 0 until n) {
                    val sx0 = x0 + sc * subW
                    val sy0 = y0 + sr * subH
                    // Secondary: 25% chance to split this sub-square into 2×2.
                    val subHash = (col * 73 + sc) * 1664525 + (row * 73 + sr) * 1013904223
                    if (subHash and 0x3 == 0) {
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
 * Mixed2: overlapping circles, segment-sampled. Circles are placed on a grid of
 * spacing [tile] but each circle's centre is independently jittered (up to ±35% of
 * [tile]) and its radius varied ([50%, 75%] of [tile]) via per-cell hashes. A 5×5
 * neighbourhood is scanned so the wider reach of jittered large circles is covered.
 * Every distinct combination of covering circles (and each gap's Voronoi cell) is
 * its own segment, sampling one true base-image colour at its centroid.
 */
private fun mixed2Mosaic(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()

    return segmentSampledBitmap(source) { x, y ->
        val xf = x.toFloat()
        val yf = y.toFloat()
        val baseCol = floor(xf / tileF).toInt()
        val baseRow = floor(yf / tileF).toInt()

        var key = 1L
        var covered = false
        // 5×5 window: jitter up to ±0.35·tile + radius up to 0.75·tile → reach ≤ 1.1·tile.
        for (dRow in -2..2) {
            for (dCol in -2..2) {
                val col = baseCol + dCol
                val row = baseRow + dRow
                // Two hash passes for independent axes / radius.
                val h1 = col * 1664525 + row * 1013904223
                val h2 = h1 * -1640531535  // second Knuth pass (2654435761.toInt())
                // Centre jitter: ±0.35·tile on each axis.
                val jx = ((h1 and 0xFFFF).toFloat() / 65536f - 0.5f) * 0.7f * tileF
                val jy = ((h2 and 0xFFFF).toFloat() / 65536f - 0.5f) * 0.7f * tileF
                // Radius: 50%–75% of tile.
                val r = (0.5f + ((h1 ushr 16 and 0xFF).toFloat() / 255f) * 0.25f) * tileF
                val cx = col * tileF + tileF * 0.5f + jx
                val cy = row * tileF + tileF * 0.5f + jy
                val dx = xf - cx; val dy = yf - cy
                if (dx * dx + dy * dy <= r * r) {
                    key = key * 1_000_003L + col.toLong() * 10_000L + row.toLong()
                    covered = true
                }
            }
        }
        if (!covered) {
            val nearCol = ((xf / tileF) + 0.5f).toInt()
            val nearRow = ((yf / tileF) + 0.5f).toInt()
            -(nearCol.toLong() * 100_000L + nearRow.toLong() + 1_000_000_000L)
        } else {
            // Guard against the EMPTY sentinel used by segmentSampledBitmap.
            if (key == Long.MIN_VALUE) key + 1L else key
        }
    }
}

/**
 * Returns a compact Int identifying which triangle in an equilateral tessellation
 * covers pixel ([px], [py]). The grid uses [tileF] as the base size, [rowH] as the
 * row height (`tile * sqrt(3) / 2`), and is globally shifted by ([offsetX], [offsetY]).
 *
 * The rhombus row-offset makes the cell boundaries diagonal, so the naive `floor` cell
 * can contain a left-wedge strip [fx < 0.5·fy] that geometrically belongs to triangle B
 * of the previous rhombus. This function handles all three cases correctly.
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
    val lxRel = lx - i * tileF  // ∈ [0, tileF)
    val lyRel = localY - j * rowH // ∈ [0, rowH)
    val fx = lxRel / tileF  // ∈ [0, 1)
    val fy = lyRel / rowH   // ∈ [0, 1)

    return when {
        fx < 0.5f * fy -> {
            // Left wedge: belongs to triangle B of rhombus (i−1, j).
            val ci = i - 1
            ((ci + 1000) shl 16) or ((j + 1000) shl 1) or 1
        }
        fx > 1f - 0.5f * fy -> {
            // Right wedge within rhombus i: triangle B of (i, j).
            ((i + 1000) shl 16) or ((j + 1000) shl 1) or 1
        }
        else -> {
            // Triangle A of rhombus (i, j).
            ((i + 1000) shl 16) or ((j + 1000) shl 1) or 0
        }
    }
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
 * Mixed4: sparse recursive equilateral triangle subdivision. Uses the same tessellation
 * as [triangleMosaic] but each base triangle has a 25% chance of splitting into 4
 * sub-triangles (3 corner + 1 central inverted), and each of those has a 25% chance
 * of splitting once more (depth cap 2). Most triangles stay whole; subdivision reads
 * as scattered, irregular detail.
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
        // ~25% chance to split at each level; stop at depth 2.
        val doSplit = depth < 2 && ((seed * 2654435761L).toInt() and 0x3) == 0
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
