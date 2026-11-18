package com.bingoq.analyzer

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bingoq.analyzer.data.local.BingoDatabase
import com.bingoq.analyzer.data.local.BingoDrawEntity
import com.bingoq.analyzer.data.local.VerificationBadge
import com.bingoq.analyzer.data.remote.AuzoHtmlSource
import com.bingoq.analyzer.data.remote.OkHttpProvider
import com.bingoq.analyzer.data.remote.TaiwanLotteryApiSource
import com.bingoq.analyzer.data.validation.DataValidator
import com.bingoq.analyzer.data.validation.RawDrawRecord
import com.bingoq.analyzer.data.validation.ValidationReport
import com.bingoq.analyzer.domain.BingoAnalyzerEngine
import com.bingoq.analyzer.domain.ModelWeights
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvValidationBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnPredict: MaterialButton
    private lateinit var btnOptimize: MaterialButton
    private lateinit var btnWeights: MaterialButton

    private val okHttpClient = OkHttpProvider().client
    private val apiSource = TaiwanLotteryApiSource(okHttpClient)
    private val htmlSource = AuzoHtmlSource(okHttpClient)
    private val analyzerEngine = BingoAnalyzerEngine()
    private val drawDao by lazy { BingoDatabase.getInstance(this).drawDao() }
    private val prefs by lazy { getSharedPreferences("bingo_quant_prefs", Context.MODE_PRIVATE) }

    private var cachedEntities: List<BingoDrawEntity> = emptyList()
    private var lastReport: ValidationReport? = null
    private var activeWeights: ModelWeights = ModelWeights()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvValidationBadge = findViewById(R.id.tvValidationBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        progressBar = findViewById(R.id.progressBar)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnPredict = findViewById(R.id.btnPredict)
        btnOptimize = findViewById(R.id.btnOptimize)
        btnWeights = findViewById(R.id.btnWeights)

        activeWeights = loadSavedWeights()

        btnUpdate.setOnClickListener { fetchAndLoadData() }
        btnPredict.setOnClickListener { runPrediction() }
        btnOptimize.setOnClickListener { runOptimization() }
        btnWeights.setOnClickListener { showWeightDialog() }

        loadOfflineCacheOnStart()
    }

    private fun loadSavedWeights(): ModelWeights {
        return ModelWeights(
            wA = prefs.getFloat("wA", 0.25f).toDouble(),
            wB = prefs.getFloat("wB", 0.20f).toDouble(),
            wC = prefs.getFloat("wC", 0.20f).toDouble(),
            wD = prefs.getFloat("wD", 0.10f).toDouble(),
            wE = prefs.getFloat("wE", 0.10f).toDouble(),
            wF = prefs.getFloat("wF", 0.15f).toDouble()
        ).normalized()
    }

    private fun saveWeights(weights: ModelWeights) {
        val norm = weights.normalized()
        activeWeights = norm
        prefs.edit()
            .putFloat("wA", norm.wA.toFloat())
            .putFloat("wB", norm.wB.toFloat())
            .putFloat("wC", norm.wC.toFloat())
            .putFloat("wD", norm.wD.toFloat())
            .putFloat("wE", norm.wE.toFloat())
            .putFloat("wF", norm.wF.toFloat())
            .apply()
    }

    private fun formatWeightsSummary(w: ModelWeights): String {
        val n = w.normalized()
        return String.format(
            Locale.US,
            "歷史%.0f%% / 動能%.0f%% / 衰減%.0f%% / 遺漏%.0f%% / 尾數%.0f%% / 穩定%.0f%%",
            n.wA * 100, n.wB * 100, n.wC * 100, n.wD * 100, n.wE * 100, n.wF * 100
        )
    }

    private fun showWeightDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val hintView = TextView(this).apply {
            text = "可點快速策略或拖動滑桿（系統會自動換算成總和 100%）："
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        container.addView(hintView)

        val factorNames = listOf(
            "A. 歷史頻率（長期熱門度）",
            "B. 近期動能（近10期升溫）",
            "C. 指數衰減（越近權重越高）",
            "D. 遺漏期數（冷門補開傾向）",
            "E. 尾數分布（0~9尾數熱度）",
            "F. 頻率穩定度（波動小較穩）"
        )

        val currentValues = intArrayOf(
            (activeWeights.wA * 100).roundToInt(),
            (activeWeights.wB * 100).roundToInt(),
            (activeWeights.wC * 100).roundToInt(),
            (activeWeights.wD * 100).roundToInt(),
            (activeWeights.wE * 100).roundToInt(),
            (activeWeights.wF * 100).roundToInt()
        )

        val labels = mutableListOf<TextView>()
        val seekBars = mutableListOf<SeekBar>()

        fun refreshLabels() {
            val sum = currentValues.sum()
            for (i in 0 until 6) {
                val pct = if (sum > 0) (currentValues[i] * 100.0 / sum) else (100.0 / 6.0)
                labels[i].text = String.format(
                    Locale.US,
                    "%s：拉桿 %d（實際佔 %.0f%%）",
                    factorNames[i], currentValues[i], pct
                )
            }
        }

        fun applyPresetToSliders(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
            val preset = intArrayOf(a, b, c, d, e, f)
            for (i in 0 until 6) {
                currentValues[i] = preset[i]
                seekBars[i].progress = preset[i]
            }
            refreshLabels()
        }

        fun createPresetRow(
            leftTitle: String,
            leftAction: () -> Unit,
            rightTitle: String,
            rightAction: () -> Unit
        ): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
                val btnLeft = MaterialButton(this@MainActivity).apply {
                    text = leftTitle
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 8
                    }
                    setOnClickListener { leftAction() }
                }
                val btnRight = MaterialButton(this@MainActivity).apply {
                    text = rightTitle
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 8
                    }
                    setOnClickListener { rightAction() }
                }
                addView(btnLeft)
                addView(btnRight)
            }
        }

        container.addView(
            createPresetRow(
                "預設平衡", { applyPresetToSliders(25, 20, 20, 10, 10, 15) },
                "熱門強勢派", { applyPresetToSliders(35, 30, 25, 0, 0, 10) }
            )
        )
        container.addView(
            createPresetRow(
                "冷門補開派", { applyPresetToSliders(15, 0, 0, 50, 20, 15) },
                "近期急衝派", { applyPresetToSliders(10, 40, 40, 0, 10, 0) }
            )
        )

        for (i in 0 until 6) {
            val tv = TextView(this).apply {
                textSize = 13.5f
                setPadding(0, 16, 0, 4)
            }
            val sb = SeekBar(this).apply {
                max = 100
                progress = currentValues[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            currentValues[i] = progress
                            refreshLabels()
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            labels.add(tv)
            seekBars.add(sb)
            container.addView(tv)
            container.addView(sb)
        }

        refreshLabels()

        val scroll = ScrollView(this).apply { addView(container) }

        MaterialAlertDialogBuilder(this)
            .setTitle("手動調整分析權重")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("套用並立即分析") { _, _ ->
                val chosen = if (currentValues.sum() <= 0) {
                    ModelWeights()
                } else {
                    ModelWeights(
                        wA = currentValues[0].toDouble(),
                        wB = currentValues[1].toDouble(),
                        wC = currentValues[2].toDouble(),
                        wD = currentValues[3].toDouble(),
                        wE = currentValues[4].toDouble(),
                        wF = currentValues[5].toDouble()
                    ).normalized()
                }
                saveWeights(chosen)
                if (cachedEntities.isNotEmpty()) {
                    runPrediction()
                } else {
                    tvStatus.text = "已儲存權重：${formatWeightsSummary(activeWeights)}"
                }
            }
            .show()
    }

    private fun loadOfflineCacheOnStart() {
        lifecycleScope.launch {
            val localDraws = withContext(Dispatchers.IO) { drawDao.getLatestValidDraws(150) }
            if (localDraws.isNotEmpty()) {
                cachedEntities = localDraws
                btnPredict.isEnabled = true
                btnOptimize.isEnabled = localDraws.size >= 16
                tvValidationBadge.text = "驗證燈號：本機快取 (${localDraws.size} 期)"
                tvValidationBadge.setTextColor(Color.parseColor("#EF6C00"))
                tvStatus.text = "狀態：已載入離線資料 | 權重：${formatWeightsSummary(activeWeights)}"
            } else {
                tvStatus.text = "目前權重：${formatWeightsSummary(activeWeights)}"
            }
        }
    }

    private fun fetchAndLoadData() {
        setLoading(true, "狀態：正在連線台灣彩券官方 API 與奧索備援網交叉驗證...")

        lifecycleScope.launch {
            val combinedRaw = mutableListOf<RawDrawRecord>()
            val sourceErrors = mutableListOf<String>()

            val apiRes = apiSource.fetchRecentDraws(150)
            apiRes.onSuccess { combinedRaw.addAll(it) }
                .onFailure { sourceErrors.add("台彩API失敗: ${it.localizedMessage}") }

            val htmlRes = htmlSource.fetchRecentDraws(80)
            htmlRes.onSuccess { combinedRaw.addAll(it) }
                .onFailure { sourceErrors.add("奧索網失敗: ${it.localizedMessage}") }

            if (combinedRaw.isEmpty()) {
                val localDraws = withContext(Dispatchers.IO) { drawDao.getLatestValidDraws(150) }
                setLoading(false, "狀態：網路擷取失敗 (${sourceErrors.joinToString("; ")})")
                if (localDraws.isNotEmpty()) {
                    cachedEntities = localDraws
                    btnPredict.isEnabled = true
                    btnOptimize.isEnabled = localDraws.size >= 16
                    tvResult.text = "網路連線異常，已自動保留並啟用本機 SQLite 現有 ${localDraws.size} 期資料。"
                }
                return@launch
            }

            val (validatedEntities, report) = withContext(Dispatchers.Default) {
                DataValidator.validateAndMerge(combinedRaw)
            }

            withContext(Dispatchers.IO) {
                drawDao.upsertDraws(validatedEntities)
            }

            cachedEntities = validatedEntities
            lastReport = report

            updateBadgeUi(report)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date(report.updatedAtEpochMs))
            setLoading(false, "狀態：${report.crossCheckMessage} (更新於 $timeStr)")

            btnPredict.isEnabled = report.canPredictOfficially
            btnOptimize.isEnabled = report.canPredictOfficially && validatedEntities.size >= 16

            if (report.canPredictOfficially) {
                runPrediction()
            } else {
                tvResult.text = "資料驗證為 RED（紅燈），禁止產生正式預測。\n衝突期數：${report.conflictPeriods}\n異常筆數：${report.invalidFormatCount}"
            }
        }
    }

    private fun runPrediction() {
        if (cachedEntities.isEmpty()) return
        setLoading(true, "狀態：正在計算多因素權重與防資料洩漏歷史回測...")

        lifecycleScope.launch(Dispatchers.Default) {
            val res = analyzerEngine.analyzeEntities(cachedEntities, lastReport, activeWeights)
            val top3 = res.rankedNumbers.take(3).joinToString(", ") { "%02d".format(Locale.US, it.number) }
            val top5 = res.rankedNumbers.take(5).joinToString(", ") { "%02d".format(Locale.US, it.number) }
            val top10 = res.rankedNumbers.take(10)
            val top10Str = top10.joinToString(", ") { "%02d".format(Locale.US, it.number) }

            val sb = StringBuilder().apply {
                append("【量化預測與驗證報告】\n")
                append("• 預測參考期數：${res.referencePeriod}\n")
                append("• 資料截止期數：${res.cutoffPeriod}\n")
                append("• 有效歷史期數：${res.totalAnalyzedDraws} 期\n")
                append("• 目前套用權重：${formatWeightsSummary(activeWeights)}\n")
                append("• 近50期回測 Top3 至少中1球率：${String.format(Locale.US, "%.1f", res.backtestWinRate)}%\n")
                append("• 近50期回測 Top3 平均命中：${String.format(Locale.US, "%.2f", res.top3AvgHits)} 顆 (隨機基準 0.75)\n\n")
                append("★ Top 3 參考號碼：$top3\n")
                append("★ Top 5 參考號碼：$top5\n")
                append("★ Top 10 參考號碼：$top10Str\n\n")
                append("【前 10 名量化指標明細（分數非開獎機率）】\n")
                top10.forEachIndexed { idx, item ->
                    append(
                        String.format(
                            Locale.US,
                            "#%02d 號碼 %02d | 頻率:%2d | 遺漏:%2d | 得分:%.2f\n",
                            idx + 1, item.number, item.frequency, item.missingCount, item.weightedScore
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                tvResult.text = sb.toString()
                setLoading(false, "狀態：分析與回測完成！")
            }
        }
    }

    private fun runOptimization() {
        if (cachedEntities.size < 16) return
        setLoading(true, "狀態：正在執行 Walk-Forward 權重最佳化與 5 模型競賽...")

        lifecycleScope.launch(Dispatchers.Default) {
            val opt = analyzerEngine.optimize(cachedEntities, activeWeights)
            saveWeights(opt.bestWeights)

            val sb = StringBuilder().apply {
                append("【Walk-Forward 5 大模型公平競賽結果】\n")
                opt.warningMessage?.let { append("⚠ $it\n\n") }
                opt.competitionTable.forEachIndexed { i, m ->
                    append("${i + 1}. ${m.modelName}\n")
                    append(
                        String.format(
                            Locale.US,
                            "   Top3平均命中: %.2f 顆 | 至少中1球: %.1f%% | Top1: %.1f%%\n",
                            m.top3AvgHits, m.top3AtLeastOneRate * 100, m.top1HitRate * 100
                        )
                    )
                }
                append("\n已自動套用並儲存最佳權重：\n")
                append(formatWeightsSummary(activeWeights))
            }

            withContext(Dispatchers.Main) {
                tvResult.text = sb.toString()
                setLoading(false, "狀態：最佳化完成！可點擊「一鍵執行預測」查看新排名")
            }
        }
    }

    private fun updateBadgeUi(report: ValidationReport) {
        when (report.badge) {
            VerificationBadge.GREEN -> {
                tvValidationBadge.text = "驗證燈號：GREEN（已交叉驗證 ${report.fetchedCount} 期）"
                tvValidationBadge.setTextColor(Color.parseColor("#2E7D32"))
            }
            VerificationBadge.YELLOW -> {
                tvValidationBadge.text = "驗證燈號：YELLOW（部分驗證，缺漏 ${report.missingPeriods.size} 期）"
                tvValidationBadge.setTextColor(Color.parseColor("#EF6C00"))
            }
            VerificationBadge.RED -> {
                tvValidationBadge.text = "驗證燈號：RED（資料驗證失敗，禁止預測）"
                tvValidationBadge.setTextColor(Color.parseColor("#C62828"))
            }
        }
    }

    private fun setLoading(loading: Boolean, statusMsg: String) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnUpdate.isEnabled = !loading
        btnWeights.isEnabled = !loading
        tvStatus.text = statusMsg
    }
}
