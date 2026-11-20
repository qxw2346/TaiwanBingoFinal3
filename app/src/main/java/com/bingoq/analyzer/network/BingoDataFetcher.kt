package com.bingoq.analyzer.network
import com.bingoq.analyzer.model.BingoDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
        val draws: List<BingoDraw>
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    suspend fun fetchAndVerify(fullBackfill: Boolean = false): FetchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val daysToFetch = if (fullBackfill) 5 else 1
            val pilioExtraDays = if (fullBackfill) 4 else 0
            val auzoExtraDays = if (fullBackfill) 2 else 0
            val defOfficial = async { fetchFromTaiwanLotteryApi(daysToFetch) }
            val defPilio = async { fetchFromPilio(pilioExtraDays) }
            val defAuzo = async { fetchFromAuzo(auzoExtraDays) }
            val listOfficial = defOfficial.await()
            val listPilio = defPilio.await()
            val listAuzo = defAuzo.await()
            val primary = when {
                listOfficial.isNotEmpty() -> listOfficial
                listPilio.isNotEmpty() -> listPilio
                else -> listAuzo
            }
            if (primary.isEmpty()) {
                return@coroutineScope FetchResult(Status.RED, emptyList())
            }
            val recentDraws = primary.take(5)
            var verifiedGreen = false
            for (target in recentDraws) {
                var matchCount = 0
                if (listOfficial.any { it.drawNo == target.drawNo && it.numbers.toSet() == target.numbers.toSet() }) matchCount++
                if (listPilio.any { it.drawNo == target.drawNo && it.numbers.toSet() == target.numbers.toSet() }) matchCount++
                if (listAuzo.any { it.drawNo == target.drawNo && it.numbers.toSet() == target.numbers.toSet() }) matchCount++
                val hasConflict = listOf(listOfficial, listPilio, listAuzo).any { src ->
                    src.any { it.drawNo == target.drawNo && it.numbers.toSet() != target.numbers.toSet() }
                }
                if (hasConflict) {
                    return@coroutineScope FetchResult(Status.RED, emptyList())
                }
                if (matchCount >= 2) {
                    verifiedGreen = true
                }
            }
            val merged = (listOfficial + listPilio + listAuzo)
                .distinctBy { it.drawNo }
                .sortedByDescending { it.drawNo.toLongOrNull() ?: 0L }
                .take(1000)
            val status = if (verifiedGreen) Status.GREEN else Status.YELLOW
            FetchResult(status, merged)
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
            val resp = client.newCall(req).execute()
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
        } catch (_: Exception) {
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
        if (drawNo.isEmpty()) return null
        val time = obj.optString("dDate", obj.optString("openDate", "")).trim()
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
        if (str.isNotBlank()) {
            val unique = str.split(Regex("[,\\s]+"))
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..80 }
                .distinct()
            if (unique.size == 20) return unique
        }
        return emptyList()
    }
    private fun fetchFromPilio(extraDays: Int): List<BingoDraw> {
        val results = mutableListOf<BingoDraw>()
        try {
            val baseUrl = "https://www.pilio.idv.tw/bingobingo/list.asp"
            val doc = Jsoup.connect(baseUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(8000)
                .get()
            parsePilioText(doc.body().text(), results)
            if (extraDays > 0) {
                val dateLinks = doc.select("a[href]")
                    .filter { Regex("\\d{4}/\\d{1,2}/\\d{1,2}").containsMatchIn(it.text()) }
                    .map { it.absUrl("href") }
                    .distinct()
                    .take(extraDays)
                for (link in dateLinks) {
                    try {
                        val subDoc = Jsoup.connect(link)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .timeout(8000)
                            .get()
                        parsePilioText(subDoc.body().text(), results)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return results.distinctBy { it.drawNo }
    }
    private fun parsePilioText(pageText: String, out: MutableList<BingoDraw>) {
        val pattern = Regex("【期別[:：]\\s*(\\d{7,12})】([\\s\\S]*?)(?:超級獎號|【期別|$)")
        for (match in pattern.findAll(pageText)) {
            val drawNo = match.groupValues[1]
            val body = match.groupValues[2]
            val nums = Regex("\\b\\d{1,2}\\b").findAll(body)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..80 }
                .distinct()
                .toList()
            if (nums.size == 20) {
                out.add(BingoDraw(drawNo, "", nums))
            }
        }
    }
    private fun fetchFromAuzo(extraDays: Int): List<BingoDraw> {
        val results = mutableListOf<BingoDraw>()
        try {
            val baseUrl = "https://lotto.auzo.tw/bingo/"
            val doc = Jsoup.connect(baseUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(8000)
                .get()
            parseAuzoDoc(doc, results)
            if (extraDays > 0) {
                val dateLinks = doc.select("a[href]")
                    .filter { Regex("^\\d{2}/\\d{2}$").matches(it.text().trim()) }
                    .map { it.absUrl("href") }
                    .distinct()
                    .take(extraDays)
                for (link in dateLinks) {
                    try {
                        val subDoc = Jsoup.connect(link)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .timeout(8000)
                            .get()
                        parseAuzoDoc(subDoc, results)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return results.distinctBy { it.drawNo }
    }
    private fun parseAuzoDoc(doc: org.jsoup.nodes.Document, out: MutableList<BingoDraw>) {
        val rows = doc.select("tr").filter { it.select("tr").isEmpty() }
        for (tr in rows) {
            val text = tr.text()
            val periodMatch = Regex("\\b(11\\d{7})\\b").find(text) ?: continue
            val drawNo = periodMatch.groupValues[1]
            val afterPeriod = text.substring(periodMatch.range.last + 1)
            val withoutTime = afterPeriod.replace(Regex("\\b\\d{1,2}:\\d{2}\\b"), " ")
            val twoDigitNums = Regex("\\b\\d{2}\\b").findAll(withoutTime)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..80 }
                .toList()
            if (twoDigitNums.size >= 20) {
                val nums = twoDigitNums.take(20).distinct()
                if (nums.size == 20) {
                    out.add(BingoDraw(drawNo, "", nums))
                }
            }
        }
    }
}