package cn.niuyannet.kaochang.android.services

internal data class HighTrafficSupplementTrackingState(
    val pitCount: Int,
    val lastFillPitTime: Long
)

internal fun recordHighTrafficSupplementLaunch(
    previous: HighTrafficSupplementTrackingState,
    filledCount: Int,
    now: Long
): HighTrafficSupplementTrackingState {
    if (filledCount <= 0) {
        return previous
    }
    return HighTrafficSupplementTrackingState(
        pitCount = filledCount,
        lastFillPitTime = now
    )
}

internal fun resetHighTrafficSupplementTracking(): HighTrafficSupplementTrackingState =
    HighTrafficSupplementTrackingState(
        pitCount = 0,
        lastFillPitTime = 0L
    )

internal fun shouldSwitchHighToLowByTimeout(
    lastFillPitTime: Long,
    now: Long,
    timeThresholdLowMinutes: Int
): Boolean {
    if (lastFillPitTime <= 0L || timeThresholdLowMinutes <= 0) {
        return false
    }
    val thresholdMs = timeThresholdLowMinutes * 60 * 1000L
    return now - lastFillPitTime > thresholdMs
}
