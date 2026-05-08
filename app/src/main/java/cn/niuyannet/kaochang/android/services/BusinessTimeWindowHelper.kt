package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.BusinessTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal object BusinessTimeWindowHelper {
    private const val SECONDS_PER_DAY = 24 * 60 * 60

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
                val dayOfWeek = currentDayOfWeek()
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

    fun remainingMillisUntilClose(
        currentTimeStr: String,
        businessTime: BusinessTime?,
        dayOfWeek: Int = currentDayOfWeek()
    ): Long {
        val currentSecondOfDay = parseSecondOfDay(currentTimeStr) ?: return -1
        val timeRange = resolveTimeRange(businessTime, dayOfWeek) ?: return -1
        parseSecondOfDay(timeRange.first) ?: return -1
        val end = parseSecondOfDay(timeRange.second) ?: return -1

        val remainingSeconds = if (currentSecondOfDay <= end) {
            end - currentSecondOfDay
        } else {
            SECONDS_PER_DAY - currentSecondOfDay + end
        }
        return remainingSeconds * 1000L
    }

    fun isBeforeCloseWindow(
        currentTimeStr: String,
        businessTime: BusinessTime?,
        minute: Int,
        dayOfWeek: Int = currentDayOfWeek()
    ): Boolean {
        val remainingMillis = remainingMillisUntilClose(currentTimeStr, businessTime, dayOfWeek)
        if (remainingMillis < 0) {
            return false
        }
        val beforeCloseMillis = minute.coerceAtLeast(0) * 60L * 1000L
        return remainingMillis <= beforeCloseMillis
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

    private fun currentDayOfWeek(): Int {
        val calendar = Calendar.getInstance()
        return if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            7
        } else {
            calendar.get(Calendar.DAY_OF_WEEK) - 1
        }
    }

    private fun resolveTimeRange(businessTime: BusinessTime?, dayOfWeek: Int): Pair<String, String>? {
        if (businessTime == null) {
            return null
        }
        return when {
            businessTime.mode.equals("everyday", ignoreCase = true) &&
                businessTime.defaultTime != null &&
                businessTime.defaultTime.size >= 2 -> {
                businessTime.defaultTime[0] to businessTime.defaultTime[1]
            }
            businessTime.mode.equals("custom", ignoreCase = true) &&
                businessTime.customTimes != null -> {
                businessTime.customTimes
                    .firstOrNull { timeItem ->
                        timeItem.day == dayOfWeek &&
                            timeItem.enabled &&
                            timeItem.timeRange != null &&
                            timeItem.timeRange.size >= 2
                    }
                    ?.let { timeItem -> timeItem.timeRange[0] to timeItem.timeRange[1] }
            }
            else -> null
        }
    }
}
