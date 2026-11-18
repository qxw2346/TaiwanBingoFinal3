package com.bingoq.analyzer.domain
import com.bingoq.analyzer.data.local.BingoDrawEntity
import com.bingoq.analyzer.data.local.VerificationBadge
import com.bingoq.analyzer.data.validation.DataValidator
import com.bingoq.analyzer.data.validation.RawDrawRecord
import com.bingoq.analyzer.data.validation.ValidationReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
data class ModelWeights(
    val wA: Double = 0.25,
    val wB: Double = 0.20,
    val wC: Double = 0.20,
    val wD: Double = 0.10,
    val wE: Double = 0.10,
    val wF: Double = 0.15
) {
    fun normalized(): ModelWeights {
        val sum = wA + wB + wC + wD + wE + wF
        if (sum <= 0.0) return ModelWeights()
        return ModelWeights(wA / sum, wB / sum, wC / sum, wD / sum, wE / sum, wF / sum)
    }
}
data class AnalysisParams(
    val historyWindow: Int = 100,
    val recentWindow: Int = 10,
    val halfLife: Double = 20.0
)
data class NumberScoreResult(
    val number: Int,
    val rank: Int,
    val totalScore: Double,
    val totalAppearances: Int,
    val recentAppearances: Int,
    val omissionPeriods: Int
)
data class BacktestSummary(
    val modelName: String,
    val weights: ModelWeights,
    val testedPeriodsCount: Int,
    val top1HitRate: Double,
    val top3AvgHits: Double,
    val top3AtLeastOneRate: Double,
    val top5AvgHits: Double,
    val top10AvgHits: Double,
    val beatsRandomBaseline: Boolean,
    val stabilityScore: Double
)
data class OptimizationResult(
    val bestWeights: ModelWeights,
    val bestSummary: BacktestSummary,
    val competitionTable: List<BacktestSummary>,
    val warningMessage: String?
)
data class RankedNumberUi(
    val number: Int,
    val frequency: Int,
    val missingCount: Int,
    val weightedScore: Double
)
data class LegacyAnalysisResult(
    val referencePeriod: String,
    val cutoffPeriod: String,
    val totalAnalyzedDraws: Int,
    val backtestWinRate: Double,
    val top3AvgHits: Double,
    val canPredictOfficially: Boolean,
    val badge: VerificationBadge,
    val rankedNumbers: List<RankedNumberUi>
)
object QuantEngine {
    fun analyze(
        drawsDesc: List<BingoDrawEntity>,
        weights: ModelWeights,
        params: AnalysisParams
    ): List<NumberScoreResult> {
        if (drawsDesc.isEmpty()) return emptyList()
        val windowDraws = drawsDesc.take(params.historyWindow.coerceAtLeast(1))
        val n = windowDraws.size
        val recentN = params.recentWindow.coerceIn(1, n)
        val w = weights.normalized()
        val drawSets = windowDraws.map { it.numberList().toSet() }
        val rawA = DoubleArray(80)
        val rawB = DoubleArray(80)
        val rawC = DoubleArray(80)
        val rawD = DoubleArray(80)
        val rawE = DoubleArray(80)
        val rawF = DoubleArray(80)
        val totalCounts = IntArray(80)
        val recentCounts = IntArray(80)
        val omissions = IntArray(80)
        val lambda = ln(2.0) / params.halfLife.coerceAtLeast(1.0)
        var decayWeightSum = 0.0
        for (t in 0 until n) decayWeightSum += exp(-lambda * t)
        val tailCounts = IntArray(10)
        for (set in drawSets) {
            for (num in set) tailCounts[num % 10]++
        }
        val tailTotal = tailCounts.sum().coerceAtLeast(1).toDouble()
        val segmentSize = (n / 4).coerceAtLeast(1)
        for (num in 1..80) {
            val idx = num - 1
            var countAll = 0
            var countRecent = 0
            var expWeighted = 0.0
            var firstHitAge = n
            for (t in 0 until n) {
                if (num in drawSets[t]) {
                    countAll++
                    if (t < recentN) countRecent++
                    expWeighted += exp(-lambda * t)
                    if (firstHitAge == n) firstHitAge = t
                }
            }
            totalCounts[idx] = countAll
            recentCounts[idx] = countRecent
            omissions[idx] = firstHitAge
            rawA[idx] = countAll.toDouble() / n
            rawB[idx] = (countRecent.toDouble() / recentN) - rawA[idx]
            rawC[idx] = expWeighted / decayWeightSum
            rawD[idx] = firstHitAge.toDouble()
            rawE[idx] = tailCounts[num % 10] / tailTotal
            val segRates = (0 until 4).map { seg ->
                val start = seg * segmentSize
                val end = if (seg == 3) n else ((seg + 1) * segmentSize).coerceAtMost(n)
                if (start >= end) {
                    rawA[idx]
                } else {
                    (start until end).count { num in drawSets[it] }.toDouble() / (end - start)
                }
            }
            val meanSeg = segRates.average()
            val stdSeg = sqrt(segRates.sumOf { (it - meanSeg).pow(2) } / segRates.size)
            rawF[idx] = 1.0 / (stdSeg + 0.01)
        }
        val normA = zScoreTo0To100(rawA)
        val normB = zScoreTo0To100(rawB)
        val normC = zScoreTo0To100(rawC)
        val normD = zScoreTo0To100(rawD)
        val normE = zScoreTo0To100(rawE)
        val normF = zScoreTo0To100(rawF)
        val unsorted = (1..80).map { num ->
            val idx = num - 1
            val score = (
                w.wA * normA[idx] + w.wB * normB[idx] + w.wC * normC[idx] +
                    w.wD * normD[idx] + w.wE * normE[idx] + w.wF * normF[idx]
                ).coerceIn(0.0, 100.0)
            NumberScoreResult(
                number = num,
                rank = 0,
                totalScore = score,
                totalAppearances = totalCounts[idx],
                recentAppearances = recentCounts[idx],
                omissionPeriods = omissions[idx]
            )
        }
        return unsorted.sortedWith(
            compareByDescending<NumberScoreResult> { it.totalScore }.thenBy { it.number }
        ).mapIndexed { index, item -> item.copy(rank = index + 1) }
    }
    private fun zScoreTo0To100(values: DoubleArray): DoubleArray {
        val mean = values.average()
        val std = sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
        if (std < 1e-12) return DoubleArray(values.size) { 50.0 }
        return DoubleArray(values.size) { i ->
            val z = ((values[i] - mean) / std).coerceIn(-3.0, 3.0)
            ((z + 3.0) / 6.0) * 100.0
        }
    }
}
object BacktestOptimizer {
    private const val RANDOM_TOP3_EXPECTED_HITS = 0.75
    fun runBacktest(
        modelName: String,
        allDrawsDesc: List<BingoDrawEntity>,
        backtestPeriods: Int,
        weights: ModelWeights,
        params: AnalysisParams
    ): BacktestSummary {
        val usableCount = minOf(backtestPeriods, allDrawsDesc.size - 10).coerceAtLeast(0)
        if (usableCount == 0) {
            return BacktestSummary(modelName, weights, 0, 0.0, 0.0, 0.0, 0.0, 0.0, false, 0.0)
        }
        var top1Hits = 0
        var top3Sum = 0
        var top3AtLeastOne = 0
        var top5Sum = 0
        var top10Sum = 0
        val perPeriodTop3Hits = mutableListOf<Int>()
        for (i in 0 until usableCount) {
            val targetDraw = allDrawsDesc[i]
            val historyBeforeTarget = allDrawsDesc.subList(i + 1, allDrawsDesc.size)
            val ranked = QuantEngine.analyze(historyBeforeTarget, weights, params)
            val actual = targetDraw.numberList().toSet()
            if (ranked.first().number in actual) top1Hits++
            val h3 = ranked.take(3).count { it.number in actual }
            val h5 = ranked.take(5).count { it.number in actual }
            val h10 = ranked.take(10).count { it.number in actual }
            top3Sum += h3
            if (h3 >= 1) top3AtLeastOne++
            top5Sum += h5
            top10Sum += h10
            perPeriodTop3Hits.add(h3)
        }
        val n = usableCount.toDouble()
        val top3Avg = top3Sum / n
        val half = (perPeriodTop3Hits.size / 2).coerceAtLeast(1)
        val firstHalf = perPeriodTop3Hits.take(half).average()
        val secondHalf = perPeriodTop3Hits.drop(half).let {
            if (it.isEmpty()) firstHalf else it.average()
        }
        val stability = 1.0 - abs(firstHalf - secondHalf).coerceAtMost(1.0)
        return BacktestSummary(
            modelName = modelName,
            weights = weights.normalized(),
            testedPeriodsCount = usableCount,
            top1HitRate = top1Hits / n,
            top3AvgHits = top3Avg,
            top3AtLeastOneRate = top3AtLeastOne / n,
            top5AvgHits = top5Sum / n,
            top10AvgHits = top10Sum / n,
            beatsRandomBaseline = top3Avg > RANDOM_TOP3_EXPECTED_HITS,
            stabilityScore = stability
        )
    }
    fun optimizeAndCompete(
        allDrawsDesc: List<BingoDrawEntity>,
        backtestPeriods: Int,
        userCurrentWeights: ModelWeights,
        params: AnalysisParams,
        searchIterations: Int = 80
    ): OptimizationResult {
        val rng = Random(System.currentTimeMillis())
        val total = minOf(backtestPeriods, allDrawsDesc.size - 10).coerceAtLeast(6)
        val oosCount = (total / 3).coerceAtLeast(2)
        val isDraws = allDrawsDesc.drop(oosCount)
        var bestCandidate = userCurrentWeights.normalized()
        var bestScore = -1.0
        val candidates = mutableListOf(
            ModelWeights(1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6),
            userCurrentWeights.normalized()
        )
        repeat(searchIterations) {
            val g = DoubleArray(6) { -ln(rng.nextDouble(0.05, 1.0)) }
            candidates.add(ModelWeights(g[0], g[1], g[2], g[3], g[4], g[5]).normalized())
        }
        for (w in candidates) {
            val s = runBacktest("Candidate", isDraws, total - oosCount, w, params)
            val composite = s.top3AvgHits * 0.50 + s.top3AtLeastOneRate * 0.25 +
                s.top1HitRate * 0.15 + s.stabilityScore * 0.10
            if (composite > bestScore) {
                bestScore = composite
                bestCandidate = w
            }
        }
        val modelA = runBacktest("模型A 純歷史頻率", allDrawsDesc, total, ModelWeights(1.0, 0.0, 0.0, 0.0, 0.0, 0.0), params)
        val modelB = runBacktest("模型B 近期動能", allDrawsDesc, total, ModelWeights(0.0, 1.0, 0.0, 0.0, 0.0, 0.0), params)
        val modelC = runBacktest("模型C 指數衰減", allDrawsDesc, total, ModelWeights(0.0, 0.0, 1.0, 0.0, 0.0, 0.0), params)
        val modelD = runBacktest("模型D 預設多因子", allDrawsDesc, total, userCurrentWeights.normalized(), params)
        val modelE = runBacktest("模型E 最佳化權重", allDrawsDesc, total, bestCandidate, params)
        val competition = listOf(modelA, modelB, modelC, modelD, modelE)
            .sortedByDescending { it.top3AvgHits }
        val oosCheck = runBacktest("OOS樣本外", allDrawsDesc, oosCount, bestCandidate, params)
        val warning = if (!modelE.beatsRandomBaseline || !oosCheck.beatsRandomBaseline) {
            "提醒：最佳權重 Top3 平均命中 (${String.format(Locale.US, "%.2f", modelE.top3AvgHits)} 顆) 未顯著優於隨機基準 (0.75 顆)，歷史分數僅供統計參考，不代表提高中獎率。"
        } else null
        return OptimizationResult(bestCandidate, modelE, competition, warning)
    }
}
class BingoAnalyzerEngine {
    fun analyze(
        rawDraws: List<RawDrawRecord>,
        weights: ModelWeights = ModelWeights()
    ): LegacyAnalysisResult {
        if (rawDraws.isEmpty()) {
            return LegacyAnalysisResult("--", "--", 0, 0.0, 0.0, false, VerificationBadge.RED, emptyList())
        }
        val (validEntities, report) = DataValidator.validateAndMerge(rawDraws)
        return analyzeEntities(validEntities, report, weights)
    }
    fun analyzeEntities(
        validEntities: List<BingoDrawEntity>,
        report: ValidationReport?,
        weights: ModelWeights = ModelWeights()
    ): LegacyAnalysisResult {
        if (validEntities.isEmpty()) {
            return LegacyAnalysisResult("驗證失敗", "無合法資料", 0, 0.0, 0.0, false, VerificationBadge.RED, emptyList())
        }
        val params = AnalysisParams(
            historyWindow = validEntities.size.coerceAtMost(100),
            recentWindow = 10,
            halfLife = 20.0
        )
        val scores = QuantEngine.analyze(validEntities, weights, params)
        val testablePeriods = (validEntities.size - 10).coerceIn(0, 50)
        val backtest = BacktestOptimizer.runBacktest(
            modelName = "多因素權重模型",
            allDrawsDesc = validEntities,
            backtestPeriods = testablePeriods,
            weights = weights,
            params = params
        )
        val latestPeriod = report?.latestPeriod ?: validEntities.first().period
        val oldestPeriod = validEntities.last().period
        val badge = report?.badge ?: VerificationBadge.YELLOW
        return LegacyAnalysisResult(
            referencePeriod = "${latestPeriod + 1} (來源驗證:${badge.name})",
            cutoffPeriod = "$oldestPeriod ~ $latestPeriod",
            totalAnalyzedDraws = validEntities.size,
            backtestWinRate = backtest.top3AtLeastOneRate * 100.0,
            top3AvgHits = backtest.top3AvgHits,
            canPredictOfficially = badge != VerificationBadge.RED,
            badge = badge,
            rankedNumbers = scores.map {
                RankedNumberUi(
                    number = it.number,
                    frequency = it.totalAppearances,
                    missingCount = it.omissionPeriods,
                    weightedScore = it.totalScore
                )
            }
        )
    }
    fun optimize(
        validEntities: List<BingoDrawEntity>,
        currentWeights: ModelWeights
    ): OptimizationResult {
        val params = AnalysisParams(
            historyWindow = validEntities.size.coerceAtMost(100),
            recentWindow = 10,
            halfLife = 20.0
        )
        val testablePeriods = (validEntities.size - 10).coerceIn(6, 50)
        return BacktestOptimizer.optimizeAndCompete(validEntities, testablePeriods, currentWeights, params)
    }
}