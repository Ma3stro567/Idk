package com.example.chessbot

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import android.content.Context
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
        val fs = floatArrayOf(0.07f, 0.09f, 0.11f, 0.89f, 0.91f, 0.93f)
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

    val ready: Boolean get() = templates.size == 12

    fun clear() = templates.clear()

    fun save(f: File) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(templates.size)
            for ((c, t) in templates) {
                o.writeChar(c.code)
                for (v in t.px) o.writeInt(v)
                for (b in t.isBg) o.writeBoolean(b)
            }
        }
    }

    fun load(f: File): Boolean {
        if (!f.exists()) return false
        return try {
            f.inputStream().use { readFrom(it) }
        } catch (_: Exception) {
            false
        }
    }

    /** Шаблоны фигур, вшитые в приложение (набор chess.com, сделан с реального скриншота). */
    fun loadAsset(ctx: Context): Boolean {
        return try {
            ctx.assets.open("templates_chesscom.bin").use { readFrom(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun readFrom(ins: InputStream): Boolean {
        val i = DataInputStream(ins.buffered())
        val n = i.readInt()
        val m = HashMap<Char, Template>()
        repeat(n) {
            val c = i.readChar()
            val px = IntArray(N * N) { i.readInt() }
            val bg = BooleanArray(N * N) { i.readBoolean() }
            m[c] = Template(px, bg)
        }
        if (m.size != 12) return false
        templates.clear()
        templates.putAll(m)
        return true
    }

    /** Белые внизу? Сравниваем среднюю высоту белых и чёрных фигур на экране (при отображении «белые снизу»). */
    fun whiteAtBottom(obs: CharArray): Boolean? {
        var ws = 0.0; var wn = 0; var bs = 0.0; var bn = 0
        for (i in 0 until 64) {
            val c = obs[i]
            if (c == ' ' || c == '?') continue
            val row = 7 - i / 8 // экранная строка при white=true
            if (c.isUpperCase()) { ws += row; wn++ } else { bs += row; bn++ }
        }
        if (wn == 0 || bn == 0) return null
        return ws / wn >= bs / bn
    }

    /** [сумма яркости, число пикселей] фигуры в клетке (для определения, белые внизу или чёрные). */
    fun pieceLuma(bmp: Bitmap, rect: Rect, row: Int, col: Int): FloatArray {
        val p = patch(bmp, rect, row, col)
        var s = 0f
        var n = 0
        for (c in p.px) {
            if (dist(c, p.bg) > 60) {
                s += 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
                n++
            }
        }
        return floatArrayOf(s, n.toFloat())
    }

    /**
     * Запоминает вид фигур с экрана, где стоит (почти) начальная позиция.
     * set 0 — берём фигуры с клеток a1,b1,c1,d1,e1 + пешка d; set 1 — с h1,g1,f1,d1,e1 + пешка f.
     * Так учёба работает и после первых ходов (если ходили фигурами из другой половины).
     */
    fun learn(bmp: Bitmap, rect: Rect, white: Boolean, set: Int = 0) {
        templates.clear()
        val reps = if (set == 0) mapOf(
            'P' to Pair(3, 1), 'N' to Pair(1, 0), 'B' to Pair(2, 0),
            'R' to Pair(0, 0), 'Q' to Pair(3, 0), 'K' to Pair(4, 0),
            'p' to Pair(3, 6), 'n' to Pair(1, 7), 'b' to Pair(2, 7),
            'r' to Pair(0, 7), 'q' to Pair(3, 7), 'k' to Pair(4, 7)
        ) else mapOf(
            'P' to Pair(5, 1), 'N' to Pair(6, 0), 'B' to Pair(5, 0),
            'R' to Pair(7, 0), 'Q' to Pair(3, 0), 'K' to Pair(4, 0),
            'p' to Pair(5, 6), 'n' to Pair(6, 7), 'b' to Pair(5, 7),
            'r' to Pair(7, 7), 'q' to Pair(3, 7), 'k' to Pair(4, 7)
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
        // углы клетки с подписями (цифры/буквы) не сравниваем
        val use = BooleanArray(n) { k -> val x = k % N; val y = k / N; !((x >= 17 && y >= 17) || (x <= 2 && y <= 2)) }
        val cnt = use.count { it }
        var s0 = 0
        for (i in 0 until n) if (use[i]) s0 += dist(p.px[i], p.bg)
        var bestD = s0.toFloat() / cnt
        var best = ' '
        for ((c, t) in templates) {
            var s = 0
            for (i in 0 until n) {
                if (!use[i]) continue
                val ref = if (t.isBg[i]) p.bg else t.px[i]
                s += dist(p.px[i], ref)
            }
            val d = s.toFloat() / cnt
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
