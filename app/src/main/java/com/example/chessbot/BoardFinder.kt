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
            val r = candidate(px, w, h, a, b) ?: continue
            val sc = verify(bmp, r, a, b)
            if (sc > bestScore) { bestScore = sc; best = r }
        }
        return if (bestScore >= 0.8) best else null
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

    /** Доля клеток, где фон в углах соответствует шахматному чередованию двух цветов. */
    private fun verify(bmp: Bitmap, r: Rect, a: Int, b: Int): Double {
        val cell = r.width() / 8f
        var okA = 0
        var okB = 0
        val offs = floatArrayOf(0.12f, 0.88f)
        for (row in 0..7) for (col in 0..7) {
            var va = 0 // сколько углов похожи на a
            var vb = 0
            for (ox in offs) for (oy in offs) {
                val x = (r.left + (col + ox) * cell).toInt().coerceIn(0, bmp.width - 1)
                val y = (r.top + (row + oy) * cell).toInt().coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                if (d(p, a) <= 24) va++
                if (d(p, b) <= 24) vb++
            }
            val aFirst = (row + col) % 2 == 0
            if ((aFirst && va >= 3) || (!aFirst && vb >= 3)) okA++
            if ((aFirst && vb >= 3) || (!aFirst && va >= 3)) okB++
        }
        return max(okA, okB) / 64.0
    }

    /** Белые ли внизу: у белых фигур пиксели ярче, чем у чёрных. */
    fun whiteAtBottom(reader: BoardReader, bmp: Bitmap, r: Rect): Boolean {
        fun avg(rows: IntArray): Float {
            var s = 0f
            var n = 0f
            for (row in rows) for (col in 0..7) {
                val v = reader.pieceLuma(bmp, r, row, col)
                s += v[0]; n += v[1]
            }
            return if (n > 0) s / n else 0f
        }
        return avg(intArrayOf(6, 7)) >= avg(intArrayOf(0, 1))
    }
}
