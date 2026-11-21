package com.bingoq.analyzer.algorithm
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.WeightConfig
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.abs
object BingoAnalyzerEngine {
    private const val TOTAL_BALLS = 80
    private const val EPSILON = 1e-9
    fun computeScores(history: List<BingoDraw>, weights: WeightConfig): Map<Int, Double> {
        val normMatrix = computeNormalizedFactorMatrix(history)
        return scoreFromFactorMatrix(normMatrix, weights)
    }
    fun scoreFromFactorMatrix(normMatrix: Array<DoubleArray>, weights: WeightConfig): Map<Int, Double> {
        val finalMap = HashMap<Int, Double>(TOTAL_BALLS)
        val wNorm = weights.normalizedWeights()
        for (n in 1..TOTAL_BALLS) {
            var total = 0.0
            val row = normMatrix[n]
            for (f in 0 until 9) {
                total += row[f] * wNorm[f]
            }
            finalMap[n] = if (total.isNaN() || total.isInfinite()) 0.0 else total
        }
        return finalMap
    }
    fun computeNormalizedFactorMatrix(history: List<BingoDraw>): Array<DoubleArray> {
        val normMatrix = Array(TOTAL_BALLS + 1) { DoubleArray(9) { 0.5 } }
        if (history.isEmpty()) return normMatrix
        val totalDraws = history.size
        val w5 = minOf(5, totalDraws)
        val w10 = minOf(10, totalDraws)
        val w20 = minOf(20, totalDraws)
        val w30 = minOf(30, totalDraws)
        val freqAll = IntArray(TOTAL_BALLS + 1)
        val freq5 = IntArray(TOTAL_BALLS + 1)
        val freq10 = IntArray(TOTAL_BALLS + 1)
        val freq20 = IntArray(TOTAL_BALLS + 1)
        val freq30 = IntArray(TOTAL_BALLS + 1)
        val lastSeenIndex = IntArray(TOTAL_BALLS + 1) { totalDraws }
        val maxGapBucket = 25
        val gapTrials = IntArray(maxGapBucket + 1)
        val gapHits = IntArray(maxGapBucket + 1)
        val drawFlags = Array(totalDraws) { BooleanArray(TOTAL_BALLS + 1) }
        for (idx in 0 until totalDraws) {
            val draw = history[idx]
            val flags = drawFlags[idx]
            for (num in draw.numbers) {
                if (num in 1..TOTAL_BALLS) {
                    flags[num] = true
                    freqAll[num]++
                    if (idx < 5) freq5[num]++
                    if (idx < 10) freq10[num]++
                    if (idx < 20) freq20[num]++
                    if (idx < 30) freq30[num]++
                    if (lastSeenIndex[num] == totalDraws) lastSeenIndex[num] = idx
                }
            }
        }
        // Estimate P(hit next draw | current omission age) from prior walk-forward states.
        // Skip each number's first left-censored streak because its true age is unknown.
        // The former code counted only completed hit-to-hit gaps, making every trial a hit.
        if (totalDraws >= 2) {
            for (n in 1..TOTAL_BALLS) {
                var hasPriorHit = false
                var omissionAge = 0
                for (idx in totalDraws - 1 downTo 0) {
                    val hit = drawFlags[idx][n]
                    if (!hasPriorHit) {
                        if (hit) {
                            hasPriorHit = true
                            omissionAge = 0
                        }
                        continue
                    }
                    val bucket = omissionAge.coerceIn(0, maxGapBucket)
                    gapTrials[bucket]++
                    if (hit) {
                        gapHits[bucket]++
                        omissionAge = 0
                    } else {
                        omissionAge++
                    }
                }
            }
        }
        val markovMatrix = Array(TOTAL_BALLS + 1) { IntArray(TOTAL_BALLS + 1) }
        val markovRowSums = IntArray(TOTAL_BALLS + 1)
        val markovDepth = minOf(600, totalDraws - 1)
        for (i in 0 until markovDepth) {
            val currFlags = drawFlags[i]
            val prevNums = history[i + 1].numbers
            for (p in prevNums) {
                if (p !in 1..TOTAL_BALLS) continue
                val row = markovMatrix[p]
                for (c in 1..TOTAL_BALLS) {
                    if (currFlags[c]) {
                        row[c]++
                        markovRowSums[p]++
                    }
                }
            }
        }
        val markovConfidence = minOf(1.0, markovDepth / 100.0)
        val coMatrix = Array(TOTAL_BALLS + 1) { IntArray(TOTAL_BALLS + 1) }
        val coFreq = IntArray(TOTAL_BALLS + 1)
        val coDepth = minOf(400, totalDraws)
        for (i in 0 until coDepth) {
            val nums = history[i].numbers
            for (a in nums) {
                if (a !in 1..TOTAL_BALLS) continue
                coFreq[a]++
                val row = coMatrix[a]
                for (b in nums) {
                    if (b in 1..TOTAL_BALLS && a != b) {
                        row[b]++
                    }
                }
            }
        }
        val anchors = IntArray(TOTAL_BALLS)
        var anchorCount = 0
        val anchorThreshold = maxOf(2, (w10 * 0.3).toInt())
        for (n in 1..TOTAL_BALLS) {
            if (freq10[n] >= anchorThreshold) {
                anchors[anchorCount++] = n
            }
        }
        val tailAll = DoubleArray(10)
        val tail10 = DoubleArray(10)
        val tail30 = DoubleArray(10)
        for (n in 1..TOTAL_BALLS) {
            val t = n % 10
            tailAll[t] += freqAll[n].toDouble()
            tail10[t] += freq10[n].toDouble()
            tail30[t] += freq30[n].toDouble()
        }
        val segStability = DoubleArray(8)
        val blockCount = minOf(10, totalDraws / 5)
        for (s in 0 until 8) {
            val start = s * 10 + 1
            if (blockCount >= 2) {
                var sum = 0.0
                var sumSq = 0.0
                for (b in 0 until blockCount) {
                    var blockHits = 0
                    val bStart = b * 5
                    for (k in bStart until bStart + 5) {
                        val f = drawFlags[k]
                        for (n in start until start + 10) {
                            if (f[n]) blockHits++
                        }
                    }
                    sum += blockHits
                    sumSq += blockHits * blockHits
                }
                val mean = sum / blockCount
                val variance = maxOf(0.0, (sumSq / blockCount) - (mean * mean))
                val cv = if (mean > EPSILON) sqrt(variance) / mean else 1.0
                val recentRate = (start until start + 10).sumOf { freq20[it] }.toDouble() / w20
                val longRate = (start until start + 10).sumOf { freqAll[it] }.toDouble() / totalDraws
                val consistency = 1.0 / (cv + 0.2)
                segStability[s] = consistency * (0.5 + 0.5 * (recentRate / (longRate + EPSILON)).coerceIn(0.5, 1.5))
            } else {
                segStability[s] = 1.0
            }
        }
        val zoneMomentum = DoubleArray(4)
        for (z in 0 until 4) {
            val start = z * 20 + 1
            var s5 = 0.0; var s10 = 0.0; var s20 = 0.0; var s30 = 0.0; var sAll = 0.0
            for (n in start until start + 20) {
                s5 += freq5[n]
                s10 += freq10[n]
                s20 += freq20[n]
                s30 += freq30[n]
                sAll += freqAll[n]
            }
            val r5 = s5 / w5
            val r10 = s10 / w10
            val r20 = s20 / w20
            val r30 = s30 / w30
            val rAll = sAll / totalDraws
            val shiftShort = r5 - r20
            val shiftMid = r10 - r30
            val baselineDev = (r20 - rAll)
            zoneMomentum[z] = (0.4 * shiftShort + 0.4 * shiftMid - 0.2 * baselineDev)
        }
        val rawMatrix = Array(TOTAL_BALLS + 1) { DoubleArray(9) }
        val lambdaDecay = ln(2.0) / 12.0
        val decayDepth = minOf(80, totalDraws)
        var maxDecayPossible = 0.0
        for (idx in 0 until decayDepth) {
            maxDecayPossible += exp(-lambdaDecay * idx)
        }
        val lastDrawNums = history.first().numbers
        for (n in 1..TOTAL_BALLS) {
            rawMatrix[n][0] = (freqAll[n] + 16.0 * 0.25) / (totalDraws + 16.0)
            rawMatrix[n][1] = 0.40 * (freq5[n].toDouble() / w5) +
                              0.30 * (freq10[n].toDouble() / w10) +
                              0.20 * (freq20[n].toDouble() / w20) +
                              0.10 * (freq30[n].toDouble() / w30)
            var decaySum = 0.0
            for (idx in 0 until decayDepth) {
                if (drawFlags[idx][n]) decaySum += exp(-lambdaDecay * idx)
            }
            rawMatrix[n][2] = if (maxDecayPossible > EPSILON) decaySum / maxDecayPossible else 0.25
            val gap = lastSeenIndex[n].coerceIn(0, maxGapBucket)
            rawMatrix[n][3] = (gapHits[gap] + 8.0 * 0.25) / (gapTrials[gap] + 8.0)
            val tailIdx = n % 10
            val tailRecentRate = 0.5 * (tail10[tailIdx] / w10) + 0.5 * (tail30[tailIdx] / w30)
            val tailLongRate = tailAll[tailIdx] / totalDraws
            rawMatrix[n][4] = (tailRecentRate + 0.5) / (tailLongRate + 0.5)
            rawMatrix[n][5] = segStability[(n - 1) / 10]
            var markovSum = 0.0
            var validLastCount = 0
            for (p in lastDrawNums) {
                if (p in 1..TOTAL_BALLS) {
                    val transProb = (markovMatrix[p][n] + 1.0) / (markovRowSums[p] + TOTAL_BALLS)
                    markovSum += transProb
                    validLastCount++
                }
            }
            val rawMarkov = if (validLastCount > 0) markovSum / validLastCount else 0.0125
            rawMatrix[n][6] = markovConfidence * rawMarkov + (1.0 - markovConfidence) * 0.0125
            var liftSum = 0.0
            var eligibleAnchorCount = 0
            val pN = (coFreq[n] + 4.0 * 0.25) / (coDepth + 4.0)
            // In a 20-of-80 draw without replacement, two distinct numbers
            // have joint probability (20/80)*(19/79), not 0.25*0.25.
            val nullJointProbability = (20.0 / 80.0) * (19.0 / 79.0)
            for (aIdx in 0 until anchorCount) {
                val anchor = anchors[aIdx]
                if (anchor != n) {
                    eligibleAnchorCount++
                    val pA = (coFreq[anchor] + 4.0 * 0.25) / (coDepth + 4.0)
                    val pNA = (coMatrix[n][anchor] + 4.0 * nullJointProbability) / (coDepth + 4.0)
                    // Correct the independence denominator for fixed-size sampling.
                    // For N=80, K=20: P(A∩B)/(P(A)P(B)) = ((K-1)/K)*(N/(N-1)) = 76/79.
                    val expectedJoint = pN * pA * (76.0 / 79.0)
                    val lift = pNA / (expectedJoint + EPSILON)
                    // Limit sparse-history extremes before cross-number ranking.
                    liftSum += lift.coerceIn(0.25, 4.0)
                }
            }
            // Do not divide by anchorCount when the candidate itself is an anchor:
            // self-pairs are excluded from the numerator and denominator alike.
            rawMatrix[n][7] = if (eligibleAnchorCount > 0) {
                liftSum / eligibleAnchorCount
            } else {
                1.0
            }
            rawMatrix[n][8] = zoneMomentum[(n - 1) / 20]
        }
        // Robust percentile ranks replace min-max scaling: a single extreme value
        // can no longer compress the other 79 numbers into nearly identical scores.
        // Ties receive their average rank, preserving neutral treatment of equal values.
        for (f in 0 until 9) {
            val ordered = (1..TOTAL_BALLS).sortedWith(
                compareBy<Int> { rawMatrix[it][f] }.thenBy { it }
            )
            var start = 0
            while (start < ordered.size) {
                var end = start + 1
                val tiedValue = rawMatrix[ordered[start]][f]
                while (end < ordered.size &&
                    abs(rawMatrix[ordered[end]][f] - tiedValue) <= EPSILON
                ) {
                    end++
                }
                val averageRank = (start + end - 1) / 2.0
                val percentile = if (ordered.size <= 1) 0.5 else averageRank / (ordered.size - 1)
                for (i in start until end) {
                    normMatrix[ordered[i]][f] = percentile.coerceIn(0.0, 1.0)
                }
                start = end
            }
        }
        return normMatrix
    }
}