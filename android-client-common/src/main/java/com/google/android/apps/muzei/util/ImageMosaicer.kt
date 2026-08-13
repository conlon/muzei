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
import android.graphics.RectF
import androidx.core.graphics.scale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Shape primitives (row 1 in UI). RANDOM kept in logic only, not shown in UI chips.
enum class MosaicShape { SQUARE, EQUILATERAL, IRREGULAR, HEXAGON, CIRCLE, RANDOM }

// Filters (row 2 in UI). Applied on top of any shape.
// Salt assignments:
//   0-2  : RECURSIVE subdivision (gate / 2×2-vs-3×3 / secondary)
//   3-5  : RAINDROP circles (jitter-x / jitter-y / radius)
//   6-7  : IRREGULAR base vertex jitter (x / y)
//   8-9  : IRREGULAR raindrop vertex jitter (x / y)
//   10-12: unused (were GLITCH internal recursive-block base)
//   13   : GLITCH1 H-displacement bands
//   14   : GLITCH1+GLITCH2 pixel sort
//   15   : GLITCH1+GLITCH2 channel-split H-bands
//   16   : GLITCH1 V-displacement bands
//   17   : GLITCH1+GLITCH2 channel-split V-bands
//   18   : GLITCH2 edge-weighted H-displacement
//   19   : GLITCH2 edge-weighted V-displacement
//   20   : RECURSIVE triangle subdivision gate
//   21   : RECURSIVE hexagon child count + corner placement
//   22   : GLITCH2 H chunk start / length / sign
//   23   : GLITCH2 H per-row smear random-walk step
//   24   : GLITCH2 V chunk start / length / sign
//   25   : GLITCH2 V per-col smear random-walk step
//   26   : GLITCH2 pixel-tear chunk start + length
//   27   : GLITCH2 pixel-tear per-row band (run length + start)
enum class MosaicFilter { NONE, RAINDROP, GLITCH1, GLITCH2, RECURSIVE }

/**
 * Two-stage mosaic pipeline:
 *   Stage 1 — build the shape base (a tiling of [source] in the chosen [shape] primitive).
 *   Stage 2 — apply [filter] to that base.
 *
 * GLITCH1/GLITCH2 filters bypass the tile≤1 early-return so they run even at minimum tile
 * size (the shape base collapses to a copy of source, but the glitch passes still execute).
 */
fun mosaicBitmap(
    source: Bitmap?,
    tileSizePx: Int,
    shape: MosaicShape = MosaicShape.SQUARE,
    filter: MosaicFilter = MosaicFilter.NONE,
    glitchHDisplacement: Int = 250,
    glitchVDisplacement: Int = 250,
    glitchChannelSplit: Int = 250,
    glitchPixelSort: Int = 250,
    subjectRegions: List<RectF>? = null,
): Bitmap? {
    if (source == null || source.width == 0 || source.height == 0) return null
    val tile = max(1, tileSizePx)
    val isGlitch = filter == MosaicFilter.GLITCH1 || filter == MosaicFilter.GLITCH2
    if (tile <= 1 && !isGlitch) {
        return source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
    }
    // RAINDROP and RECURSIVE are self-contained (don't need a separate shape-base step).
    if (filter == MosaicFilter.RAINDROP) return raindropMosaic(source, tile, shape)
    if (filter == MosaicFilter.RECURSIVE) return recursiveMosaic(source, tile, shape)

    // Stage 1: shape base
    val base = shapeBaseMosaic(source, tile, shape)

    // Stage 2: filter
    return when (filter) {
        MosaicFilter.NONE      -> base
        MosaicFilter.GLITCH1   -> glitch1Filter(base, tile, glitchHDisplacement, glitchVDisplacement, glitchChannelSplit, glitchPixelSort)
        MosaicFilter.GLITCH2   -> glitch2Filter(base, source, tile, glitchHDisplacement, glitchVDisplacement, glitchChannelSplit, glitchPixelSort, subjectRegions)
        MosaicFilter.RAINDROP  -> base  // handled by early dispatch above; unreachable
        MosaicFilter.RECURSIVE -> base  // handled by early dispatch above; unreachable
    }
}

