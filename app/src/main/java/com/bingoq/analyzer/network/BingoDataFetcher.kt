package com.bingoq.analyzer.network
import com.bingoq.analyzer.model.BingoDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    suspend fun fetchAndVerify(): FetchResult = withContext(Dispatchers.IO) {
        val list1 = fetchSourceOfficial()
        val list2 = fetchSourceAuto()
        if (list1.isNotEmpty() && list2.isNotEmpty()) {
            val draw1 = list1.first()
            val matchInList2 = list2.find { it.drawNo == draw1.drawNo }
            if (matchInList2 != null && matchInList2.numbers.toSet() == draw1.numbers.toSet()) {
                return@withContext FetchResult(Status.GREEN, list1)
            }
            return@withContext FetchResult(Status.YELLOW, list1)
        }
        if (list1.isNotEmpty()) {
            return@withContext FetchResult(Status.YELLOW, list1)
        }
        if (list2.isNotEmpty()) {
            return@withContext FetchResult(Status.YELLOW, list2)
        }
        FetchResult(Status.RED, emptyList())
    }
    private fun fetchSourceOfficial(): List<BingoDraw> {
        return try {
            val url = "https://api.taiwanlottery.com/TLLotto/ShowBingoResult?period="
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val result = mutableListOf<BingoDraw>()
            val json = JSONObject(body)
            if (json.has("content")) {
                val arr = json.getJSONArray("content")
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val drawNo = item.optString("drawTerm")
                    val time = item.optString("dDate")
                    val numsStr = item.optString("openShowOrder")
                    val nums = numsStr.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..80 }
                    if (nums.size == 20) {
                        result.add(BingoDraw(drawNo, time, nums))
                    }
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
    private fun fetchSourceAuto(): List<BingoDraw> {
        return try {
            val url = "https://www.pilio.idv.tw/bingobingo/list.asp"
            val doc = Jsoup.connect(url).timeout(6000).get()
            val rows = doc.select("table tr")
            val list = mutableListOf<BingoDraw>()
            for (tr in rows) {
                val text = tr.text()
                if (text.contains("期") && text.contains(",")) {
                    val elements = tr.select("td")
                    if (elements.size >= 2) {
                        val drawNo = elements[0].text().replace(Regex("[^0-9]"), "")
                        val nums = elements[1].text().split(Regex("[,\\s]+"))
                            .mapNotNull { it.trim().toIntOrNull() }
                            .filter { it in 1..80 }
                        if (drawNo.isNotEmpty() && nums.size == 20) {
                            list.add(BingoDraw(drawNo, "", nums))
                        }
                    }
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}