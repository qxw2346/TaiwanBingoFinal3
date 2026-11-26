package com.bingoq.analyzer.network
import com.bingoq.analyzer.model.BingoDraw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
class BingoDataFetcher {
    enum class Status {
        GREEN, YELLOW, RED
    }
    data class FetchResult(
        val status: Status,
        val draws: List<BingoDraw>,
        val sourceSummary: String = ""
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    suspend fun fetchAndVerify(fullBackfill: Boolean = false): FetchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // Keep yesterday's draws in the normal sync window as well. Otherwise,
            // a prediction made near midnight can never be settled on the next day:
            // the current-day-only fetch omits its target draw permanently.
            val daysToFetch = if (fullBackfill) 5 else 2
            val defOfficial = async { fetchFromTaiwanLotteryApi(daysToFetch) }
            val defAuzo = async { fetchFromAuzo(daysToFetch) }
            val defTimetable = async { fetchFromTimetable() }
            val listOfficial = defOfficial.await().sortedByDescending { it.drawNo.toLongOrNull() ?: 0L }
            val listAuzo = defAuzo.await().sortedByDescending { it.drawNo.toLongOrNull() ?: 0L }
            val listTimetable = defTimetable.await().sortedByDescending { it.drawNo.toLongOrNull() ?: 0L }
            // Auzo daily pages are multiple date slices from ONE provider, not independent sources.
            val sources = listOf(
                "台彩官方 API" to listOfficial,
                "Auzo 歷史頁面" to listAuzo,
                "賓果歷史查詢" to listTimetable
            )
            val primary = sources.firstOrNull { it.second.isNotEmpty() }?.second.orEmpty()
            if (primary.isEmpty()) {
                return@coroutineScope FetchResult(Status.RED, emptyList(), "三個保留來源均未取得有效資料")
            }
            val recentDraws = primary.take(5)
            var latestVerified = false
            for (target in recentDraws) {
                val samePeriod = sources.mapNotNull { (name, rows) ->
                    rows.firstOrNull { it.drawNo == target.drawNo }?.let { name to it }
                }
                val matchingSources = samePeriod.filter { it.second.numbers.toSet() == target.numbers.toSet() }
                val distinctResults = samePeriod.map { it.second.numbers.toSet() }.distinct()
                if (distinctResults.size > 1 && samePeriod.size >= 2) {
                    val conflict = samePeriod.joinToString("；") { pair ->
                        pair.first + "=" + pair.second.numbers.sorted().joinToString(",")
                    }
                    return@coroutineScope FetchResult(Status.RED, emptyList(), "期號 " + target.drawNo + " 來源衝突：" + conflict)
                }
                if (target.drawNo == recentDraws.first().drawNo) latestVerified = matchingSources.size >= 2
            }
            val merged = sources.flatMap { it.second }
                .distinctBy { it.drawNo }
                .sortedByDescending { it.drawNo.toLongOrNull() ?: 0L }
                .take(1000)
            val newest = recentDraws.first()
            val sourceSummary = sources.joinToString("\n") { pair ->
                val name = pair.first
                val rows = pair.second
                val row = rows.firstOrNull { it.drawNo == newest.drawNo }
                val mark = if (row == null) "未取得" else if (row.numbers.toSet() == newest.numbers.toSet()) "一致" else "不一致"
                name + "：" + mark + "（" + rows.size + " 期；最新 " + (rows.firstOrNull()?.drawNo ?: "--") + "）"
            }
            val status = if (latestVerified) Status.GREEN else Status.YELLOW
            FetchResult(status, merged, sourceSummary)
        }
    }
    private fun fetchFromTaiwanLotteryApi(days: Int): List<BingoDraw> {
        val results = mutableListOf<BingoDraw>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Taipei")
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
        for (dayOffset in 0 until days) {
            val dateStr = sdf.format(cal.time)
            var daySuccess = tryFetchOfficialUrl(
                "https://api.taiwanlottery.com/TLCAPIWeB/Lottery/BingoResult?openDate=" + dateStr + "&pageNum=1&pageSize=210",
                results
            )
            if (!daySuccess) {
                for (page in 1..4) {
                    val pageOk = tryFetchOfficialUrl(
                        "https://api.taiwanlottery.com/TLCAPIWeB/Lottery/BingoResult?openDate=" + dateStr + "&pageNum=" + page + "&pageSize=50",
                        results
                    )
                    if (!pageOk) break
                    daySuccess = true
                }
            } else if (days > 1) {
                // During historical backfill, retrieve the next official API page too.
                // A single 210-row page can omit several hours of 5-minute draws.
                tryFetchOfficialUrl(
                    "https://api.taiwanlottery.com/TLCAPIWeB/Lottery/BingoResult?openDate=" + dateStr + "&pageNum=2&pageSize=210",
                    results
                )
            }
            if (dayOffset == 0) {
                tryFetchOfficialUrl("https://api.taiwanlottery.com/TLCAPIWeB/Lottery/LatestBingoResult", results)
            }
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return results.distinctBy { it.drawNo }
    }
    private fun tryFetchOfficialUrl(url: String, out: MutableList<BingoDraw>): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return false
                if (!resp.isSuccessful || body.isBlank()) return false
                val root = JSONObject(body)
                val beforeCount = out.size
                val contentObj = root.optJSONObject("content")
                if (contentObj != null) {
                    val keys = contentObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = contentObj.optJSONArray(k)
                        if (arr != null) parseBingoJsonArray(arr, out)
                        val subObj = contentObj.optJSONObject(k)
                        if (subObj != null) parseBingoJsonObject(subObj)?.let { out.add(it) }
                    }
                } else {
                    val contentArr = root.optJSONArray("content")
                    if (contentArr != null) parseBingoJsonArray(contentArr, out)
                }
                out.size > beforeCount
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }
    private fun parseBingoJsonArray(arr: JSONArray, out: MutableList<BingoDraw>) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseBingoJsonObject(obj)?.let { out.add(it) }
        }
    }
    private fun parseBingoJsonObject(obj: JSONObject): BingoDraw? {
        val drawNo = obj.optString("drawTerm", "").trim()
        if (drawNo.isEmpty() || drawNo == "null") return null
        val dDate = obj.optString("dDate", "").trim().let { if (it == "null") "" else it }
        val openDate = obj.optString("openDate", "").trim().let { if (it == "null") "" else it }
        val time = dDate.ifBlank { openDate }
        val nums = extractNumbers(obj, "bigShowOrder").ifEmpty { extractNumbers(obj, "openShowOrder") }
        return if (nums.size == 20) BingoDraw(drawNo, time, nums) else null
    }
    private fun extractNumbers(obj: JSONObject, key: String): List<Int> {
        val arr = obj.optJSONArray(key)
        if (arr != null) {
            val list = mutableListOf<Int>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim().toIntOrNull() ?: arr.optInt(i, -1)
                if (v in 1..80) list.add(v)
            }
            val unique = list.distinct()
            if (unique.size == 20) return unique
        }
        val str = obj.optString(key, "")
        if (str.isNotBlank() && str != "null") {
            val unique = str.split(Regex("[,\\s]+"))
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..80 }
                .distinct()
            if (unique.size == 20) return unique
        }
        return emptyList()
    }
    private fun fetchFromTimetable(): List<BingoDraw> =
        fetchHtmlDraws("https://lottery.timetable.tw/bin-guo-bin-guo", "Timetable")

    private fun fetchHtmlDraws(url: String, source: String): List<BingoDraw> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .timeout(12000)
                .get()
            val pageText = doc.body().text()
            val marker = Regex("(?:期別\\s*[:：]?\\s*|No\\.\\s*)(\\d{9})")
            val matches = marker.findAll(pageText).toList()
            val results = mutableListOf<BingoDraw>()
            for (i in matches.indices) {
                val match = matches[i]
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else pageText.length
                var block = pageText.substring(match.range.last + 1, end)
                block = block.replace(Regex("20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}"), " ")
                    .replace(Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), " ")
                val numbers = Regex("\\b\\d{1,2}\\b").findAll(block)
                    .mapNotNull { it.value.toIntOrNull() }
                    .filter { it in 1..80 }
                    .distinct()
                    .take(20)
                    .toList()
                if (numbers.size == 20) results.add(BingoDraw(match.groupValues[1], "", numbers))
            }
            results.distinctBy { it.drawNo }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Fetch Auzo's date-addressed historical pages, e.g.
     * https://lotto.auzonet.com/bingobingo/list_20261121.html
     * The pages are date slices from the same provider and must count as ONE source.
     */
    private fun fetchFromAuzo(days: Int): List<BingoDraw> {
        val results = mutableListOf<BingoDraw>()
        val tz = TimeZone.getTimeZone("Asia/Taipei")
        val calendar = Calendar.getInstance(tz)
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }
        for (offset in 0 until days.coerceAtLeast(1)) {
            val datePart = dateFormat.format(calendar.time)
            val url = "https://lotto.auzonet.com/bingobingo/list_" + datePart + ".html"
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .timeout(15000)
                    .get()
                parseAuzoHistoricalDoc(doc, results)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }
        return results.distinctBy { it.drawNo }
    }

    private fun parseAuzoHistoricalDoc(doc: Document, out: MutableList<BingoDraw>) {
        val periodPattern = Regex("\\b(1[1-9]\\d{7})\\b")
        for (tr in doc.select("tr")) {
            val rowText = tr.text()
            val periodMatch = periodPattern.find(rowText) ?: continue
            val drawNo = periodMatch.groupValues[1]
            var remainder = rowText.substring(periodMatch.range.last + 1)
            remainder = remainder
                .replace(Regex("20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}"), " ")
                .replace(Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"), " ")
            val numbers = Regex("\\b\\d{1,2}\\b").findAll(remainder)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..80 }
                .distinct()
                .take(20)
                .toList()
            if (numbers.size == 20) {
                out.add(BingoDraw(drawNo, "", numbers))
            }
        }
    }
}
