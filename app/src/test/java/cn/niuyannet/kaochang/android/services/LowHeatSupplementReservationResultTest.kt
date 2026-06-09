package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowHeatSupplementReservationResultTest {

    @Test
    fun applyResult_shouldReserveAllFrontPansWhenTargetsSucceeded() {
        val fronts = (1..3).map { frontPan(it) }
        val targets = (13..15).map { position ->
            KaoChangOperate.SupplementTarget(targetPan(position, hasSausage = true), "C")
        }

        val result = LowCurrentTrafficMode.applyLowHeatSupplementReservationResult(
            frontPans = fronts,
            supplementTargets = targets,
            activeLowHeatIntentTargetPositions = emptySet(),
            movedCount = 3
        )

        assertEquals(listOf(1, 2, 3), result.reservedFrontPositions)
        assertEquals(3, result.actualSuccessCount)
        assertTrue(fronts.all { it.isHasGrilling })
        assertTrue(targets.all { it.kaoPan.taste?.tasteCode == "C" })
    }

    @Test
    fun applyResult_shouldClearFailedTargetsWhenNoActiveIntentProtectsThem() {
        val fronts = (1..3).map { frontPan(it).apply { isHasGrilling = true } }
        val targets = (13..15).map { position ->
            KaoChangOperate.SupplementTarget(targetPan(position, hasSausage = false), "C")
        }

        val result = LowCurrentTrafficMode.applyLowHeatSupplementReservationResult(
            frontPans = fronts,
            supplementTargets = targets,
            activeLowHeatIntentTargetPositions = emptySet(),
            movedCount = 0
        )

        assertEquals(listOf(1, 2, 3), result.clearedFrontPositions)
        assertEquals(listOf(13, 14, 15), result.failedTargetPositions)
        assertTrue(fronts.none { it.isHasGrilling })
        targets.forEach { assertNull(it.kaoPan.taste) }
    }

    @Test
    fun applyResult_shouldKeepFailedTargetReservationWhenActiveIntentProtectsIt() {
        val fronts = (1..3).map { frontPan(it) }
        val targets = listOf(
            KaoChangOperate.SupplementTarget(targetPan(13, hasSausage = true), "C"),
            KaoChangOperate.SupplementTarget(targetPan(14, hasSausage = false), "C"),
            KaoChangOperate.SupplementTarget(targetPan(15, hasSausage = false), "C")
        )

        val result = LowCurrentTrafficMode.applyLowHeatSupplementReservationResult(
            frontPans = fronts,
            supplementTargets = targets,
            activeLowHeatIntentTargetPositions = setOf(14),
            movedCount = 1
        )

        assertEquals(listOf(1), result.reservedFrontPositions)
        assertEquals(listOf(2), result.keptForActiveIntentFrontPositions)
        assertEquals(listOf(3), result.clearedFrontPositions)
        assertTrue(fronts[0].isHasGrilling)
        assertTrue(fronts[1].isHasGrilling)
        assertFalse(fronts[2].isHasGrilling)
        assertEquals("C", targets[1].kaoPan.taste?.tasteCode)
        assertNull(targets[2].kaoPan.taste)
    }

    @Test(expected = IllegalArgumentException::class)
    fun applyResult_shouldRejectMismatchedIndexAlignedLists() {
        LowCurrentTrafficMode.applyLowHeatSupplementReservationResult(
            frontPans = listOf(frontPan(1)),
            supplementTargets = listOf(
                KaoChangOperate.SupplementTarget(targetPan(13, hasSausage = false), "C"),
                KaoChangOperate.SupplementTarget(targetPan(14, hasSausage = false), "C")
            ),
            activeLowHeatIntentTargetPositions = emptySet(),
            movedCount = 0
        )
    }

    @Test
    fun clearGhostReservations_shouldOnlyClearWhenHeatAreaEmptyAndNoActiveIntent() {
        val fronts = listOf(
            frontPan(1).apply { isHasGrilling = true },
            frontPan(2).apply { isHasSausage = true; isHasGrilling = true },
            frontPan(3).apply { isHasGrilling = true }
        )
        val heatArea = (13..18).map { targetPan(it, hasSausage = false) }

        val cleared = LowCurrentTrafficMode.clearGhostLowFrontReservations(
            list1_9 = fronts,
            list13_18 = heatArea,
            activeLowHeatIntentTargetPositions = emptySet()
        )

        assertEquals(listOf(1, 3), cleared)
        assertFalse(fronts[0].isHasGrilling)
        assertTrue(fronts[1].isHasGrilling)
        assertFalse(fronts[2].isHasGrilling)
    }

    @Test
    fun clearGhostReservations_shouldSkipWhenHeatAreaHasSausageOrIntentActive() {
        val fronts = listOf(frontPan(1).apply { isHasGrilling = true })
        val occupiedHeat = (13..18).map { targetPan(it, hasSausage = it == 13) }

        assertTrue(
            LowCurrentTrafficMode.clearGhostLowFrontReservations(
                list1_9 = fronts,
                list13_18 = occupiedHeat,
                activeLowHeatIntentTargetPositions = emptySet()
            ).isEmpty()
        )
        assertTrue(fronts[0].isHasGrilling)

        assertTrue(
            LowCurrentTrafficMode.clearGhostLowFrontReservations(
                list1_9 = fronts,
                list13_18 = occupiedHeat.map { it.apply { isHasSausage = false } },
                activeLowHeatIntentTargetPositions = setOf(13)
            ).isEmpty()
        )
        assertTrue(fronts[0].isHasGrilling)
    }

    private fun frontPan(position: Int): KaoPan =
        KaoPan().apply {
            positionSn = position
            taste = Taste("C")
        }

    private fun targetPan(position: Int, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            positionSn = position
            isHasSausage = hasSausage
            taste = Taste("C")
        }
}