private fun shapeBaseMosaic(source: Bitmap, tile: Int, shape: MosaicShape): Bitmap = when (shape) {
    MosaicShape.SQUARE     -> squareMosaic(source, tile)
    MosaicShape.EQUILATERAL -> triangleMosaic(source, tile)
    MosaicShape.IRREGULAR  -> irregularMosaic(source, tile)
    MosaicShape.HEXAGON    -> hexagonMosaic(source, tile)
    MosaicShape.CIRCLE     -> circleMosaicBase(source, tile)
    MosaicShape.RANDOM     -> squareMosaic(source, tile)
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
 * Circle mosaic base (CIRCLE shape, NONE filter). Draws a regular grid of opaque
 * circles on a bilinear-blurred base layer. The blurred base fills the inter-circle
 * gaps so the background is a soft version of the source rather than black.
 */
private fun circleMosaicBase(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val sW = source.width; val sH = source.height
    val baseW = max(1, sW / tile); val baseH = max(1, sH / tile)
    val base = source.scale(baseW, baseH, filter = true)
    val (out, canvas) = newCanvasBitmap(source)
    val blitPaint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(base, Rect(0, 0, baseW, baseH), Rect(0, 0, sW, sH), blitPaint)
    if (base != source) base.recycle()
    val sampler = PixelSampler(source)
    val circlePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    val r = tileF * 0.5f
    val colMin = -1; val colMax = ceil(sW.toFloat() / tileF).toInt() + 1
    val rowMin = -1; val rowMax = ceil(sH.toFloat() / tileF).toInt() + 1
    for (row in rowMin..rowMax) {
        for (col in colMin..colMax) {
            val cx = col * tileF + tileF * 0.5f
            val cy = row * tileF + tileF * 0.5f
            if (cx + r < 0 || cx - r > sW || cy + r < 0 || cy - r > sH) continue
            circlePaint.color = sampler.sample(cx, cy) or (0xFF shl 24)
            canvas.drawCircle(cx, cy, r, circlePaint)
        }
    }
    return out
}

/**
 * RECURSIVE filter dispatcher. Routes to the shape-native implementation:
 * SQUARE/RANDOM → square-grid subdivision (preserves legacy "mixed1" look).
 * EQUILATERAL/IRREGULAR → midpoint triangle subdivision (no-gap fill).
 * HEXAGON → hex grid + nestled child hexagons.
 * CIRCLE → square-grid circles over a blurred base (dot-matrix with sampled gaps).
 */
private fun recursiveMosaic(source: Bitmap, tile: Int, shape: MosaicShape): Bitmap = when (shape) {
    MosaicShape.EQUILATERAL -> recursiveTriangleImpl(source, tile, jitter = false)
    MosaicShape.IRREGULAR   -> recursiveTriangleImpl(source, tile, jitter = true)
    MosaicShape.HEXAGON     -> recursiveHexImpl(source, tile)
    MosaicShape.CIRCLE      -> recursiveCircleImpl(source, tile)
    else                    -> recursiveSquareImpl(source, tile, shape)
}

private const val EDGE_THRESHOLD = 0.12f

// ---------------------------------------------------------------------------
// Shared coarse-edge-map builder. Returns a lambda edgeMag(col, row) → [0,1].
// The thumb is scaled to (numCols+2) × (numRows+2) for 1-cell border lookup.
// ---------------------------------------------------------------------------
private data class CoarseEdgeMap(
    val mapW: Int,
    val mapH: Int,
    val thumbPx: IntArray,
) {
    private fun lum(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8  and 0xFF) / 255f
        val b = (argb        and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
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
}

private fun buildCoarseEdgeMap(source: Bitmap, numCols: Int, numRows: Int): CoarseEdgeMap {
    val mapW = numCols + 2; val mapH = numRows + 2
    val thumb = source.scale(mapW, mapH, filter = true)
    val px = IntArray(mapW * mapH).also { thumb.getPixels(it, 0, mapW, 0, 0, mapW, mapH) }
    if (thumb != source) thumb.recycle()
    return CoarseEdgeMap(mapW, mapH, px)
}

/**
 * Square-grid recursive subdivision. Preserves the legacy "mixed1" look for
 * SQUARE and RANDOM shapes. Salts 0/1/2.
 */
private fun recursiveSquareImpl(source: Bitmap, tile: Int, shape: MosaicShape): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply { isAntiAlias = false; isDither = false; style = Paint.Style.FILL }
    val sW = source.width; val sH = source.height; val tileF = tile.toFloat()
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1
    val em = buildCoarseEdgeMap(source, numCols, numRows)
    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val x0 = col * tileF; val y0 = row * tileF
            val isFlat = em.edgeMag(col, row) < EDGE_THRESHOLD
            if (!isFlat || hashUnit(col, row, 0) >= 0.25f) {
                paint.color = sampler.sample(x0 + tileF / 2f, y0 + tileF / 2f)
                drawShapeInBounds(canvas, paint, shape, x0, y0, tileF, col, row)
                continue
            }
            val n = if (hashUnit(col, row, 1) < 0.5f) 2 else 3
            val subW = tileF / n; val subH = tileF / n
            for (sr in 0 until n) {
                for (sc in 0 until n) {
                    val sx0 = x0 + sc * subW; val sy0 = y0 + sr * subH
                    if (hashUnit(col * 4 + sc, row * 4 + sr, 2) < 0.25f) {
                        val ssW = subW / 2f; val ssH = subH / 2f
                        for (ssr in 0..1) for (ssc in 0..1) {
                            val ssx0 = sx0 + ssc * ssW; val ssy0 = sy0 + ssr * ssH
                            paint.color = sampler.sample(ssx0 + ssW / 2f, ssy0 + ssH / 2f)
                            drawShapeInBounds(canvas, paint, shape, ssx0, ssy0, ssW, col * 4 + sc, row * 4 + sr)
                        }
                    } else {
                        paint.color = sampler.sample(sx0 + subW / 2f, sy0 + subH / 2f)
                        drawShapeInBounds(canvas, paint, shape, sx0, sy0, subW, col * 4 + sc, row * 4 + sr)
                    }
                }
            }
        }
    }
    return out
}

/**
 * Triangle-native recursive subdivision (EQUILATERAL and IRREGULAR shapes).
 *
 * Matches [triangleMosaic] / [irregularMosaic]'s top-level tessellation exactly
 * (same rhombus layout with rowH = tile·√3/2). Each top-level triangle is
 * recursively subdivided by midpoint subdivision into 4 child triangles that
 * together form a watertight cover — no gaps. Subdivision is edge-aware: only
 * cells whose edgeMag ≥ EDGE_THRESHOLD get children, up to maxDepth=2.
 *
 * For IRREGULAR ([jitter]=true), the top-level lattice vertices are jittered
 * (salts 6/7) exactly as in [irregularMosaic]. Sub-vertices are midpoints —
 * never jittered — to keep the subdivision watertight.
 *
 * Salt 20 gates random continuation of subdivision within qualifying cells.
 */
