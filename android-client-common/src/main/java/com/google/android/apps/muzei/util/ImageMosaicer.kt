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

// ---------------------------------------------------------------------------
// Avalanche hash helpers — used by all Mixed variants.
// Replaces the old linear `col*A + row*B` hash (whose low bits are not random
// and produce visible checkerboard / lattice artifacts).
// ---------------------------------------------------------------------------

/** lowbias32 finalizer (Chris Wellons / hash-prospector): strong bit-avalanche. */
private fun mix32(x: Int): Int {
    var h = x
    h = h xor (h ushr 16); h *= 0x7feb352d
    h = h xor (h ushr 15); h *= 0x846ca68b.toInt()  // 0x846ca68b > Int.MAX_VALUE → Long literal, .toInt() reinterprets bits
    h = h xor (h ushr 16); return h
}

/** Two-dimensional integer hash with an independent salt per decision. */
private fun hashInt(x: Int, y: Int, salt: Int): Int =
    mix32(mix32(x + salt * 0x9E3779B1.toInt()) + y)  // 0x9E3779B1 > Int.MAX_VALUE → Long literal

/** Returns a float in [0, 1) derived from hash(x, y, salt). */
private fun hashUnit(x: Int, y: Int, salt: Int): Float =
    ((hashInt(x, y, salt) ushr 8) and 0xFFFFFF) / 16777216f

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
 * Mixed1: edge-aware sparse recursive square subdivision. Subdivision is gated on
 * local image smoothness — cells whose luminance gradient is below [EDGE_THRESHOLD]
 * (i.e. flat, low-detail areas) may subdivide; cells crossing an edge stay as a
 * single whole tile. This concentrates the glitch-art recursive blocks in smooth
 * regions and leaves edges crisp, enhancing the glitch-art look.
 *
 * Edge detection uses a cheap coarse pass: the source is downscaled to the cell grid
 * once (one bilinear-filtered scale), and each cell's gradient magnitude is the max
 * absolute luminance difference to its 4 neighbours. No per-pixel work on the full
 * image. Within the subdivision gate, all hash-driven 2×2 / 3×3 / secondary-split
 * decisions are unchanged from the non-edge-aware variant.
 *
 * [EDGE_THRESHOLD] ∈ [0,1]: lower → only flattest cells subdivide (more crisp edges,
 * fewer blocks); higher → more cells subdivide (closer to the non-edge-aware version).
 */
