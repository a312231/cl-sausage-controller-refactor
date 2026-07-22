package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowHeatSupplementReservationResultTest {

    @Test
    fun formatNoSameTasteStockLog_shouldExplainMissingTasteAndAvailableStock() {
        val frontPans = listOf(
            frontPan(1, "B"),
            frontPan(2, "B"),
            frontPan(3, "B")
        )
        val boxes = listOf(
            box(positionSn = 10, tasteCode = "C", num = 15),
            box(positionSn = 21, tasteCode = "C", num = 15)
        )

        val message = LowCurrentTrafficMode.formatNoSameTasteStockLog(
            groupName = "13~15",
            selectedFrontPositions = frontPans,
            neededTastes = listOf("B", "B", "B"),
            listBox = boxes,
            isBoxAvailable = { true }
        )

        assertTrue(message.contains("无同口味可用库存"))
        assertTrue(message.contains("目标补热区=13~15"))
        assertTrue(message.contains("前区空位口味=1:B,2:B,3:B"))
        assertTrue(message.contains("缺失口味=B"))
        assertTrue(message.contains("可用源箱库存=C=30"))
    }

    @Test
    fun formatNoSameTasteStockLog_shouldUseSameSourceBoxPolicyAsSupplementGate() {
        val frontPans = listOf(
            frontPan(1, "B"),
            frontPan(2, "B"),
            frontPan(3, "B")
        )
        val boxes = listOf(
            box(positionSn = 23, tasteCode = "B", num = 15),
            box(positionSn = 21, tasteCode = "C", num = 15)
        )

        val message = LowCurrentTrafficMode.formatNoSameTasteStockLog(
            groupName = "13~15",
            selectedFrontPositions = frontPans,
            neededTastes = listOf("B", "B", "B"),
            listBox = boxes,
            isBoxAvailable = { true }
        )

        assertTrue(message.contains("缺失口味=B"))
        assertFalse(message.contains("B=15"))
        assertTrue(message.contains("可用源箱库存=C=15"))
    }

    @Test
    fun formatLowHeatSupplementGateLog_shouldExplainBlockedReasonAndFrontReservation() {
        val frontPans = listOf(
            frontPan(1, "B").apply { isHasGrilling = true },
            frontPan(2, "B"),
            frontPan(3, "C")
        )

        val message = LowCurrentTrafficMode.formatLowHeatSupplementGateLog(
            supplementDecision = 1,
            schedulingEnabled = false,
            boxSupplyAvailable = true,
            errorStatus = 0,
            isBeforeCloseWindow = false,
            selectedFrontPositions = frontPans
        )

        assertTrue(message.contains("13~15 补热区补肠未进入"))
        assertTrue(message.contains("decision=1(补 13~15 补热区)"))
        assertTrue(message.contains("阻断原因=schedulingDisabled"))
        assertTrue(message.contains("前区空位口味=1:B(回补预占),2:B(预占),3:C(预占)"))
    }

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
    fun planTargets_shouldFallbackToAvailableTasteWithoutLeavingLowHeatHoles() {
        val frontPans = listOf(
            frontPan(1, "B"),
            frontPan(2, "B"),
            frontPan(3, "A")
        )
        val supplementPans = (13..15).map { targetPan(it, hasSausage = false).apply { taste = null } }
        val boxes = listOf(
            box(positionSn = 19, tasteCode = "A", num = 2),
            box(positionSn = 15, tasteCode = "B", num = 1)
        )

        val plan = LowCurrentTrafficMode.planLowHeatSupplementTargets(
            selectedFrontPositions = frontPans,
            supplementPositions = supplementPans,
            listBox = boxes,
            isBoxAvailable = { true }
        )

        assertEquals(listOf(1, 2, 3), plan.frontPans.map { it.positionSn })
        assertEquals(listOf(13, 14, 15), plan.targets.map { it.kaoPan.positionSn })
        assertEquals(listOf("B", "A", "A"), plan.targets.map { it.tasteCode })
        assertEquals(listOf(1, 2, 3), plan.targets.map { it.returnPositionSn })
        assertEquals("A", frontPans[1].taste.tasteCode)
        assertEquals("A", supplementPans[1].taste.tasteCode)
    }

    @Test
    fun planTargets_shouldOnlyRequestContiguousLowHeatTargetsCoveredByCurrentStock() {
        val frontPans = listOf(
            frontPan(1, "B"),
            frontPan(2, "B"),
            frontPan(3, "B")
        )
        val supplementPans = (13..15).map { targetPan(it, hasSausage = false).apply { taste = null } }
        val boxes = listOf(
            box(positionSn = 15, tasteCode = "B", num = 1)
        )

        val plan = LowCurrentTrafficMode.planLowHeatSupplementTargets(
            selectedFrontPositions = frontPans,
            supplementPositions = supplementPans,
            listBox = boxes,
            isBoxAvailable = { true }
        )

        assertEquals(listOf(1), plan.frontPans.map { it.positionSn })
        assertEquals(listOf(13), plan.targets.map { it.kaoPan.positionSn })
        assertEquals(listOf("B"), plan.targets.map { it.tasteCode })
        assertEquals("B", supplementPans[0].taste.tasteCode)
        assertNull(supplementPans[1].taste)
        assertNull(supplementPans[2].taste)
    }

    @Test
    fun maturedPartialInitialization_shouldPlanThreeLowHeatReturnsAndLeaveNinthGapForNextBatch() {
        val emptyFront = (6..9).map { frontPan(it, "A") }
        val supplementPans = (13..15).map { targetPan(it, hasSausage = false) }
        val boxes = listOf(box(positionSn = 15, tasteCode = "A", num = 4))

        assertEquals(1, LowCurrentTrafficMode.lowConcurrencyIntestinalSupplementation(4, 3, 3))
        val plan = LowCurrentTrafficMode.planLowHeatSupplementTargets(
            selectedFrontPositions = emptyFront.take(3),
            supplementPositions = supplementPans,
            listBox = boxes,
            isBoxAvailable = { true }
        )

        assertEquals(listOf(13, 14, 15), plan.targets.map { it.kaoPan.positionSn })
        assertEquals(listOf(6, 7, 8), plan.targets.map { it.returnPositionSn })
        assertEquals(3, LowCurrentTrafficMode.lowConcurrencyIntestinalSupplementation(1, 3, 3))
        assertTrue("9号单个空位不足三根，不应立即开下一批", emptyFront.last().positionSn == 9)
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

    private fun frontPan(position: Int, tasteCode: String = "C"): KaoPan =
        KaoPan().apply {
            positionSn = position
            taste = Taste(tasteCode)
        }

    private fun targetPan(position: Int, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            positionSn = position
            isHasSausage = hasSausage
            taste = Taste("C")
        }

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
        }
}
