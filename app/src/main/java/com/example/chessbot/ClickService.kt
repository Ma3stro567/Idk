package com.example.chessbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Службу нужно включить в «Спец. возможности» — через неё бот делает тапы по экрану. */
class ClickService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: ClickService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Тап «как пальцем»: небольшой дрейф точки касания и случайная длительность нажатия. */
    fun tap(x: Float, y: Float) {
        val rnd = java.util.Random()
        val dur = (55 + rnd.nextInt(95)).toLong()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + (rnd.nextFloat() - 0.5f) * 6f, y + (rnd.nextFloat() - 0.5f) * 6f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        dispatchGesture(g, null, null)
    }
}
