package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementBarrierState
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPipelinePhase
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchLaneCheckpoint
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPipelineReplanReleasePolicyTest {

    @Test
    fun canRelease_shouldAcceptCommittedDeliveryWithoutOrderOrPrefetch() {
        assertTrue(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session = releasableSession(),
                expectedSessionId = "session-1",
                expectedRevision = 7L,
                durableStateSaved = true
            )
        )
    }

    @Test
    fun canRelease_shouldRejectPendingOrderBarrier() {
        assertFalse(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session = releasableSession().copy(
                    pendingOrderNo = "order-1",
                    phase = SupplementPipelinePhase.PAUSED_FOR_ORDER,
                    barrierState = SupplementBarrierState.ORDER_WON
                ),
                expectedSessionId = "session-1",
                expectedRevision = 7L,
                durableStateSaved = true
            )
        )
    }

    @Test
    fun canRelease_shouldRejectRevisionChangePrefetchOrIncompletePhysicalCommit() {
        val session = releasableSession()

        assertFalse(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session,
                expectedSessionId = "session-1",
                expectedRevision = 8L,
                durableStateSaved = true
            )
        )
        assertFalse(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session.copy(prefetchLane = session.deliveryLane?.copy(targetPanNo = 6)),
                expectedSessionId = "session-1",
                expectedRevision = 7L,
                durableStateSaved = true
            )
        )
        assertFalse(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session.copy(deliveryLane = session.deliveryLane?.copy(panPlacementApplied = false)),
                expectedSessionId = "session-1",
                expectedRevision = 7L,
                durableStateSaved = true
            )
        )
        assertFalse(
            SupplementPipelineReplanReleasePolicy.canRelease(
                session,
                expectedSessionId = "session-1",
                expectedRevision = 7L,
                durableStateSaved = false
            )
        )
    }

    private fun releasableSession(): SupplementPrefetchPipelineSession =
        SupplementPrefetchPipelineSession(
            revision = 7L,
            sessionId = "session-1",
            deliveryLane = SupplementPrefetchLaneCheckpoint(
                sourceBoxNo = 16,
                sourceBoxCandidates = listOf(18, 17, 16),
                targetPanNo = 5,
                currentAction = "in7",
                lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED,
                lastConfirmedStage = "in7",
                boxDeductionApplied = true,
                panPlacementApplied = true
            ),
            barrierState = SupplementBarrierState.DELIVERY_COMMITTED,
            phase = SupplementPipelinePhase.RUNNING
        )
}
