package cn.niuyannet.kaochang.scheduler

import java.util.Collections

enum class LowHeatAction(val requestedCount: Int) {
    NONE(0),
    START_13_15(3),
    START_16_18(3),
    STOP_ZONE_2(0)
}

data class LowHeatBatchInput(
    val frontEmptyCount: Int,
    val firstHeatEmptyCount: Int,
    val secondHeatEmptyCount: Int
)

data class LowHeatBatchPlan(val action: LowHeatAction) {
    val requestedCount: Int
        get() = action.requestedCount
}

enum class HighHeatAction(val requestedCount: Int) {
    NONE(0),
    START_25_33(9)
}

data class HighHeatSteadyInput(
    val frontEmptyCount: Int,
    val heatEmptyCount: Int
)

data class HighHeatBatchPlan(val action: HighHeatAction) {
    val shouldStart: Boolean
        get() = action == HighHeatAction.START_25_33

    val requestedCount: Int
        get() = action.requestedCount
}

class SupplementPositionInput(
    val requestedCount: Int,
    targetCandidates: List<Int>,
    returnCandidates: List<Int>
) {
    val targetCandidates: List<Int> =
        Collections.unmodifiableList(ArrayList(targetCandidates))
    val returnCandidates: List<Int> =
        Collections.unmodifiableList(ArrayList(returnCandidates))
}

data class SupplementPositionPair(
    val targetPosition: Int,
    val returnPosition: Int
)

class SupplementPositionPlan(pairs: List<SupplementPositionPair>) {
    val pairs: List<SupplementPositionPair> =
        Collections.unmodifiableList(ArrayList(pairs))
}

interface SupplementBatchHeatPositionStrategy {
    val id: StrategyId

    fun planLowHeat(input: LowHeatBatchInput): LowHeatBatchPlan

    fun planHighHeatSteady(input: HighHeatSteadyInput): HighHeatBatchPlan

    fun planPositions(input: SupplementPositionInput): SupplementPositionPlan
}

class LegacyFixedBatchHeatPositionStrategy : SupplementBatchHeatPositionStrategy {
    override val id: StrategyId = StrategyId("legacy-fixed-batch-heat-position-v1")

    override fun planLowHeat(input: LowHeatBatchInput): LowHeatBatchPlan {
        val combinedHeatEmptyCount = input.firstHeatEmptyCount + input.secondHeatEmptyCount
        val action = if (input.frontEmptyCount >= LOW_BATCH_SIZE) {
            when {
                input.firstHeatEmptyCount == LOW_BATCH_SIZE &&
                    input.secondHeatEmptyCount == LOW_BATCH_SIZE -> LowHeatAction.START_13_15

                input.firstHeatEmptyCount == LOW_BATCH_SIZE &&
                    input.frontEmptyCount >= LOW_DOUBLE_BATCH_SIZE &&
                    input.secondHeatEmptyCount == 0 -> LowHeatAction.START_13_15

                input.secondHeatEmptyCount == LOW_BATCH_SIZE &&
                    input.frontEmptyCount >= LOW_DOUBLE_BATCH_SIZE &&
                    input.firstHeatEmptyCount == 0 -> LowHeatAction.START_16_18

                else -> LowHeatAction.NONE
            }
        } else if (combinedHeatEmptyCount == LOW_DOUBLE_BATCH_SIZE) {
            LowHeatAction.STOP_ZONE_2
        } else {
            LowHeatAction.NONE
        }
        return LowHeatBatchPlan(action)
    }

    override fun planHighHeatSteady(input: HighHeatSteadyInput): HighHeatBatchPlan {
        val action = if (
            input.frontEmptyCount >= HIGH_BATCH_SIZE &&
            input.heatEmptyCount == HIGH_BATCH_SIZE
        ) {
            HighHeatAction.START_25_33
        } else {
            HighHeatAction.NONE
        }
        return HighHeatBatchPlan(action)
    }

    override fun planPositions(input: SupplementPositionInput): SupplementPositionPlan {
        if (
            input.requestedCount <= 0 ||
            input.targetCandidates.any { it <= 0 } ||
            input.returnCandidates.any { it <= 0 } ||
            input.targetCandidates.toSet().size != input.targetCandidates.size ||
            input.returnCandidates.toSet().size != input.returnCandidates.size
        ) {
            return SupplementPositionPlan(emptyList())
        }

        val selectedCount = minOf(
            input.requestedCount,
            input.targetCandidates.size,
            input.returnCandidates.size
        )
        return SupplementPositionPlan(
            List(selectedCount) { index ->
                SupplementPositionPair(
                    targetPosition = input.targetCandidates[index],
                    returnPosition = input.returnCandidates[index]
                )
            }
        )
    }

    private companion object {
        const val LOW_BATCH_SIZE = 3
        const val LOW_DOUBLE_BATCH_SIZE = 6
        const val HIGH_BATCH_SIZE = 9
    }
}
