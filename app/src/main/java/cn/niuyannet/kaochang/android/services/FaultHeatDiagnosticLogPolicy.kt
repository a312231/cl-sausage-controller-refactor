package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.utils.RepeatedLogGate

internal class FaultHeatDiagnosticLogPolicy(
    summaryIntervalMs: Long = SUMMARY_INTERVAL_MS
) {
    enum class Kind {
        EMIT,
        RECOVERED
    }

    data class Decision(
        val kind: Kind,
        val stateChanged: Boolean,
        val suppressedCount: Int
    )

    private val gate = RepeatedLogGate(summaryIntervalMs)
    private var episodeActive = false

    @Synchronized
    fun evaluate(state: String?, nowMs: Long): Decision? {
        if (state == null) {
            if (!episodeActive) {
                return null
            }
            episodeActive = false
            gate.reset()
            return Decision(Kind.RECOVERED, stateChanged = true, suppressedCount = 0)
        }

        episodeActive = true
        val decision = gate.evaluate(state, nowMs) ?: return null
        return Decision(
            kind = Kind.EMIT,
            stateChanged = decision.stateChanged,
            suppressedCount = decision.suppressedCount
        )
    }

    companion object {
        const val SUMMARY_INTERVAL_MS = 30L * 60L * 1000L
    }
}
