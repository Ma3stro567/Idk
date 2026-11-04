package com.example.chessbot

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/** Ищет шахматную доску на скриншоте: сетка 8x8 из двух чередующихся цветов. */
object BoardFinder {
    private const val STEP = 4
    private const val TOL = 10

    private fun d(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 255) - ((b shr 16) and 255))
        val dg = abs(((a shr 8) and 255) - ((b shr 8) and 255))
        val db = abs((a and 255) - (b and 255))
        return max(dr, max(dg, db))
    }

    /** Самая длинная серия подряд идущих индексов, где cnt >= порога (допускаем разрыв в 1). */
    private fun longestRun(cnt: IntArray, from: Int, to: Int): IntArray? {
        var mx = 0
        for (i in from..to) if (cnt[i] > mx) mx = cnt[i]
        if (mx < 20) return null
        val thr = mx * 0.5
        var bestS = -1
        var bestE = -1
        var s = -1
        var gap = 0
        for (i in from..to + 1) {
            val ok = i <= to && cnt[i] >= thr
            if (ok) {
                if (s < 0) s = i
                gap = 0
                if (i - s > bestE - bestS) { bestS = s; bestE = i }
            } else if (s >= 0) {
                gap++
                if (gap > 1 || i > to) { s = -1; gap = 0 }
            }
        }
        return if (bestS < 0) null else intArrayOf(bestS, bestE)
    }

    fun find(bmp: Bitmap): Rect? {
        val w = bmp.width / STEP
        val h = bmp.height / STEP
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = bmp.getPixel(x * STEP, y * STEP)

        // самые частые цвета экрана
        val hist = HashMap<Int, IntArray>() // ключ -> [счёт, пример цвета]
        for (p in px) {
            val k = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)
            val e = hist.getOrPut(k) { intArrayOf(0, p) }
            e[0]++
        }
        val sorted = hist.values.filter { it[0] > w * h * 0.004 }.sortedByDescending { it[0] }
        val cands = ArrayList<Int>()
        for (e in sorted) {
            if (cands.size >= 14) break
            if (cands.none { d(it, e[1]) < 10 }) cands.add(e[1])
        }

        var best: Rect? = null
        var bestScore = 0.0
        for (i in cands.indices) for (j in i + 1 until cands.size) {
            val a = cands[i]
            val b = cands[j]
            if (d(a, b) < 15) continue
            val r0 = candidate(px, w, h, a, b) ?: continue
            val r = refine(bmp, r0, a, b)
            val sc = sharp(bmp, r, a, b)
            if (sc > bestScore) { bestScore = sc; best = r }
        }
        return if (bestScore >= 0.75) best else null
    }

    /** Уточняет рамку, заданную вручную (калибровкой): цвета клеток берём прямо с экрана. */
    fun refineManual(bmp: Bitmap, r: Rect): Rect {
        val cw = r.width() / 8f
        val ch = r.height() / 8f
        fun px(col: Int, row: Int): Int {
            val x = (r.left + (col + 0.5f) * cw).toInt().coerceIn(0, bmp.width - 1)
            val y = (r.top + (row + 0.1f) * ch).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(x, y)
        }
        return refine(bmp, r, px(0, 0), px(1, 0))
    }

    /** Точность до пикселя: двигаем каждый край доски там, где углы клеток лучше всего ложатся в шахматный узор. */
    private fun refine(bmp: Bitmap, r0: Rect, a: Int, b: Int): Rect {
        var l = r0.left
        var t = r0.top
        var rr = r0.right
        var bb = r0.bottom
        fun edge(cur: Int, f: (Int) -> Double): Int {
            var best = -1.0
            val hits = ArrayList<Int>()
            for (dlt in -56..56 step 2) {
                val sc = f(cur + dlt)
                if (sc > best + 1e-9) { best = sc; hits.clear(); hits.add(cur + dlt) }
                else if (abs(sc - best) <= 1e-9) hits.add(cur + dlt)
            }
            return hits.average().toInt()
        }
        repeat(2) {
            l = edge(l) { v -> if (rr - v < 100) 0.0 else sharp(bmp, Rect(v, t, rr, bb), a, b) }
            t = edge(t) { v -> if (bb - v < 100) 0.0 else sharp(bmp, Rect(l, v, rr, bb), a, b) }
            rr = edge(rr) { v -> if (v - l < 100) 0.0 else sharp(bmp, Rect(l, t, v, bb), a, b) }
            bb = edge(bb) { v -> if (v - t < 100) 0.0 else sharp(bmp, Rect(l, t, rr, v), a, b) }
        }
        val size = ((rr - l) + (bb - t)) / 2
        return Rect(l, t, l + size, t + size)
    }

    /** Как verify, но точки у самых углов клеток (3% от края): чувствительно к сдвигу границ. */
    private fun sharp(bmp: Bitmap, r: Rect, a: Int, b: Int): Double {
        val cw = r.width() / 8f
        val ch = r.height() / 8f
        var okA = 0
        var okB = 0
        val offs = floatArrayOf(0.03f, 0.97f)
        for (row in 0..7) for (col in 0..7) {
            val aFirst = (row + col) % 2 == 0
            for (ox in offs) for (oy in offs) {
                val x = (r.left + (col + ox) * cw).toInt().coerceIn(0, bmp.width - 1)
                val y = (r.top + (row + oy) * ch).toInt().coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                val nearA = d(p, a) <= 24
                val nearB = d(p, b) <= 24
                if ((aFirst && nearA) || (!aFirst && nearB)) okA++
                if ((aFirst && nearB) || (!aFirst && nearA)) okB++
            }
        }
        return max(okA, okB) / 256.0
    }

    private fun candidate(px: IntArray, w: Int, h: Int, a: Int, b: Int): Rect? {
        val rowC = IntArray(h)
        val colC = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (d(p, a) <= TOL || d(p, b) <= TOL) { rowC[y]++; colC[x]++ }
        }
        var ry = longestRun(rowC, 0, h - 1) ?: return null
        val colR = IntArray(w)
        for (y in ry[0]..ry[1]) for (x in 0 until w) {
            val p = px[y * w + x]
            if (d(p, a) <= TOL || d(p, b) <= TOL) colR[x]++
        }
        val rx = longestRun(colR, 0, w - 1) ?: return null
        val rowR = IntArray(h)
        for (y in 0 until h) for (x in rx[0]..rx[1]) {
            val p = px[y * w + x]
            if (d(p, a) <= TOL || d(p, b) <= TOL) rowR[y]++
        }
        ry = longestRun(rowR, 0, h - 1) ?: return null
        val left = rx[0] * STEP
        val top = ry[0] * STEP
        val bw = (rx[1] + 1 - rx[0]) * STEP
        val bh = (ry[1] + 1 - ry[0]) * STEP
        if (bw < 160 || bh < 160) return null
        if (abs(bw - bh) > 0.08 * max(bw, bh)) return null
        val size = (bw + bh) / 2
        return Rect(left, top, left + size, top + size)
    }

    /**
     * Белые ли внизу: сравниваем среднюю яркость центра клеток в двух нижних и двух верхних рядах.
     * Фон (шахматные клетки) в этих рядах одинаковый, различаются только фигуры: белые ярче чёрных.
     */
    fun whiteAtBottom(bmp: Bitmap, r: Rect): Boolean {
        val cw = r.width() / 8f
        val ch = r.height() / 8f
        fun avg(rows: IntArray): Double {
            var sum = 0.0
            var n = 0
            for (row in rows) for (col in 0..7) for (iy in 0 until 6) for (ix in 0 until 6) {
                val x = (r.left + (col + 0.25f + 0.5f * (ix + 0.5f) / 6f) * cw).toInt().coerceIn(0, bmp.width - 1)
                val y = (r.top + (row + 0.2f + 0.6f * (iy + 0.5f) / 6f) * ch).toInt().coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                sum += 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
                n++
            }
            return sum / n
        }
        return avg(intArrayOf(6, 7)) >= avg(intArrayOf(0, 1))
    }
}
