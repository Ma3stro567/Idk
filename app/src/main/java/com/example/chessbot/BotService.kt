package com.example.chessbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

class BotService : Service() {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var sw = 0
    private var sh = 0

    private var overlay: OverlayView? = null
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var autoBtn: Button? = null
    private var calib: CalibView? = null

    @Volatile
    private var running = false

    @Volatile
    private var alive = true
    private var worker: Thread? = null
    private var engine: Engine? = null
    private var lastBmp: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY
        startFg()
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", 0)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, ui)
        projection = proj
        setupCapture(proj)
        setupUi()
        setStatus("Готов. Калибр. → открой новую партию → Старт")
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bot", "Chess Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("Chess Bot работает")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- захват экрана ----------

    private fun setupCapture(proj: MediaProjection) {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            sw = m.widthPixels; sh = m.heightPixels
        }
        val dpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        vdisplay = proj.createVirtualDisplay(
            "chessbot", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
    }

    /** Последний кадр экрана. Пока оверлей со стрелкой виден — на мгновение прячем его, чтобы он не мешал распознаванию. */
    private fun grab(): Bitmap? {
        val hide = overlay?.hasMarks == true
        if (hide) {
            ui.post { overlay?.alpha = 0f }
            nap(160)
        }
        val img = imageReader?.acquireLatestImage()
        if (img != null) {
            try {
                val pl = img.planes[0]
                val rowPad = pl.rowStride - pl.pixelStride * img.width
                val full = Bitmap.createBitmap(img.width + rowPad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(pl.buffer)
                lastBmp = if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            } finally {
                img.close()
            }
        }
        if (hide) ui.post { overlay?.alpha = 1f }
        return lastBmp
    }

    // ---------- интерфейс поверх экрана ----------

    private fun lp(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(14, 4, 14, 4)
        setOnClickListener { f() }
    }

    private fun setupUi() {
        val ov = OverlayView(this)
        overlay = ov
        wm.addView(ov, lp(sw, sh, false))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(10, 10, 10, 10)
        }
        val st = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            maxWidth = (sw * 0.8f).toInt()
        }
        statusView = st
        col.addView(st)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Калибр.") { calibrate() })
        row.addView(btn("Старт") { startGame() })
        row.addView(btn("Стоп") { stopGame() })
        val ab = btn(if (Cfg.auto(this)) "Авто: вкл" else "Авто: выкл") {
            Cfg.setAuto(this, !Cfg.auto(this))
            autoBtn?.text = if (Cfg.auto(this)) "Авто: вкл" else "Авто: выкл"
        }
        autoBtn = ab
        row.addView(ab)
        row.addView(btn("✕") { stopSelf() })
        col.addView(row)
        panel = col

