package com.bingoq.analyzer.model
data class HitRateRecommendation(
    val top5: List<Int>,
    val top10: List<Int>,
    val top20: List<Int>,
    val lastDrawNo: String,
    val lastDrawActual: List<Int>,
    val lastDrawHitsTop5: List<Int>,
    val lastDrawHitsTop10: List<Int>,
    val lastDrawHitsTop20: List<Int>,
    val avgHitRate10: HitMetrics,
    val avgHitRate30: HitMetrics,
    val avgHitRate50: HitMetrics
) {
    data class HitMetrics(
        val top5Avg: Double,
        val top10Avg: Double,
        val top20Avg: Double,
        val periods: Int
    )
}