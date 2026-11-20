package com.bingoq.analyzer.network

import com.bingoq.analyzer.model.BingoDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class BingoDataFetcher {
    enum class Status { GREEN, YELLOW, RED }

    data class FetchResult(val status: Status, val draws: List<BingoDraw>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAndVerify(): FetchResult = withContext(Dispatchers.IO) {
        // The previous TLLotto/ShowBingoResult URL was an obsolete/incomplete
        // endpoint (it returned no usable "content" payload). Read the visible
        // draw rows from the two published Bingo result pages instead.
        val today = fetchPage("https://www.pilio.idv.tw/bingobingo/list.asp?auto=1")
        val history = fetchPage("https://www.pilio.idv.tw/bingo/list_history.asp")
        val merged = (today + history)
            .filter { it.drawNo.isNotBlank() && it.numbers.size == 20 }
            .distinctBy { it.drawNo }
            .sortedByDescending { it.drawNo }
            .take(1000)

        when {
            merged.isEmpty() -> FetchResult(Status.RED, emptyList())
            else -> FetchResult(Status.YELLOW, merged)
        }
    }

    private fun fetchPage(url: String): List<BingoDraw> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                .timeout(15000)
                .ignoreHttpErrors(true)
                .get()

            val bodyText = doc.body()?.text().orEmpty()
            parseDraws(bodyText)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Parse the page's visible text rather than assuming a fixed table-column
    // layout. The old parser incorrectly assumed drawNo was td[0] and all
    // twenty balls were in td[1], which is not guaranteed by the site markup.
    private fun parseDraws(text: String): List<BingoDraw> {
        val periodRegex = Regex("期別\\s*[:：]?\\s*(\\d{8,9})")
        val matches = periodRegex.findAll(text).toList()
        val draws = mutableListOf<BingoDraw>()

        for (index in matches.indices) {
            val match = matches[index]
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val segment = text.substring(match.range.last + 1, end)

            val numbers = Regex("(?<!\\d)\\d{1,2}(?!\\d)")
                .findAll(segment)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..80 }
                .take(20)
                .toList()

            if (numbers.size == 20 && numbers.toSet().size == 20) {
                val time = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b")
                    .find(segment)?.value.orEmpty()
                draws.add(BingoDraw(match.groupValues[1], time, numbers))
            }
        }
        return draws.distinctBy { it.drawNo }
    }
}
