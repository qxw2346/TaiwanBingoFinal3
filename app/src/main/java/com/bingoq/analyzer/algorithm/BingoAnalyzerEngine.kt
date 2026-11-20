package com.bingoq.analyzer.algorithm
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.WeightConfig
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
object BingoAnalyzerEngine {
    private const val TOTAL_BALLS = 80
    private const val EPSILON = 1e-9
    fun computeScores(history: List<BingoDraw>, weights: WeightConfig): Map<Int, Double> {
        val finalMap = mutableMapOf<Int, Double>()
        if (history.isEmpty()) {
            for (i in 1..TOTAL_BALLS) finalMap[i] = 1.0
            return finalMap
        }
        val totalDraws = history.size
        val wNorm = weights.normalizedWeights()
        val freqAll = IntArray(TOTAL_BALLS + 1)
        val freqRecent5 = IntArray(TOTAL_BALLS + 1)
        val freqRecent10 = IntArray(TOTAL_BALLS + 1)
        val freqRecent20 = IntArray(TOTAL_BALLS + 1)
        val freqRecent30 = IntArray(TOTAL_BALLS + 1)
        val lastSeenIndex = IntArray(TOTAL_BALLS + 1) { totalDraws }
        for ((idx, draw) in history.withIndex()) {
            for (num in draw.numbers) {
                if (num in 1..TOTAL_BALLS) {
                    freqAll[num]++
                    if (idx < 5) freqRecent5[num]++
                    if (idx < 10) freqRecent10[num]++
                    if (idx < 20) freqRecent20[num]++
                    if (idx < 30) freqRecent30[num]++
                    if (lastSeenIndex[num] == totalDraws) lastSeenIndex[num] = idx
                }
            }
        }
        val lastDrawSet = history.first().numbers.toSet()
        val markovMatrix = Array(TOTAL_BALLS + 1) { IntArray(TOTAL_BALLS + 1) }
        val markovRowSums = IntArray(TOTAL_BALLS + 1)
        val markovSampleDepth = minOf(100, history.size - 1)
        for (i in 0 until markovSampleDepth) {
            val currDraw = history[i].numbers
            val prevDraw = history[i + 1].numbers
            for (p in prevDraw) {
                for (c in currDraw) {
                    if (p in 1..TOTAL_BALLS && c in 1..TOTAL_BALLS) {
                        markovMatrix[p][c]++
                        markovRowSums[p]++
                    }
                }
            }
        }
        val coMatrix = Array(TOTAL_BALLS + 1) { IntArray(TOTAL_BALLS + 1) }
        val coSampleDepth = minOf(40, history.size)
        for (i in 0 until coSampleDepth) {
            val nums = history[i].numbers
            for (a in nums) {
                for (b in nums) {
                    if (a in 1..TOTAL_BALLS && b in 1..TOTAL_BALLS && a != b) {
                        coMatrix[a][b]++
                    }
                }
            }
        }
        val tailFreqRecent20 = DoubleArray(10)
        for (n in 1..TOTAL_BALLS) tailFreqRecent20[n % 10] += freqRecent20[n].toDouble()
        val segCV = DoubleArray(8)
        for (s in 0 until 8) {
            val start = s * 10 + 1
            val counts = (start until start + 10).map { freqRecent20[it].toDouble() }
            val mean = counts.average()
            val variance = counts.sumOf { (it - mean) * (it - mean) } / counts.size
            segCV[s] = if (mean > 0) sqrt(variance) / mean else 1.0
        }
        val zoneDiff = DoubleArray(4)
        for (z in 0 until 4) {
            val start = z * 20 + 1
            val f5 = (start until start + 20).sumOf { freqRecent5[it] }.toDouble() / 5.0
            val f20 = (start until start + 20).sumOf { freqRecent20[it] }.toDouble() / 20.0
            zoneDiff[z] = f5 - f20
        }
        val rawMatrix = Array(TOTAL_BALLS + 1) { DoubleArray(9) }
        val lambdaDecay = ln(2.0) / 12.0
        for (n in 1..TOTAL_BALLS) {
            rawMatrix[n][0] = (freqAll[n] + 16.0 * 0.25) / (totalDraws + 16.0)
            rawMatrix[n][1] = 0.40 * (freqRecent5[n] / 5.0) +
                              0.30 * (freqRecent10[n] / 10.0) +
                              0.20 * (freqRecent20[n] / 20.0) +
                              0.10 * (freqRecent30[n] / 30.0)
            var decayScore = 0.0
            for ((idx, draw) in history.take(40).withIndex()) {
                if (n in draw.numbers) decayScore += exp(-lambdaDecay * idx)
            }
            rawMatrix[n][2] = decayScore
            val gap = lastSeenIndex[n]
            rawMatrix[n][3] = when (gap) {
                in 2..6 -> 1.5
                in 0..1 -> 1.0
                in 7..14 -> 0.7
                else -> 0.4
            }
            rawMatrix[n][4] = tailFreqRecent20[n % 10]
            val segIdx = (n - 1) / 10
            rawMatrix[n][5] = 1.0 / (segCV[segIdx] + 0.1)
            var markovProb = 0.0
            for (lastNum in lastDrawSet) {
                val rowTotal = markovRowSums[lastNum]
                val transCount = markovMatrix[lastNum][n]
                markovProb += (transCount + 1.0) / (rowTotal + TOTAL_BALLS)
            }
            rawMatrix[n][6] = markovProb
            var coScore = 0.0
            for (anchor in 1..TOTAL_BALLS) {
                if (freqRecent10[anchor] >= 3 && anchor != n) {
                    coScore += coMatrix[n][anchor].toDouble()
                }
            }
            rawMatrix[n][7] = coScore
            val zIdx = (n - 1) / 20
            rawMatrix[n][8] = zoneDiff[zIdx]
        }
        val normMatrix = Array(TOTAL_BALLS + 1) { DoubleArray(9) }
        for (f in 0 until 9) {
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (n in 1..TOTAL_BALLS) {
                val v = rawMatrix[n][f]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val range = maxV - minV
            for (n in 1..TOTAL_BALLS) {
                normMatrix[n][f] = if (range > EPSILON) (rawMatrix[n][f] - minV) / range else 0.5
            }
        }
        for (n in 1..TOTAL_BALLS) {
            var total = 0.0
            for (f in 0 until 9) {
                total += normMatrix[n][f] * wNorm[f]
            }
            finalMap[n] = if (total.isNaN() || total.isInfinite()) 0.0 else total
        }
        return finalMap
    }
}