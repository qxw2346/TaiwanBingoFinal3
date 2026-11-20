package com.bingoq.analyzer.algorithm
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.HitRateRecommendation
import com.bingoq.analyzer.model.WeightConfig
object RecommendationEngine {
    fun generate(
        scores: Map<Int, Double>,
        history: List<BingoDraw>,
        weights: WeightConfig
    ): HitRateRecommendation {
        val sorted = scores.entries.sortedByDescending { it.value }.map { it.key }
        val pool = if (sorted.size >= 20) sorted else (1..80).toList()
        val top5 = pool.take(5).sorted()
        val top10 = pool.take(10).sorted()
        val top20 = pool.take(20).sorted()
        var lastNo = "--"
        var lastActual = emptyList<Int>()
        var hit5 = emptyList<Int>()
        var hit10 = emptyList<Int>()
        var hit20 = emptyList<Int>()
        if (history.isNotEmpty()) {
            val latest = history.first()
            lastNo = latest.drawNo
            lastActual = latest.numbers.sorted()
            val actualSet = latest.numbers.toSet()
            hit5 = top5.filter { it in actualSet }
            hit10 = top10.filter { it in actualSet }
            hit20 = top20.filter { it in actualSet }
        }
        fun calculateOOS(windowSize: Int): HitRateRecommendation.HitMetrics {
            val validPeriods = minOf(windowSize, history.size - 1)
            if (validPeriods <= 0) return HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
            var sum5 = 0; var sum10 = 0; var sum20 = 0
            for (t in 0 until validPeriods) {
                val pastSlice = history.subList(t + 1, history.size)
                val periodScores = BingoAnalyzerEngine.computeScores(pastSlice, weights)
                val periodSorted = periodScores.entries.sortedByDescending { it.value }.map { it.key }
                val actual = history[t].numbers.toSet()
                sum5 += periodSorted.take(5).count { it in actual }
                sum10 += periodSorted.take(10).count { it in actual }
                sum20 += periodSorted.take(20).count { it in actual }
            }
            return HitRateRecommendation.HitMetrics(
                top5Avg = sum5.toDouble() / validPeriods,
                top10Avg = sum10.toDouble() / validPeriods,
                top20Avg = sum20.toDouble() / validPeriods,
                periods = validPeriods
            )
        }
        return HitRateRecommendation(
            top5 = top5,
            top10 = top10,
            top20 = top20,
            lastDrawNo = lastNo,
            lastDrawActual = lastActual,
            lastDrawHitsTop5 = hit5,
            lastDrawHitsTop10 = hit10,
            lastDrawHitsTop20 = hit20,
            avgHitRate10 = calculateOOS(10),
            avgHitRate30 = calculateOOS(30),
            avgHitRate50 = calculateOOS(50)
        )
    }
}