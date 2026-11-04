package com.example.chessbot

import android.content.Context
import android.graphics.Rect

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun elo(c: Context) = p(c).getInt("elo", 2800)          // 0 = максимум
    fun ms(c: Context) = p(c).getInt("ms", 1000)
    fun colorMode(c: Context) = p(c).getInt("color", 0) // 0 авто, 1 белые, 2 чёрные
    fun minSec(c: Context) = p(c).getInt("minSec", 0)
    fun maxSec(c: Context) = p(c).getInt("maxSec", 0)
    fun auto(c: Context) = p(c).getBoolean("auto", true)

    fun save(c: Context, elo: Int, ms: Int, color: Int, auto: Boolean, minSec: Int, maxSec: Int) {
        p(c).edit().putInt("minSec", minSec).putInt("maxSec", maxSec).putInt("elo", elo).putInt("ms", ms).putInt("color", color).putBoolean("auto", auto).apply()
    }

    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    /** Где доска была найдена в последний раз (запасной вариант, если автопоиск не сработает). */
    fun lastBoard(c: Context): Rect? {
        val s = p(c)
        if (!s.contains("bl")) return null
        return Rect(s.getInt("bl", 0), s.getInt("bt", 0), s.getInt("br", 0), s.getInt("bb", 0))
    }

    fun saveBoard(c: Context, r: Rect) {
        p(c).edit().putInt("bl", r.left).putInt("bt", r.top).putInt("br", r.right).putInt("bb", r.bottom).apply()
    }
}
