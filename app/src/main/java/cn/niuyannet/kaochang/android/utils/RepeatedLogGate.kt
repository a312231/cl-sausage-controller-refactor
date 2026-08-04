package cn.niuyannet.kaochang.android.utils

class RepeatedLogGate(private val summaryIntervalMs: Long?) {
    data class Decision(
        val stateChanged: Boolean,
        val suppressedCount: Int
    )

    private var lastState: String? = null
    private var lastEmittedAtMs: Long = 0L
    private var suppressedCount: Int = 0

    @Synchronized
    fun markInitial(state: String, nowMs: Long) {
        lastState = state
        lastEmittedAtMs = nowMs
        suppressedCount = 0
    }

    @Synchronized
    fun evaluate(state: String, nowMs: Long): Decision? {
        if (state != lastState) {
            val suppressed = suppressedCount
            lastState = state
            lastEmittedAtMs = nowMs
            suppressedCount = 0
            return Decision(stateChanged = true, suppressedCount = suppressed)
        }

        if (nowMs < lastEmittedAtMs) {
            lastEmittedAtMs = nowMs
        }
        suppressedCount++
        val interval = summaryIntervalMs ?: return null
        if (nowMs - lastEmittedAtMs < interval) {
            return null
        }

        val suppressed = suppressedCount
        lastEmittedAtMs = nowMs
        suppressedCount = 0
        return Decision(stateChanged = false, suppressedCount = suppressed)
    }

    @Synchronized
    fun reset() {
        lastState = null
        lastEmittedAtMs = 0L
        suppressedCount = 0
    }
}
