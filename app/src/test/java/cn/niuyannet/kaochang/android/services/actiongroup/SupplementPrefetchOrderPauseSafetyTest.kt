package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPrefetchOrderPauseSafetyTest {

    @Test
    fun singleLanePausedAfterIn2WithDeduction_shouldBeSafeForOrder() {
        assertTrue(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(delivery = lane("in2", boxDeducted = true))
            )
        )
    }

    @Test
    fun phaseBarrierAndPendingOrderMustAllMatch() {
        val valid = pausedSession(delivery = lane("in1"))

        assertFalse(SupplementPrefetchOrderPauseSafety.isSafe(valid.copy(phase = SupplementPipelinePhase.RUNNING)))
        assertFalse(SupplementPrefetchOrderPauseSafety.isSafe(valid.copy(barrierState = SupplementBarrierState.OPEN)))
        assertFalse(SupplementPrefetchOrderPauseSafety.isSafe(valid.copy(pendingOrderNo = null)))
    }

    @Test
    fun singleLaneMustHaveTerminalAndRequiredPhysicalEvidence() {
        assertFalse(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(delivery = lane("in1", lifecycle = SupplementActionLifecycle.RUNNING_CONFIRMED))
            )
        )
        assertFalse(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(delivery = lane("in2", boxDeducted = false))
            )
        )
        assertFalse(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(delivery = lane("in7", boxDeducted = true, panPlaced = false))
            )
        )
    }

    @Test
    fun dualLaneRequiresPlacedDeliveryAndEarlyTerminalPrefetch() {
        assertTrue(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(
                    delivery = lane("in7", boxDeducted = true, panPlaced = true),
                    prefetch = lane("in1", target = 14)
                )
            )
        )
        assertFalse(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(
                    delivery = lane("in6", boxDeducted = true),
                    prefetch = lane("in1", target = 14)
                )
            )
        )
        assertFalse(
            SupplementPrefetchOrderPauseSafety.isSafe(
                pausedSession(
                    delivery = lane("in7", boxDeducted = true, panPlaced = true),
                    prefetch = lane("in4", target = 14, boxDeducted = true)
                )
            )
        )
    }

    @Test
    fun orderGateAllowsOnlySafePausedEvidenceWithIdleLocks() {
        assertFalse(
            SupplementPrefetchOrderGatePolicy.isBusy(
                locksIdle = true,
                evidencePresent = true,
                safePausedForOrder = true
            )
        )
        assertTrue(SupplementPrefetchOrderGatePolicy.isBusy(true, true, false))
        assertTrue(SupplementPrefetchOrderGatePolicy.isBusy(false, true, true))
        assertFalse(SupplementPrefetchOrderGatePolicy.isBusy(true, false, false))
    }

    private fun pausedSession(
        delivery: SupplementPrefetchLaneCheckpoint,
        prefetch: SupplementPrefetchLaneCheckpoint? = null
    ) = SupplementPrefetchPipelineSession(
        sessionId = "paused-session",
        resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
        deliveryLane = delivery,
        prefetchLane = prefetch,
        barrierState = SupplementBarrierState.ORDER_WON,
        pendingOrderNo = "ORDER-1",
        phase = SupplementPipelinePhase.PAUSED_FOR_ORDER
    )

    private fun lane(
        action: String,
        target: Int = 13,
        lifecycle: SupplementActionLifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
        boxDeducted: Boolean = false,
        panPlaced: Boolean = false
    ) = SupplementPrefetchLaneCheckpoint(
        sourceBoxNo = 17,
        sourceBoxCandidates = listOf(17),
        targetPanNo = target,
        currentAction = action,
        lifecycle = lifecycle,
        lastConfirmedStage = action.takeIf { lifecycle >= SupplementActionLifecycle.TERMINAL_CONFIRMED },
        stockBefore = 5,
        expectedStockAfter = 4,
        boxDeductionApplied = boxDeducted,
        panPlacementApplied = panPlaced
    )
}