private fun recursiveTriangleImpl(source: Bitmap, tile: Int, jitter: Boolean): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val paint = Paint().apply { isAntiAlias = false; isDither = false; style = Paint.Style.FILL }
    val path = Path()
    val sW = source.width.toFloat(); val sH = source.height.toFloat()
    val tileF = tile.toFloat()
    val rowH = tileF * sqrt(3f) / 2f

    // Jittered lattice vertex — identical to triangleMosaic / irregularMosaic
    // so the top layer aligns with the None filter output.
    fun lx(i: Int, j: Int): Float = i * tileF + j * tileF / 2f +
        if (jitter) (hashUnit(i, j, 6) - 0.5f) * 0.6f * tileF else 0f
    fun ly(i: Int, j: Int): Float = j * rowH +
        if (jitter) (hashUnit(i, j, 7) - 0.5f) * 0.6f * rowH else 0f

    // Edge map grid dimension: same as triangleMosaic's i/j range.
    val jMin = -1; val jMax = ceil(sH / rowH).toInt() + 1
    val iRangeMax = ceil(sW / tileF).toInt() + 3
    val numCols = iRangeMax * 2 + 4  // generous estimate for the map
    val numRows = (jMax - jMin) + 2
    val em = buildCoarseEdgeMap(source, numCols, numRows)

    // Draw a single filled triangle given three vertices.
    fun drawTri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
                sampleX: Float, sampleY: Float) {
        paint.color = sampler.sample(sampleX, sampleY)
        path.rewind()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy)
        path.close(); canvas.drawPath(path, paint)
    }

    // Midpoint-subdivide a triangle into 4 children. The centre child is inverted.
    // hx/hy are integer hash coords for determinism; depth=0 is the first recursion.
    fun subdivide(
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
        hx: Int, hy: Int, depth: Int,
    ) {
        // Midpoints of each edge
        val mABx = (ax + bx) / 2f; val mABy = (ay + by) / 2f
        val mBCx = (bx + cx) / 2f; val mBCy = (by + cy) / 2f
        val mCAx = (cx + ax) / 2f; val mCAy = (cy + ay) / 2f
        // Four children: three corner tris + inverted centre
        val children = arrayOf(
            floatArrayOf(ax, ay, mABx, mABy, mCAx, mCAy),
            floatArrayOf(mABx, mABy, bx, by, mBCx, mBCy),
            floatArrayOf(mCAx, mCAy, mBCx, mBCy, cx, cy),
            floatArrayOf(mABx, mABy, mBCx, mBCy, mCAx, mCAy),
        )
        for ((idx, tri) in children.withIndex()) {
            val x0 = tri[0]; val y0 = tri[1]; val x1 = tri[2]; val y1 = tri[3]; val x2 = tri[4]; val y2 = tri[5]
            val sx = (x0 + x1 + x2) / 3f; val sy = (y0 + y1 + y2) / 3f
            // Map pixel centroid to tile-grid coords for edge lookup
            val gridCol = (sx / tileF).toInt(); val gridRow = (sy / rowH).toInt()
            val mag = em.edgeMag(gridCol, gridRow)
            val childHx = hx * 2 + idx; val childHy = hy * 2 + depth
            // Recurse if edge-rich and not at max depth, gated by salt 20
            if (depth < 1 && mag >= EDGE_THRESHOLD && hashUnit(childHx, childHy, 20) < 0.85f) {
                subdivide(x0, y0, x1, y1, x2, y2, childHx, childHy, depth + 1)
            } else {
                drawTri(x0, y0, x1, y1, x2, y2, sx, sy)
            }
        }
    }

    for (j in jMin..jMax) {
        val rowOffsetX = j * (tileF / 2f)
        val iMin2 = floor(-rowOffsetX / tileF).toInt() - 2
        val iMax2 = ceil((sW - rowOffsetX) / tileF).toInt() + 2
        for (i in iMin2..iMax2) {
            // Triangle A: L(i,j), L(i+1,j), L(i,j+1)
            val ax = lx(i, j);   val ay = ly(i, j)
            val bx = lx(i+1, j); val by = ly(i+1, j)
            val cx = lx(i, j+1); val cy = ly(i, j+1)
            val agx = (ax + bx + cx) / 3f; val agy = (ay + by + cy) / 3f
            val gridColA = (agx / tileF).toInt(); val gridRowA = (agy / rowH).toInt()
            val magA = em.edgeMag(gridColA, gridRowA)
            if (magA >= EDGE_THRESHOLD && hashUnit(i, j * 2, 20) < 0.85f) {
                subdivide(ax, ay, bx, by, cx, cy, i, j * 2, 0)
            } else {
                drawTri(ax, ay, bx, by, cx, cy, agx, agy)
            }

            // Triangle B: L(i+1,j), L(i+1,j+1), L(i,j+1)
            val dx = lx(i+1, j+1); val dy = ly(i+1, j+1)
            val bgx = (bx + dx + cx) / 3f; val bgy = (by + dy + cy) / 3f
            val gridColB = (bgx / tileF).toInt(); val gridRowB = (bgy / rowH).toInt()
            val magB = em.edgeMag(gridColB, gridRowB)
            if (magB >= EDGE_THRESHOLD && hashUnit(i, j * 2 + 1, 20) < 0.85f) {
                subdivide(bx, by, dx, dy, cx, cy, i, j * 2 + 1, 0)
            } else {
                drawTri(bx, by, dx, dy, cx, cy, bgx, bgy)
            }
        }
    }
    return out
}

/**
 * Hexagon-native recursive (HEXAGON shape). Keeps the same no-gap flat-top hex grid
 * as [hexagonMosaic] (NONE filter), drawn first as the base. Inside detail cells,
 * 2–3 child hexagons are nestled into alternating corners of the parent hex (gaps OK).
 * Salt 21 chooses child count (2 or 3) and the starting corner.
 */
private fun recursiveHexImpl(source: Bitmap, tile: Int): Bitmap {
    val sampler = PixelSampler(source)
    val (out, canvas) = newCanvasBitmap(source)
    val path = Path()
    val sW = source.width.toFloat(); val sH = source.height.toFloat()
    val tileF = tile.toFloat()
    val r = tileF / sqrt(3f)
    val colSpacing = 1.5f * r
    val rowSpacing = tileF
    val colCount = ceil(sW / colSpacing).toInt() + 2
    val rowCount = ceil(sH / rowSpacing).toInt() + 2

    // Pre-compute flat-top vertex offsets (angles 0°, 60°, ..., 300°).
    val vx = FloatArray(6); val vy = FloatArray(6)
    for (k in 0..5) { val a = k * 60f * PI.toFloat() / 180f; vx[k] = r * cos(a); vy[k] = r * sin(a) }

    val numCols2 = ceil(sW / colSpacing).toInt() + 4
    val numRows2 = ceil(sH / rowSpacing).toInt() + 4
    val em = buildCoarseEdgeMap(source, numCols2, numRows2)

    val paint = Paint().apply { isAntiAlias = false; isDither = false; style = Paint.Style.FILL }
    val childPaint = Paint().apply { isAntiAlias = true; isDither = false; style = Paint.Style.FILL }

    fun drawHex(cx: Float, cy: Float, radius: Float, p: Paint, sampleX: Float, sampleY: Float) {
        p.color = sampler.sample(sampleX, sampleY) or (0xFF shl 24)
        path.rewind()
        path.moveTo(cx + radius, cy)
        for (k in 1..5) {
            val a = k * 60f * PI.toFloat() / 180f
            path.lineTo(cx + radius * cos(a), cy + radius * sin(a))
        }
        path.close(); canvas.drawPath(path, p)
    }

    for (col in -1..colCount) {
        val cx = col * colSpacing
        val yOffset = if (col and 1 == 0) 0f else rowSpacing / 2f
        for (row in -1..rowCount) {
            val cy = row * rowSpacing + yOffset
            // Draw parent hex (no-gap base layer, same as None filter)
            drawHex(cx, cy, r, paint, cx, cy)
            // Check edge map at this cell
            val gridCol = (cx / colSpacing).toInt().coerceIn(0, numCols2 - 1)
            val gridRow = (cy / rowSpacing).toInt().coerceIn(0, numRows2 - 1)
            if (em.edgeMag(gridCol, gridRow) < EDGE_THRESHOLD) continue
            // 3 or 6 children sized so they meet edge-to-edge / corner-to-corner.
            val h21 = hashUnit(col, row, 21)
            val count = if (h21 < 0.5f) 3 else 6
            val k0 = ((h21 * 6f).toInt() % 6)  // θ0: snapped to one of 6 parent-vertex angles
            if (count == 3) {
                // Flat-top children (same orientation as parent): rc = r/2.
                // Centers at distance rc from parent center at θ0+k*120°. Each child has
                // the parent center as a vertex; adjacent pairs share a full edge. 75% fill.
                val rc = r / 2f
                for (ci in 0 until 3) {
                    val a = (k0 * 60f + ci * 120f) * PI.toFloat() / 180f
                    val childCx = cx + rc * cos(a)
                    val childCy = cy + rc * sin(a)
                    drawHex(childCx, childCy, rc, childPaint, childCx, childCy)
                }
            } else {
                // 6 pointy-top children (rotated 30° vs flat-top parent): rc = r/3.
                // Centers at d = sqrt(3)*rc ≈ r/sqrt(3) at k*60°. Adjacent children
                // touch at shared corners.
                val rc = r / 3f
                val dist = sqrt(3f) * rc
                for (ci in 0 until 6) {
                    val a = ci * 60f * PI.toFloat() / 180f
                    val childCx = cx + dist * cos(a)
                    val childCy = cy + dist * sin(a)
                    childPaint.color = sampler.sample(childCx, childCy) or (0xFF shl 24)
                    path.rewind()
                    for (k in 0..5) {
                        val va = (30f + k * 60f) * PI.toFloat() / 180f
                        val vxc = childCx + rc * cos(va)
                        val vyc = childCy + rc * sin(va)
                        if (k == 0) path.moveTo(vxc, vyc) else path.lineTo(vxc, vyc)
                    }
                    path.close()
                    canvas.drawPath(path, childPaint)
                }
            }
        }
    }
    return out
}

