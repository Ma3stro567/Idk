package com.example.chessbot

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * Читает позицию прямо с картинки экрана.
 * Шаблоны фигур берутся из начальной позиции (так бот подстраивается под любой набор фигур/тему).
 * Индекс клетки: rank * 8 + file (a1 = 0, h8 = 63).
 */
class BoardReader {
    companion object {
        const val N = 20
        const val BG_THR = 14
    }

    private class Patch(val px: IntArray, val bg: Int)
    private class Template(val px: IntArray, val isBg: BooleanArray)

    private val templates = HashMap<Char, Template>()

    private fun pixel(bmp: Bitmap, x: Float, y: Float): Int =
        bmp.getPixel(x.toInt().coerceIn(0, bmp.width - 1), y.toInt().coerceIn(0, bmp.height - 1))

    private fun dist(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 255) - ((b shr 16) and 255))
        val dg = abs(((a shr 8) and 255) - ((b shr 8) and 255))
        val db = abs((a and 255) - (b and 255))
        return max(dr, max(dg, db))
    }

    private fun median(v: IntArray): Int {
        v.sort()
        return v[v.size / 2]
    }

    private fun patch(bmp: Bitmap, rect: Rect, row: Int, col: Int): Patch {
        val cw = rect.width() / 8f
        val ch = rect.height() / 8f
        val x0 = rect.left + col * cw
        val y0 = rect.top + row * ch
        val px = IntArray(N * N)
        for (j in 0 until N) for (i in 0 until N) {
            px[j * N + i] = pixel(
                bmp,
                x0 + cw * (0.10f + 0.80f * (i + 0.5f) / N),
                y0 + ch * (0.10f + 0.80f * (j + 0.5f) / N)
            )
        }
        // фон клетки = медиана пикселей из 4 углов (там фигур нет)
        val fs = floatArrayOf(0.04f, 0.06f, 0.08f, 0.92f, 0.94f, 0.96f)
        val rs = IntArray(36)
        val gs = IntArray(36)
        val bs = IntArray(36)
        var k = 0
        for (fx in fs) for (fy in fs) {
            val c = pixel(bmp, x0 + cw * fx, y0 + ch * fy) // 36 точек: 3x3 в каждом из 4 углов
            rs[k] = (c shr 16) and 255
            gs[k] = (c shr 8) and 255
            bs[k] = c and 255
            k++
        }
        val bg = (0xFF shl 24) or (median(rs) shl 16) or (median(gs) shl 8) or median(bs)
        return Patch(px, bg)
    }

    private fun rowCol(file: Int, rank: Int, white: Boolean): Pair<Int, Int> =
        if (white) Pair(7 - rank, file) else Pair(rank, 7 - file)

    /** Запоминает вид фигур. Вызывать, когда на экране начальная позиция. */
    fun learn(bmp: Bitmap, rect: Rect, white: Boolean) {
        templates.clear()
        val reps = mapOf(
            'P' to Pair(4, 1), 'N' to Pair(1, 0), 'B' to Pair(2, 0),
            'R' to Pair(0, 0), 'Q' to Pair(3, 0), 'K' to Pair(4, 0),
            'p' to Pair(4, 6), 'n' to Pair(1, 7), 'b' to Pair(2, 7),
            'r' to Pair(0, 7), 'q' to Pair(3, 7), 'k' to Pair(4, 7)
        )
        for ((c, fr) in reps) {
            val (row, col) = rowCol(fr.first, fr.second, white)
            val p = patch(bmp, rect, row, col)
            templates[c] = Template(p.px, BooleanArray(p.px.size) { dist(p.px[it], p.bg) <= BG_THR })
        }
    }

    private fun classify(bmp: Bitmap, rect: Rect, row: Int, col: Int): Char {
        val p = patch(bmp, rect, row, col)
        val n = p.px.size
        var s0 = 0
        for (i in 0 until n) s0 += dist(p.px[i], p.bg)
        var bestD = s0.toFloat() / n
        var best = ' '
        for ((c, t) in templates) {
            var s = 0
            for (i in 0 until n) {
                val ref = if (t.isBg[i]) p.bg else t.px[i]
                s += dist(p.px[i], ref)
            }
            val d = s.toFloat() / n
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return if (bestD > 70f) '?' else best
    }

    /** Возвращает 64 символа в FEN-нотации: ' ' пусто, '?' не распознано. */
    fun read(bmp: Bitmap, rect: Rect, white: Boolean): CharArray {
        val res = CharArray(64) { ' ' }
        for (rank in 0..7) for (file in 0..7) {
            val (row, col) = rowCol(file, rank, white)
            res[rank * 8 + file] = classify(bmp, rect, row, col)
        }
        return res
    }
}
