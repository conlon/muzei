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

enum class MosaicShape { SQUARE, TRIANGLE, HEXAGON, MIXED1, MIXED2, MIXED3, MIXED4, GLITCH }

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
    glitchDisplacement: Int = 250,
    glitchChannelSplit: Int = 250,
    glitchPixelSort: Int = 250,
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
        MosaicShape.GLITCH -> glitchMosaic(source, tile, glitchDisplacement, glitchChannelSplit, glitchPixelSort)
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
 * Mixed1: sparse recursive square subdivision. Driven by an avalanche hash so
 * each cell's decision is independent of its neighbours (no checkerboard artifact).
 * 25% of cells subdivide into 2×2 or 3×3; within those, 25% of sub-squares split
 * once more into 2×2. Most cells (75%) stay whole.
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
            // 25% chance to subdivide (salt 0).
            if (hashUnit(col, row, 0) >= 0.25f) {
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
 * Mixed4: sparse recursive equilateral triangle subdivision. Uses the same tessellation
 * as [triangleMosaic] but each base triangle has a 25% chance of splitting into 4
 * sub-triangles (3 corner + 1 central inverted), and each of those has a 25% chance
 * of splitting once more (depth cap 2). Most triangles stay whole; subdivision reads
 * as scattered, irregular detail.
 *
 * Split decisions are driven by [mix32] avalanche hashes of a node-local integer key
 * so adjacent triangles are uncorrelated (no checkerboard / lattice artifacts).
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
        nodeHash: Int
    ) {
        // 25%: top 2 bits of avalanche-mixed node hash are both 0.
        val doSplit = depth < 2 && mix32(nodeHash) ushr 30 == 0
        if (doSplit) {
            val mabx = (ax + bx) / 2f; val maby = (ay + by) / 2f
            val mbcx = (bx + cx) / 2f; val mbcy = (by + cy) / 2f
            val mcax = (cx + ax) / 2f; val mcay = (cy + ay) / 2f
            drawTri(ax, ay, mabx, maby, mcax, mcay, depth + 1, nodeHash * 4 + 1)
            drawTri(mabx, maby, bx, by, mbcx, mbcy, depth + 1, nodeHash * 4 + 2)
            drawTri(mcax, mcay, mbcx, mbcy, cx, cy, depth + 1, nodeHash * 4 + 3)
            // Central inverted triangle.
            drawTri(mabx, maby, mbcx, mbcy, mcax, mcay, depth + 1, nodeHash * 4 + 4)
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
            drawTri(v00x, y0, v10x, y0, v01x, y1, 0, hashInt(i, j, 202))
            drawTri(v10x, y0, v11x, y1, v01x, y1, 0, hashInt(i, j, 101))
        }
    }
    return out
}

/**
 * Glitch: datamosh variation of Mixed1's recursive-block subdivision. After rendering
 * the base recursive blocks (all cells, no edge gate), four glitch passes run on a
 * flat IntArray pixel buffer (no boxing — avoids GC spikes):
 *
 *   1. Horizontal band displacement — block shifts and scanline tears, with wrap-around
 *      and occasional frozen rows (sync-error streaks). Scaled by [displacement].
 *   2. Vertical band displacement — same structure but along columns. Scaled by
 *      [displacement]. Salt 16.
 *   3. Pixel sorting — a sparse subset of rows sorted by luminance, producing melted
 *      gradient streaks. Scaled by [pixelSort]. Salt 14.
 *   4. 2-D RGB channel split (chromatic aberration) — R and B channels sampled from
 *      offset (x,y) positions; horizontal bands (salt 15) and vertical bands (salt 17)
 *      independently randomise the offset magnitude. Scaled by [channelSplit].
 *
 * All three strengths are 0–500, where 250 is the design baseline and 0 fully disables
 * that technique. All randomness uses salts 10–17 (independent of other Mixed variants).
 */
