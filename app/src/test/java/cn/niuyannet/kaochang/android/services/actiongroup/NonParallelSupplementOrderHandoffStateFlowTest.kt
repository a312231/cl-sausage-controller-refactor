package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.OrderExecutionOutcome
import cn.niuyannet.kaochang.android.services.OrderSupplementResumePolicy
import cn.niuyannet.kaochang.android.services.SupplementBatchIntent
import cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepository
import cn.niuyannet.kaochang.android.services.SupplementBatchIntentStore
import cn.niuyannet.kaochang.android.services.SupplementBatchIntentTarget
import cn.niuyannet.kaochang.android.services.SupplementBatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonParallelSupplementOrderHandoffStateFlowTest {

    @Test
    fun in7Completed_thenWholeOrderSuccess_shouldReleaseMechanicalStateAndKeepOnlyRemainingTargets() {
        val batchStore = MemoryBatchStore(batchIntent())
        val batchRepository = SupplementBatchIntentRepository(
            store = batchStore,
            clock = { 200L },
            infoLogger = {},
            warnLogger = {}
        )
        batchRepository.markCompleted(BATCH_ID, 13)

        val ownerStore = MemoryOwnerStore(OWNER, MechanicalClaimState.CLAIMED)
        val moveStore = MemoryMoveStore(1)
        val resourceLocks = ResourceLocks().apply {
            assertTrue(tryAcquire(ResourceMask.bottom or ResourceMask.lift, OWNER))
        }
        val closeout = SupplementPipelineOrderCloseoutPolicy.classify(
            parallelEnabled = false,
            pausedAfterAction = "in7",
            actionGroupCompleted = true,
            durableStateSaved = true
        )

        val released = NonParallelSupplementOrderHandoffCoordinator(
            transitionLock = Any(),
            ownerStore = ownerStore,
            moveStore = moveStore,
            resourceLocks = resourceLocks
        ).releaseCompletedRoot(OWNER, closeout)

        assertTrue(released)
        assertNull(ownerStore.owner)
        assertEquals(0, moveStore.status)
        assertTrue(resourceLocks.isIdle())
        assertTrue(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.SUCCESS))
        assertEquals(listOf(14, 15), batchRepository.loadActive()!!.remainingTargets().map { it.positionSn })
        assertTrue(batchRepository.loadActive()!!.targets.first { it.positionSn == 13 }.completed)
    }

    private fun batchIntent() = SupplementBatchIntent(
        batchId = BATCH_ID,
        type = SupplementBatchType.LOW_HEAT_13_15,
        targets = mutableListOf(13, 14, 15).map { position ->
            SupplementBatchIntentTarget(
                positionSn = position,
                tasteCode = "A",
                originBatchId = BATCH_ID,
                originBatchType = SupplementBatchType.LOW_HEAT_13_15
            )
        }.toMutableList(),
        createdAtMs = 100L,
        updatedAtMs = 100L
    )

    private class MemoryBatchStore(var intent: SupplementBatchIntent?) : SupplementBatchIntentStore {
        override fun load(): SupplementBatchIntent? = intent
        override fun save(intent: SupplementBatchIntent) {
            this.intent = intent
        }
        override fun clear() {
            intent = null
        }
    }

    private class MemoryOwnerStore(
        var owner: String?,
        var state: MechanicalClaimState?
    ) : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? = owner?.let {
            MechanicalClaimOwnerSnapshot(it, state, 1L)
        }
        override fun savePrepared(owner: String) = error("not used")
        override fun saveClaimed(owner: String) = error("not used")
        override fun clearIfOwner(owner: String): Boolean {
            if (this.owner != owner) return false
            this.owner = null
            state = null
            return true
        }
    }

    private class MemoryMoveStore(var status: Int) : MechanicalMoveStatusStore {
        override fun load(): Int = status
        override fun saveDurable(moveStatus: Int) {
            status = moveStatus
        }
    }

    private companion object {
        const val OWNER = "serial-supplement-A"
        const val BATCH_ID = "batch-13-15"
    }
}
