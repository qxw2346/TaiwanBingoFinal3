package com.bingoq.analyzer.algorithm

import com.bingoq.analyzer.model.BingoDraw
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Leakage-safe walk-forward audit for 20-of-80 draws.
 *
 * Input history must be newest-first. Each target draw is scored using only
 * older rows (subList(t + 1, history.size)); the target row is never passed
 * to the scorer.
 */
object StatisticalAuditEngine {
    data class Metric(
        val k: Int,
        val periods: Int,
        val meanHits: Double,
        val randomExpected: Double,
        val lift: Double,
        val randomStdPerDraw: Double,
        val meanCiLow: Double,
        val meanCiHigh: Double,
        val lowHitRate: Double
    )

    data class Report(
        val evaluatedPeriods: Int,
        val cutoffPeriod: String,
        val targetNewestPeriod: String,
        val top5: Metric,
        val top10: Metric,
        val top20: Metric
    )

    fun validateHistory(historyNewestFirst: List<BingoDraw>) {
        require(historyNewestFirst.size >= 2) { "至少需要 2 期資料" }
        val ids = HashSet<String>()
        var previous: Long? = null
        historyNewestFirst.forEachIndexed { index, draw ->
            require(ids.add(draw.drawNo)) { "期數重複：${draw.drawNo}" }
            val period = draw.drawNo.toLongOrNull()
                ?: throw IllegalArgumentException("期數格式錯誤：${draw.drawNo}")
            if (previous != null) {
                require(previous!! > period) {
                    "資料必須依期數由新到舊排序（錯誤位置：${index + 1}）"
                }
            }
            previous = period
            require(draw.numbers.size == 20) { "第 ${draw.drawNo} 期不是 20 個號碼" }
            require(draw.numbers.all { it in 1..80 }) { "第 ${draw.drawNo} 期包含 1～80 以外的號碼" }
            require(draw.numbers.toSet().size == 20) { "第 ${draw.drawNo} 期有重複號碼" }
        }
    }

    fun evaluate(
        historyNewestFirst: List<BingoDraw>,
        requestedPeriods: Int = 200,
        scoreUsingPastOnly: (List<BingoDraw>) -> Map<Int, Double>
    ): Report {
        validateHistory(historyNewestFirst)
        require(requestedPeriods > 0) { "回測期數必須大於 0" }
        val periods = minOf(requestedPeriods, historyNewestFirst.size - 1)
        val hits5 = ArrayList<Int>(periods)
        val hits10 = ArrayList<Int>(periods)
        val hits20 = ArrayList<Int>(periods)

        for (t in 0 until periods) {
            val pastOnly = historyNewestFirst.subList(t + 1, historyNewestFirst.size)
            val scores = scoreUsingPastOnly(pastOnly)
            require(scores.keys == (1..80).toSet() && scores.values.all { it.isFinite() }) {
                "第 ${historyNewestFirst[t].drawNo} 期的模型分數必須完整包含 01～80，且不得有非有限值"
            }
            val ranked = scores.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
                .map { it.key }
            val actual = historyNewestFirst[t].numbers.toHashSet()
            hits5 += ranked.take(5).count { it in actual }
            hits10 += ranked.take(10).count { it in actual }
            hits20 += ranked.take(20).count { it in actual }
        }

        return Report(
            evaluatedPeriods = periods,
            cutoffPeriod = historyNewestFirst[periods].drawNo,
            targetNewestPeriod = historyNewestFirst.first().drawNo,
            top5 = summarize(5, hits5),
            top10 = summarize(10, hits10),
            top20 = summarize(20, hits20)
        )
    }

    fun summarize(k: Int, hits: List<Int>, bootstrapReplicates: Int = 2000): Metric {
        require(k in 1..80) { "K 必須介於 1～80" }
        require(hits.isNotEmpty()) { "命中資料不可為空" }
        require(hits.all { it in 0..minOf(k, 20) }) { "命中數超出合法範圍" }
        val mean = hits.average()
        val expected = k * 0.25
        val randomVariance = k * 0.25 * 0.75 * ((80.0 - k) / 79.0)
        val ci = movingBlockBootstrapMeanCi(hits, bootstrapReplicates)
        return Metric(
            k = k,
            periods = hits.size,
            meanHits = mean,
            randomExpected = expected,
            lift = mean - expected,
            randomStdPerDraw = sqrt(randomVariance),
            meanCiLow = ci.first,
            meanCiHigh = ci.second,
            lowHitRate = hits.count { it < 3 }.toDouble() / hits.size
        )
    }

    private fun movingBlockBootstrapMeanCi(
        values: List<Int>,
        replicates: Int
    ): Pair<Double, Double> {
        if (values.size == 1 || replicates <= 0) {
            val mean = values.average()
            return mean to mean
        }
        val n = values.size
        val blockLength = sqrt(n.toDouble()).toInt().coerceIn(1, n)
        val rng = Random(20261123L + n * 31L + values.sum())
        val means = DoubleArray(replicates)
        repeat(replicates) { r ->
            var total = 0.0
            var sampled = 0
            while (sampled < n) {
                val start = rng.nextInt(0, n - blockLength + 1)
                val take = minOf(blockLength, n - sampled)
                for (j in 0 until take) total += values[start + j]
                sampled += take
            }
            means[r] = total / n
        }
        means.sort()
        return percentile(means, 0.025) to percentile(means, 0.975)
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        val position = (sorted.size - 1) * p
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }
}
