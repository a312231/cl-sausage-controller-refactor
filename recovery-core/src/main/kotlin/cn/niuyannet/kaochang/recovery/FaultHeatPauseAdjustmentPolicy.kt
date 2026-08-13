package cn.niuyannet.kaochang.recovery

import java.util.Collections
import java.util.TreeMap

data class FaultHeatPanSnapshot(
    val positionSn: Int,
    val hasSausage: Boolean,
    val startTimeMs: Long,
    val holdingTimeMs: Long,
    val closeTimeMs: Long
)

class FaultHeatPauseAdjustmentInput(
    val nowMs: Long,
    val pauseStartedAtMs: Long,
    val bakingPauseAbortMs: Long,
    val pauseAnomalyMs: Long,
    pans: List<FaultHeatPanSnapshot>
) {
    init {
        require(bakingPauseAbortMs > 0L) { "bakingPauseAbortMs must be positive" }
        require(pauseAnomalyMs > 0L) { "pauseAnomalyMs must be positive" }
    }

    val pans: List<FaultHeatPanSnapshot> = immutableList(pans)
}

enum class FaultHeatPauseAlert {
    CLOCK_REGRESSED,
    ANOMALY_TOO_LONG
}

enum class FaultHeatPanAdjustmentAction {
    SHIFT_BAKING,
    DISCARD_BAKING,
    KEEP_HOLDING,
    DISCARD_HOLDING,
    IGNORE
}

data class FaultHeatPanAdjustmentDecision(
    val inputIndex: Int,
    val positionSn: Int,
    val action: FaultHeatPanAdjustmentAction,
    val decisionPauseOverlapMs: Long,
    val targetStartTimeMs: Long?
)

class FaultHeatPauseAdjustment private constructor(
    val pauseDurationMs: Long,
    panDecisions: List<FaultHeatPanAdjustmentDecision>,
    val alert: FaultHeatPauseAlert?,
    val failClosed: Boolean
) {
    val panDecisions: List<FaultHeatPanAdjustmentDecision> = immutableList(panDecisions)

    val shiftedPositions: List<Int> = immutableList(
        panDecisions
            .filter { it.action == FaultHeatPanAdjustmentAction.SHIFT_BAKING }
            .map { it.positionSn }
            .sorted()
    )

    val holdingPositions: List<Int> = immutableList(
        panDecisions
            .filter {
                it.action == FaultHeatPanAdjustmentAction.KEEP_HOLDING ||
                    it.action == FaultHeatPanAdjustmentAction.DISCARD_HOLDING
            }
            .map { it.positionSn }
            .sorted()
    )

    val pendingDiscardPositions: List<Int> = immutableList(
        panDecisions
            .filter {
                it.action == FaultHeatPanAdjustmentAction.DISCARD_BAKING ||
                    it.action == FaultHeatPanAdjustmentAction.DISCARD_HOLDING
            }
            .map { it.positionSn }
            .distinct()
            .sorted()
    )

    val targetStartTimes: Map<Int, Long> = immutableSortedMap(
        linkedMapOf<Int, Long>().apply {
            panDecisions.forEach { decision ->
                decision.targetStartTimeMs?.let { target ->
                    put(decision.positionSn, target)
                }
            }
        }
    )

    companion object {
        @JvmSynthetic
        internal fun create(
            pauseDurationMs: Long,
            panDecisions: List<FaultHeatPanAdjustmentDecision>,
            alert: FaultHeatPauseAlert?,
            failClosed: Boolean
        ): FaultHeatPauseAdjustment = FaultHeatPauseAdjustment(
            pauseDurationMs = pauseDurationMs,
            panDecisions = panDecisions,
            alert = alert,
            failClosed = failClosed
        )
    }
}

object FaultHeatPauseAdjustmentPolicy {
    fun calculate(input: FaultHeatPauseAdjustmentInput): FaultHeatPauseAdjustment {
        val pauseDurationMs = input.nowMs - input.pauseStartedAtMs
        val hasAnySausageInPhysicalPans = input.pans.any {
            it.positionSn in PHYSICAL_PAN_RANGE && it.hasSausage
        }
        val globalAlert = when {
            input.nowMs < input.pauseStartedAtMs -> FaultHeatPauseAlert.CLOCK_REGRESSED
            hasAnySausageInPhysicalPans && pauseDurationMs > input.pauseAnomalyMs ->
                FaultHeatPauseAlert.ANOMALY_TOO_LONG
            else -> null
        }
        val hasFutureBakingStart = input.pans.any {
            isBaking(it) && it.startTimeMs > input.nowMs
        }
        val failClosed = globalAlert != null || hasFutureBakingStart
        val alert = globalAlert ?: if (hasFutureBakingStart) {
            FaultHeatPauseAlert.CLOCK_REGRESSED
        } else {
            null
        }
        val decisions = input.pans.mapIndexed { index, pan ->
            decidePan(index, pan, input, failClosed)
        }
        return FaultHeatPauseAdjustment.create(
            pauseDurationMs = pauseDurationMs,
            panDecisions = decisions,
            alert = alert,
            failClosed = failClosed
        )
    }

    private fun decidePan(
        inputIndex: Int,
        pan: FaultHeatPanSnapshot,
        input: FaultHeatPauseAdjustmentInput,
        failClosed: Boolean
    ): FaultHeatPanAdjustmentDecision {
        val overlapMs = (input.nowMs - maxOf(input.pauseStartedAtMs, pan.startTimeMs))
            .coerceAtLeast(0L)
        val action: FaultHeatPanAdjustmentAction
        val targetStartTimeMs: Long?
        when {
            isBaking(pan) && (failClosed || overlapMs > input.bakingPauseAbortMs) -> {
                action = FaultHeatPanAdjustmentAction.DISCARD_BAKING
                targetStartTimeMs = if (failClosed) null else pan.startTimeMs
            }
            isBaking(pan) -> {
                action = FaultHeatPanAdjustmentAction.SHIFT_BAKING
                targetStartTimeMs = pan.startTimeMs + overlapMs
            }
            isHolding(pan) && pan.closeTimeMs > 0L &&
                input.nowMs - pan.holdingTimeMs > pan.closeTimeMs -> {
                action = FaultHeatPanAdjustmentAction.DISCARD_HOLDING
                targetStartTimeMs = null
            }
            isHolding(pan) -> {
                action = FaultHeatPanAdjustmentAction.KEEP_HOLDING
                targetStartTimeMs = null
            }
            else -> {
                action = FaultHeatPanAdjustmentAction.IGNORE
                targetStartTimeMs = null
            }
        }
        return FaultHeatPanAdjustmentDecision(
            inputIndex = inputIndex,
            positionSn = pan.positionSn,
            action = action,
            decisionPauseOverlapMs = overlapMs,
            targetStartTimeMs = targetStartTimeMs
        )
    }

    private fun isBaking(pan: FaultHeatPanSnapshot): Boolean =
        pan.hasSausage && pan.startTimeMs > 0L && pan.holdingTimeMs <= 0L

    private fun isHolding(pan: FaultHeatPanSnapshot): Boolean =
        pan.hasSausage && pan.holdingTimeMs > 0L

    private val PHYSICAL_PAN_RANGE = 1..33
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K : Comparable<K>, V> immutableSortedMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(TreeMap(values))
