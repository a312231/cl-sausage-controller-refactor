package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SupplementPrefetchPhysicalStateCoordinatorTest {
    @Test
    fun duplicateIn2AndIn7_shouldApplyEachPhysicalMutationOnlyOnce() = runBlocking {
        val store = MemoryStore()
        val repository = SupplementPrefetchSessionRepository(store)
        repository.create(session())
        val in2 = AtomicInteger()
        val in7 = AtomicInteger()
        val persisted = AtomicInteger()
        val published = AtomicInteger()
        val coordinator = coordinator(repository, persisted, published)

        awaitAll(
            async(Dispatchers.Default) { coordinator.applySuccessfulPhysicalCheckpoint(SupplementLaneRole.PREFETCH, "in2") { in2.incrementAndGet(); SupplementPhysicalMutation.APPLIED } },
            async(Dispatchers.Default) { coordinator.applySuccessfulPhysicalCheckpoint(SupplementLaneRole.DELIVERY, "in7") { in7.incrementAndGet(); SupplementPhysicalMutation.APPLIED } },
            async(Dispatchers.Default) { coordinator.applySuccessfulPhysicalCheckpoint(SupplementLaneRole.PREFETCH, "in2") { in2.incrementAndGet(); SupplementPhysicalMutation.APPLIED } },
            async(Dispatchers.Default) { coordinator.applySuccessfulPhysicalCheckpoint(SupplementLaneRole.DELIVERY, "in7") { in7.incrementAndGet(); SupplementPhysicalMutation.APPLIED } }
        )

        assertEquals(1, in2.get())
        assertEquals(1, in7.get())
        assertEquals(2, persisted.get())
        assertEquals(2, published.get())
        assertTrue(requireNotNull(repository.load()).prefetchLane!!.boxDeductionApplied)
        assertTrue(requireNotNull(repository.load()).deliveryLane!!.panPlacementApplied)
    }

    @Test
    fun physicalSaveFailure_shouldKeepSessionAndNotPublishSuccess() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(session())
        var published = false
        val coordinator = SupplementPrefetchPhysicalStateCoordinator(
            repository = repository,
            persistPhysicalState = { _, _ -> error("physical save failed") },
            publishPhysicalDelta = { _, _ -> published = true }
        )
        val result = coordinator.applySuccessfulPhysicalCheckpoint(SupplementLaneRole.PREFETCH, "in2") {
            SupplementPhysicalMutation.APPLIED
        }
        assertTrue(result is SupplementPhysicalCheckpointResult.Failed)
        assertFalse(published)
        assertFalse(requireNotNull(repository.load()).prefetchLane!!.boxDeductionApplied)
    }

    private fun coordinator(
        repository: SupplementPrefetchSessionRepository,
        persisted: AtomicInteger,
        published: AtomicInteger
    ) = SupplementPrefetchPhysicalStateCoordinator(
        repository = repository,
        persistPhysicalState = { _, _ -> persisted.incrementAndGet() },
        publishPhysicalDelta = { _, _ -> published.incrementAndGet() }
    )

    private fun session() = SupplementPrefetchPipelineSession(
        sessionId = "pipeline-session",
        resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
        deliveryLane = lane(17, 13, "in7"),
        prefetchLane = lane(18, 14, "in2")
    )

    private fun lane(source: Int, target: Int, action: String) = SupplementPrefetchLaneCheckpoint(
        sourceBoxNo = source,
        sourceBoxCandidates = listOf(source),
        targetPanNo = target,
        currentAction = action,
        lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
        lastConfirmedStage = action
    )

    private class MemoryStore : SupplementPrefetchSessionStore {
        private var value: SupplementPrefetchPipelineSession? = null
        override fun load() = value
        override fun save(session: SupplementPrefetchPipelineSession) { value = session }
        override fun clear() { value = null }
    }
}
