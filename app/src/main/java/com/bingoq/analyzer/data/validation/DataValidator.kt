package com.bingoq.analyzer.data.validation
import com.bingoq.analyzer.data.local.BingoDrawEntity
import com.bingoq.analyzer.data.local.VerificationBadge
data class RawDrawRecord(
    val period: Long,
    val drawDate: String,
    val drawTime: String?,
    val numbers: List<Int>,
    val superNumber: Int?,
    val sourceName: String,
    val sourceUrl: String
)
data class ValidationReport(
    val badge: VerificationBadge,
    val sourceUrls: List<String>,
    val fetchedCount: Int,
    val latestPeriod: Long?,
    val latestDate: String?,
    val duplicateCount: Int,
    val invalidFormatCount: Int,
    val missingPeriods: List<Long>,
    val conflictPeriods: List<Long>,
    val crossCheckMessage: String,
    val updatedAtEpochMs: Long
) {
    val canPredictOfficially: Boolean
        get() = badge != VerificationBadge.RED && latestPeriod != null
}
object DataValidator {
    fun isValidDrawNumbers(numbers: List<Int>): Boolean {
        if (numbers.size != 20) return false
        val unique = numbers.toSet()
        return unique.size == 20 && unique.all { it in 1..80 }
    }
    fun validateAndMerge(
        recordsFromSources: List<RawDrawRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<BingoDrawEntity>, ValidationReport> {
        val sourceUrls = recordsFromSources.map { it.sourceUrl }.distinct()
        var duplicateCount = 0
        var invalidFormatCount = 0
        val conflictPeriods = mutableListOf<Long>()
        val validFormatRecords = mutableListOf<RawDrawRecord>()
        for (rec in recordsFromSources) {
            if (isValidDrawNumbers(rec.numbers)) {
                validFormatRecords.add(rec.copy(numbers = rec.numbers.sorted()))
            } else {
                invalidFormatCount++
            }
        }
        val groupedByPeriod = validFormatRecords.groupBy { it.period }
        val mergedEntities = mutableListOf<BingoDrawEntity>()
        for ((period, group) in groupedByPeriod) {
            if (group.size > 1) duplicateCount += (group.size - 1)
            val distinctNumberSets = group.map { it.numbers.toSet() }.distinct()
            val chosen = group.first()
            val status = when {
                distinctNumberSets.size > 1 -> {
                    conflictPeriods.add(period)
                    VerificationBadge.RED
                }
                group.map { it.sourceName }.distinct().size >= 2 ||
                    chosen.sourceName.contains("官方") -> VerificationBadge.GREEN
                else -> VerificationBadge.YELLOW
            }
            mergedEntities.add(
                BingoDrawEntity(
                    period = period,
                    drawDate = chosen.drawDate,
                    drawTime = chosen.drawTime,
                    numbersCsv = chosen.numbers.joinToString(",") { it.toString().padStart(2, '0') },
                    superNumber = chosen.superNumber,
                    source = group.map { it.sourceName }.distinct().joinToString(" + "),
                    verificationStatus = status.name,
                    updatedAtEpochMs = nowMs
                )
            )
        }
        val sortedDesc = mergedEntities.sortedByDescending { it.period }
        val missingPeriods = detectMissingPeriods(sortedDesc.map { it.period })
        val latest = sortedDesc.firstOrNull()
        val latestIsRed = latest == null || latest.verificationStatus == VerificationBadge.RED.name
        val overallBadge = when {
            sortedDesc.isEmpty() || latestIsRed || conflictPeriods.isNotEmpty() -> VerificationBadge.RED
            missingPeriods.isNotEmpty() || invalidFormatCount > 0 ||
                latest?.verificationStatus == VerificationBadge.YELLOW.name -> VerificationBadge.YELLOW
            else -> VerificationBadge.GREEN
        }
        val crossMsg = when (overallBadge) {
            VerificationBadge.GREEN -> "交叉驗證通過，期數格式與號碼皆無誤"
            VerificationBadge.YELLOW -> "部分驗證：單一來源或區間缺漏 ${missingPeriods.size} 期"
            VerificationBadge.RED -> "驗證失敗：資料異常、空白或來源衝突 (${conflictPeriods.size} 期)"
        }
        val report = ValidationReport(
            badge = overallBadge,
            sourceUrls = sourceUrls,
            fetchedCount = sortedDesc.size,
            latestPeriod = latest?.period,
            latestDate = latest?.drawDate,
            duplicateCount = duplicateCount,
            invalidFormatCount = invalidFormatCount,
            missingPeriods = missingPeriods,
            conflictPeriods = conflictPeriods,
            crossCheckMessage = crossMsg,
            updatedAtEpochMs = nowMs
        )
        return sortedDesc to report
    }
    private fun detectMissingPeriods(periods: List<Long>): List<Long> {
        if (periods.size < 2) return emptyList()
        val sorted = periods.distinct().sorted()
        val missing = mutableListOf<Long>()
        for (i in 0 until sorted.lastIndex) {
            val diff = sorted[i + 1] - sorted[i]
            if (diff in 2..20) {
                for (p in (sorted[i] + 1) until sorted[i + 1]) {
                    missing.add(p)
                }
            }
        }
        return missing
    }
}