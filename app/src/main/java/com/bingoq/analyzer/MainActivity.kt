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
        private const val DB_RETENTION_CAP = 3000
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var countdownManager: BingoCountdownManager
    private lateinit var weightManager: WeightManager
    private var currentWeights = WeightConfig.default()
    private var currentRecommendation: HitRateRecommendation? = null
    private var isUiEditingUnlocked: Boolean = false
    private var analysisJob: Job? = null
    private var optimizationJob: Job? = null
    private var activeTasksCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        weightManager = WeightManager(this)
        currentWeights = weightManager.getSavedWeights()
        populateWeightsToUI(currentWeights)
        setUiEditingUnlocked(false)
        initCountdown()
        initListeners()
        loadDataAndAnalyze()
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
            loadDataAndAnalyze()
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
        countdownManager.start(lifecycleScope)
    }

    override fun onPause() {
        super.onPause()
        countdownManager.stop()
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
            binding.tvDrawStatus.text = "正在進行歷史 Walk-Forward 前向尋優..."
            Toast.makeText(this, "啟動歷史前向尋優測算...", Toast.LENGTH_SHORT).show()
            optimizationJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dao = BingoDatabase.getInstance(applicationContext).bingoDao()
                    val history = dao.getRecentDraws(ANALYSIS_WINDOW_LIMIT)
                    val optResult = weightManager.optimizeFromHistory(history, baseConfigSnapshot)
                    withContext(Dispatchers.Main) {
                        decrementTask()
                        showOptimizationDialog(optResult)
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
            Toast.makeText(this, "刷新按鈕有反應", Toast.LENGTH_SHORT).show()
            loadDataAndAnalyze()
        }

        binding.btnCopyTop.setOnClickListener {
            currentRecommendation?.let { rec ->
                val text = buildString {
                    appendLine("【台彩賓果 9 因子命中推薦 (v3.3.4)】")
                    appendLine("🔥 Top 5 核心: " + rec.top5.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    appendLine("⚡ Top 10 黃金: " + rec.top10.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    appendLine("📋 Top 20 對標: " + rec.top20.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) })
                    append("📈 近30期盲測平均命中: Top5[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", rec.avgHitRate30.top20Avg) + "/5.00]")
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
            appendLine("• Top 20: " + String.format(Locale.TAIWAN, "%.2f", res.originalTop20Hit) + " 顆 (隨機基準 5.00)")
            appendLine()
            appendLine("【最佳化權重表現】:")
            appendLine("• Top 5 : " + String.format(Locale.TAIWAN, "%.2f", res.bestTop5Hit) + " 顆")
            appendLine("• Top 10: " + String.format(Locale.TAIWAN, "%.2f", res.bestTop10Hit) + " 顆")
            appendLine("• Top 20: " + String.format(Locale.TAIWAN, "%.2f", res.bestTop20Hit) + " 顆 (OOS獨立盲測: " + String.format(Locale.TAIWAN, "%.2f", res.oosHitRate) + ")")
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

    private fun loadDataAndAnalyze() {
        analysisJob?.cancel()
        val weightSnapshot = currentWeights.copy()
        incrementTask()
        binding.tvDrawStatus.text = "正在同步開獎資料..."
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            val dao = BingoDatabase.getInstance(applicationContext).bingoDao()
            val fetcher = BingoDataFetcher()
            var networkStatus = BingoDataFetcher.Status.RED
            var networkErrorMessage: String? = null
            try {
                val result = fetcher.fetchAndVerify()
                networkStatus = result.status
                if (result.status != BingoDataFetcher.Status.RED && result.draws.isNotEmpty()) {
                    dao.insertDraws(result.draws)
                    dao.pruneOldDraws(DB_RETENTION_CAP)
                } else if (result.status == BingoDataFetcher.Status.RED) {
                    networkErrorMessage = "三來源資料核實異常 (RED 燈號，已拒絕寫入)"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                networkStatus = BingoDataFetcher.Status.RED
                networkErrorMessage = "網路連線失敗，已切換至本機離線模式"
            }
            val analysisDraws = dao.getRecentDraws(ANALYSIS_WINDOW_LIMIT)
            val totalStoredCount = dao.getDrawCount()
            if (analysisDraws.isEmpty()) {
                withContext(Dispatchers.Main) {
                    decrementTask()
                    binding.tvDrawStatus.text = "本機無開獎資料，請連網同步"
                    binding.tvSecurityBadge.text = "✕ 無資料"
                    binding.tvSecurityBadge.setTextColor(Color.parseColor("#FF4D4F"))
                    Toast.makeText(this@MainActivity, networkErrorMessage ?: "首次使用請先連網獲取資料", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val scores = BingoAnalyzerEngine.computeScores(analysisDraws, weightSnapshot)
            val recommendation = RecommendationEngine.generate(scores, analysisDraws, weightSnapshot)
            withContext(Dispatchers.Main) {
                decrementTask()
                currentRecommendation = recommendation
                binding.tvSecurityBadge.text = when (networkStatus) {
                    BingoDataFetcher.Status.GREEN -> "● 三源核實"
                    BingoDataFetcher.Status.YELLOW -> "▲ 雙源核實 (警示)"
                    BingoDataFetcher.Status.RED -> "✕ 單源/離線"
                }
                binding.tvSecurityBadge.setTextColor(
                    when (networkStatus) {
                        BingoDataFetcher.Status.GREEN -> Color.parseColor("#52C41A")
                        BingoDataFetcher.Status.YELLOW -> Color.parseColor("#FFA940")
                        BingoDataFetcher.Status.RED -> Color.parseColor("#FF4D4F")
                    }
                )
                binding.tvDrawStatus.text = "台彩賓果分析 (分析窗口: " + analysisDraws.size + "/1000期 | 本機庫存: " + totalStoredCount + "/3000期)"
                if (networkErrorMessage != null) {
                    Toast.makeText(this@MainActivity, networkErrorMessage, Toast.LENGTH_SHORT).show()
                }
                val m30 = recommendation.avgHitRate30
                val edge20 = ((m30.top20Avg - 5.00) / 5.00) * 100.0
                binding.tvHitRateStats.text = "近30期盲測: Top5[" + String.format(Locale.TAIWAN, "%.2f", m30.top5Avg) + "/1.25] Top10[" + String.format(Locale.TAIWAN, "%.2f", m30.top10Avg) + "/2.50] Top20[" + String.format(Locale.TAIWAN, "%.2f", m30.top20Avg) + "/5.00] (優勢: " + String.format(Locale.TAIWAN, "%+.1f%%", edge20) + ")"
                val hitDesc = if (recommendation.lastDrawHitsTop20.isNotEmpty()) {
                    recommendation.lastDrawHitsTop20.joinToString(", ") { String.format(Locale.TAIWAN, "%02d", it) }
                } else "無"
                binding.tvLastHitDetail.text = "第 " + recommendation.lastDrawNo + " 期命中 Top20: " + recommendation.lastDrawHitsTop20.size + " 顆 [" + hitDesc + "]"
                binding.tvTop5.text = "🔥 Top 5 核心: " + recommendation.top5.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
                binding.tvTop10.text = "⚡ Top 10 黃金: " + recommendation.top10.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
                binding.tvTop20.text = "📋 Top 20 對標: " + recommendation.top20.joinToString(" , ") { String.format(Locale.TAIWAN, "%02d", it) }
                renderHeatmap(scores)
            }
        }
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
