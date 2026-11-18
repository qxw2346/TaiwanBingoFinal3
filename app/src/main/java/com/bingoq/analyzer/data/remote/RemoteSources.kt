package com.bingoq.analyzer.data.remote
import com.bingoq.analyzer.data.validation.RawDrawRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.util.concurrent.TimeUnit
interface BingoRemoteSource {
    val sourceName: String
    val sourceUrl: String
    suspend fun fetchRecentDraws(targetCount: Int): Result<List<RawDrawRecord>>
}
class OkHttpProvider {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
class TaiwanLotteryApiSource(
    private val client: OkHttpClient
) : BingoRemoteSource {
    override val sourceName: String = "台灣彩券官方API"
    override val sourceUrl: String = "https://api.taiwanlottery.com/TLCAPIWeB/Lottery/BingoResult"
    override suspend fun fetchRecentDraws(targetCount: Int): Result<List<RawDrawRecord>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val collected = mutableListOf<RawDrawRecord>()
                for (dayOffset in 0L..2L) {
                    if (collected.size >= targetCount) break
                    val dateStr = LocalDate.now().minusDays(dayOffset).toString()
                    val reqUrl = "$sourceUrl?openDate=$dateStr&pageNum=1&pageSize=210"
                    val request = Request.Builder()
                        .url(reqUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-N9860)")
                        .header("Accept", "application/json")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                collected.addAll(parseOfficialJson(body))
                            }
                        } else if (dayOffset == 0L && collected.isEmpty()) {
                            error("HTTP ${resp.code}")
                        }
                    }
                }
                collected.distinctBy { it.period }
                    .sortedByDescending { it.period }
                    .take(targetCount)
            }
        }
    private fun parseOfficialJson(jsonStr: String): List<RawDrawRecord> {
        val root = JSONObject(jsonStr)
        val content = root.optJSONObject("content") ?: return emptyList()
        val list = content.optJSONArray("bingoQueryResult") ?: return emptyList()
        val records = mutableListOf<RawDrawRecord>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val period = item.optLong("drawTerm", -1L)
            if (period <= 0) continue
            val rawDate = item.optString("dDate", "").take(10)
            val ballArr = item.optJSONArray("bigShowOrder")
                ?: item.optJSONArray("openShowOrder")
                ?: continue
            val nums = (0 until ballArr.length()).mapNotNull { idx ->
                ballArr.optString(idx).trim().toIntOrNull()
            }
            val superNum = item.optJSONObject("bullEyeTop")
                ?.optString("bullEyeTop")
                ?.toIntOrNull()
            records.add(
                RawDrawRecord(
                    period = period,
                    drawDate = rawDate,
                    drawTime = null,
                    numbers = nums,
                    superNumber = superNum,
                    sourceName = sourceName,
                    sourceUrl = sourceUrl
                )
            )
        }
        return records
    }
}
class AuzoHtmlSource(
    private val client: OkHttpClient
) : BingoRemoteSource {
    override val sourceName: String = "奧索樂透網"
    override val sourceUrl: String = "https://lotto.auzo.tw/bingobingo/"
    override suspend fun fetchRecentDraws(targetCount: Int): Result<List<RawDrawRecord>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-N9860)")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val html = resp.body?.string() ?: error("空白網頁")
                    parseAuzoHtml(html, targetCount)
                }
            }
        }
    private fun parseAuzoHtml(html: String, limit: Int): List<RawDrawRecord> {
        val doc = Jsoup.parse(html)
        val records = mutableListOf<RawDrawRecord>()
        val rows = doc.select("table tr")
        val today = LocalDate.now().toString()
        for (tr in rows) {
            val text = tr.text()
            val periodMatch = Regex("""\b(1\d{8})\b""").find(text) ?: continue
            val period = periodMatch.groupValues[1].toLongOrNull() ?: continue
            val timeMatch = Regex("""\b([012]\d:[0-5]\d)\b""").find(text)?.groupValues?.get(1)
            val afterPeriod = text.substringAfter(periodMatch.value)
            val nums = Regex("""\b(0[1-9]|[1-7]\d|80)\b""")
                .findAll(afterPeriod)
                .map { it.value.toInt() }
                .distinct()
                .take(20)
                .toList()
            if (nums.size == 20) {
                records.add(
                    RawDrawRecord(
                        period = period,
                        drawDate = today,
                        drawTime = timeMatch,
                        numbers = nums,
                        superNumber = null,
                        sourceName = sourceName,
                        sourceUrl = sourceUrl
                    )
                )
            }
            if (records.size >= limit) break
        }
        return records
    }
}