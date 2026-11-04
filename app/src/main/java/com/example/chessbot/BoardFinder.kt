package com.example.chessbot

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/** Ищет шахматную доску на скриншоте: сетка 8x8 из двух чередующихся цветов. */
object BoardFinder {
    private const val STEP = 4
    private var tolMask = 10   // допуск цвета при поиске столбцов доски
    private var tolMatch = 24  // допуск цвета клетки при сверке с шахматным узором

    /** Что нашёл последний поиск (для диагностики на панели). */
    @Volatile
    var lastInfo: String = ""

    private fun d(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 255) - ((b shr 16) and 255))
        val dg = abs(((a shr 8) and 255) - ((b shr 8) and 255))
        val db = abs((a and 255) - (b and 255))
        return max(dr, max(dg, db))
    }

    /** Самая длинная серия подряд идущих индексов, где cnt >= порога (допускаем разрыв в 1). */
    private fun longestRun(cnt: IntArray, from: Int, to: Int, frac: Double = 0.5): IntArray? {
        var mx = 0
        for (i in from..to) if (cnt[i] > mx) mx = cnt[i]
        if (mx < 20) return null
        val thr = mx * frac
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

    // известные цвета досок (светлая, тёмная): chess.com зелёная, lichess коричневая и зелёная
    private val PRESETS = arrayOf(
        intArrayOf(0xFFEEEED2.toInt(), 0xFF769656.toInt()),
        intArrayOf(0xFFF0D9B5.toInt(), 0xFFB58863.toInt()),
        intArrayOf(0xFFEAE9D2.toInt(), 0xFF779A58.toInt())
    )

    /** relaxed = true: мягкие допуски для досок с градиентом/текстурой. */
    fun find(bmp: Bitmap, relaxed: Boolean = false): Rect? {
        tolMask = if (relaxed) 26 else 10
        tolMatch = if (relaxed) 44 else 24
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
        val cands = ArrayList<IntArray>() // [счёт, цвет]
        for (e in sorted) {
            if (cands.size >= 14) break
            if (cands.none { d(it[1], e[1]) < 10 }) cands.add(e)
        }

        val pairs = ArrayList<IntArray>()
        for (pr in PRESETS) pairs.add(pr)
        for (i in cands.indices) for (j in i + 1 until cands.size) {
            val a = cands[i]
            val b = cands[j]
            if (d(a[1], b[1]) < 15) continue
            // светлых и тёмных клеток примерно поровну
            if (maxOf(a[0], b[0]) > 2.5 * minOf(a[0], b[0])) continue
            pairs.add(intArrayOf(a[1], b[1]))
        }

        var best: Rect? = null
        var bestScore = 0.0
        for (pr in pairs) {
            val r0 = candidate(bmp, px, w, h, pr[0], pr[1]) ?: continue
            val r = refine(bmp, r0, pr[0], pr[1])
            val sc = sharp(bmp, r, pr[0], pr[1])
            if (sc > bestScore) { bestScore = sc; best = r }
        }
        val hex = cands.take(4).joinToString(" ") { String.format("#%06X", it[1] and 0xFFFFFF) }
        lastInfo = "цвета экрана: $hex; лучшая оценка доски: ${"%.2f".format(bestScore)}" + (if (relaxed) " (мягкий режим)" else "")
        return if (bestScore >= (if (relaxed) 0.5 else 0.6)) best else null
    }

    /**
     * Доска квадратная. Ширину берём по столбцам, где много клеток двух цветов доски
     * (её не портят панели поверх доски), а вертикальное положение ищем по шахматному узору.
     */
    /**
     * Запасной вариант: доска «примерно здесь» (там, где нашли в прошлый раз, или типичное место).
     * Подгоняем края по шахматному узору и принимаем, если узор действительно сходится.
     */
    fun fallback(bmp: Bitmap, start: Rect): Rect? {
        tolMatch = 24
        val cw = start.width() / 8f
        val ch = start.height() / 8f
        fun px(col: Int): Int {
            val x = (start.left + (col + 0.5f) * cw).toInt().coerceIn(0, bmp.width - 1)
            val y = (start.top + 0.1f * ch).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(x, y)
        }
        val pairs = ArrayList<IntArray>()
        for (pr in PRESETS) pairs.add(pr)
        pairs.add(intArrayOf(px(0), px(1))) // цвета прямо с экрана в этом месте
        var best: Rect? = null
        var bestScore = 0.0
        for (pr in pairs) {
            if (d(pr[0], pr[1]) < 15) continue
            val r = refine(bmp, start, pr[0], pr[1])
            val sc = sharp(bmp, r, pr[0], pr[1])
            if (sc > bestScore) { bestScore = sc; best = r }
        }
        lastInfo += "; запасной вариант (оценка ${"%.2f".format(bestScore)})"
        return if (bestScore >= 0.45) best else null
    }

    private fun candidate(bmp: Bitmap, px: IntArray, w: Int, h: Int, a: Int, b: Int): Rect? {
        val colC = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            if (d(p, a) <= tolMask || d(p, b) <= tolMask) colC[x]++
        }
        val rx = longestRun(colC, 0, w - 1, 0.3) ?: return null
        val left = rx[0] * STEP
        val size = (rx[1] + 1 - rx[0]) * STEP
        if (size < 160 || size > minOf(bmp.width, bmp.height) + 8) return null
        var bestSc = -1.0
        var bestT = 0
        var t = 0
        while (t <= bmp.height - size) {
            val sc = sharp(bmp, Rect(left, t, left + size, t + size), a, b)
            if (sc > bestSc) { bestSc = sc; bestT = t }
            t += 4
        }
        return Rect(left, bestT, left + size, bestT + size)
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

    /** Цвет клетки с полупрозрачной подсветкой хода (chess.com: жёлтая 50%, lichess: зелёная 41%). */
    private fun mix(base: Int, over: Int, alpha: Float): Int {
        fun ch(sh: Int) = (((base shr sh) and 255) * (1 - alpha) + ((over shr sh) and 255) * alpha).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Какие цвета допустимы для клетки данного «шахматного» цвета: обычный и с подсветкой хода. */
    private fun tints(base: Int) = intArrayOf(
        base,
        mix(base, 0xFFFFFF33.toInt(), 0.5f),
        mix(base, 0xFF9BC700.toInt(), 0.41f)
    )

    private fun near(p: Int, ts: IntArray): Boolean {
        for (t in ts) if (d(p, t) <= tolMatch) return true
        return false
    }

    /**
     * Сетка 8x8: углы клеток (3% от края) должны чередоваться светлый/тёмный по шахматной расстановке.
     * Жёлтая (подсвеченная) клетка засчитывается как свой цвет по чётности, а не как ошибка.
     */
    private fun sharp(bmp: Bitmap, r: Rect, a: Int, b: Int): Double {
        val cw = r.width() / 8f
        val ch = r.height() / 8f
        val ta = tints(a)
        val tb = tints(b)
        var okA = 0
        var okB = 0
        val offs = floatArrayOf(0.03f, 0.97f)
        for (row in 0..7) for (col in 0..7) {
            val aFirst = (row + col) % 2 == 0
            for (ox in offs) for (oy in offs) {
                val x = (r.left + (col + ox) * cw).toInt().coerceIn(0, bmp.width - 1)
                val y = (r.top + (row + oy) * ch).toInt().coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                val nearA = near(p, ta)
                val nearB = near(p, tb)
                if ((aFirst && nearA) || (!aFirst && nearB)) okA++
                if ((aFirst && nearB) || (!aFirst && nearA)) okB++
            }
        }
        return max(okA, okB) / 256.0
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
