package com.example.chessbot

import android.content.Context
import android.graphics.Rect

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun elo(c: Context) = p(c).getInt("elo", 2800)          // 0 = максимум
    fun ms(c: Context) = p(c).getInt("ms", 1000)
    fun colorMode(c: Context) = p(c).getInt("color", 0) // 0 авто, 1 белые, 2 чёрные
    fun auto(c: Context) = p(c).getBoolean("auto", true)

    fun save(c: Context, elo: Int, ms: Int, color: Int, auto: Boolean) {
        p(c).edit().putInt("elo", elo).putInt("ms", ms).putInt("color", color).putBoolean("auto", auto).apply()
    }

    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    fun rect(c: Context): Rect? {
        val s = p(c)
        if (!s.contains("l")) return null
        return Rect(s.getInt("l", 0), s.getInt("t", 0), s.getInt("r", 0), s.getInt("b", 0))
    }

    fun saveRect(c: Context, r: Rect) {
        p(c).edit().putInt("l", r.left).putInt("t", r.top).putInt("r", r.right).putInt("b", r.bottom).apply()
    }
}
