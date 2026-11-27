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
        if (history.size >= 2) StatisticalAuditEngine.validateHistory(history)
        val currentCutoff = history.firstOrNull()?.drawNo ?: "--"
        val sorted = scores.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Double>> { it.value }
                .thenBy { BingoAnalyzerEngine.tieBreakKey(currentCutoff, it.key) }
        ).map { it.key }
        val pool = if (sorted.size >= 20) sorted else (1..80).toList()
        // Preserve composite-score rank order. Sorting each selected set numerically
        // hides the ranking and makes the displayed first number look arbitrary.
        val top5 = pool.take(5)
        val top10 = pool.take(10)
        val top20 = pool.take(20)
        var lastNo = "--"
        var lastActual = emptyList<Int>()
        // These hit lists must come from a prediction made BEFORE the target draw.
        // They are populated by the walk-forward loop below, never by today's ranking.
        var hit5 = emptyList<Int>()
        var hit10 = emptyList<Int>()
        var hit20 = emptyList<Int>()
        if (history.isNotEmpty()) {
            val latest = history.first()
            lastNo = latest.drawNo
            lastActual = latest.numbers.sorted()
        }
        val maxPeriods = minOf(200, history.size - 1)
        var sum5 = 0
        var sum10 = 0
        var sum20 = 0
        val auditHits5 = mutableListOf<Int>()
        val auditHits10 = mutableListOf<Int>()
        val auditHits20 = mutableListOf<Int>()
        var metrics10 = HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
        var metrics30 = HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
        var metrics50 = HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
        var metrics100 = HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
        var metrics200 = HitRateRecommendation.HitMetrics(1.25, 2.50, 5.00, 0)
        if (maxPeriods > 0) {
            for (t in 0 until maxPeriods) {
                val pastSlice = history.subList(t + 1, history.size)
                val periodScores = BingoAnalyzerEngine.computeScores(pastSlice, weights)
                val cutoffPeriod = pastSlice.firstOrNull()?.drawNo ?: "--"
                val periodSorted = periodScores.entries.sortedWith(
                    compareByDescending<Map.Entry<Int, Double>> { it.value }
                        .thenBy { BingoAnalyzerEngine.tieBreakKey(cutoffPeriod, it.key) }
                ).map { it.key }
                val actual = history[t].numbers.toSet()
                val periodHits5 = periodSorted.take(5).filter { it in actual }
                val periodHits10 = periodSorted.take(10).filter { it in actual }
                val periodHits20 = periodSorted.take(20).filter { it in actual }
                sum5 += periodHits5.size
                sum10 += periodHits10.size
                sum20 += periodHits20.size
                auditHits5 += periodHits5.size
                auditHits10 += periodHits10.size
                auditHits20 += periodHits20.size
                if (t == 0) {
                    hit5 = periodHits5
                    hit10 = periodHits10
                    hit20 = periodHits20
                }
                val count = t + 1
                val snapshot = HitRateRecommendation.HitMetrics(
                    top5Avg = sum5.toDouble() / count,
                    top10Avg = sum10.toDouble() / count,
                    top20Avg = sum20.toDouble() / count,
                    periods = count
                )
                if (count <= 10) metrics10 = snapshot
                if (count <= 30) metrics30 = snapshot
                if (count <= 50) metrics50 = snapshot
                if (count <= 100) metrics100 = snapshot
                metrics200 = snapshot
            }
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
            avgHitRate10 = metrics10,
            avgHitRate30 = metrics30,
            avgHitRate50 = metrics50,
            avgHitRate100 = metrics100,
            avgHitRate200 = metrics200,
            auditTop5 = auditHits5.takeIf { it.isNotEmpty() }?.let { StatisticalAuditEngine.summarize(5, it) },
            auditTop10 = auditHits10.takeIf { it.isNotEmpty() }?.let { StatisticalAuditEngine.summarize(10, it) },
            auditTop20 = auditHits20.takeIf { it.isNotEmpty() }?.let { StatisticalAuditEngine.summarize(20, it) }
        )
    }
}
