package com.example.chessbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
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

/** Затемнённый экран: нужно тапнуть по двум углам доски (левый-верхний и правый-нижний). */
class CalibView(ctx: Context, private val done: (Rect) -> Unit) : View(ctx) {
    private val pts = ArrayList<FloatArray>()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 59, 48) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 44f; textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.argb(110, 0, 0, 0))
    }

    override fun onDraw(c: Canvas) {
        val msg = if (pts.isEmpty()) "Тапни ЛЕВЫЙ ВЕРХНИЙ угол доски" else "Теперь ПРАВЫЙ НИЖНИЙ угол доски"
        c.drawText(msg, width / 2f, height * 0.15f, txt)
        for (p in pts) c.drawCircle(p[0], p[1], 14f, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            pts.add(floatArrayOf(e.rawX, e.rawY))
            invalidate()
            if (pts.size == 2) {
                val l = minOf(pts[0][0], pts[1][0]).toInt()
                val t = minOf(pts[0][1], pts[1][1]).toInt()
                val r = maxOf(pts[0][0], pts[1][0]).toInt()
                val b = maxOf(pts[0][1], pts[1][1]).toInt()
                done(Rect(l, t, r, b))
            }
        }
        return true
    }
}
