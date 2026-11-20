package com.bingoq.analyzer

import android.app.Application
import java.io.File

class BingoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildString {
                    appendLine("TaiwanBingoFinal3 crash report")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine()
                    append(android.util.Log.getStackTraceString(error))
                }
                File(filesDir, "last_crash.txt").writeText(report)
            } catch (_: Throwable) {
                // Preserve the original exception handling if report writing fails.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
