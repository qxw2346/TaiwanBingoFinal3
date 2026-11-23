package com.bingoq.analyzer

import com.bingoq.analyzer.algorithm.StatisticalAuditEngine
import com.bingoq.analyzer.model.BingoDraw
import org.junit.Assert.*
import org.junit.Test

class StatisticalAuditEngineTest {
    private fun draws(count: Int): List<BingoDraw> =
        (count downTo 1).map { period ->
            val nums = (0 until 20).map { ((period * 7 + it * 13) % 80) + 1 }
            BingoDraw("115" + period.toString().padStart(6, '0'), "2026-11-23", nums)
        }

    @Test
    fun walkForwardNeverPassesTargetDrawIntoScorer() {
        val history = draws(25)
        val seenSizes = mutableListOf<Int>()
        val report = StatisticalAuditEngine.evaluate(history, 10) { past ->
            seenSizes += past.size
            (1..80).associateWith { 1.0 / it }
        }
        assertEquals(10, report.evaluatedPeriods)
        assertEquals((15 downTo 6).toList(), seenSizes)
        assertEquals(history[0].drawNo, report.targetNewestPeriod)
        assertEquals(history[10].drawNo, report.cutoffPeriod)
    }

    @Test
    fun randomBaselineUsesWithoutReplacementVariance() {
        val metric = StatisticalAuditEngine.summarize(20, List(100) { 5 })
        assertEquals(5.0, metric.randomExpected, 1e-12)
        assertEquals(0.0, metric.lift, 1e-12)
        assertEquals(kotlin.math.sqrt(20.0 * 0.25 * 0.75 * (60.0 / 79.0)),
            metric.randomStdPerDraw, 1e-12)
    }

    @Test
    fun rejectsDuplicateOrInvalidDraws() {
        val valid = draws(3)
        val duplicatePeriod = valid.toMutableList().apply { this[1] = this[0] }
        try {
            StatisticalAuditEngine.validateHistory(duplicatePeriod)
            fail("應拒絕重複期數")
        } catch (_: IllegalArgumentException) { }

        val duplicateNumbers = valid.toMutableList().apply {
            this[0] = this[0].copy(numbers = List(20) { 1 })
        }
        try {
            StatisticalAuditEngine.validateHistory(duplicateNumbers)
            fail("應拒絕重複號碼")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun stableTieBreakAndFiniteScoresProduceValidReport() {
        val report = StatisticalAuditEngine.evaluate(draws(20), 5) {
            (1..80).associateWith { 0.5 }
        }
        assertEquals(5, report.top5.k)
        assertEquals(10, report.top10.k)
        assertEquals(20, report.top20.k)
        assertTrue(report.top20.meanCiLow <= report.top20.meanCiHigh)
    }
}
