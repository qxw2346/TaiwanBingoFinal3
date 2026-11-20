package com.bingoq.analyzer.util
import kotlinx.coroutines.*
import java.util.*
class BingoCountdownManager(
    private val onTick: (remainingMinutes: Long, remainingSeconds: Long, nextDrawNo: String) -> Unit,
    private val onDrawTimeReached: () -> Unit
) {
    private var timerJob: Job? = null
    fun start(scope: CoroutineScope) {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            var lastTriggerMinute = -1
            while (isActive) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val minute = cal.get(Calendar.MINUTE)
                val second = cal.get(Calendar.SECOND)
                val isOpenTime = (hour > 7 || (hour == 7 && minute >= 5)) && (hour < 23 || (hour == 23 && minute <= 55))
                val nextMinuteMod = 5 - (minute % 5)
                val remainingSecTotal = if (nextMinuteMod == 5 && second == 0) 0 else (nextMinuteMod * 60 - second)
                val remMin = (remainingSecTotal / 60).toLong()
                val remSec = (remainingSecTotal % 60).toLong()
                val drawIndex = if (isOpenTime) {
                    val passedMin = (hour - 7) * 60 + minute - 5
                    (passedMin / 5) + 1
                } else 0
                val drawLabel = if (isOpenTime) "第 %03d 期".format(drawIndex + 1) else "未開盤"
                withContext(Dispatchers.Main) {
                    onTick(remMin, remSec, drawLabel)
                }
                if (remainingSecTotal <= 1 && minute != lastTriggerMinute && isOpenTime) {
                    lastTriggerMinute = minute
                    withContext(Dispatchers.Main) {
                        onDrawTimeReached()
                    }
                }
                delay(1000)
            }
        }
    }
    fun stop() {
        timerJob?.cancel()
    }
}