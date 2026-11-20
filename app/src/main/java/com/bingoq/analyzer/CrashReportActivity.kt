package com.bingoq.analyzer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reportFile = File(filesDir, "last_crash.txt")
        val report = runCatching { reportFile.readText() }.getOrDefault("").trim()
        if (report.isEmpty()) {
            openMain()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(0xFF171717.toInt())
        }
        root.addView(TextView(this).apply {
            text = "APP 啟動錯誤診斷"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "已保存上次閃退原因。請先複製下方內容並貼回 ChatGPT。"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 20, 0, 20)
        })
        root.addView(Button(this).apply {
            text = "複製完整錯誤內容"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash report", report))
                text = "已複製，請貼回 ChatGPT"
            }
        })
        root.addView(Button(this).apply {
            text = "清除報告並重新啟動主程式"
            setOnClickListener {
                reportFile.delete()
                openMain()
            }
        })
        root.addView(TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(0xFFE0E0E0.toInt())
            setPadding(0, 20, 0, 0)
        })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
