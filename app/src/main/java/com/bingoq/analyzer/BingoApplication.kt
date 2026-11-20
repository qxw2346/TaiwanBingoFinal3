package com.bingoq.analyzer

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
                Handler(Looper.getMainLooper()).post {
                    try {
                        startActivity(Intent(this, CrashReportActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                    } catch (_: Throwable) {
                        // Crash report remains saved in app-private storage.
                    }
                }
            } catch (_: Throwable) {
                // Never let crash reporting cause a second crash.
            }
            // Do not delegate: Android's default handler would immediately kill the process
            // before the report screen can be displayed.
        }
    }
}