        val params = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        params.x = 10
        params.y = 60
        // панель можно перетаскивать за строку статуса
        var sx = 0f; var sy = 0f; var px = 0; var py = 0
        st.setOnTouchListener { _: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = px + (e.rawX - sx).toInt()
                    params.y = py + (e.rawY - sy).toInt()
                    wm.updateViewLayout(col, params)
                }
            }
            true
        }
        wm.addView(col, params)
    }

    private fun setStatus(s: String) {
        ui.post { statusView?.text = s }
    }

    private fun calibrate() {
        if (calib != null) return
        stopGame()
        val cv = CalibView(this) { r ->
            Cfg.saveRect(this, r)
            ui.post {
                calib?.let { wm.removeView(it) }
                calib = null
                setStatus("Доска откалибрована. Открой НОВУЮ партию и жми Старт")
            }
        }
        calib = cv
        wm.addView(cv, lp(sw, sh, true))
    }

    // ---------- игра ----------

    private fun stopGame() {
        running = false
        overlay?.clear()
    }

    private fun startGame() {
        if (running) return
        running = true
        worker = Thread { runBot() }.also { it.isDaemon = true; it.start() }
    }

    private fun nap(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun sqOf(i: Int): Square = Square.valueOf("${'A' + i % 8}${i / 8 + 1}")

    private fun idx(s: Square): Int = (s.value()[1] - '1') * 8 + (s.value()[0] - 'A')

    private fun placement(b: Board) = CharArray(64) { i ->
        val p = b.getPiece(sqOf(i))
        if (p == Piece.NONE) ' ' else p.fenSymbol[0]
    }

    private fun mismatch(a: CharArray, b: CharArray): Int {
        var n = 0
        for (i in 0 until 64) if (a[i] != b[i]) n++
        return n
    }

    private fun center(r: Rect, white: Boolean, sq: Int): PointF {
        val f = sq % 8
        val rk = sq / 8
        val col = if (white) f else 7 - f
        val row = if (white) 7 - rk else rk
        return PointF(r.left + (col + 0.5f) * r.width() / 8f, r.top + (row + 0.5f) * r.height() / 8f)
    }

    private fun tap(p: PointF): Boolean {
        val s = ClickService.instance ?: return false
        s.tap(p.x, p.y)
        return true
    }

    private fun clickMove(m: Move, r: Rect, white: Boolean) {
        val a = center(r, white, idx(m.from))
        val b = center(r, white, idx(m.to))
        if (!tap(a)) {
            setStatus("Включи службу «Chess Bot» в спец. возможностях!")
            return
        }
        nap(450)
        tap(b)
        if (m.promotion != Piece.NONE) {
            nap(600)
            tap(b) // ферзь — первый вариант в окне превращения, он на поле назначения
        }
    }

    /** Выполняет блокирующий вызов с таймаутом, чтобы бот не «висел» бесконечно. */
    private fun <T> timed(sec: Long, f: () -> T): T {
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return ex.submit(java.util.concurrent.Callable { f() }).get(sec, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("Stockfish не ответил за $sec с")
        } finally {
            ex.shutdownNow()
        }
    }

    private fun runBot() {
        try {
            var bmp: Bitmap? = null
            for (i in 0 until 20) {
                bmp = grab()
                if (bmp != null) break
                nap(150)
            }
            if (bmp == null) {
                setStatus("Нет кадра экрана")
                running = false
                return
            }

            setStatus("Ищу доску на экране…")
            val found = BoardFinder.find(bmp)
            val rect = found ?: Cfg.rect(this)
            if (rect == null) {
                setStatus("Не нашёл доску. Открой партию так, чтобы доска была видна целиком, или нажми «Калибр.»")
                running = false
                return
            }
            val reader = BoardReader()
            val white = when (Cfg.colorMode(this)) {
                1 -> true
                2 -> false
                else -> BoardFinder.whiteAtBottom(bmp, rect)
            }
            val mySide = if (white) Side.WHITE else Side.BLACK
            val cell = rect.width() / 8f
            setStatus((if (found != null) "Доска найдена" else "Доска из калибровки") +
                ", я за ${if (white) "белых" else "чёрных"}")

            reader.learn(bmp, rect, white)
            val board = Board()
            val first = reader.read(bmp, rect, white)
            val bad = mismatch(first, placement(board))
            if (bad > 2) {
                // показываем, что бот видит (как на экране: сверху вниз), '.' = пусто, '?' = не понял
                val sb = StringBuilder()
                for (row in 0..7) {
                    for (col in 0..7) {
                        val file = if (white) col else 7 - col
                        val rank = if (white) 7 - row else row
                        val c = first[rank * 8 + file]
                        sb.append(if (c == ' ') '.' else c).append(' ')
                    }
                    sb.append('\n')
                }
                setStatus(
                    "Не вижу начальную позицию (ошибок: $bad из 32). Кадр ${bmp.width}x${bmp.height}, " +
                        "доска $rect. Проверь калибровку, цвет и что партия новая. Бот видит:\n$sb"
                )
                running = false
                return
            }

            if (engine == null) {
                setStatus("Запускаю Stockfish…")
                engine = timed(20) { Engine(this) }
            }
            val eng = engine!!
            eng.configure(Cfg.elo(this))
            eng.newGame()
            setStatus("Играю. Жду…")

            var pending: Move? = null
            var pendingSince = 0L
            var prev: CharArray? = null

            while (alive && running) {
                val b = grab()
                if (b == null) { nap(300); continue }
                val obs = reader.read(b, rect, white)
                // ждём, пока картинка устоится (нет анимации)
                if (prev == null || mismatch(obs, prev) > 1) {
                    prev = obs
                    setStatus("Жду, пока картинка устоится…")
                    nap(250)
                    continue
                }

                val diffCur = mismatch(obs, placement(board))
                if (diffCur >= 2) {
                    var bestM: Move? = null
                    var bestD = 99
                    for (m in board.legalMoves()) {
                        board.doMove(m)
                        val d = mismatch(obs, placement(board))
                        board.undoMove()
                        if (d < bestD) { bestD = d; bestM = m }
                    }
                    if (bestM != null && bestD <= 1 && bestD < diffCur) {
                        board.doMove(bestM)
                        pending = null
                        overlay?.clear()
                        if (board.isMated) { setStatus("Мат. Партия окончена"); break }
                        if (board.isDraw) { setStatus("Ничья. Партия окончена"); break }
                    } else {
                        setStatus("Не понял, что изменилось на доске (отличий: $diffCur). Жду…")
                        nap(300)
                        continue
                    }
                }

                if (board.sideToMove == mySide && pending == null) {
                    setStatus("Думаю…")
                    val fen = board.fen
                    val u = timed(Cfg.ms(this) / 1000L + 15) { eng.bestMove(fen, Cfg.ms(this)) }
                    if (u.length < 4) { setStatus("Нет хода от движка"); break }
                    val f = u.substring(0, 2).uppercase()
                    val t = u.substring(2, 4).uppercase()
                    val promo = if (u.length > 4) u.substring(4, 5) else ""
                    val move = board.legalMoves().firstOrNull {
                        it.from.value() == f && it.to.value() == t &&
                            (promo.isEmpty() || it.promotion.fenSymbol.lowercase() == promo)
                    }
                    if (move == null) { setStatus("Ход движка не найден среди легальных: $u"); break }
                    pending = move
                    pendingSince = System.currentTimeMillis()
                    val a = center(rect, white, idx(move.from))
                    val c = center(rect, white, idx(move.to))
                    overlay?.show(a.x, a.y, c.x, c.y, cell * 0.42f)
                    setStatus("Ход: ${u.substring(0, 2)} → ${u.substring(2)}   (1 — откуда, 2 — куда)")
                    if (Cfg.auto(this)) clickMove(move, rect, white)
                } else if (pending != null && Cfg.auto(this) &&
                    System.currentTimeMillis() - pendingSince > 6000
                ) {
                    pendingSince = System.currentTimeMillis()
                    clickMove(pending, rect, white) // ход не прошёл — пробуем ещё раз
                }
                if (board.sideToMove != mySide) setStatus("Жду хода соперника…")
                nap(350)
            }
        } catch (e: Exception) {
            val m = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            setStatus("Ошибка: $m")
        } finally {
            running = false
            overlay?.clear()
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        worker?.interrupt()
        try { engine?.destroy() } catch (_: Exception) {}
        listOf<View?>(overlay, panel, calib).forEach { v ->
            try { if (v != null) wm.removeView(v) } catch (_: Exception) {}
        }
        try { vdisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
