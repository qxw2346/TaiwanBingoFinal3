package com.bingoq.analyzer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching { File(filesDir, "last_crash.txt").readText() }
            .getOrElse { "診斷報告讀取失敗：${it.message}" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(0xFF171717.toInt())
        }
        root.addView(TextView(this).apply {
            text = "APP 異常診斷報告"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(Button(this).apply {
            text = "複製完整錯誤內容"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash report", report))
                text = "已複製，請貼回 ChatGPT"
            }
        })
        root.addView(TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(0xFFE0E0E0.toInt())
        })
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
