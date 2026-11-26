package com.bingoq.analyzer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bingoq.analyzer.audit.PredictionAuditStore
import com.bingoq.analyzer.algorithm.BingoAnalyzerEngine
import com.bingoq.analyzer.algorithm.RecommendationEngine
import com.bingoq.analyzer.data.BingoDatabase
import com.bingoq.analyzer.data.WeightManager
import com.bingoq.analyzer.databinding.ActivityMainBinding
import com.bingoq.analyzer.model.HitRateRecommendation
import com.bingoq.analyzer.model.OptimizationResult
import com.bingoq.analyzer.model.WeightConfig
import com.bingoq.analyzer.network.BingoDataFetcher
import com.bingoq.analyzer.util.BingoCountdownManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class MainActivity : AppCompatActivity() {
    companion object {
        private const val ANALYSIS_WINDOW_LIMIT = 1000
        private const val DB_RETENTION_CAP = 1200
        private const val RECOMMENDED_MIN_SAMPLES = 200
        private const val BACKFILL_THRESHOLD = 1000
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var countdownManager: BingoCountdownManager
    private lateinit var weightManager: WeightManager
    private var startupFailed: Boolean = false
    private var currentWeights = WeightConfig.default()
    private var currentRecommendation: HitRateRecommendation? = null
    private var lastNetworkStatus: BingoDataFetcher.Status = BingoDataFetcher.Status.RED
    private var lastSourceSummary: String = "資料來源：尚未取得"
    private var isUiEditingUnlocked: Boolean = false
    private var analysisJob: Job? = null
    private var optimizationJob: Job? = null
    private var activeTasksCount: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            // Initialize lifecycle-dependent components before any other setup can fail.
            initCountdown()
            weightManager = WeightManager(this)
            currentWeights = weightManager.getSavedWeights()
            populateWeightsToUI(currentWeights)
            setUiEditingUnlocked(false)
            initListeners()
            loadDataAndAnalyze()
        } catch (error: Throwable) {
            startupFailed = true
            android.util.Log.e("BingoStartup", "MainActivity startup failed", error)
            showStartupDiagnostic(error)
        }
    }

    private fun showStartupDiagnostic(error: Throwable) {
        val details = android.util.Log.getStackTraceString(error)
        val scroll = android.widget.ScrollView(this)
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        panel.addView(TextView(this).apply {
            text = "APP 啟動診斷"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = "啟動時發生錯誤。請按「複製錯誤內容」，將文字貼回對話。"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 20)
        })
        panel.addView(android.widget.Button(this).apply {
            text = "複製錯誤內容"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Bingo startup error", details))
                Toast.makeText(this@MainActivity, "錯誤內容已複製，請貼回對話", Toast.LENGTH_LONG).show()
            }
        })
        panel.addView(TextView(this).apply {
            text = details
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 0)
        })
        scroll.addView(panel)
        setContentView(scroll)
    }
    @Synchronized
    private fun incrementTask() {
        activeTasksCount++
        binding.progressBar.visibility = View.VISIBLE
    }
    @Synchronized
    private fun decrementTask() {
        activeTasksCount = maxOf(0, activeTasksCount - 1)
        if (activeTasksCount == 0) {
            binding.progressBar.visibility = View.GONE
        }
    }
    private fun populateWeightsToUI(w: WeightConfig) {
        binding.etWeightA.setText(w.wA.toString())
        binding.etWeightB.setText(w.wB.toString())
        binding.etWeightC.setText(w.wC.toString())
        binding.etWeightD.setText(w.wD.toString())
        binding.etWeightE.setText(w.wE.toString())
        binding.etWeightF.setText(w.wF.toString())
        binding.etWeightG.setText(w.wG.toString())
        binding.etWeightH.setText(w.wH.toString())
        binding.etWeightI.setText(w.wI.toString())
        val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date(w.updatedAt))
        binding.tvWeightMeta.text = "版本: v" + w.version + " | 模式: " + w.mode + " | 總權重: " + w.totalWeight() + " | 存檔: " + dateStr
    }
    private fun setUiEditingUnlocked(unlocked: Boolean) {
        isUiEditingUnlocked = unlocked
        val inputs = listOf(
            binding.etWeightA, binding.etWeightB, binding.etWeightC,
            binding.etWeightD, binding.etWeightE, binding.etWeightF,
            binding.etWeightG, binding.etWeightH, binding.etWeightI
        )
        for (et in inputs) {
            et.isEnabled = unlocked
            et.alpha = if (unlocked) 1.0f else 0.6f
        }
        binding.btnSaveWeights.isEnabled = unlocked
        binding.btnToggleLock.text = if (unlocked) "🔓 鎖定編輯" else "🔒 點擊解鎖"
    }
    private fun readWeightsFromUI(): WeightConfig? {
        val inputs = listOf(
            binding.etWeightA, binding.etWeightB, binding.etWeightC,
            binding.etWeightD, binding.etWeightE, binding.etWeightF,
            binding.etWeightG, binding.etWeightH, binding.etWeightI
        )
        for (input in inputs) {
            val txt = input.text.toString().trim()
            if (txt.isEmpty()) {
                input.error = "不得為空"
                return null
            }
            val v = txt.toIntOrNull()
            if (v == null || v !in 0..1000) {
                input.error = "範圍限 0~1000"
                return null
            }
        }
        val config = WeightConfig(
            wA = binding.etWeightA.text.toString().trim().toInt(),
            wB = binding.etWeightB.text.toString().trim().toInt(),
            wC = binding.etWeightC.text.toString().trim().toInt(),
            wD = binding.etWeightD.text.toString().trim().toInt(),
            wE = binding.etWeightE.text.toString().trim().toInt(),
            wF = binding.etWeightF.text.toString().trim().toInt(),
            wG = binding.etWeightG.text.toString().trim().toInt(),
            wH = binding.etWeightH.text.toString().trim().toInt(),
            wI = binding.etWeightI.text.toString().trim().toInt(),
            mode = "CUSTOM"
        )
        if (config.totalWeight() <= 0) {
            Toast.makeText(this, "權重總和必須大於 0", Toast.LENGTH_SHORT).show()
            return null
        }
        return config
    }
    private fun applyAndSaveWeights(newConfig: WeightConfig, reason: String): Boolean {
        if (!newConfig.isValid()) {
            Toast.makeText(this, "權重驗證失敗：各因子需介於 0~1000 且總和大於 0", Toast.LENGTH_SHORT).show()
            return false
        }
        val configToSave = newConfig.copy(
            version = currentWeights.version + 1,
            updatedAt = System.currentTimeMillis()
        )
        val isSaved = weightManager.saveWeights(configToSave)
        if (isSaved) {
            currentWeights = configToSave
            populateWeightsToUI(configToSave)
            setUiEditingUnlocked(false)
            Toast.makeText(this, reason + " 套用並儲存成功！", Toast.LENGTH_SHORT).show()
            recomputeFromLocalCache()
            return true
        } else {
            Toast.makeText(this, reason + " 儲存失敗，請檢查儲存空間", Toast.LENGTH_SHORT).show()
            return false
        }
    }
    private fun initCountdown() {
        countdownManager = BingoCountdownManager(
            onTick = { remMin, remSec, drawLabel ->
                binding.tvCountdown.text = "距下期開獎 (" + drawLabel + ") : " + String.format(Locale.TAIWAN, "%02d 分 %02d 秒", remMin, remSec)
            },
            onDrawTimeReached = {
                Toast.makeText(this, "開獎時刻已到，自動同步最新開獎號碼...", Toast.LENGTH_SHORT).show()
                loadDataAndAnalyze()
            }
        )
    }
    override fun onResume() {
        super.onResume()
        if (!startupFailed && ::countdownManager.isInitialized) {
            countdownManager.start(lifecycleScope)
        }
    }
    override fun onPause() {
        if (::countdownManager.isInitialized) {
            countdownManager.stop()
        }
        super.onPause()
    }
    override fun onDestroy() {
        super.onDestroy()
        analysisJob?.cancel()
        optimizationJob?.cancel()
    }
    private fun initListeners() {
        binding.btnToggleLock.setOnClickListener {
            val nextState = !isUiEditingUnlocked
            setUiEditingUnlocked(nextState)
            val msg = if (nextState) "已解鎖，現在可修改九因子數字" else "已鎖定，防止滑動螢幕誤觸"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        binding.btnSaveWeights.setOnClickListener {
            val uiConfig = readWeightsFromUI()
            if (uiConfig != null) {
                applyAndSaveWeights(uiConfig, "手動自訂權重")
            }
        }
        binding.btnResetWeights.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢復預設權重")
                .setMessage("確定要還原九因子為系統預設值 (總計 100) 嗎？")
                .setPositiveButton("還原") { _, _ ->
                    applyAndSaveWeights(WeightConfig.default(), "恢復預設權重")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.btnExportWeights.setOnClickListener {
            val code = weightManager.exportWeights()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BingoWeights", code))
            Toast.makeText(this, "權重代碼已複製到剪貼簿！", Toast.LENGTH_SHORT).show()
        }
        binding.btnImportWeights.setOnClickListener {
            val et = EditText(this).apply { hint = "在此貼上 Base64 權重代碼" }
            AlertDialog.Builder(this)
                .setTitle("匯入權重代碼")
                .setView(et)
                .setPositiveButton("匯入") { _, _ ->
                    val text = et.text.toString().trim()
                    val res = weightManager.importWeights(text)
                    if (res.isSuccess) {
                        applyAndSaveWeights(res.getOrThrow(), "匯入權重")
                    } else {
                        Toast.makeText(this, "匯入失敗: " + (res.exceptionOrNull()?.message ?: "未知錯誤"), Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.btnOptimizeWeights.setOnClickListener {
            if (optimizationJob?.isActive == true) {
                Toast.makeText(this, "最佳化測算已在背景進行中，請稍候...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val baseConfigSnapshot = currentWeights.copy()
            incrementTask()
            Toast.makeText(this, "啟動歷史前向尋優測算...", Toast.LENGTH_SHORT).show()
            optimizationJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dao = BingoDatabase.getInstance(applicationContext).bingoDao()
                    val history = dao.getRecentDraws(ANALYSIS_WINDOW_LIMIT)
                    val optResult = weightManager.optimizeFromHistory(history, baseConfigSnapshot)
                    withContext(Dispatchers.Main) {
                        decrementTask()
                        if (optResult.totalTestPeriods <= 0) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("樣本數不足")
                                .setMessage("目前僅有 " + history.size + " 期真實開獎資料，至少需要 " + WeightManager.MIN_DRAWS_FOR_OPTIMIZATION + " 期才能執行嚴謹的前向尋優，請先連線同步最新資料。")
                                .setPositiveButton("了解", null)
                                .show()
                        } else {
                            showOptimizationDialog(optResult)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    withContext(Dispatchers.Main) {
                        decrementTask()
                        Toast.makeText(this@MainActivity, "最佳化測算失敗: " + (e.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.btnRefreshAll.setOnClickListener {
            loadDataAndAnalyze()
        }
        binding.btnExportAudit.setOnClickListener {
            val store = PredictionAuditStore(applicationContext)
            val journal = store.exportJsonLines()
            if (journal.isBlank()) {
                Toast.makeText(this, "目前尚無預測紀錄可匯出", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BingoPredictionAudit", journal))
                Toast.makeText(this, "已複製完整稽核紀錄；請貼到文字檔另存備份", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnCopyTop.setOnClickListener {
            currentRecommendation?.let { rec ->
                val text = buildString {
                    appendLine("【台彩賓果 9 因子命中推薦 (v3.3.7)】")
                    val cutoff = rec.lastDrawNo
                    val target = cutoff.toLongOrNull()?.plus(1L)?.toString() ?: "未知"
                    appendLine("預測目標期：" + target + " 期")
                    appendLine("參考截止期：" + cutoff + " 期")
                    appendLine("🔥 Top 5 核心: " + rec.top5.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    appendLine("⚡ Top 10 黃金: " + rec.top10.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    appendLine("📋 Top 20 對標: " + rec.top20.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    appendLine("近30期歷史回溯平均（非未來保證）: Top5[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top20Avg) + "/5.00]")
                    append("近" + rec.avgHitRate200.periods + "期歷史回溯平均: Top5[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate200.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate200.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate200.top20Avg) + "/5.00]；尚未證實預測優勢")
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BingoTopNumbers", text))
                Toast.makeText(this, "已複製號碼到剪貼簿！", Toast.LENGTH_SHORT).show()
            } ?: Toast.makeText(this, "運算中，請稍候...", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showOptimizationDialog(res: OptimizationResult) {
        val msg = buildString {
            appendLine("【回測期數】: 總測試 " + res.totalTestPeriods + " 期")
            appendLine("(訓練: " + res.trainPeriods + " 期 | 驗證: " + res.valPeriods + " 期 | 獨立 OOS: " + res.oosPeriods + " 期)")
            appendLine()
            appendLine("【原基準權重表現】:")
            appendLine("• Top 5 : " + String.format(Locale.TAIWAN, "%.2f", res.originalTop5Hit) + " 顆 (隨機基準 1.25)")
            appendLine("• Top 10: " + String.format(Locale.TAIWAN, "%.2f", res.originalTop10Hit) + " 顆 (隨機基準 2.50)")
            appendLine("• Top 20: " + String.format(Locale.TAIWAN, "%.2f", res.originalTop20Hit) + " 顆 (訓練+驗證集；隨機基準 5.00)")
            appendLine()
            appendLine("【最佳化權重表現】:")
            appendLine("• Top 5 : " + String.format(Locale.TAIWAN, "%.2f", res.bestTop5Hit) + " 顆")
            appendLine("• Top 10: " + String.format(Locale.TAIWAN, "%.2f", res.bestTop10Hit) + " 顆")
            appendLine("• Top 20: " + String.format(Locale.TAIWAN, "%.2f", res.bestTop20Hit) + " 顆 (訓練+驗證集；不是最終盲測)")
            appendLine()
            appendLine("【最新獨立 OOS 盲測比較】")
            appendLine("原權重 Top5/10/20: " + String.format(Locale.TAIWAN, "%.2f / %.2f / %.2f", res.originalOosTop5Hit, res.originalOosTop10Hit, res.originalOosTop20Hit))
            appendLine("新權重 Top5/10/20: " + String.format(Locale.TAIWAN, "%.2f / %.2f / %.2f", res.bestOosTop5Hit, res.bestOosTop10Hit, res.bestOosTop20Hit))
            appendLine("OOS期數: " + res.oosPeriods + "；隨機基準: 1.25 / 2.50 / 5.00")
            appendLine()
            append("推薦方案: A:" + res.bestWeights.wA + " B:" + res.bestWeights.wB + " C:" + res.bestWeights.wC + " D:" + res.bestWeights.wD + " E:" + res.bestWeights.wE + " F:" + res.bestWeights.wF + " G:" + res.bestWeights.wG + " H:" + res.bestWeights.wH + " I:" + res.bestWeights.wI)
        }
        AlertDialog.Builder(this)
            .setTitle("🤖 歷史前向尋優結果")
            .setMessage(msg)
            .setPositiveButton("套用最佳化權重") { _, _ ->
                if (currentWeights.version != res.baseConfigVersion || currentWeights.updatedAt != res.baseConfigUpdatedAt) {
                    AlertDialog.Builder(this)
                        .setTitle("權重版本衝突警告")
                        .setMessage("在最佳化測算期間，您已修改過權重設定 (目前為 v" + currentWeights.version + ")。\n若強制套用，將會覆蓋剛才的修改。是否仍要覆蓋套用？")
                        .setPositiveButton("確認覆蓋") { _, _ ->
                            applyAndSaveWeights(res.bestWeights, "最佳化權重")
                        }
                        .setNegativeButton("保留當前權重", null)
                        .show()
                } else {
                    applyAndSaveWeights(res.bestWeights, "最佳化權重")
                }
            }
            .setNegativeButton("保留目前權重", null)
            .show()
    }
    private fun recomputeFromLocalCache() {
        analysisJob?.cancel()
        val weightSnapshot = currentWeights.copy()
        incrementTask()
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = BingoDatabase.getInstance(applicationContext).bingoDao()
                val analysisDraws = dao.getRecentDraws(ANALYSIS_WINDOW_LIMIT)
                val totalStoredCount = dao.getDrawCount()
                // Only settle a saved prediction when this sync returned non-RED source status.
                if (lastNetworkStatus == BingoDataFetcher.Status.GREEN) {
                    PredictionAuditStore(applicationContext).settleAvailable(analysisDraws)
                }
                if (analysisDraws.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        decrementTask()
                        loadDataAndAnalyze()
                    }
                    return@launch
                }
                val scores = BingoAnalyzerEngine.computeScores(analysisDraws, weightSnapshot)
                val recommendation = RecommendationEngine.generate(scores, analysisDraws, weightSnapshot)
                withContext(Dispatchers.Main) {
                    decrementTask()
                    updateAnalysisUI(scores, recommendation, analysisDraws.size, totalStoredCount, lastNetworkStatus, null, lastSourceSummary)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    decrementTask()
                    Toast.makeText(this@MainActivity, "計算錯誤: " + (e.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun loadDataAndAnalyze() {
        analysisJob?.cancel()
        val weightSnapshot = currentWeights.copy()
        incrementTask()
        binding.tvDrawStatus.text = "正在同步真實開獎資料..."
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = BingoDatabase.getInstance(applicationContext).bingoDao()
                val fetcher = BingoDataFetcher()
                val existingCount = dao.getDrawCount()
                val needBackfill = existingCount < BACKFILL_THRESHOLD
                var networkStatus = BingoDataFetcher.Status.RED
                var syncErrorMsg: String? = null
                var sourceSummary = "資料來源：尚未取得"
                try {
                    val result = fetcher.fetchAndVerify(fullBackfill = needBackfill)
                    networkStatus = result.status
                    sourceSummary = result.sourceSummary
                    if (result.status != BingoDataFetcher.Status.RED && result.draws.isNotEmpty()) {
                        dao.insertDraws(result.draws)
                        dao.pruneOldDraws(DB_RETENTION_CAP)
                    } else if (result.status == BingoDataFetcher.Status.RED) {
                        syncErrorMsg = "無法連線或資料來源衝突 (RED 燈號，已拒絕寫入)"
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    networkStatus = BingoDataFetcher.Status.RED
                    syncErrorMsg = "網路同步異常，改用本機真實快取資料"
                    sourceSummary = "資料來源：連線失敗；目前僅使用本機快取，不代表最新資料已核實"
                }
                lastNetworkStatus = networkStatus
                lastSourceSummary = sourceSummary
                val analysisDraws = dao.getRecentDraws(ANALYSIS_WINDOW_LIMIT)
                val totalStoredCount = dao.getDrawCount()
                // Settle predictions immediately after a successful, cross-verified sync.
                // RED/YELLOW data must never be used to finalize an immutable audit result.
                if (networkStatus == BingoDataFetcher.Status.GREEN) {
                    val settledCount = PredictionAuditStore(applicationContext).settleAvailable(analysisDraws)
                    if (settledCount > 0) {
                        android.util.Log.i("BingoAudit", "Settled $settledCount prediction(s) after verified sync")
                    }
                }
                if (analysisDraws.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        decrementTask()
                        binding.tvDrawStatus.text = "無真實開獎資料，請保持網路連線後點擊刷新"
                        binding.tvSourceSummary.text = sourceSummary
                        binding.tvPredictionPeriod.text = "預測目標期：尚無可核實資料"
                        binding.tvSecurityBadge.text = "✕ 無資料"
                        binding.tvSecurityBadge.setTextColor(Color.parseColor("#FF4D4F"))
                        binding.tvTop5.text = "🔥 Top 5 核心: 等待同步真實開獎..."
                        binding.tvTop10.text = "⚡ Top 10 黃金: 等待同步真實開獎..."
                        binding.tvTop20.text = "📋 Top 20 對標: 等待同步真實開獎..."
                        Toast.makeText(this@MainActivity, syncErrorMsg ?: "請確認手機網路已開啟", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val scores = BingoAnalyzerEngine.computeScores(analysisDraws, weightSnapshot)
                val recommendation = RecommendationEngine.generate(scores, analysisDraws, weightSnapshot)
                withContext(Dispatchers.Main) {
                    decrementTask()
                    updateAnalysisUI(scores, recommendation, analysisDraws.size, totalStoredCount, networkStatus, syncErrorMsg, sourceSummary)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    decrementTask()
                    Toast.makeText(this@MainActivity, "分析失敗: " + (e.message ?: "未知錯誤"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun updateAnalysisUI(
        scores: Map<Int, Double>,
        recommendation: HitRateRecommendation,
        windowSize: Int,
        totalStoredCount: Int,
        networkStatus: BingoDataFetcher.Status,
        syncErrorMsg: String?,
        sourceSummary: String
    ) {
        currentRecommendation = recommendation
        // Lock exactly one immutable prediction for the next sequential period.
        // Refreshes or later weight edits cannot overwrite an existing target period.
        val cutoff = recommendation.lastDrawNo
        val target = cutoff.toLongOrNull()?.plus(1L)?.toString()
        binding.tvPredictionPeriod.text = if (target != null) {
            "預測目標期：" + target + " 期｜參考截止：" + cutoff + " 期"
        } else {
            "預測目標期：無法由最新期號推算"
        }
        binding.tvSourceSummary.text = sourceSummary
        if (networkStatus != BingoDataFetcher.Status.RED) {
            if (target != null) {
                PredictionAuditStore(applicationContext).recordPrediction(
                    targetPeriod = target,
                    cutoffPeriod = cutoff,
                    scores = scores,
                    top5 = recommendation.top5,
                    top10 = recommendation.top10,
                    top20 = recommendation.top20,
                    weights = currentWeights.copy(),
                    historyCount = windowSize,
                    sourceStatus = networkStatus.name + " | " + sourceSummary
                )
            }
        }
        updateAuditStatus()
        binding.tvSecurityBadge.text = when (networkStatus) {
            BingoDataFetcher.Status.GREEN -> "● " + sourceSummary.lineSequence().count { it.contains("：一致") } + " 源一致"
            BingoDataFetcher.Status.YELLOW -> "▲ 尚未達雙源核實"
            BingoDataFetcher.Status.RED -> "✕ 離線/快取"
        }
        binding.tvSecurityBadge.setTextColor(
            when (networkStatus) {
                BingoDataFetcher.Status.GREEN -> Color.parseColor("#52C41A")
                BingoDataFetcher.Status.YELLOW -> Color.parseColor("#FFA940")
                BingoDataFetcher.Status.RED -> Color.parseColor("#FF4D4F")
            }
        )
        val sampleNotice = if (windowSize < RECOMMENDED_MIN_SAMPLES) " [樣本偏少]" else ""
        binding.tvDrawStatus.text = "台彩賓果分析 (窗口: " + windowSize + "/1000期" + sampleNotice + " | 循環保留: " + totalStoredCount + "/1200期)"
        if (syncErrorMsg != null) {
            Toast.makeText(this, syncErrorMsg, Toast.LENGTH_SHORT).show()
        }
        val m30 = recommendation.avgHitRate30
        val m200 = recommendation.avgHitRate200
        // Hypergeometric null SD for selecting 20 from 80 when 20 are drawn.
        val nullSdTop20 = kotlin.math.sqrt(20.0 * 0.25 * 0.75 * 60.0 / 79.0)
        val z200 = if (m200.periods > 0) {
            (m200.top20Avg - 5.0) / (nullSdTop20 / kotlin.math.sqrt(m200.periods.toDouble()))
        } else 0.0
        val verdict = when {
            m200.periods < 100 -> "樣本不足（至少100期才作初步判讀）"
            kotlin.math.abs(z200) < 1.96 -> "未達常用統計門檻；無足夠證據證明優於隨機"
            z200 > 1.96 -> "高於隨機基準的訊號；仍須另一批獨立資料重複驗證"
            else -> "低於隨機基準；需持續觀察並檢查資料"
        }
        binding.tvHitRateStats.text =
            "近30期歷史回溯平均: Top5[" + String.format(Locale.TAIWAN, "%.2f", m30.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", m30.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", m30.top20Avg) + "/5.00]" +
            "\n近" + m200.periods + "期歷史回溯平均: Top5[" + String.format(Locale.TAIWAN, "%.2f", m200.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", m200.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", m200.top20Avg) + "/5.00]" +
            "\nTop20 隨機基準 Z值: " + String.format(Locale.TAIWAN, "%.2f", z200) + "｜" + verdict +
            "\n※歷史回溯不代表未來命中；Z值為簡化隨機基準比較，期數相依及多重測試可能使其偏樂觀。"
        val hitDesc = if (recommendation.lastDrawHitsTop20.isNotEmpty()) {
            recommendation.lastDrawHitsTop20.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) }
        } else "無"
        binding.tvLastHitDetail.text = "第 " + recommendation.lastDrawNo + " 期命中 Top20: " + recommendation.lastDrawHitsTop20.size + " 顆 [" + hitDesc + "]"
        binding.tvTop5.text = "🔥 Top 5 核心: " + recommendation.top5.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
        binding.tvTop10.text = "⚡ Top 10 黃金: " + recommendation.top10.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
        binding.tvTop20.text = "📋 Top 20 對標: " + recommendation.top20.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
        renderHeatmap(scores)
    }
    private fun updateAuditStatus() {
        val result = PredictionAuditStore(applicationContext).verify()
        binding.tvAuditStatus.text = if (result.valid) {
            "驗證：通過｜" + result.message + "\n預測紀錄：" + result.predictionCount +
                " 期｜開獎結算：" + result.settlementCount +
                " 期\n原始預測一經建立即鎖定；匯出 JSONL 可留存外部證據。"
        } else {
            "⚠️ 驗證失敗：" + result.message +
                "\n已停止新增紀錄，請勿採信目前命中統計。"
        }
        binding.tvAuditStatus.setTextColor(
            if (result.valid) Color.parseColor("#52C41A") else Color.parseColor("#FF4D4F")
        )
    }

    private fun renderHeatmap(scores: Map<Int, Double>) {
        binding.gridHeatmap.removeAllViews()
        val maxScore = scores.values.maxOrNull() ?: 1.0
        val minScore = scores.values.minOrNull() ?: 0.0
        for (ballNum in 1..80) {
            val score = scores[ballNum] ?: 0.0
            val norm = if (maxScore > minScore) ((score - minScore) / (maxScore - minScore)) * 100 else 50.0
            val tv = TextView(this).apply {
                text = String.format(Locale.TAIWAN, "%02d", ballNum)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 80
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(3, 3, 3, 3)
                }
                layoutParams = params
                setBackgroundResource(
                    when {
                        norm >= 75 -> R.drawable.bg_ball_red
                        norm >= 50 -> R.drawable.bg_ball_orange
                        norm >= 30 -> R.drawable.bg_ball_green
                        else -> R.drawable.bg_ball_blue
                    }
                )
                setOnClickListener {
                    Toast.makeText(
                        this@MainActivity,
                        "號碼: " + String.format(Locale.TAIWAN, "%02d", ballNum) + " | 得分: " + String.format(Locale.TAIWAN, "%.3f", score) + " | 歸一熱度: " + norm.toInt() + "%",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            binding.gridHeatmap.addView(tv)
        }
    }
}