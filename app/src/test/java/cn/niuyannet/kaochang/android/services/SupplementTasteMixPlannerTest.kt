package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileTestBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SupplementTasteMixPlannerTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun pinSchedulerProfile() = SchedulerProfileTestBootstrap.pinLegacyA()
    }

    @Test
    fun plan_shouldFillNewTasteDeficitFromNextBatch() {
        val plan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = mapOf("A" to 20, "F" to 20),
            occupiedTasteByPosition = (1..6).associateWith { "A" },
            assignablePositions = listOf(7, 8, 9)
        )

        assertEquals(listOf("F", "F", "F"), plan.assignments.map { it.tasteCode })
    }

    @Test
    fun plan_shouldAllocateByRelativeThenAbsoluteDeficit() {
        val occupied = mapOf(1 to "A", 2 to "A", 3 to "A", 4 to "A", 5 to "F", 6 to "F")

        val plan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = mapOf("A" to 20, "F" to 20),
            occupiedTasteByPosition = occupied,
            assignablePositions = listOf(7, 8, 9)
        )

        assertEquals(mapOf("A" to 2, "F" to 1), plan.assignments.groupingBy { it.tasteCode }.eachCount())
    }

    @Test
    fun plan_shouldConvergeAcrossTwoLowHeatBatches() {
        val first = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = mapOf("A" to 20, "F" to 20),
            occupiedTasteByPosition = mapOf(1 to "A", 2 to "A", 3 to "A"),
            assignablePositions = listOf(4, 5, 6)
        )
        val occupiedAfterFirst = mapOf(1 to "A", 2 to "A", 3 to "A") +
            first.assignments.associate { it.positionSn to it.tasteCode }
        val remainingStock = mapOf(
            "A" to 20 - first.assignments.count { it.tasteCode == "A" },
            "F" to 20 - first.assignments.count { it.tasteCode == "F" }
        )

        val second = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = remainingStock,
            occupiedTasteByPosition = occupiedAfterFirst,
            assignablePositions = listOf(7, 8, 9)
        )

        val allAssignments = first.assignments + second.assignments
        assertEquals(mapOf("A" to 3, "F" to 3), allAssignments.groupingBy { it.tasteCode }.eachCount())
    }

    @Test
    fun plan_shouldTreatUncommittedOldTasteAsAssignable() {
        val plan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = mapOf("A" to 20, "F" to 20),
            occupiedTasteByPosition = (1..6).associateWith { "A" },
            assignablePositions = listOf(7)
        )

        assertEquals(listOf(SupplementTasteMixPlanner.Assignment(7, "F")), plan.assignments)
    }

    @Test
    fun plan_shouldNotExceedAvailableStock() {
        val plan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 6, "F" to 3),
            availableStockByTaste = mapOf("A" to 2, "F" to 1),
            occupiedTasteByPosition = emptyMap(),
            assignablePositions = (1..6).toList()
        )

        assertEquals(3, plan.assignments.size)
        assertEquals(mapOf("A" to 2, "F" to 1), plan.assignments.groupingBy { it.tasteCode }.eachCount())
        assertTrue(plan.stockLimited)
    }

    @Test
    fun plan_shouldHandleSingleTasteAndNoStock() {
        val singleTaste = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = mapOf("A" to 9),
            availableStockByTaste = mapOf("A" to 2),
            occupiedTasteByPosition = emptyMap(),
            assignablePositions = listOf(1, 2, 3)
        )
        val noStock = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = emptyMap(),
            availableStockByTaste = emptyMap(),
            occupiedTasteByPosition = emptyMap(),
            assignablePositions = listOf(1, 2, 3)
        )

        assertEquals(listOf("A", "A"), singleTaste.assignments.map { it.tasteCode })
        assertTrue(singleTaste.stockLimited)
        assertTrue(noStock.assignments.isEmpty())
    }

    @Test
    fun plan_shouldUseTasteCodeForStableFinalTieBreak() {
        val plan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = linkedMapOf("F" to 3, "A" to 3, "C" to 3),
            availableStockByTaste = linkedMapOf("F" to 3, "A" to 3, "C" to 3),
            occupiedTasteByPosition = emptyMap(),
            assignablePositions = listOf(1, 2, 3)
        )

        assertEquals(listOf("A", "C", "F"), plan.assignments.map { it.tasteCode })
    }

    @Test
    fun committedReturnTasteByPosition_shouldIgnoreCancelledAndSupplyDegradedTargets() {
        val intent = SupplementBatchIntent(
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 13, tasteCode = "A", returnPositionSn = 1),
                SupplementBatchIntentTarget(
                    positionSn = 14,
                    tasteCode = "F",
                    returnPositionSn = 2,
                    cancelledByFaultTimeout = true
                ),
                SupplementBatchIntentTarget(
                    positionSn = 15,
                    tasteCode = "F",
                    returnPositionSn = 3,
                    skippedBySupplyDegrade = true
                )
            )
        )

        val committed = SupplementTasteMixPlanner.committedReturnTasteByPosition(intent, 1..9)

        assertEquals(mapOf(1 to "A"), committed)
    }
}
