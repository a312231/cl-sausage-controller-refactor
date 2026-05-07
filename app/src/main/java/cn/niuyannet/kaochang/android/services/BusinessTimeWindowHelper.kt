package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.BusinessTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal object BusinessTimeWindowHelper {
    fun currentTimeString(): String {
        val currentTime = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return dateFormat.format(currentTime.time)
    }

    fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        if (businessTime == null) {
            return false
        }
        val currentSecondOfDay = parseSecondOfDay(currentTimeStr) ?: return false
        return when {
            businessTime.mode.equals("everyday", ignoreCase = true) &&
                businessTime.defaultTime != null &&
                businessTime.defaultTime.size >= 2 -> {
                isTimeInRange(
                    currentSecondOfDay,
                    businessTime.defaultTime[0],
                    businessTime.defaultTime[1]
                )
            }
            businessTime.mode.equals("custom", ignoreCase = true) &&
                businessTime.customTimes != null -> {
                val calendar = Calendar.getInstance()
                val dayOfWeek =
                    if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
                    else calendar.get(Calendar.DAY_OF_WEEK) - 1
                businessTime.customTimes.any { timeItem ->
                    timeItem.day == dayOfWeek &&
                        timeItem.enabled &&
                        timeItem.timeRange != null &&
                        timeItem.timeRange.size >= 2 &&
                        isTimeInRange(currentSecondOfDay, timeItem.timeRange[0], timeItem.timeRange[1])
                }
            }
            else -> false
        }
    }

    private fun isTimeInRange(currentSecondOfDay: Int, startText: String, endText: String): Boolean {
        val start = parseSecondOfDay(startText) ?: return false
        val end = parseSecondOfDay(endText) ?: return false
        return if (start <= end) {
            currentSecondOfDay in start..end
        } else {
            currentSecondOfDay >= start || currentSecondOfDay <= end
        }
    }

    private fun parseSecondOfDay(value: String?): Int? {
        val parts = value?.trim()?.split(":") ?: return null
        if (parts.size !in 2..3) {
            return null
        }
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        return hour * 3600 + minute * 60 + second
    }
}
