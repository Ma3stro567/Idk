package com.example.chessbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqProjection = 1001
    private lateinit var elo: EditText
    private lateinit var ms: EditText
    private lateinit var rbWhite: RadioButton
    private lateinit var rbBlack: RadioButton
    private lateinit var auto: CheckBox

    private fun tv(t: String, size: Float = 15f) = TextView(this).apply {
        text = t
        textSize = size
        setPadding(0, 16, 0, 8)
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        col.addView(tv("Chess Bot (Stockfish)", 22f))
        col.addView(tv("1) Разреши показ поверх других окон:"))
        col.addView(btn("Разрешить оверлей") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(tv("2) Включи службу «Chess Bot» в спец. возможностях (для тапов):"))
        col.addView(btn("Открыть спец. возможности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(tv("3) Настройки:"))
        col.addView(tv("Сила в Эло (1320–3190, 0 = максимум, ~3500):", 13f))
        elo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Cfg.elo(this@MainActivity).toString())
        }
        col.addView(elo)
        col.addView(tv("Время на ход, мс:", 13f))
        ms = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Cfg.ms(this@MainActivity).toString())
        }
        col.addView(ms)
        col.addView(tv("Я играю за:", 13f))
        val rg = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        rbWhite = RadioButton(this).apply { text = "Белых"; id = View.generateViewId() }
        rbBlack = RadioButton(this).apply { text = "Чёрных"; id = View.generateViewId() }
        rg.addView(rbWhite)
        rg.addView(rbBlack)
        if (Cfg.white(this)) rbWhite.isChecked = true else rbBlack.isChecked = true
        col.addView(rg)
        auto = CheckBox(this).apply {
            text = "Делать тапы автоматически (иначе только подсказка 1 → 2)"
            isChecked = Cfg.auto(this@MainActivity)
        }
        col.addView(auto)

        col.addView(tv("4) Запуск (потом сверни приложение и открой шахматы):"))
        col.addView(btn("Запустить бота") { start() })
        col.addView(
            tv(
                "В шахматах: на плавающей панели жми «Калибр.» и тапни два угла доски, " +
                    "затем в НАЧАЛЕ партии жми «Старт». Доска должна быть полностью видна.",
                13f
            )
        )
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши оверлей", Toast.LENGTH_LONG).show()
            return
        }
        Cfg.save(
            this,
            elo.text.toString().toIntOrNull() ?: 0,
            (ms.text.toString().toIntOrNull() ?: 1000).coerceIn(100, 60000),
            rbWhite.isChecked,
            auto.isChecked
        )
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java).putExtra("code", resultCode).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
