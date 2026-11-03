package com.example.chessbot

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/** Stockfish, запущенный как отдельный процесс и управляемый по протоколу UCI. */
class Engine(ctx: Context) {
    private val proc: Process
    private val out: BufferedWriter
    private val inp: BufferedReader

    init {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!f.exists()) throw IllegalStateException("Stockfish не найден в APK")
        proc = ProcessBuilder(f.absolutePath).redirectErrorStream(true).start()
        out = proc.outputStream.bufferedWriter()
        inp = proc.inputStream.bufferedReader()
        send("uci")
        waitFor("uciok")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
    }

    private fun send(cmd: String) {
        out.write(cmd)
        out.write("\n")
        out.flush()
    }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = inp.readLine() ?: throw IllegalStateException("Stockfish завершился")
            if (line.startsWith(prefix)) return line
        }
    }

    /** elo = 0 — полная сила (~3500), иначе ограничение силы в Эло (1320..3190). */
    fun configure(elo: Int) {
        if (elo > 0) {
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value ${elo.coerceIn(1320, 3190)}")
        } else {
            send("setoption name UCI_LimitStrength value false")
        }
        send("isready")
        waitFor("readyok")
    }

    fun newGame() {
        send("ucinewgame")
        send("isready")
        waitFor("readyok")
    }

    /** Возвращает ход в формате UCI, например "e2e4" или "e7e8q". */
    fun bestMove(fen: String, ms: Int): String {
        send("position fen $fen")
        send("go movetime $ms")
        val line = waitFor("bestmove")
        return line.split(" ").getOrElse(1) { "" }
    }

    fun destroy() {
        try {
            send("quit")
        } catch (_: Exception) {
        }
        proc.destroy()
    }
}
