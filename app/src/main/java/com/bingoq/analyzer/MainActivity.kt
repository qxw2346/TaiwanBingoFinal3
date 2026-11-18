package com.bingoq.analyzer
import android.graphics.Color
import android.os.Bundle
import android.view.View
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class MainActivity : AppCompatActivity() {
    private lateinit var tvValidationBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnPredict: MaterialButton
    private lateinit var btnOptimize: MaterialButton
    private val okHttpClient = OkHttpProvider().client
    private val apiSource = TaiwanLotteryApiSource(okHttpClient)
    private val htmlSource = AuzoHtmlSource(okHttpClient)
    private val analyzerEngine = BingoAnalyzerEngine()
    private val drawDao by lazy { BingoDatabase.getInstance(this).drawDao() }
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
        btnUpdate.setOnClickListener { fetchAndLoadData() }
        btnPredict.setOnClickListener { runPrediction() }
        btnOptimize.setOnClickListener { runOptimization() }
        loadOfflineCacheOnStart()
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
                tvStatus.text = "狀態：已載入離線歷史資料，可直接預測或點擊更新"
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
            activeWeights = opt.bestWeights
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
                val w = opt.bestWeights
                append("\n已自動套用搜尋最佳權重：\n")
                append(
                    String.format(
                        Locale.US,
                        "歷史%.0f%% / 動能%.0f%% / 衰減%.0f%% / 遺漏%.0f%% / 尾數%.0f%% / 穩定%.0f%%",
                        w.wA * 100, w.wB * 100, w.wC * 100, w.wD * 100, w.wE * 100, w.wF * 100
                    )
                )
            }
            withContext(Dispatchers.Main) {
                tvResult.text = sb.toString()
                setLoading(false, "狀態：最佳化完成！可再次點擊「一鍵執行預測」查看新排名")
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
        tvStatus.text = statusMsg
    }
}