private fun glitchMosaic(
    source: Bitmap,
    tile: Int,
    displacement: Int = 250,
    channelSplit: Int = 250,
    pixelSort: Int = 250,
): Bitmap {
    // ---- Normalised strengths [0.0, 1.0] per technique -------------------
    val td = displacement / 500f   // displacement (horizontal + vertical)
    val tc = channelSplit / 500f   // chromatic aberration
    val ts = pixelSort / 500f      // pixel sorting

    // ---- Tunable constants (documented; scale with strength) -------------
    /** Fraction of displacement bands that hold zero offset. */
    val ZERO_BAND_FRAC   = 0.35f
    /** Max horizontal displacement: fraction of image width. At td=0.5 → 0.25·sW. */
    val DISP_H_MAX_FRAC  = td * 0.5f
    /** Max vertical displacement: fraction of image height. At td=0.5 → 0.25·sH. */
    val DISP_V_MAX_FRAC  = td * 0.5f
    /** Fraction of displacement bands that are long block shifts (vs short tears). */
    val BLOCK_BAND_FRAC  = 0.40f
    /** Block-shift run length range (multiples of tile). */
    val BLOCK_MIN_TILES  = 1; val BLOCK_MAX_TILES = 3
    /** Scanline / column-tear run length range (rows or columns). */
    val TEAR_MIN = 1; val TEAR_MAX = 4
    /** Per-row probability of a "frozen" (repeat-previous-row) sync-error glitch. */
    val FROZEN_ROW_FRAC  = 0.03f
    /** Fraction of rows sorted. At ts=0.5 → 0.15. */
    val SORT_ROW_FRAC    = ts * 0.30f
    /** Max pixel-sort run as a fraction of width. At ts=0.5 → 0.30. */
    val SORT_MAX_FRAC    = ts * 0.60f
    /** Minimum channel-split offset (px). At tc=0.5 → 4px. */
    val CA_MIN = (tc * 8f).toInt().coerceAtLeast(0)
    /** Maximum channel-split offset (px). At tc=0.5 → 16px. */
    val CA_MAX = (tc * 32f).toInt().coerceAtLeast(CA_MIN)
    // -----------------------------------------------------------------------

    val sW = source.width
    val sH = source.height

    // ------------------------------------------------------------------
    // Stage 1: Render the recursive-block base — same structure as
    // mixed1Mosaic but without the edge-aware gate so the whole frame
    // is busy. Salts 10, 11, 12.
    // ------------------------------------------------------------------
    val (out, canvas) = newCanvasBitmap(source)
    val sampler = PixelSampler(source)
    val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        style = Paint.Style.FILL
    }
    val tileF = tile.toFloat()
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1
    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val x0 = col * tileF
            val y0 = row * tileF
            // 25% chance to stay whole (salt 10).
            if (hashUnit(col, row, 10) >= 0.25f) {
                paint.color = sampler.sample(x0 + tileF / 2f, y0 + tileF / 2f)
                canvas.drawRect(x0, y0, x0 + tileF, y0 + tileF, paint)
                continue
            }
            // Subdivide into 2×2 or 3×3 (salt 11).
            val n = if (hashUnit(col, row, 11) < 0.5f) 2 else 3
            val subW = tileF / n
            val subH = tileF / n
            for (sr in 0 until n) {
                for (sc in 0 until n) {
                    val sx0 = x0 + sc * subW
                    val sy0 = y0 + sr * subH
                    // 25% chance to split sub-square into 2×2 (salt 12).
                    if (hashUnit(col * 4 + sc, row * 4 + sr, 12) < 0.25f) {
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

    // Flatten the rendered bitmap into a primitive pixel array for the remaining passes.
    val buf = IntArray(sW * sH)
    out.getPixels(buf, 0, sW, 0, 0, sW, sH)

    // ------------------------------------------------------------------
    // Stage 2a: Horizontal band displacement. Each band holds a fixed x-offset
    // (content wraps around) for a run of rows. Long runs → block shifts;
    // short runs → scanline tears. Occasional rows repeat the previous row
    // (sync-error freeze). Salt 13.
    // ------------------------------------------------------------------
    val rowTmp  = IntArray(sW)
    val prevRow = IntArray(sW)
    var hasPrev = false
    var hRunLeft = 0
    var hOffset  = 0

    for (y in 0 until sH) {
        if (hRunLeft <= 0) {
            val h0 = hashUnit(y, 0, 13)   // zero-band gate
            val h1 = hashUnit(y, 1, 13)   // block vs tear
            val h2 = hashUnit(y, 2, 13)   // run-length fraction
            val h3 = hashUnit(y, 3, 13)   // offset magnitude
            val h4 = hashUnit(y, 4, 13)   // offset sign
            val isBlock = h1 < BLOCK_BAND_FRAC
            hRunLeft = if (isBlock) {
                ((BLOCK_MIN_TILES + (h2 * (BLOCK_MAX_TILES - BLOCK_MIN_TILES + 1)).toInt()) * tile)
                    .coerceAtLeast(1)
            } else {
                (TEAR_MIN + (h2 * (TEAR_MAX - TEAR_MIN + 1)).toInt()).coerceAtLeast(1)
            }
            hOffset = if (h0 < ZERO_BAND_FRAC || DISP_H_MAX_FRAC <= 0f) {
                0
            } else {
                val mag = (h3 * DISP_H_MAX_FRAC * sW).toInt()
                if (h4 < 0.5f) mag else -mag
            }
        }
        hRunLeft--

        val rowStart = y * sW
        val isFrozen = hasPrev && hashUnit(y, 5, 13) < FROZEN_ROW_FRAC
        if (isFrozen) {
            System.arraycopy(prevRow, 0, buf, rowStart, sW)
        } else if (hOffset != 0) {
            System.arraycopy(buf, rowStart, rowTmp, 0, sW)
            val off = ((hOffset % sW) + sW) % sW
            System.arraycopy(rowTmp, off, buf, rowStart, sW - off)
            System.arraycopy(rowTmp, 0, buf, rowStart + sW - off, off)
        }
        System.arraycopy(buf, rowStart, prevRow, 0, sW)
        hasPrev = true
    }

    // ------------------------------------------------------------------
    // Stage 2b: Vertical band displacement. Same structure as 2a but along
    // columns — each column-band holds a fixed y-offset with wrap-around.
    // Column reads and writes are strided (one element per sW step). Salt 16.
    // ------------------------------------------------------------------
    val colTmp = IntArray(sH)
    var vRunLeft = 0
    var vOffset  = 0

    for (x in 0 until sW) {
        if (vRunLeft <= 0) {
            val h0 = hashUnit(x, 0, 16)
            val h1 = hashUnit(x, 1, 16)
            val h2 = hashUnit(x, 2, 16)
            val h3 = hashUnit(x, 3, 16)
            val h4 = hashUnit(x, 4, 16)
            val isBlock = h1 < BLOCK_BAND_FRAC
            vRunLeft = if (isBlock) {
                ((BLOCK_MIN_TILES + (h2 * (BLOCK_MAX_TILES - BLOCK_MIN_TILES + 1)).toInt()) * tile)
                    .coerceAtLeast(1)
            } else {
                (TEAR_MIN + (h2 * (TEAR_MAX - TEAR_MIN + 1)).toInt()).coerceAtLeast(1)
            }
            vOffset = if (h0 < ZERO_BAND_FRAC || DISP_V_MAX_FRAC <= 0f) {
                0
            } else {
                val mag = (h3 * DISP_V_MAX_FRAC * sH).toInt()
                if (h4 < 0.5f) mag else -mag
            }
        }
        vRunLeft--
        if (vOffset != 0) {
            // Read column x into scratch, shift, write back.
            for (y in 0 until sH) { colTmp[y] = buf[y * sW + x] }
            val off = ((vOffset % sH) + sH) % sH
            for (y in 0 until sH - off) { buf[y * sW + x] = colTmp[y + off] }
            for (y in sH - off until sH) { buf[y * sW + x] = colTmp[y + off - sH] }
        }
    }

    // ------------------------------------------------------------------
    // Stage 3: Pixel sorting. A sparse subset of rows get one contiguous
    // run sorted by luminance to produce melted gradient streaks.
    // Encoding: (lum xor 0x80) in bits 31–24 maps 0..255 onto a
    // monotonically increasing signed integer so IntArray.sort() gives
    // ascending luminance order; position in bits 23–0. Salt 14.
    // ------------------------------------------------------------------
    val maxRun  = (sW * SORT_MAX_FRAC).toInt().coerceAtLeast(2)
    val sortKey = IntArray(maxRun)
    val sortPix = IntArray(maxRun)

    for (y in 0 until sH) {
        if (hashUnit(y, 0, 14) >= SORT_ROW_FRAC) continue
        val h1     = hashUnit(y, 1, 14)
        val h2     = hashUnit(y, 2, 14)
        val runLen = (h1 * maxRun).toInt().coerceIn(2, maxRun)
        val start  = (h2 * (sW - runLen)).toInt().coerceIn(0, sW - runLen)
        val base   = y * sW + start
        for (i in 0 until runLen) {
            val px  = buf[base + i]
            val r   = (px shr 16) and 0xFF
            val g   = (px shr 8)  and 0xFF
            val b   = px and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) ushr 8
            sortPix[i] = px
            sortKey[i] = ((lum xor 0x80) shl 24) or i
        }
        sortKey.sort(0, runLen)
        for (d in 0 until runLen) {
            buf[base + d] = sortPix[sortKey[d] and 0x00FFFFFF]
        }
    }

    // ------------------------------------------------------------------
    // Stage 4: 2-D RGB channel split (chromatic aberration).
    //   R sampled from (x−dRx, y−dRy);  B from (x+dBx, y+dBy);  G at (x,y).
    // Horizontal offsets (dRx/dBx) from per-row bands (salt 15); vertical
    // offsets (dRy/dBy) from per-column bands precomputed below (salt 17).
    // All offsets range from CA_MIN (base) to CA_MAX (amplified bands).
    // ------------------------------------------------------------------

    // Precompute vertical CA offset per column (salt 17).
    val colVDr = IntArray(sW)
    val colVDb = IntArray(sW)
    var cvLeft = 0; var cvDr = CA_MIN; var cvDb = CA_MIN
    for (x in 0 until sW) {
        if (cvLeft <= 0) {
            val hv0 = hashUnit(x, 0, 17)
            val hv1 = hashUnit(x, 1, 17)
            val hv2 = hashUnit(x, 2, 17)
            cvLeft = (1 + (hv0 * tile * 3f).toInt()).coerceAtLeast(1)
            val vExtra = (hv1 * (CA_MAX - CA_MIN).toFloat()).toInt()
            if (hv2 < 0.30f) { cvDr = CA_MIN; cvDb = CA_MIN }
            else             { cvDr = CA_MIN + vExtra; cvDb = CA_MIN + vExtra }
        }
        cvLeft--
        colVDr[x] = cvDr; colVDb[x] = cvDb
    }

    val out2   = IntArray(sW * sH)
    var caLeft = 0
    var caDr   = CA_MIN
    var caDb   = CA_MIN

    for (y in 0 until sH) {
        if (caLeft <= 0) {
            val h0 = hashUnit(y, 0, 15)
            val h1 = hashUnit(y, 1, 15)
            val h2 = hashUnit(y, 2, 15)
            caLeft = (1 + (h0 * tile * 3f).toInt()).coerceAtLeast(1)
            val extra = (h1 * (CA_MAX - CA_MIN).toFloat()).toInt()
            if (h2 < 0.30f) { caDr = CA_MIN; caDb = CA_MIN }
            else             { caDr = CA_MIN + extra; caDb = CA_MIN + extra }
        }
        caLeft--
        val rowStart = y * sW
        for (x in 0 until sW) {
            val xR = (x - caDr).coerceIn(0, sW - 1)
            val xB = (x + caDb).coerceIn(0, sW - 1)
            val yR = (y - colVDr[x]).coerceIn(0, sH - 1)
            val yB = (y + colVDb[x]).coerceIn(0, sH - 1)
            val pr = buf[yR * sW + xR]
            val pg = buf[rowStart + x]
            val pb = buf[yB * sW + xB]
            out2[rowStart + x] = 0xFF000000.toInt() or
                    (pr and 0x00FF0000) or
                    (pg and 0x0000FF00) or
                    (pb and 0x000000FF)
        }
    }

    out.setPixels(out2, 0, sW, 0, 0, sW, sH)
    return out
}
