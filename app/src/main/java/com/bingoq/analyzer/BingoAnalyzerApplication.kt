package com.bingoq.analyzer

import android.app.Application
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class BingoAnalyzerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                val report = buildString {
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine(throwable.message ?: "(no message)")
                    appendLine()
                    append(writer.toString())
                }
                getSharedPreferences("bingo_crash_diagnostics", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash_report", report)
                    .commit()
                Log.e("BingoCrash", report, throwable)
            } catch (loggingFailure: Throwable) {
                Log.e("BingoCrash", "Failed to save crash report", loggingFailure)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