private const val EDGE_THRESHOLD = 0.12f

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

    // --- Coarse edge map ---
    // Downscale to the cell grid (+2 border cells for neighbour lookup at the edges).
    val mapW = numCols + 2
    val mapH = numRows + 2
    val thumb = source.scale(mapW, mapH, filter = true)
    val thumbPx = IntArray(mapW * mapH).also { thumb.getPixels(it, 0, mapW, 0, 0, mapW, mapH) }
    if (thumb != source) thumb.recycle()

    // Luminance Y = 0.299R + 0.587G + 0.114B, normalised to [0,1].
    fun lum(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8  and 0xFF) / 255f
        val b = (argb        and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
    // Gradient magnitude for cell (col,row): offset by +1 because thumb has a 1-cell border.
    fun edgeMag(col: Int, row: Int): Float {
        val ci = (col + 1).coerceIn(0, mapW - 1)
        val ri = (row + 1).coerceIn(0, mapH - 1)
        val centre = lum(thumbPx[ri * mapW + ci])
        val dxL = if (ci > 0)       kotlin.math.abs(centre - lum(thumbPx[ri * mapW + ci - 1])) else 0f
        val dxR = if (ci < mapW-1)  kotlin.math.abs(centre - lum(thumbPx[ri * mapW + ci + 1])) else 0f
        val dyU = if (ri > 0)       kotlin.math.abs(centre - lum(thumbPx[(ri-1) * mapW + ci])) else 0f
        val dyD = if (ri < mapH-1)  kotlin.math.abs(centre - lum(thumbPx[(ri+1) * mapW + ci])) else 0f
        return maxOf(dxL, dxR, dyU, dyD)
    }

    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val x0 = col * tileF
            val y0 = row * tileF
            // Only flat (low-edge) cells may subdivide; edge cells stay whole.
            val isFlat = edgeMag(col, row) < EDGE_THRESHOLD
            // 25% chance to subdivide (salt 0) — only when flat.
            if (!isFlat || hashUnit(col, row, 0) >= 0.25f) {
                paint.color = sampler.sample(x0 + tileF / 2f, y0 + tileF / 2f)
                canvas.drawRect(x0, y0, x0 + tileF, y0 + tileF, paint)
                continue
            }
            // Subdivide: 2×2 or 3×3 (salt 1).
            val n = if (hashUnit(col, row, 1) < 0.5f) 2 else 3
            val subW = tileF / n
            val subH = tileF / n
            for (sr in 0 until n) {
                for (sc in 0 until n) {
                    val sx0 = x0 + sc * subW
                    val sy0 = y0 + sr * subH
                    // Secondary: 25% chance to split this sub-square into 2×2 (salt 2).
                    if (hashUnit(col * 4 + sc, row * 4 + sr, 2) < 0.25f) {
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
 * Mixed2: translucent overlapping circles. A blurred base layer (bilinear
 * downscale → bilinear upscale) fills the canvas, then antialiased circles
 * at ~45% opacity are painted at jittered positions with varied radii. Overlapping
 * translucent discs blend (SRC_OVER) into combined colours, creating a soft
 * "colour blur" effect at circle intersections.
 */
private fun mixed2Mosaic(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val sW = source.width; val sH = source.height
    val sWf = sW.toFloat(); val sHf = sH.toFloat()

    // Base layer: smooth blur via bilinear downscale then bilinear upscale.
    val baseW = max(1, sW / tile); val baseH = max(1, sH / tile)
    val base = source.scale(baseW, baseH, filter = true)
    val (out, canvas) = newCanvasBitmap(source)
    val blitPaint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(base, Rect(0, 0, baseW, baseH), Rect(0, 0, sW, sH), blitPaint)
    if (base != source) base.recycle()

    val sampler = PixelSampler(source)
    val circlePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val colMin = -2; val colMax = ceil(sWf / tileF).toInt() + 2
    val rowMin = -2; val rowMax = ceil(sHf / tileF).toInt() + 2

    for (row in rowMin..rowMax) {
        for (col in colMin..colMax) {
            val jx = (hashUnit(col, row, 3) - 0.5f) * tileF    // ±0.5 tile
            val jy = (hashUnit(col, row, 4) - 0.5f) * tileF
            val r = (0.4f + hashUnit(col, row, 5) * 0.6f) * tileF  // [0.4, 1.0] * tile
            val cx = col * tileF + tileF * 0.5f + jx
            val cy = row * tileF + tileF * 0.5f + jy
            if (cx + r < 0 || cx - r > sWf || cy + r < 0 || cy - r > sHf) continue
            // Bake alpha ~45% (0x73) into the colour; paint.color= overwrites alpha.
            val rgb = sampler.sample(cx, cy) and 0x00FFFFFF
            circlePaint.color = (0x73 shl 24) or rgb
            canvas.drawCircle(cx, cy, r, circlePaint)
        }
    }
    return out
}

/**
 * Mixed3: irregular triangle mesh. An equilateral triangle lattice is rendered
 * with each vertex independently jittered (±30% of tile in x, ±30% of row-height
 * in y) via an avalanche hash. Because all three vertices of every shared edge use
 * the same hash function keyed on their grid coordinates, adjacent triangles share
 * identical jittered vertices — the mesh is watertight with no gaps or overlaps.
 * Each triangle is filled with the colour sampled at its (jittered) centroid.
 */
private fun mixed3Mosaic(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val path = Path()
    val tileF = tile.toFloat()
    val rowH = tile * sqrt(3f) / 2f
    val sW = source.width.toFloat(); val sH = source.height.toFloat()

    // Jittered lattice vertex L(i,j). Shared vertices compute identically → watertight.
    fun vx(i: Int, j: Int) = i * tileF + j * tileF / 2f + (hashUnit(i, j, 6) - 0.5f) * 0.6f * tileF
    fun vy(i: Int, j: Int) = j * rowH + (hashUnit(i, j, 7) - 0.5f) * 0.6f * rowH

    val jMin = -1; val jMax = ceil(sH / rowH).toInt() + 1
    for (j in jMin..jMax) {
        val rowOffsetX = j * (tileF / 2f)
        val iMin = floor(-rowOffsetX / tileF).toInt() - 2
        val iMax = ceil((sW - rowOffsetX) / tileF).toInt() + 2
        for (i in iMin..iMax) {
            // Triangle A: L(i,j), L(i+1,j), L(i,j+1)
            val ax = vx(i, j);   val ay = vy(i, j)
            val bx = vx(i+1, j); val by = vy(i+1, j)
            val cx = vx(i, j+1); val cy = vy(i, j+1)
            paint.color = sampler.sample((ax + bx + cx) / 3f, (ay + by + cy) / 3f)
            path.rewind(); path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy)
            path.close(); canvas.drawPath(path, paint)

            // Triangle B: L(i+1,j), L(i+1,j+1), L(i,j+1)
            val dx = vx(i+1, j+1); val dy = vy(i+1, j+1)
            paint.color = sampler.sample((bx + dx + cx) / 3f, (by + dy + cy) / 3f)
            path.rewind(); path.moveTo(bx, by); path.lineTo(dx, dy); path.lineTo(cx, cy)
            path.close(); canvas.drawPath(path, paint)
        }
    }
    return out
}

/**
 * Mixed4: translucent overlapping jittered triangle mesh. Forks [mixed3Mosaic]'s
 * irregular lattice, then draws each triangle expanded ~40% about its centroid so
 * adjacent triangles overlap. Triangles are drawn translucent (~50% alpha, antialiased)
 * over a bilinear-blurred base layer, exactly like [mixed2Mosaic]'s circles.
 * Overlapping translucent triangles blend (SRC_OVER) into combined colours — a soft
 * "colour blur" at every intersection.
 */
private fun mixed4Mosaic(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val sW = source.width; val sH = source.height
    val sWf = sW.toFloat(); val sHf = sH.toFloat()
    val rowH = tile * sqrt(3f) / 2f

    // Base layer: smooth blur via bilinear downscale then upscale (same as mixed2Mosaic).
    val baseW = max(1, sW / tile); val baseH = max(1, sH / tile)
    val base = source.scale(baseW, baseH, filter = true)
    val (out, canvas) = newCanvasBitmap(source)
    val blitPaint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(base, Rect(0, 0, baseW, baseH), Rect(0, 0, sW, sH), blitPaint)
    if (base != source) base.recycle()

    val sampler = PixelSampler(source)
    val triPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val path = Path()

    // Expand factor: vertices are pushed away from their centroid by this ratio so
    // adjacent triangles overlap and blend at their edges.
    val expand = 1.4f

    // Jittered lattice vertex (salts 8/9 → independent stream from mixed3Mosaic's 6/7).
    fun vx(i: Int, j: Int) = i * tileF + j * tileF / 2f + (hashUnit(i, j, 8) - 0.5f) * 0.6f * tileF
    fun vy(i: Int, j: Int) = j * rowH + (hashUnit(i, j, 9) - 0.5f) * 0.6f * rowH

    val jMin = -1; val jMax = ceil(sHf / rowH).toInt() + 1
    for (j in jMin..jMax) {
        val rowOffsetX = j * (tileF / 2f)
        val iMin = floor(-rowOffsetX / tileF).toInt() - 2
        val iMax = ceil((sWf - rowOffsetX) / tileF).toInt() + 2
        for (i in iMin..iMax) {
            // Triangle A vertices: L(i,j), L(i+1,j), L(i,j+1)
            val a0x = vx(i, j);   val a0y = vy(i, j)
            val a1x = vx(i+1, j); val a1y = vy(i+1, j)
            val a2x = vx(i, j+1); val a2y = vy(i, j+1)
            val agx = (a0x + a1x + a2x) / 3f; val agy = (a0y + a1y + a2y) / 3f
            val rgb4a = sampler.sample(agx, agy) and 0x00FFFFFF
            triPaint.color = (0x80 shl 24) or rgb4a
            path.rewind()
            // Expand each vertex away from the centroid.
            path.moveTo(agx + expand * (a0x - agx), agy + expand * (a0y - agy))
            path.lineTo(agx + expand * (a1x - agx), agy + expand * (a1y - agy))
            path.lineTo(agx + expand * (a2x - agx), agy + expand * (a2y - agy))
            path.close()
            canvas.drawPath(path, triPaint)

            // Triangle B vertices: L(i+1,j), L(i+1,j+1), L(i,j+1)
            val b0x = vx(i+1, j);   val b0y = vy(i+1, j)
            val b1x = vx(i+1, j+1); val b1y = vy(i+1, j+1)
            val b2x = vx(i, j+1);   val b2y = vy(i, j+1)
            val bgx = (b0x + b1x + b2x) / 3f; val bgy = (b0y + b1y + b2y) / 3f
            val rgb4b = sampler.sample(bgx, bgy) and 0x00FFFFFF
            triPaint.color = (0x80 shl 24) or rgb4b
            path.rewind()
            path.moveTo(bgx + expand * (b0x - bgx), bgy + expand * (b0y - bgy))
            path.lineTo(bgx + expand * (b1x - bgx), bgy + expand * (b1y - bgy))
            path.lineTo(bgx + expand * (b2x - bgx), bgy + expand * (b2y - bgy))
            path.close()
            canvas.drawPath(path, triPaint)
        }
    }
    return out
}
