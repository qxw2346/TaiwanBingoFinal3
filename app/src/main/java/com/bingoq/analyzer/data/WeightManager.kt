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
        const val MIN_DRAWS_FOR_OPTIMIZATION = 300
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
        // Chronological split: newest OOS periods are never used to select weights.
        val totalTestPeriods = minOf(200, history.size - 40)
        val oosPeriods = minOf(50, maxOf(15, totalTestPeriods / 4))
        val valPeriods = minOf(50, (totalTestPeriods - oosPeriods) / 3)
        val trainPeriods = totalTestPeriods - oosPeriods - valPeriods
        val periodMatrices = Array(totalTestPeriods) { t ->
            BingoAnalyzerEngine.computeNormalizedFactorMatrix(history.subList(t + 1, history.size))
        }
        val periodActuals = Array(totalTestPeriods) { t -> history[t].numbers.toSet() }

        fun evaluateCached(weights: WeightConfig, startT: Int, endT: Int): Triple<Double, Double, Double> {
            var h5 = 0
            var h10 = 0
            var h20 = 0
            val count = endT - startT
            if (count <= 0) return Triple(1.25, 2.50, 5.00)
            for (t in startT until endT) {
                val scores = BingoAnalyzerEngine.scoreFromFactorMatrix(periodMatrices[t], weights)
                val cutoff = history[t + 1].drawNo
                val ranked = scores.entries.sortedWith(
                    compareByDescending<Map.Entry<Int, Double>> { it.value }
                        .thenBy { BingoAnalyzerEngine.tieBreakKey(cutoff, it.key) }
                ).map { it.key }
                val actual = periodActuals[t]
                h5 += ranked.take(5).count { it in actual }
                h10 += ranked.take(10).count { it in actual }
                h20 += ranked.take(20).count { it in actual }
            }
            return Triple(h5.toDouble() / count, h10.toDouble() / count, h20.toDouble() / count)
        }

        // Top20 is the primary objective; Top10 and Top5 remain secondary objectives.
        fun metric(res: Triple<Double, Double, Double>): Double =
            res.third + 0.50 * res.second + 0.25 * res.first

        val trainStart = oosPeriods + valPeriods
        val trainEnd = totalTestPeriods
        val baseNorm = baseConfig.normalizedWeights()
        fun regularization(weights: WeightConfig): Double {
            val candidate = weights.normalizedWeights()
            var l1 = 0.0
            for (i in 0 until 9) l1 += kotlin.math.abs(candidate[i] - baseNorm[i])
            return 0.05 * l1
        }
        fun objective(weights: WeightConfig): Double {
            val train = evaluateCached(weights, trainStart, trainEnd)
            val validation = evaluateCached(weights, oosPeriods, trainStart)
            // Favor validation performance, retain some training signal, and penalize
            // candidates whose Top20 result changes sharply between the two periods.
            return 0.35 * metric(train) + 0.65 * metric(validation) -
                0.15 * kotlin.math.abs(train.third - validation.third) -
                regularization(weights)
        }

        fun fromRaw(values: DoubleArray): WeightConfig {
            val sum = values.sum().coerceAtLeast(1e-12)
            val exact = DoubleArray(9) { values[it] / sum * 100.0 }
            val ints = IntArray(9) { exact[it].toInt() }
            var remainder = 100 - ints.sum()
            val order = (0 until 9).sortedByDescending { exact[it] - ints[it] }
            var cursor = 0
            while (remainder > 0) {
                ints[order[cursor % 9]]++
                remainder--
                cursor++
            }
            return WeightConfig(
                wA = ints[0], wB = ints[1], wC = ints[2], wD = ints[3],
                wE = ints[4], wF = ints[5], wG = ints[6], wH = ints[7], wI = ints[8],
                mode = "OPTIMIZED"
            )
        }

        val candidates = LinkedHashMap<String, WeightConfig>()
        fun addCandidate(candidate: WeightConfig) {
            if (!candidate.isValid()) return
            val key = listOf(candidate.wA, candidate.wB, candidate.wC, candidate.wD,
                candidate.wE, candidate.wF, candidate.wG, candidate.wH, candidate.wI).joinToString(":")
            candidates[key] = candidate.copy(mode = "OPTIMIZED")
        }
        addCandidate(baseConfig)
        addCandidate(WeightConfig(11, 11, 11, 11, 11, 11, 11, 11, 12, mode = "OPTIMIZED"))
        addCandidate(WeightConfig(10, 22, 14, 6, 10, 8, 12, 10, 8, mode = "OPTIMIZED"))
        addCandidate(WeightConfig(18, 10, 8, 15, 8, 12, 10, 9, 10, mode = "OPTIMIZED"))
        addCandidate(WeightConfig(8, 12, 8, 6, 12, 8, 20, 18, 8, mode = "OPTIMIZED"))
        for (factor in 0 until 9) {
            val values = DoubleArray(9) { if (it == factor) 0.0 else baseNorm[it] }
            addCandidate(fromRaw(values))
        }

        // Deterministic Dirichlet-style search explores the full 9-factor simplex.
        // The seed is tied to the latest verified draw so identical data gives identical weights.
        val seed = history.first().drawNo.hashCode().toLong() xor 0x6A09E667F3BCC909L
        val rng = kotlin.random.Random(seed.toInt())
        repeat(512) {
            val raw = DoubleArray(9) {
                -kotlin.math.ln(rng.nextDouble().coerceAtLeast(1e-12))
            }
            addCandidate(fromRaw(raw))
        }

        var bestConfig = baseConfig
        var bestScore = objective(baseConfig)
        for (candidate in candidates.values) {
            val score = objective(candidate)
            if (score > bestScore + 1e-9) {
                bestScore = score
                bestConfig = candidate
            }
        }

        // Two small coordinate-refinement rounds around the best broad-search candidate.
        repeat(2) { round ->
            val delta = if (round == 0) 4 else 2
            val local = mutableListOf<WeightConfig>()
            val current = listOf(bestConfig.wA, bestConfig.wB, bestConfig.wC,
                bestConfig.wD, bestConfig.wE, bestConfig.wF, bestConfig.wG,
                bestConfig.wH, bestConfig.wI)
            for (i in 0 until 9) {
                for (change in listOf(-delta, delta)) {
                    val values = current.map { it.toDouble() }.toDoubleArray()
                    values[i] = (values[i] + change).coerceIn(0.0, 100.0)
                    local.add(fromRaw(values))
                }
            }
            for (candidate in local) {
                val score = objective(candidate)
                if (score > bestScore + 1e-9) {
                    bestScore = score
                    bestConfig = candidate
                }
            }
        }

        // OOS is evaluated only after the candidate has been selected.
        val origPerf = evaluateCached(baseConfig, oosPeriods, totalTestPeriods)
        val bestPerf = evaluateCached(bestConfig, oosPeriods, totalTestPeriods)
        val originalOosPerf = evaluateCached(baseConfig, 0, oosPeriods)
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
            oosHitRate = oosPerf.third,
            originalOosTop5Hit = originalOosPerf.first,
            originalOosTop10Hit = originalOosPerf.second,
            originalOosTop20Hit = originalOosPerf.third,
            bestOosTop5Hit = oosPerf.first,
            bestOosTop10Hit = oosPerf.second,
            bestOosTop20Hit = oosPerf.third
        )
    }
}