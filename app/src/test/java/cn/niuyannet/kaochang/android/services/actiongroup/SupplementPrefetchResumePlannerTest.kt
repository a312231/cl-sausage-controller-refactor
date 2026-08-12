package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupplementPrefetchResumePlannerTest {

    @Test
    fun nextActionAfterConfirmedStage_shouldNeverReplayPhysicalAction() {
        assertEquals("in2", SupplementPrefetchResumePlanner.nextActionAfter("in1"))
        assertEquals("in3", SupplementPrefetchResumePlanner.nextActionAfter("in2"))
        assertEquals("in4", SupplementPrefetchResumePlanner.nextActionAfter("in3"))
        assertNull(SupplementPrefetchResumePlanner.nextActionAfter("in4"))
        assertNull(SupplementPrefetchResumePlanner.nextActionAfter(null))
    }

    @Test
    fun prepareAfterOrder_shouldClearBarrierAndResumeSinglePausedLane() {
        val repository = repository(
            session(delivery = lane("in2", boxDeducted = true))
        )
        repository.requestOrderBarrier("ORDER-1")

        val result = SupplementPrefetchResumePlanner.prepareAfterOrder(repository, "ORDER-1")

        assertTrue(result is SupplementPrefetchResumePreparation.Ready)
        assertEquals("in2", (result as SupplementPrefetchResumePreparation.Ready).plan.resumeAfterAction)
        assertEquals(SupplementBarrierState.OPEN, repository.load()?.barrierState)
        assertNull(repository.load()?.pendingOrderNo)
    }

    @Test
    fun prepareAfterOrder_shouldPromotePrefetchPausedAfterIn1() {
        val repository = repository(
            session(
                delivery = lane("in7", panPlaced = true),
                prefetch = lane("in1", targetPanNo = 14, boxDeducted = false)
            )
        )
        repository.requestOrderBarrier("ORDER-2")

        val result = SupplementPrefetchResumePlanner.prepareAfterOrder(repository, "ORDER-2")

        assertTrue(result is SupplementPrefetchResumePreparation.Ready)
        assertEquals("in1", (result as SupplementPrefetchResumePreparation.Ready).plan.resumeAfterAction)
        assertEquals(14, repository.load()?.deliveryLane?.targetPanNo)
        assertNull(repository.load()?.prefetchLane)
    }

    @Test
    fun prepareAfterOrder_shouldRejectMismatchedOrderWithoutClearingBarrier() {
        val repository = repository(session(delivery = lane("in2", boxDeducted = true)))
        repository.requestOrderBarrier("ORDER-3")

        val result = SupplementPrefetchResumePlanner.prepareAfterOrder(repository, "OTHER")

        assertTrue(result is SupplementPrefetchResumePreparation.Unsafe)
        assertEquals(SupplementBarrierState.ORDER_WON, repository.load()?.barrierState)
        assertEquals("ORDER-3", repository.load()?.pendingOrderNo)
    }

    @Test
    fun maintenanceSession_shouldRejectNewOrderBarrierMutation() {
        val repository = repository(
            session(delivery = lane("in2", boxDeducted = true)).copy(
                phase = SupplementPipelinePhase.MAINTENANCE_REQUIRED
            )
        )

        try {
            repository.requestOrderBarrier("ORDER-MAINTENANCE")
            fail("maintenance session must reject order barrier mutation")
        } catch (_: IllegalStateException) {
            assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
            assertEquals(SupplementBarrierState.OPEN, repository.load()?.barrierState)
        }
    }

    @Test
    fun prepareRecovered_shouldFinishCommittedDeliveryBeforePromotingPrefetch() {
        val repository = repository(
            session(
                delivery = lane("in6", boxDeducted = true),
                prefetch = lane("in1", targetPanNo = 14, boxDeducted = false)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchResumePlanner.prepareRecovered(repository)

        assertTrue(result is SupplementPrefetchResumePreparation.Ready)
        assertEquals("in6", (result as SupplementPrefetchResumePreparation.Ready).plan.resumeAfterAction)
        assertEquals(13, result.plan.targetPanNo)
        assertEquals(14, repository.load()?.prefetchLane?.targetPanNo)
    }

    @Test
    fun prepareRuntimeRecovery_shouldResumeAfterIn2WithoutReplayingPickOrDeduction() {
        val repository = repository(
            session(delivery = lane("in2", boxDeducted = true)).copy(
                phase = SupplementPipelinePhase.PAUSED_FOR_RUNTIME,
                mechanicalClaimState = null
            )
        )

        val result = SupplementPrefetchResumePlanner.prepareRuntimeRecovery(repository)

        assertTrue(result is SupplementPrefetchResumePreparation.Ready)
        val plan = (result as SupplementPrefetchResumePreparation.Ready).plan
        assertEquals("in2", plan.resumeAfterAction)
        assertEquals("in3", SupplementPrefetchResumePlanner.nextActionAfter(plan.resumeAfterAction))
        assertEquals(5, repository.load()?.deliveryLane?.stockBefore)
        assertEquals(4, repository.load()?.deliveryLane?.expectedStockAfter)
        assertTrue(repository.load()?.deliveryLane?.boxDeductionApplied == true)
        assertEquals(SupplementPipelinePhase.PAUSED_FOR_RUNTIME, repository.load()?.phase)
    }

    @Test
    fun prepareRuntimeRecovery_shouldRejectSessionThatIsNotRuntimePaused() {
        val repository = repository(
            session(delivery = lane("in3", boxDeducted = true)).copy(
                phase = SupplementPipelinePhase.RUNNING
            )
        )

        val result = SupplementPrefetchResumePlanner.prepareRuntimeRecovery(repository)

        assertTrue(result is SupplementPrefetchResumePreparation.Unsafe)
        assertEquals(SupplementPipelinePhase.RUNNING, repository.load()?.phase)
    }

    private fun repository(initial: SupplementPrefetchPipelineSession): SupplementPrefetchSessionRepository {
        return SupplementPrefetchSessionRepository(MemoryStore()).also { it.create(initial) }
    }

    private fun session(
        delivery: SupplementPrefetchLaneCheckpoint,
        prefetch: SupplementPrefetchLaneCheckpoint? = null
    ) = SupplementPrefetchPipelineSession(
        sessionId = "SESSION-1",
        batchId = "BATCH-1",
        resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
        deliveryLane = delivery,
        prefetchLane = prefetch
    )

    private fun lane(
        actionName: String,
        targetPanNo: Int = 13,
        boxDeducted: Boolean = actionName in setOf("in2", "in3", "in7"),
        panPlaced: Boolean = false
    ) = SupplementPrefetchLaneCheckpoint(
        sourceBoxNo = 17,
        sourceBoxCandidates = listOf(17),
        targetPanNo = targetPanNo,
        currentAction = actionName,
        lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED,
        lastConfirmedStage = actionName,
        stockBefore = 5,
        expectedStockAfter = 4,
        boxDeductionApplied = boxDeducted,
        panPlacementApplied = panPlaced
    )

    private class MemoryStore : SupplementPrefetchSessionStore {
        private var value: SupplementPrefetchPipelineSession? = null
        override fun load(): SupplementPrefetchPipelineSession? = value
        override fun save(session: SupplementPrefetchPipelineSession) { value = session }
        override fun clear() { value = null }
    }
}
