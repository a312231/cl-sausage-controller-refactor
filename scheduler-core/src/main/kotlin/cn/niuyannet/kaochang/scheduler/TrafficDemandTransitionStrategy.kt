package cn.niuyannet.kaochang.scheduler

enum class TrafficTransitionRequest {
    NONE,
    LOW_TO_HIGH,
    HIGH_TO_LOW
}

enum class TrafficTransitionReason {
    NONE,
    EFFECTIVE_LOW_FRONT_DEMAND,
    BEFORE_BUSINESS_CLOSE,
    HIGH_MODE_INACTIVITY_TIMEOUT
}

enum class TrafficTransitionOutcome(
    val request: TrafficTransitionRequest,
    val reason: TrafficTransitionReason
) {
    NONE(TrafficTransitionRequest.NONE, TrafficTransitionReason.NONE),
    LOW_TO_HIGH_EFFECTIVE_DEMAND(
        TrafficTransitionRequest.LOW_TO_HIGH,
        TrafficTransitionReason.EFFECTIVE_LOW_FRONT_DEMAND
    ),
    HIGH_TO_LOW_BEFORE_CLOSE(
        TrafficTransitionRequest.HIGH_TO_LOW,
        TrafficTransitionReason.BEFORE_BUSINESS_CLOSE
    ),
    HIGH_TO_LOW_INACTIVITY_TIMEOUT(
        TrafficTransitionRequest.HIGH_TO_LOW,
        TrafficTransitionReason.HIGH_MODE_INACTIVITY_TIMEOUT
    )
}

data class LowToHighDemandInput(
    val effectiveEmptyFrontCount: Int,
    val hasCompleteActiveLowHeatBatch: Boolean
)

data class HighToLowDemandInput(
    val remainingUntilCloseMs: Long,
    val beforeCloseMinutes: Int,
    val nowMs: Long,
    val lastHighSupplementTimeMs: Long?,
    val inactivityThresholdMinutes: Int
)

interface TrafficDemandTransitionStrategy {
    val id: StrategyId

    fun planLowToHigh(input: LowToHighDemandInput): TrafficTransitionOutcome

    fun planHighToLow(input: HighToLowDemandInput): TrafficTransitionOutcome
}

class LegacyDemandTimeoutTransitionStrategy : TrafficDemandTransitionStrategy {
    override val id: StrategyId = StrategyId("legacy-demand-timeout-transition-v1")

    override fun planLowToHigh(input: LowToHighDemandInput): TrafficTransitionOutcome {
        if (input.effectiveEmptyFrontCount !in PHYSICAL_LOW_FRONT_EMPTY_RANGE) {
            return TrafficTransitionOutcome.NONE
        }
        return if (
            input.effectiveEmptyFrontCount > LOW_TO_HIGH_EMPTY_THRESHOLD &&
            input.hasCompleteActiveLowHeatBatch
        ) {
            TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND
        } else {
            TrafficTransitionOutcome.NONE
        }
    }

    override fun planHighToLow(input: HighToLowDemandInput): TrafficTransitionOutcome {
        val beforeCloseThresholdMs = (input.beforeCloseMinutes * 60) * 1000L
        if (
            input.remainingUntilCloseMs > 0L &&
            input.remainingUntilCloseMs < beforeCloseThresholdMs
        ) {
            return TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE
        }

        val lastSupplementTimeMs = input.lastHighSupplementTimeMs
            ?: return TrafficTransitionOutcome.NONE
        if (input.inactivityThresholdMinutes <= 0) {
            return TrafficTransitionOutcome.NONE
        }
        val thresholdMs = (input.inactivityThresholdMinutes * 60) * 1000L
        return if (input.nowMs - lastSupplementTimeMs > thresholdMs) {
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT
        } else {
            TrafficTransitionOutcome.NONE
        }
    }

    private companion object {
        val PHYSICAL_LOW_FRONT_EMPTY_RANGE = 0..9
        const val LOW_TO_HIGH_EMPTY_THRESHOLD = 7
    }
}
