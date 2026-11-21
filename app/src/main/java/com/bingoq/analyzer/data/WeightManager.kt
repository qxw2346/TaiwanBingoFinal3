package com.bingoq.analyzer.data
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.bingoq.analyzer.algorithm.BingoAnalyzerEngine
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.OptimizationResult
import com.bingoq.analyzer.model.WeightConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
class WeightManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bingo_weights_pref_v333", Context.MODE_PRIVATE)
    companion object {
        private const val MAX_HISTORY_SNAPSHOTS = 5
        const val MIN_DRAWS_FOR_OPTIMIZATION = 100
    }
    fun getSavedWeights(): WeightConfig {
        val json = prefs.getString("saved_config_json", null)
        if (json != null) {
            val parsed = WeightConfig.fromJson(json)
            if (parsed != null && parsed.isValid()) return parsed
        }
        return WeightConfig.default()
    }
    fun saveWeights(config: WeightConfig): Boolean {
        if (!config.isValid()) return false
        val success = prefs.edit().apply {
            putString("saved_config_json", config.toJson())
            putLong("last_save_time", config.updatedAt)
        }.commit()
        if (success) {
            pushHistorySnapshot(config)
        }
        return success
    }
    private fun pushHistorySnapshot(config: WeightConfig) {
        try {
            val historyRaw = prefs.getString("weight_history_list", "[]") ?: "[]"
            val arr = JSONArray(historyRaw)
            arr.put(config.toJson())
            while (arr.length() > MAX_HISTORY_SNAPSHOTS) {
                arr.remove(0)
            }
            prefs.edit().putString("weight_history_list", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
    fun resetWeights(): WeightConfig {
        val def = WeightConfig.default()
        saveWeights(def)
        return def
    }
    fun exportWeights(): String {
        val json = getSavedWeights().toJson()
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
    fun importWeights(encoded: String): Result<WeightConfig> {
        return try {
            val json = String(Base64.decode(encoded.trim(), Base64.NO_WRAP), Charsets.UTF_8)
            val config = WeightConfig.fromJson(json) ?: return Result.failure(IllegalArgumentException("JSON 解析失敗"))
            if (!config.isValid()) return Result.failure(IllegalArgumentException("權重數值非法"))
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun optimizeFromHistory(
        history: List<BingoDraw>,
        baseConfig: WeightConfig
    ): OptimizationResult = withContext(Dispatchers.Default) {
        if (history.size < MIN_DRAWS_FOR_OPTIMIZATION) {
            return@withContext OptimizationResult(
                originalWeights = baseConfig,
                bestWeights = baseConfig,
                baseConfigVersion = baseConfig.version,
                baseConfigUpdatedAt = baseConfig.updatedAt,
                totalTestPeriods = 0,
                trainPeriods = 0,
                valPeriods = 0,
                oosPeriods = 0,
                originalTop5Hit = 0.0,
                originalTop10Hit = 0.0,
                originalTop20Hit = 0.0,
                bestTop5Hit = 0.0,
                bestTop10Hit = 0.0,
                bestTop20Hit = 0.0,
                oosHitRate = 0.0
            )
        }
        val totalTestPeriods = minOf(60, history.size - 40)
        // Reserve a larger, untouched recent holdout so weight search cannot tune against it.
        // With 60 test periods this yields 30 train / 15 validation / 15 OOS periods.
        val oosPeriods = minOf(15, maxOf(5, totalTestPeriods / 4))
        val valPeriods = minOf(15, (totalTestPeriods - oosPeriods) / 3)
        val trainPeriods = totalTestPeriods - oosPeriods - valPeriods
        val periodMatrices = Array(totalTestPeriods) { t ->
            BingoAnalyzerEngine.computeNormalizedFactorMatrix(history.subList(t + 1, history.size))
        }
        val periodActuals = Array(totalTestPeriods) { t ->
            history[t].numbers.toSet()
        }
        fun evaluateCached(weights: WeightConfig, startT: Int, endT: Int): Triple<Double, Double, Double> {
            var h5 = 0; var h10 = 0; var h20 = 0
            val count = endT - startT
            if (count <= 0) return Triple(1.25, 2.50, 5.00)
            for (t in startT until endT) {
                val scores = BingoAnalyzerEngine.scoreFromFactorMatrix(periodMatrices[t], weights)
                val sorted = scores.entries.sortedByDescending { it.value }.map { it.key }
                val actual = periodActuals[t]
                h5 += sorted.take(5).count { it in actual }
                h10 += sorted.take(10).count { it in actual }
                h20 += sorted.take(20).count { it in actual }
            }
            return Triple(h5.toDouble() / count, h10.toDouble() / count, h20.toDouble() / count)
        }
        val trainStart = oosPeriods + valPeriods
        val trainEnd = totalTestPeriods
        fun compositeScore(weights: WeightConfig): Double {
            val trainRes = evaluateCached(weights, trainStart, trainEnd)
            val valRes = evaluateCached(weights, oosPeriods, trainStart)
            val trainComp = trainRes.first * 4.0 + trainRes.second * 2.0 + trainRes.third
            val valComp = valRes.first * 4.0 + valRes.second * 2.0 + valRes.third
            return trainComp * 0.4 + valComp * 0.6
        }
        val seedCandidates = listOf(
            baseConfig,
            WeightConfig(wA = 10, wB = 22, wC = 14, wD = 6, wE = 10, wF = 8, wG = 12, wH = 10, wI = 8, mode = "OPTIMIZED"),
            WeightConfig(wA = 18, wB = 10, wC = 8, wD = 15, wE = 8, wF = 12, wG = 10, wH = 9, wI = 10, mode = "OPTIMIZED"),
            WeightConfig(wA = 8, wB = 12, wC = 8, wD = 6, wE = 12, wF = 8, wG = 20, wH = 18, wI = 8, mode = "OPTIMIZED"),
            WeightConfig(wA = 14, wB = 16, wC = 12, wD = 10, wE = 12, wF = 10, wG = 8, wH = 10, wI = 8, mode = "OPTIMIZED"),
            WeightConfig(wA = 12, wB = 12, wC = 12, wD = 10, wE = 10, wF = 12, wG = 11, wH = 11, wI = 10, mode = "OPTIMIZED")
        )
        var bestConfig = baseConfig
        var bestScore = compositeScore(baseConfig)
        for (cand in seedCandidates) {
            val s = compositeScore(cand)
            if (s > bestScore) {
                bestScore = s
                bestConfig = cand
            }
        }
        val refinedList = mutableListOf<WeightConfig>()
        for (delta in intArrayOf(-5, 6)) {
            refinedList.add(bestConfig.copy(wA = (bestConfig.wA + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wB = (bestConfig.wB + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wC = (bestConfig.wC + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wD = (bestConfig.wD + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wE = (bestConfig.wE + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wF = (bestConfig.wF + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wG = (bestConfig.wG + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wH = (bestConfig.wH + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
            refinedList.add(bestConfig.copy(wI = (bestConfig.wI + delta).coerceIn(0, 100), mode = "OPTIMIZED"))
        }
        for (cand in refinedList) {
            if (cand.isValid()) {
                val s = compositeScore(cand)
                if (s > bestScore) {
                    bestScore = s
                    bestConfig = cand
                }
            }
        }
        val origPerf = evaluateCached(baseConfig, 0, totalTestPeriods)
        val bestPerf = evaluateCached(bestConfig, 0, totalTestPeriods)
        val oosPerf = evaluateCached(bestConfig, 0, oosPeriods)
        OptimizationResult(
            originalWeights = baseConfig,
            bestWeights = bestConfig.copy(mode = "OPTIMIZED"),
            baseConfigVersion = baseConfig.version,
            baseConfigUpdatedAt = baseConfig.updatedAt,
            totalTestPeriods = totalTestPeriods,
            trainPeriods = trainPeriods,
            valPeriods = valPeriods,
            oosPeriods = oosPeriods,
            originalTop5Hit = origPerf.first,
            originalTop10Hit = origPerf.second,
            originalTop20Hit = origPerf.third,
            bestTop5Hit = bestPerf.first,
            bestTop10Hit = bestPerf.second,
            bestTop20Hit = bestPerf.third,
            oosHitRate = oosPerf.third
        )
    }
}