package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileRuntime
import cn.niuyannet.kaochang.scheduler.SupplementTasteMixInput

internal object SupplementTasteMixPlanner {

    private val strategy = SchedulerProfileRuntime.activeProfile.tasteMixStrategy

    data class Assignment(
        val positionSn: Int,
        val tasteCode: String
    )

    data class Plan(
        val targetCountByTaste: Map<String, Int>,
        val occupiedCountByTaste: Map<String, Int>,
        val assignments: List<Assignment>,
        val stockLimited: Boolean
    )

    fun plan(
        frontCapacity: Int,
        targetCountByTaste: Map<String, Int>,
        availableStockByTaste: Map<String, Int>,
        occupiedTasteByPosition: Map<Int, String>,
        assignablePositions: List<Int>
    ): Plan {
        val result = strategy.plan(
            SupplementTasteMixInput(
                frontCapacity = frontCapacity,
                targetCountByTaste = targetCountByTaste,
                availableStockByTaste = availableStockByTaste,
                occupiedTasteByPosition = occupiedTasteByPosition,
                assignablePositions = assignablePositions
            )
        )

        return Plan(
            targetCountByTaste = result.targetCountByTaste,
            occupiedCountByTaste = result.occupiedCountByTaste,
            assignments = result.assignments.map { Assignment(it.positionSn, it.tasteCode) },
            stockLimited = result.stockLimited
        )
    }

    fun committedReturnTasteByPosition(
        intent: SupplementBatchIntent?,
        returnPositionRange: IntRange
    ): Map<Int, String> {
        return intent
            ?.targets
            ?.asSequence()
            ?.filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade }
            ?.mapNotNull { target ->
                val returnPosition = target.returnPositionSn?.takeIf { it in returnPositionRange }
                    ?: return@mapNotNull null
                val tasteCode = target.tasteCode?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                returnPosition to tasteCode
            }
            ?.distinctBy { it.first }
            ?.toMap()
            .orEmpty()
    }

}
