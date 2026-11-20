package com.bingoq.analyzer.model
import org.json.JSONObject
data class WeightConfig(
    val wA: Int = 12,
    val wB: Int = 15,
    val wC: Int = 10,
    val wD: Int = 8,
    val wE: Int = 10,
    val wF: Int = 10,
    val wG: Int = 12,
    val wH: Int = 13,
    val wI: Int = 10,
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val mode: String = "CUSTOM"
) {
    fun totalWeight(): Int = wA + wB + wC + wD + wE + wF + wG + wH + wI
    fun isValid(): Boolean {
        val list = listOf(wA, wB, wC, wD, wE, wF, wG, wH, wI)
        return list.all { it in 0..1000 } && totalWeight() > 0
    }
    fun normalizedWeights(): DoubleArray {
        val total = totalWeight().toDouble()
        if (total <= 0.0) return DoubleArray(9) { 1.0 / 9.0 }
        return doubleArrayOf(
            wA / total, wB / total, wC / total,
            wD / total, wE / total, wF / total,
            wG / total, wH / total, wI / total
        )
    }
    fun toJson(): String {
        return JSONObject().apply {
            put("wA", wA)
            put("wB", wB)
            put("wC", wC)
            put("wD", wD)
            put("wE", wE)
            put("wF", wF)
            put("wG", wG)
            put("wH", wH)
            put("wI", wI)
            put("version", version)
            put("updatedAt", updatedAt)
            put("mode", mode)
        }.toString()
    }
    companion object {
        fun default(): WeightConfig = WeightConfig(
            wA = 12, wB = 15, wC = 10, wD = 8, wE = 10,
            wF = 10, wG = 12, wH = 13, wI = 10,
            mode = "DEFAULT"
        )
        fun fromJson(jsonStr: String): WeightConfig? {
            return try {
                val obj = JSONObject(jsonStr)
                WeightConfig(
                    wA = obj.getInt("wA"),
                    wB = obj.getInt("wB"),
                    wC = obj.getInt("wC"),
                    wD = obj.getInt("wD"),
                    wE = obj.getInt("wE"),
                    wF = obj.getInt("wF"),
                    wG = obj.getInt("wG"),
                    wH = obj.getInt("wH"),
                    wI = obj.getInt("wI"),
                    version = obj.optInt("version", 1),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    mode = obj.optString("mode", "CUSTOM")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}