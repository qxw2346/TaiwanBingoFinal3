package com.bingoq.analyzer.audit

import android.content.Context
import com.bingoq.analyzer.model.BingoDraw
import com.bingoq.analyzer.model.WeightConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Local append-only SHA-256 journal. Export it regularly to an external location. */
class PredictionAuditStore(context: Context) {
    data class Verification(val valid: Boolean, val eventCount: Int, val predictionCount: Int, val settlementCount: Int, val message: String)
    private val file = File(context.noBackupFilesDir, "prediction_audit.jsonl")
    private val zeroHash = "0".repeat(64)

    @Synchronized
    fun recordPrediction(targetPeriod: String, cutoffPeriod: String, scores: Map<Int, Double>,
        top5: List<Int>, top10: List<Int>, top20: List<Int>, weights: WeightConfig,
        historyCount: Int, sourceStatus: String): Boolean {
        if (targetPeriod.isBlank() || cutoffPeriod.isBlank() || scores.isEmpty()) return false
        if (!verify().valid) return false
        if (readPayloads().any { it.optString("type") == "PREDICTION" && it.optString("targetPeriod") == targetPeriod }) return false
        val scoreJson = JSONObject()
        scores.toSortedMap().forEach { (n, v) -> if (v.isFinite()) scoreJson.put(n.toString(), v) }
        val payload = JSONObject().apply {
            put("type", "PREDICTION"); put("schemaVersion", 1)
            put("targetPeriod", targetPeriod); put("cutoffPeriod", cutoffPeriod)
            put("createdAtEpochMs", System.currentTimeMillis()); put("timezone", "Asia/Taipei")
            put("top5", JSONArray(top5)); put("top10", JSONArray(top10)); put("top20", JSONArray(top20))
            put("scores", scoreJson); put("weights", JSONObject(weights.toJson()))
            put("historyCount", historyCount); put("sourceStatus", sourceStatus)
            put("algorithm", "BingoAnalyzerEngine/RecommendationEngine")
        }
        return appendPayload(payload)
    }

    @Synchronized
    fun settleAvailable(draws: List<BingoDraw>): Int {
        if (draws.isEmpty() || !verify().valid) return 0
        val payloads = readPayloads()
        val settled = payloads.filter { it.optString("type") == "SETTLEMENT" }.map { it.optString("targetPeriod") }.toMutableSet()
        val drawsByPeriod = draws.associateBy { it.drawNo }
        var added = 0
        for (p in payloads.filter { it.optString("type") == "PREDICTION" }) {
            val target = p.optString("targetPeriod")
            if (target.isBlank() || target in settled) continue
            val actual = drawsByPeriod[target] ?: continue
            val actualSet = actual.numbers.toSet()
            val top5 = p.optJSONArray("top5").toIntList()
            val top10 = p.optJSONArray("top10").toIntList()
            val top20 = p.optJSONArray("top20").toIntList()
            val event = JSONObject().apply {
                put("type", "SETTLEMENT"); put("schemaVersion", 1); put("targetPeriod", target)
                put("cutoffPeriod", p.optString("cutoffPeriod")); put("settledAtEpochMs", System.currentTimeMillis())
                put("actualNumbers", JSONArray(actual.numbers.sorted())); put("actualDrawTime", actual.drawTime)
                put("top5Hits", JSONArray(top5.filter { it in actualSet }))
                put("top10Hits", JSONArray(top10.filter { it in actualSet }))
                put("top20Hits", JSONArray(top20.filter { it in actualSet }))
                put("predictionCreatedAtEpochMs", p.optLong("createdAtEpochMs"))
            }
            if (appendPayload(event)) { settled.add(target); added++ } else break
        }
        return added
    }

    @Synchronized
    fun verify(): Verification {
        if (!file.exists() || file.length() == 0L) return Verification(true, 0, 0, 0, "尚無預測紀錄")
        var previous = zeroHash
        var events = 0
        var predictions = 0
        var settlements = 0
        return try {
            for (line in file.readLines(Charsets.UTF_8)) {
                if (line.isBlank()) continue
                val envelope = JSONObject(line)
                val prev = envelope.getString("previousHash")
                val payloadText = envelope.getString("payload")
                val hash = envelope.getString("hash")
                if (prev != previous || sha256(previous + "\n" + payloadText) != hash) {
                    return Verification(false, events, predictions, settlements, "雜湊鏈驗證失敗：紀錄可能遭到修改或截斷")
                }
                when (JSONObject(payloadText).optString("type")) {
                    "PREDICTION" -> predictions++
                    "SETTLEMENT" -> settlements++
                    else -> return Verification(false, events, predictions, settlements, "發現未知紀錄類型")
                }
                previous = hash
                events++
            }
            Verification(true, events, predictions, settlements, "SHA-256 雜湊鏈完整")
        } catch (e: Exception) {
            Verification(false, events, predictions, settlements, "紀錄讀取失敗：" + (e.message ?: "格式錯誤"))
        }
    }

    @Synchronized
    fun exportJsonLines(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    private fun readPayloads(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { JSONObject(JSONObject(it).getString("payload")) }
    }

    private fun appendPayload(payload: JSONObject): Boolean {
        return try {
            if (!verify().valid) {
                false
            } else {
                val previous = if (file.exists() && file.length() > 0L)
                    JSONObject(file.readLines(Charsets.UTF_8).last { it.isNotBlank() }).getString("hash") else zeroHash
                val payloadText = payload.toString()
                val envelope = JSONObject().apply {
                    put("previousHash", previous)
                    put("payload", payloadText)
                    put("hash", sha256(previous + "\n" + payloadText))
                }
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write((envelope.toString() + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it) }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optInt(it).takeIf { n -> n in 1..80 } }
    }
}