/**
 * Circle-native recursive (CIRCLE shape). Keeps the square-grid dot-matrix layout
 * (user's preference) but draws it over a bilinear-blurred base layer so the gaps
 * sample colour from the source, exactly like [circleMosaicBase] (NONE filter).
 * Salts 0/1/2 for sub-cell sizing/selection (same as recursiveSquareImpl).
 */
private fun recursiveCircleImpl(source: Bitmap, tile: Int): Bitmap {
    val tileF = tile.toFloat()
    val sW = source.width; val sH = source.height
    // Blurred base — fills the inter-circle gaps with colour from source.
    val baseW = max(1, sW / tile); val baseH = max(1, sH / tile)
    val base = source.scale(baseW, baseH, filter = true)
    val (out, canvas) = newCanvasBitmap(source)
    val blitPaint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(base, Rect(0, 0, baseW, baseH), Rect(0, 0, sW, sH), blitPaint)
    if (base != source) base.recycle()

    val sampler = PixelSampler(source)
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1
    val em = buildCoarseEdgeMap(source, numCols, numRows)
    val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    for (row in -1..numRows) {
        for (col in -1..numCols) {
            val cx = col * tileF + tileF * 0.5f
            val cy = row * tileF + tileF * 0.5f
            val r = tileF * 0.5f
            if (cx + r < 0 || cx - r > sW || cy + r < 0 || cy - r > sH) continue
            val isFlat = em.edgeMag(col, row) < EDGE_THRESHOLD
            // In flat cells: draw parent circle only. In edge cells: subdivide into smaller circles.
            if (!isFlat && hashUnit(col, row, 0) < 0.25f) {
                // 2×2 sub-circles
                val n = if (hashUnit(col, row, 1) < 0.5f) 2 else 3
                val subR = r / n
                for (sr in 0 until n) for (sc in 0 until n) {
                    val scx = cx - r + subR + sc * 2f * subR
                    val scy = cy - r + subR + sr * 2f * subR
                    paint.color = sampler.sample(scx, scy) or (0xFF shl 24)
                    canvas.drawCircle(scx, scy, subR, paint)
                }
            } else {
                paint.color = sampler.sample(cx, cy) or (0xFF shl 24)
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
    }
    return out
}

/**
 * Draw [shape] inside the square bounding box [x0, y0, x0+size, y0+size].
 * [hashCol]/[hashRow] are used for IRREGULAR jitter only.
 */
private val _shapePath = Path()
private fun drawShapeInBounds(
    canvas: Canvas, paint: Paint, shape: MosaicShape,
    x0: Float, y0: Float, size: Float,
    hashCol: Int = 0, hashRow: Int = 0,
) {
    when (shape) {
        MosaicShape.SQUARE, MosaicShape.RANDOM -> canvas.drawRect(x0, y0, x0 + size, y0 + size, paint)
        MosaicShape.EQUILATERAL -> {
            _shapePath.rewind()
            _shapePath.moveTo(x0, y0 + size)
            _shapePath.lineTo(x0 + size, y0 + size)
            _shapePath.lineTo(x0 + size / 2f, y0)
            _shapePath.close()
            canvas.drawPath(_shapePath, paint)
        }
        MosaicShape.IRREGULAR -> {
            val jitter = size * 0.25f
            val apexX = x0 + size / 2f + (hashUnit(hashCol, hashRow, 6) - 0.5f) * jitter
            val apexY = y0 + (hashUnit(hashCol, hashRow, 7) - 0.5f) * jitter
            _shapePath.rewind()
            _shapePath.moveTo(x0, y0 + size)
            _shapePath.lineTo(x0 + size, y0 + size)
            _shapePath.lineTo(apexX, apexY)
            _shapePath.close()
            canvas.drawPath(_shapePath, paint)
        }
        MosaicShape.HEXAGON -> {
            val cx = x0 + size / 2f; val cy = y0 + size / 2f; val r = size / 2f
            _shapePath.rewind()
            _shapePath.moveTo(cx + r, cy)
            for (k in 1..5) {
                val angle = k * 60f * PI.toFloat() / 180f
                _shapePath.lineTo(cx + r * cos(angle), cy + r * sin(angle))
            }
            _shapePath.close()
            canvas.drawPath(_shapePath, paint)
        }
        MosaicShape.CIRCLE -> canvas.drawCircle(x0 + size / 2f, y0 + size / 2f, size / 2f, paint)
    }
}

/**
 * RAINDROP filter dispatcher. Blurred base + translucent oversized jittered shapes.
 * CIRCLE and IRREGULAR use their original salt streams for fidelity with old looks.
 * Square/equilateral/hexagon use salts 3-5 (same as circle, different visual due to different grid).
 */
private fun raindropMosaic(source: Bitmap, tile: Int, shape: MosaicShape): Bitmap = when (shape) {
    MosaicShape.CIRCLE, MosaicShape.RANDOM -> circleRaindropMosaic(source, tile)
    MosaicShape.IRREGULAR -> irregularRaindropMosaic(source, tile)
    else -> genericRaindropMosaic(source, tile, shape)
}

/**
 * Circle raindrop (CIRCLE+RAINDROP = old mixed2 look). Salts 3/4/5. Blurred base +
 * translucent jittered circles at ~45% opacity with varied radii.
 */
private fun circleRaindropMosaic(source: Bitmap, tile: Int): Bitmap {
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
 * Irregular triangle mesh (IRREGULAR shape base). An equilateral triangle lattice
 * is rendered with each vertex independently jittered (±30% of tile in x, ±30% of
 * row-height in y) via an avalanche hash. Mesh is watertight — shared vertices
 * compute identically. Salts 6/7.
 */
private fun irregularMosaic(source: Bitmap, tile: Int): Bitmap {
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
 * Irregular raindrop (IRREGULAR+RAINDROP = old mixed4 look). Salts 8/9 for vertex
 * jitter. Blurred base + oversized expanded translucent triangles at ~50% opacity.
 */
private fun irregularRaindropMosaic(source: Bitmap, tile: Int): Bitmap {
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

/**
 * Generic raindrop for SQUARE, EQUILATERAL, HEXAGON. Blurred base + translucent
 * oversized jittered shapes drawn over it. Uses salts 3/4/5 for jitter/size, same
 * as circle raindrop (different grid → independent visual). Alpha 0x73 (~45%).
 */
private fun genericRaindropMosaic(source: Bitmap, tile: Int, shape: MosaicShape): Bitmap {
    val tileF = tile.toFloat()
    val sW = source.width; val sH = source.height
    val sWf = sW.toFloat(); val sHf = sH.toFloat()
    val baseW = max(1, sW / tile); val baseH = max(1, sH / tile)
    val base = source.scale(baseW, baseH, filter = true)
    val (out, canvas) = newCanvasBitmap(source)
    val blitPaint = Paint().apply { isFilterBitmap = true }
    canvas.drawBitmap(base, Rect(0, 0, baseW, baseH), Rect(0, 0, sW, sH), blitPaint)
    if (base != source) base.recycle()
    val sampler = PixelSampler(source)
    val shapePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    val path = Path()
    val expand = 1.4f
    val colMin = -2; val colMax = ceil(sWf / tileF).toInt() + 2
    val rowMin = -2; val rowMax = ceil(sHf / tileF).toInt() + 2
    for (row in rowMin..rowMax) {
        for (col in colMin..colMax) {
            val jx = (hashUnit(col, row, 3) - 0.5f) * tileF
            val jy = (hashUnit(col, row, 4) - 0.5f) * tileF
            val sizeScale = 0.4f + hashUnit(col, row, 5) * 0.6f
            val cx = col * tileF + tileF * 0.5f + jx
            val cy = row * tileF + tileF * 0.5f + jy
            val effectiveSize = sizeScale * tileF * expand
            if (cx + effectiveSize < 0 || cx - effectiveSize > sWf || cy + effectiveSize < 0 || cy - effectiveSize > sHf) continue
            val rgb = sampler.sample(cx, cy) and 0x00FFFFFF
            shapePaint.color = (0x73 shl 24) or rgb
            when (shape) {
                MosaicShape.SQUARE -> {
                    val half = effectiveSize / 2f
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, shapePaint)
                }
                MosaicShape.EQUILATERAL -> {
                    val h = effectiveSize * sqrt(3f) / 2f
                    path.rewind()
                    path.moveTo(cx - effectiveSize / 2f, cy + h / 3f)
                    path.lineTo(cx + effectiveSize / 2f, cy + h / 3f)
                    path.lineTo(cx, cy - 2f * h / 3f)
                    path.close()
                    canvas.drawPath(path, shapePaint)
                }
                MosaicShape.HEXAGON -> {
                    val r = effectiveSize / 2f
                    path.rewind()
                    path.moveTo(cx + r, cy)
                    for (k in 1..5) {
                        val angle = k * 60f * PI.toFloat() / 180f
                        path.lineTo(cx + r * cos(angle), cy + r * sin(angle))
                    }
                    path.close()
                    canvas.drawPath(path, shapePaint)
                }
                else -> canvas.drawCircle(cx, cy, effectiveSize / 2f, shapePaint)
            }
        }
    }
    return out
}

/**
 * Glitch: datamosh. After rendering the shape base in Stage 1, four glitch passes run
 * on a flat IntArray pixel buffer:
 *
 *   1. Horizontal band displacement — block shifts and scanline tears. Salt 13.
 *   2. Vertical band displacement. Salt 16.
 *   3. Pixel sorting — sparse rows sorted by luminance. Salt 14.
 *   4. 2-D RGB channel split (chromatic aberration). Salts 15/17.
 *
 * Salts 10-12 are retired (were used for the old internal recursive-block base).
 * GLITCH1 uses band displacement (salts 13/16); GLITCH2 uses edge-weighted
 * displacement (salts 18/19). Both share passes 3 and 4 (salts 14/15/17).
 */
/**
 * GLITCH1 filter. Applies tile-anchored band displacement (salts 13/16), pixel
 * sort (salt 14), and 2-D channel split (salts 15/17) to an already-rendered
 * [base] bitmap. Stage 1 (recursive-block base) is replaced by the caller's
 * shape base, so this function never re-tiles the source.
 */
private fun glitch1Filter(
    base: Bitmap,
    tile: Int,
    hDisplacement: Int = 250,
    vDisplacement: Int = 250,
    channelSplit: Int = 250,
    pixelSort: Int = 250,
): Bitmap {
    val tdH = hDisplacement / 500f
    val tdV = vDisplacement / 500f
    val tc = channelSplit / 500f
    val ts = pixelSort / 500f
    val ZERO_BAND_FRAC  = 0.35f
    val DISP_H_MAX_FRAC = tdH * 0.5f
    val DISP_V_MAX_FRAC = tdV * 0.5f
    val BLOCK_BAND_FRAC = 0.40f
    val BLOCK_MIN_TILES = 1; val BLOCK_MAX_TILES = 3
    val TEAR_MIN = 1; val TEAR_MAX = 4
    val FROZEN_ROW_FRAC = 0.03f
    val SORT_ROW_FRAC   = ts * 0.30f
    val SORT_MAX_FRAC   = ts * 0.60f
    val CA_MIN = (tc * 8f).toInt().coerceAtLeast(0)
    val CA_MAX = (tc * 32f).toInt().coerceAtLeast(CA_MIN)

    val sW = base.width; val sH = base.height
    val buf = IntArray(sW * sH).also { base.getPixels(it, 0, sW, 0, 0, sW, sH) }

    // Stage 2a: H-band displacement. Salt 13.
    val rowTmp = IntArray(sW); val prevRow = IntArray(sW)
    var hasPrev = false; var hRunLeft = 0; var hOffset = 0
    for (y in 0 until sH) {
        if (hRunLeft <= 0) {
            val h0 = hashUnit(y, 0, 13); val h1 = hashUnit(y, 1, 13)
            val h2 = hashUnit(y, 2, 13); val h3 = hashUnit(y, 3, 13); val h4 = hashUnit(y, 4, 13)
            val isBlock = h1 < BLOCK_BAND_FRAC
            hRunLeft = if (isBlock) ((BLOCK_MIN_TILES + (h2 * (BLOCK_MAX_TILES - BLOCK_MIN_TILES + 1)).toInt()) * tile).coerceAtLeast(1)
                       else (TEAR_MIN + (h2 * (TEAR_MAX - TEAR_MIN + 1)).toInt()).coerceAtLeast(1)
            hOffset = if (h0 < ZERO_BAND_FRAC || DISP_H_MAX_FRAC <= 0f) 0
                      else { val mag = (h3 * DISP_H_MAX_FRAC * sW).toInt(); if (h4 < 0.5f) mag else -mag }
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
        System.arraycopy(buf, rowStart, prevRow, 0, sW); hasPrev = true
    }

    // Stage 2b: V-band displacement. Salt 16.
    val colTmp = IntArray(sH); var vRunLeft = 0; var vOffset = 0
    for (x in 0 until sW) {
        if (vRunLeft <= 0) {
            val h0 = hashUnit(x, 0, 16); val h1 = hashUnit(x, 1, 16)
            val h2 = hashUnit(x, 2, 16); val h3 = hashUnit(x, 3, 16); val h4 = hashUnit(x, 4, 16)
            val isBlock = h1 < BLOCK_BAND_FRAC
            vRunLeft = if (isBlock) ((BLOCK_MIN_TILES + (h2 * (BLOCK_MAX_TILES - BLOCK_MIN_TILES + 1)).toInt()) * tile).coerceAtLeast(1)
                       else (TEAR_MIN + (h2 * (TEAR_MAX - TEAR_MIN + 1)).toInt()).coerceAtLeast(1)
            vOffset = if (h0 < ZERO_BAND_FRAC || DISP_V_MAX_FRAC <= 0f) 0
                      else { val mag = (h3 * DISP_V_MAX_FRAC * sH).toInt(); if (h4 < 0.5f) mag else -mag }
        }
        vRunLeft--
        if (vOffset != 0) {
            for (y in 0 until sH) { colTmp[y] = buf[y * sW + x] }
            val off = ((vOffset % sH) + sH) % sH
            for (y in 0 until sH - off) { buf[y * sW + x] = colTmp[y + off] }
            for (y in sH - off until sH) { buf[y * sW + x] = colTmp[y + off - sH] }
        }
    }

    applyPixelSort(buf, sW, sH, SORT_ROW_FRAC, SORT_MAX_FRAC)
    val result = applyChannelSplit(buf, sW, sH, tile, CA_MIN, CA_MAX)
    base.setPixels(result, 0, sW, 0, 0, sW, sH)
    return base
}

/**
 * GLITCH2 filter — clumped, edge-density-driven glitch art. Three passes:
 *
 *   1. H-displacement (salts 22/23): Markov run-length machine per row.
 *      P_START boosted in high-density regions; once started, P_CONTINUE is high
 *      so tears form visible horizontal clumps. Cap at MAX_RUN rows.
 *   2. V-displacement (salts 24/25): same machine, per column.
 *   3. Pixel tears (salt 26): [applyPixelTears] — luminance-sort bands grouped
 *      by the same Markov model. Replaced [applyPixelSort] for GLITCH2 so sorted
 *      regions appear as rectangular clumps, not independent per-row runs.
 *   4. 2-D channel split (salts 15/17): unchanged from GLITCH1.
 *
 * Edge density is computed as a 3×3 box-mean of per-cell edge magnitudes, then
 * normalised by the max so it spans [0,1] across the image.
 *
 * [source] is used only for edge detection; [base] is the shaped bitmap that is mutated.
 */
private fun glitch2Filter(
    base: Bitmap,
    source: Bitmap,
    tile: Int,
    hDisplacement: Int = 250,
    vDisplacement: Int = 250,
    channelSplit: Int = 250,
    pixelSort: Int = 250,
    subjectRegions: List<RectF>? = null,
): Bitmap {
    val tdH = hDisplacement / 500f
    val tdV = vDisplacement / 500f
    val tc = channelSplit / 500f
    val ts = pixelSort / 500f
    val CA_MIN = (tc * 8f).toInt().coerceAtLeast(0)
    val CA_MAX = (tc * 32f).toInt().coerceAtLeast(CA_MIN)

    val sW = base.width; val sH = base.height
    val buf = IntArray(sW * sH).also { base.getPixels(it, 0, sW, 0, 0, sW, sH) }

    // ---- Build coarse edge map via CoarseEdgeMap ----
    val numCols = ceil(sW.toFloat() / tile).toInt() + 1
    val numRows = ceil(sH.toFloat() / tile).toInt() + 1
    val em = buildCoarseEdgeMap(source, numCols, numRows)

    // ---- 3×3 box-mean density per row and column (normalised) ----
    // Raw per-row: mean of edgeMag across all columns, smoothed by 3-row box.
    val rawRowEdge = FloatArray(numRows + 2) { gr ->
        var sum = 0f; var cnt = 0
        for (gc in 0 until numCols + 2) { sum += em.edgeMag(gc - 1, gr - 1); cnt++ }
        if (cnt > 0) sum / cnt else 0f
    }
    val rowDensity = FloatArray(numRows + 2) { gr ->
        var s = 0f; var c = 0
        for (d in -1..1) { val n = gr + d; if (n in rawRowEdge.indices) { s += rawRowEdge[n]; c++ } }
        if (c > 0) s / c else 0f
    }
    val maxRowDensity = rowDensity.max().coerceAtLeast(1e-6f)
    val normRowDensity = FloatArray(rowDensity.size) { rowDensity[it] / maxRowDensity }

    val rawColEdge = FloatArray(numCols + 2) { gc ->
        var sum = 0f; var cnt = 0
        for (gr in 0 until numRows + 2) { sum += em.edgeMag(gc - 1, gr - 1); cnt++ }
        if (cnt > 0) sum / cnt else 0f
    }
    val colDensity = FloatArray(numCols + 2) { gc ->
        var s = 0f; var c = 0
        for (d in -1..1) { val n = gc + d; if (n in rawColEdge.indices) { s += rawColEdge[n]; c++ } }
        if (c > 0) s / c else 0f
    }
    val maxColDensity = colDensity.max().coerceAtLeast(1e-6f)
    val normColDensity = FloatArray(colDensity.size) { colDensity[it] / maxColDensity }

    // ---- Cell-chunk glitch constants ----
    // Frequency = slider * (P_BASE + P_GAIN * density + G_FACE_GAIN * faceWeight).
    // Bigger tile → fewer cells → fewer glitch events; halving the slider halves the rate.
    // G_FACE_GAIN boosts probability for displacement chunks that overlap a detected face.
    val G_P_BASE = 0.04f
    val G_P_GAIN = 0.20f
    val G_FACE_GAIN = 0.50f

    // ---- Displacement chunk cell size — decoupled from tile at the low end ----
    // At tile=1 the standard cell is 1px, which collapses displacement into pixel grain.
    // dispCell enforces an absolute minimum block height (~30px at 1080p) so displacement
    // stays blocky regardless of the tile slider. Once tile exceeds the floor, dispCell
    // == tile and behaviour is identical to before.
    val MIN_DISP_CELL = max(8, sH / 64)
    val dispCell = max(tile, MIN_DISP_CELL)
    val dispRows = ceil(sH.toFloat() / dispCell).toInt() + 1
    val dispCols = ceil(sW.toFloat() / dispCell).toInt() + 1

    // ---- H-displacement (salts 22/23): disp-cell-row chunks ----
    // Each chunk: 1-3 disp-cells all shifted by the same offset (block tear), with a
    // per-row correlated random-walk smear on top (salt 23, round 4).
    // Density and face-weight bias where chunks start.
    if (tdH > 0f) {
        val rowTmp = IntArray(sW)
        var cr = 0
        while (cr < dispRows) {
            val yMid = (cr + 0.5f) * dispCell
            val densIdx = ((cr * dispCell) / tile).coerceIn(0, normRowDensity.size - 1)
            val density = normRowDensity[densIdx]
            val faceW = if (!subjectRegions.isNullOrEmpty() &&
                    subjectRegions.any { yMid in it.top * sH..it.bottom * sH }) 1f else 0f
            val pStart = tdH * (G_P_BASE + G_P_GAIN * density + G_FACE_GAIN * faceW)
            if (hashUnit(cr, 0, 22) < pStart) {
                val lenCells = 1 + (hashUnit(cr, 1, 22) * 3f).toInt()
                val rawMag = (tdH * (0.10f + 0.35f * density) * sW).toInt()
                val hOffset = if (rawMag == 0) 0 else if (hashUnit(cr, 2, 22) < 0.5f) rawMag else -rawMag
                if (hOffset != 0) {
                    val yStart = cr * dispCell
                    val yEnd = min((cr + lenCells) * dispCell, sH)
                    // Per-row smear: bounded random walk (salt 23). Each row nudges a
                    // running accumulator by ±stepAmp; neighbours stay close.
                    val stepAmp = max(1f, Math.abs(hOffset) * 0.05f)
                    val maxDev  = max(1f, Math.abs(hOffset) * 0.20f)
                    var accum = 0f
                    for (y in yStart until yEnd) {
                        accum = (accum + (hashUnit(y, 0, 23) - 0.5f) * 2f * stepAmp)
                            .coerceIn(-maxDev, maxDev)
                        val rowOff = hOffset + accum.toInt()
                        if (rowOff != 0) {
                            val rowStart = y * sW
                            System.arraycopy(buf, rowStart, rowTmp, 0, sW)
                            val off = ((rowOff % sW) + sW) % sW
                            System.arraycopy(rowTmp, off, buf, rowStart, sW - off)
                            System.arraycopy(rowTmp, 0, buf, rowStart + sW - off, off)
                        }
                    }
                }
                cr += lenCells
            } else {
                cr++
            }
        }
    }

    // ---- V-displacement (salts 24/25): disp-cell-column chunks ----
    // Each chunk: 1-3 disp-cells all shifted by the same vertical offset.
    if (tdV > 0f) {
        val colTmp = IntArray(sH)
        var cc = 0
        while (cc < dispCols) {
            val xMid = (cc + 0.5f) * dispCell
            val densIdx = ((cc * dispCell) / tile).coerceIn(0, normColDensity.size - 1)
            val density = normColDensity[densIdx]
            val faceW = if (!subjectRegions.isNullOrEmpty() &&
                    subjectRegions.any { xMid in it.left * sW..it.right * sW }) 1f else 0f
            val pStart = tdV * (G_P_BASE + G_P_GAIN * density + G_FACE_GAIN * faceW)
            if (hashUnit(cc, 0, 24) < pStart) {
                val lenCells = 1 + (hashUnit(cc, 1, 24) * 3f).toInt()
                val rawMag = (tdV * (0.10f + 0.35f * density) * sH).toInt()
                val vOffset = if (rawMag == 0) 0 else if (hashUnit(cc, 2, 24) < 0.5f) rawMag else -rawMag
                if (vOffset != 0) {
                    val xStart = cc * dispCell
                    val xEnd = min((cc + lenCells) * dispCell, sW)
                    // Per-col smear: bounded random walk (salt 25). Each column nudges a
                    // running accumulator by ±stepAmp; neighbours stay close.
                    val stepAmp = max(1f, Math.abs(vOffset) * 0.05f)
                    val maxDev  = max(1f, Math.abs(vOffset) * 0.20f)
                    var accum = 0f
                    for (x in xStart until xEnd) {
                        accum = (accum + (hashUnit(x, 0, 25) - 0.5f) * 2f * stepAmp)
                            .coerceIn(-maxDev, maxDev)
                        val colOff = vOffset + accum.toInt()
                        if (colOff != 0) {
                            for (y in 0 until sH) { colTmp[y] = buf[y * sW + x] }
                            val off = ((colOff % sH) + sH) % sH
                            for (y in 0 until sH - off) { buf[y * sW + x] = colTmp[y + off] }
                            for (y in sH - off until sH) { buf[y * sW + x] = colTmp[y + off - sH] }
                        }
                    }
                }
                cc += lenCells
            } else {
                cc++
            }
        }
    }

    applyPixelTears(buf, sW, sH, ts, normRowDensity, tile)
    val result = applyChannelSplit(buf, sW, sH, tile, CA_MIN, CA_MAX)
    base.setPixels(result, 0, sW, 0, 0, sW, sH)
    return base
}

// Shared glitch sub-passes ---------------------------------------------------

private fun applyPixelSort(buf: IntArray, sW: Int, sH: Int, sortRowFrac: Float, sortMaxFrac: Float) {
    val maxRun = (sW * sortMaxFrac).toInt().coerceAtLeast(2)
    val sortKey = IntArray(maxRun); val sortPix = IntArray(maxRun)
    for (y in 0 until sH) {
        if (hashUnit(y, 0, 14) >= sortRowFrac) continue
        val runLen = (hashUnit(y, 1, 14) * maxRun).toInt().coerceIn(2, maxRun)
        val start  = (hashUnit(y, 2, 14) * (sW - runLen)).toInt().coerceIn(0, sW - runLen)
        val base   = y * sW + start
        for (i in 0 until runLen) {
            val px = buf[base + i]
            val lum = ((px shr 16 and 0xFF) * 77 + (px shr 8 and 0xFF) * 150 + (px and 0xFF) * 29) ushr 8
            sortPix[i] = px; sortKey[i] = ((lum xor 0x80) shl 24) or i
        }
        sortKey.sort(0, runLen)
        for (d in 0 until runLen) { buf[base + d] = sortPix[sortKey[d] and 0x00FFFFFF] }
    }
}

/**
 * Pixel tears — cell-granularity luminance sort for GLITCH2 (salts 26/27).
 * Cell-chunk walk (salt 26) controls *where* and *how often* tear clusters fire —
 * preserving the tile-size link and slider scaling from round 2. Within each chunk
 * every pixel row gets its **own** randomised sort band (salt 27), so tears are ragged
 * at the pixel level rather than a single repeated rectangle.
 *
 * [pixelSort] ∈ [0,1] is the raw slider fraction.
 * [rowDensity] is the normalised per-grid-row density array from [glitch2Filter].
 */
private fun applyPixelTears(
    buf: IntArray, sW: Int, sH: Int,
    pixelSort: Float,
    rowDensity: FloatArray,
    tile: Int,
) {
    if (pixelSort <= 0f) return
    val maxRun = (sW * (pixelSort * 0.60f)).toInt().coerceAtLeast(2)
    val P_BASE = 0.04f
    val P_GAIN = 0.20f

    val sortKey = IntArray(maxRun); val sortPix = IntArray(maxRun)
    val numCellRows = rowDensity.size

    var cr = 0
    while (cr < numCellRows) {
        val density = rowDensity[cr.coerceIn(0, rowDensity.size - 1)]
        val pStart = pixelSort * (P_BASE + P_GAIN * density)
        if (hashUnit(cr, 0, 26) < pStart) {
            val lenCells = 1 + (hashUnit(cr, 1, 26) * 3f).toInt()
            val yStart = cr * tile
            val yEnd = min((cr + lenCells) * tile, sH)
            // Each row in the chunk gets its own band (salt 27) — pixel-granular tears.
            for (y in yStart until yEnd) {
                val runLen = (hashUnit(y, 0, 27) * maxRun).toInt().coerceIn(2, maxRun)
                val bStart = (hashUnit(y, 1, 27) * (sW - runLen)).toInt().coerceIn(0, sW - runLen)
                val base2 = y * sW + bStart
                for (i in 0 until runLen) {
                    val px = buf[base2 + i]
                    val lum = ((px shr 16 and 0xFF) * 77 + (px shr 8 and 0xFF) * 150 + (px and 0xFF) * 29) ushr 8
                    sortPix[i] = px; sortKey[i] = ((lum xor 0x80) shl 24) or i
                }
                sortKey.sort(0, runLen)
                for (d in 0 until runLen) { buf[base2 + d] = sortPix[sortKey[d] and 0x00FFFFFF] }
            }
            cr += lenCells
        } else {
            cr++
        }
    }
}

private fun applyChannelSplit(buf: IntArray, sW: Int, sH: Int, tile: Int, caMin: Int, caMax: Int): IntArray {
    val colVDr = IntArray(sW); val colVDb = IntArray(sW)
    var cvLeft = 0; var cvDr = caMin; var cvDb = caMin
    for (x in 0 until sW) {
        if (cvLeft <= 0) {
            val hv0 = hashUnit(x, 0, 17); val hv1 = hashUnit(x, 1, 17); val hv2 = hashUnit(x, 2, 17)
            cvLeft = (1 + (hv0 * tile * 3f).toInt()).coerceAtLeast(1)
            val vExtra = (hv1 * (caMax - caMin).toFloat()).toInt()
            if (hv2 < 0.30f) { cvDr = caMin; cvDb = caMin } else { cvDr = caMin + vExtra; cvDb = caMin + vExtra }
        }
        cvLeft--; colVDr[x] = cvDr; colVDb[x] = cvDb
    }
    val out = IntArray(sW * sH)
    var caLeft = 0; var caDr = caMin; var caDb = caMin
    for (y in 0 until sH) {
        if (caLeft <= 0) {
            val h0 = hashUnit(y, 0, 15); val h1 = hashUnit(y, 1, 15); val h2 = hashUnit(y, 2, 15)
            caLeft = (1 + (h0 * tile * 3f).toInt()).coerceAtLeast(1)
            val extra = (h1 * (caMax - caMin).toFloat()).toInt()
            if (h2 < 0.30f) { caDr = caMin; caDb = caMin } else { caDr = caMin + extra; caDb = caMin + extra }
        }
        caLeft--
        val rowStart = y * sW
        for (x in 0 until sW) {
            val xR = (x - caDr).coerceIn(0, sW - 1); val xB = (x + caDb).coerceIn(0, sW - 1)
            val yR = (y - colVDr[x]).coerceIn(0, sH - 1); val yB = (y + colVDb[x]).coerceIn(0, sH - 1)
            out[rowStart + x] = 0xFF000000.toInt() or
                (buf[yR * sW + xR] and 0x00FF0000) or (buf[rowStart + x] and 0x0000FF00) or (buf[yB * sW + xB] and 0x000000FF)
        }
    }
    return out
}
