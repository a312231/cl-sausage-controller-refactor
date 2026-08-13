package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.SellCandidateInput
import cn.niuyannet.kaochang.scheduler.SellCandidateStrategy

data class SchedulerShadowMetrics(
    val comparisonCount: Long,
    val algorithmDifferenceCount: Long,
    val baselineDeviationCount: Long,
    val exceptionCount: Long
)

class SchedulerShadowObserver(
    private val strategy: SellCandidateStrategy,
    private val sessionId: String,
    private val logSink: (String) -> Unit
) {
    private val monitor = Any()
    private var comparisonCount = 0L
    private var algorithmDifferenceCount = 0L
    private var baselineDeviationCount = 0L
    private var exceptionCount = 0L
    private var detailLogCount = 0

    fun observe(legacyPosition: Int?, input: SellCandidateInput) {
        try {
            val oraclePosition = LegacySellCandidateOracle.selectPosition(input)
            val strategyPosition = strategy.selectPosition(input)
            val baselineDeviation = legacyPosition != oraclePosition
            val algorithmDifference = oraclePosition != strategyPosition
            val emission = synchronized(monitor) {
                comparisonCount++
                if (algorithmDifference) algorithmDifferenceCount++
                if (baselineDeviation) baselineDeviationCount++
                val emitDetail = (algorithmDifference || baselineDeviation) && detailLogCount < DETAIL_LOG_LIMIT
                if (emitDetail) detailLogCount++
                Emission(
                    metrics = metricsLocked(),
                    emitStart = comparisonCount == 1L,
                    emitSample = comparisonCount == 1L || comparisonCount % SAMPLE_INTERVAL == 0L,
                    emitDetail = emitDetail
                )
            }
            if (emission.emitStart) {
                emitSafely("shadow_start session=$sessionId strategy=${strategy.id.value}")
            }
            if (emission.emitDetail) {
                emitSafely(
                    "shadow_detail session=$sessionId strategy=${strategy.id.value} " +
                        "legacy=${legacyPosition ?: "none"} oracle=${oraclePosition ?: "none"} " +
                        "shadow=${strategyPosition ?: "none"} " +
                        "reason=${reasonCode(baselineDeviation, algorithmDifference)}"
                )
            }
            if (emission.emitSample) emitSample(emission.metrics)
        } catch (_: Throwable) {
            recordException("observe_failure")
        }
    }

    fun recordException(reasonCode: String) {
        val emission = synchronized(monitor) {
            exceptionCount++
            val emitDetail = detailLogCount < DETAIL_LOG_LIMIT
            if (emitDetail) detailLogCount++
            ExceptionEmission(emitDetail)
        }
        if (emission.emitDetail) {
            emitSafely("shadow_detail session=$sessionId strategy=${strategy.id.value} reason=$reasonCode")
        }
    }

    fun metricsSnapshot(): SchedulerShadowMetrics = synchronized(monitor) { metricsLocked() }

    private fun emitSample(metrics: SchedulerShadowMetrics) {
        emitSafely(
            "shadow_sample session=$sessionId strategy=${strategy.id.value} " +
                "comparisons=${metrics.comparisonCount} algorithmDiff=${metrics.algorithmDifferenceCount} " +
                "baselineDeviation=${metrics.baselineDeviationCount} exceptions=${metrics.exceptionCount}"
        )
    }

    private fun emitSafely(message: String) {
        try {
            logSink(message)
        } catch (_: Throwable) {
            // 影子诊断日志失败不得进入订单或机械动作路径。
        }
    }

    private fun metricsLocked() = SchedulerShadowMetrics(
        comparisonCount = comparisonCount,
        algorithmDifferenceCount = algorithmDifferenceCount,
        baselineDeviationCount = baselineDeviationCount,
        exceptionCount = exceptionCount
    )

    private fun reasonCode(baselineDeviation: Boolean, algorithmDifference: Boolean): String = when {
        baselineDeviation && algorithmDifference -> "baseline_and_algorithm_difference"
        baselineDeviation -> "baseline_deviation"
        else -> "algorithm_difference"
    }

    private data class Emission(
        val metrics: SchedulerShadowMetrics,
        val emitStart: Boolean,
        val emitSample: Boolean,
        val emitDetail: Boolean
    )

    private data class ExceptionEmission(
        val emitDetail: Boolean
    )

    private companion object {
        const val SAMPLE_INTERVAL = 25L
        const val DETAIL_LOG_LIMIT = 20
    }
}

class SchedulerShadowRunner(private val observer: SchedulerShadowObserver) {
    fun observeSafely(legacyPosition: Int?, inputProvider: () -> SellCandidateInput) {
        try {
            observer.observe(legacyPosition, inputProvider())
        } catch (_: Throwable) {
            observer.recordException("input_mapping_failure")
        }
    }
}

object LegacyCandidateShadowGate {
    fun <T> observeAndKeep(enabled: Boolean, legacyCandidate: T?, observer: () -> Unit): T? {
        if (enabled) {
            try {
                observer()
            } catch (_: Throwable) {
                // 影子路径的任何错误都不能替换旧候选或中断订单路径。
            }
        }
        return legacyCandidate
    }
}
