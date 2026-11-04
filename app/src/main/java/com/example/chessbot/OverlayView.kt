package com.example.chessbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Прозрачный слой поверх экрана: стрелка и кружки «1 — откуда», «2 — куда». */
class OverlayView(ctx: Context) : View(ctx) {
    @Volatile
    private var m: FloatArray? = null
    private var radius = 40f

    val hasMarks: Boolean get() = m != null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 59, 48)
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
    }
    private val ring1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 199, 89); style = Paint.Style.STROKE; strokeWidth = 9f
    }
    private val ring2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 149, 0); style = Paint.Style.STROKE; strokeWidth = 9f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 46f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    fun show(fx: Float, fy: Float, tx: Float, ty: Float, r: Float) {
        radius = r
        m = floatArrayOf(fx, fy, tx, ty)
        postInvalidate()
    }

    fun clear() {
        m = null
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        val a = m ?: return
        val ang = atan2(a[3] - a[1], a[2] - a[0])
        val head = radius * 0.9f
        val ex = a[2] - cos(ang) * radius * 0.3f
        val ey = a[3] - sin(ang) * radius * 0.3f
        c.drawLine(a[0], a[1], ex, ey, line)
        for (s in floatArrayOf(0.5f, -0.5f)) {
            c.drawLine(ex, ey, ex - head * cos(ang + s), ey - head * sin(ang + s), line)
        }
        c.drawCircle(a[0], a[1], radius, ring1)
        c.drawCircle(a[2], a[3], radius, ring2)
        c.drawText("1", a[0], a[1] + 16f, text)
        c.drawText("2", a[2], a[3] + 16f, text)
    }
}
