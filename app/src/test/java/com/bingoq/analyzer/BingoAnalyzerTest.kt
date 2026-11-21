package com.bingoq.analyzer
import com.bingoq.analyzer.algorithm.BingoAnalyzerEngine
import com.bingoq.analyzer.algorithm.RecommendationEngine
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.WeightConfig
import org.junit.Assert.*
import org.junit.Test
class BingoAnalyzerTest {
    private fun generateMockDraws(count: Int): List<BingoDraw> {
        val list = mutableListOf<BingoDraw>()
        for (i in count downTo 1) {
            val nums = (1..80).shuffled().take(20).sorted()
            list.add(BingoDraw(drawNo = String.format("113%05d", i), drawTime = "2026-03-29", numbers = nums))
        }
        return list
    }
    @Test
    fun testWeightConfigValidationAndVersion() {
        val cfg = WeightConfig(wA = 10, wB = 10, wC = 10, wD = 10, wE = 10, wF = 10, wG = 10, wH = 10, wI = 10, version = 1)
        assertTrue(cfg.isValid())
        assertEquals(90, cfg.totalWeight())
        val invalidCfg = WeightConfig(wA = -5, wB = 10, wC = 10, wD = 10, wE = 10, wF = 10, wG = 10, wH = 10, wI = 10)
        assertFalse(invalidCfg.isValid())
        val zeroCfg = WeightConfig(0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(zeroCfg.isValid())
    }
    @Test
    fun testWalkForwardTemporalIsolation() {
        val mockDraws = generateMockDraws(40)
        val weights = WeightConfig.default()
        assertTrue(mockDraws[0].drawNo > mockDraws[1].drawNo)
        val rec = RecommendationEngine.generate(
            BingoAnalyzerEngine.computeScores(mockDraws, weights),
            mockDraws,
            weights
        )
        assertEquals(5, rec.top5.size)
        assertEquals(10, rec.top10.size)
        assertEquals(20, rec.top20.size)
        assertTrue(rec.avgHitRate30.periods in 1..30)
    }
    @Test
    fun testEqualScoresUseStableAscendingNumberTieBreak() {
        val tiedScores = (1..80).associateWith { 0.5 }
        val recommendation = RecommendationEngine.generate(
            scores = tiedScores,
            history = emptyList(),
            weights = WeightConfig.default()
        )
        assertEquals((1..5).toList(), recommendation.top5)
        assertEquals((1..10).toList(), recommendation.top10)
        assertEquals((1..20).toList(), recommendation.top20)
    }
}