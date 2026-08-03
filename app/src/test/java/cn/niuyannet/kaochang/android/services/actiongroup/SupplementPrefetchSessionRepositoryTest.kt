package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class SupplementPrefetchSessionRepositoryTest {
    @Test
    fun concurrentLaneUpdates_shouldKeepBothChangesAndAdvanceRevisionForEachWrite() = runBlocking {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(baseSession().copy(
            deliveryLane = lane(13, "in6", SupplementActionLifecycle.COMMAND_WRITTEN)
        ))

        awaitAll(
            async(Dispatchers.Default) {
                repository.markRunningConfirmed(SupplementLaneRole.DELIVERY, "in6")
            },
            async(Dispatchers.Default) {
                repository.prepareAction(
                    SupplementLaneRole.PREFETCH,
                    lane(14, null),
                    "in1"
                )
            }
        )

        val saved = requireNotNull(repository.load())
        assertEquals(3L, saved.revision)
        assertEquals(SupplementActionLifecycle.RUNNING_CONFIRMED, saved.deliveryLane?.lifecycle)
        assertEquals("in1", saved.prefetchLane?.currentAction)
    }

    @Test
    fun revisionAndLifecycle_shouldAdvanceOnceAndRejectBackwardMutation() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val created = repository.create(baseSession())
        assertEquals(1L, created.revision)

        val command = repository.markCommandWritten(SupplementLaneRole.DELIVERY, "in6")
        val running = repository.markRunningConfirmed(SupplementLaneRole.DELIVERY, "in6")
        assertEquals(3L, running.revision)
        assertEquals(SupplementActionLifecycle.RUNNING_CONFIRMED, running.deliveryLane?.lifecycle)

        expectIllegalState { repository.markCommandWritten(SupplementLaneRole.DELIVERY, "in6") }
        assertEquals(running, repository.load())
        assertEquals(SupplementActionLifecycle.COMMAND_WRITTEN, command.deliveryLane?.lifecycle)
    }

    @Test
    fun concurrentBarrierRace_shouldGiveOnlyOneCommitWinner() = runBlocking {
        repeat(20) { index ->
            val repository = SupplementPrefetchSessionRepository(MemoryStore())
            repository.create(baseSession("session-$index"))
            val results = awaitAll(
                async(Dispatchers.Default) { repository.requestOrderBarrier("order-$index") },
                async(Dispatchers.Default) { repository.tryCommitDelivery(17, 13) }
            )
            assertEquals(1, results.count { it })
            val saved = requireNotNull(repository.load())
            assertEquals("order-$index", saved.pendingOrderNo)
            assertTrue(saved.barrierState != SupplementBarrierState.OPEN)
        }
    }

    @Test
    fun sharedOrderDeliveryBoundary_shouldPersistBarrierBeforeRejectedIn4Cleanup() = runBlocking {
        repeat(20) { index ->
            val transitionLock = Any()
            var orderPending = false
            val repository = SupplementPrefetchSessionRepository(MemoryStore())
            repository.create(baseSession("shared-boundary-$index"))
            val results = awaitAll(
                async(Dispatchers.Default) {
                    synchronized(transitionLock) {
                        orderPending = true
                        repository.requestOrderBarrier("order-$index")
                    }
                },
                async(Dispatchers.Default) {
                    SupplementDeliveryCommitCoordinator(
                        transitionLock = transitionLock,
                        orderPending = { orderPending }
                    ) {
                        repository.tryCommitDelivery(17, 13)
                    }.tryCommit()
                }
            )

            assertEquals(1, results.count { it })
            val saved = requireNotNull(repository.load())
            assertEquals("order-$index", saved.pendingOrderNo)
            assertTrue(saved.barrierState != SupplementBarrierState.OPEN)
            if (!results[1]) {
                assertEquals(SupplementBarrierState.ORDER_WON, saved.barrierState)
                assertEquals(SupplementPipelinePhase.PAUSED_FOR_ORDER, saved.phase)
            }
        }
    }

    @Test
    fun orderBarrierDomainOperations_shouldPersistWinnerAndLaterPendingOrder() {
        val orderWins = SupplementPrefetchSessionRepository(MemoryStore())
        orderWins.create(baseSession("order-wins"))
        assertTrue(orderWins.requestOrderBarrier("order-1"))
        assertFalse(orderWins.tryCommitDelivery(17, 13))
        assertEquals(SupplementBarrierState.ORDER_WON, orderWins.load()?.barrierState)
        assertTrue(orderWins.clearOrderBarrierAndResume("order-1"))
        assertEquals(SupplementBarrierState.OPEN, orderWins.load()?.barrierState)

        val deliveryWins = SupplementPrefetchSessionRepository(MemoryStore())
        deliveryWins.create(baseSession("delivery-wins"))
        assertTrue(deliveryWins.tryCommitDelivery(17, 13))
        assertFalse(deliveryWins.requestOrderBarrier("order-2"))
        assertEquals(SupplementBarrierState.DELIVERY_COMMITTED, deliveryWins.load()?.barrierState)
        assertEquals("order-2", deliveryWins.load()?.pendingOrderNo)
    }

    @Test
    fun committedDeliveredPendingOrder_shouldAtomicallyBecomeSafePaused() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(committedDeliveredPendingOrderSession())

        val paused = repository.pauseCommittedDeliveryForPendingOrder()

        assertEquals(SupplementBarrierState.ORDER_WON, paused.barrierState)
        assertEquals(SupplementPipelinePhase.PAUSED_FOR_ORDER, paused.phase)
        assertEquals("order-after-commit", paused.pendingOrderNo)
        assertTrue(SupplementPrefetchOrderPauseSafety.isSafe(paused))
    }

    @Test
    fun liveAndRecoveryPauseFinalizers_shouldConvergeOnOneRevision() = runBlocking {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val created = repository.create(committedDeliveredPendingOrderSession("pause-race"))

        val results = awaitAll(
            async(Dispatchers.Default) { repository.pauseCommittedDeliveryForPendingOrder() },
            async(Dispatchers.Default) { repository.pauseCommittedDeliveryForPendingOrder() }
        )

        assertTrue(results.all(SupplementPrefetchOrderPauseSafety::isSafe))
        assertEquals(created.revision + 1L, repository.load()?.revision)
    }

    @Test
    fun maintenanceCommittedDelivery_shouldNotBeDowngradedToOrderPause() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(
            committedDeliveredPendingOrderSession().copy(
                phase = SupplementPipelinePhase.MAINTENANCE_REQUIRED
            )
        )

        expectIllegalState { repository.pauseCommittedDeliveryForPendingOrder() }

        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
        assertEquals(SupplementBarrierState.DELIVERY_COMMITTED, repository.load()?.barrierState)
    }

    @Test
    fun runtimeRecoveryPause_shouldClearClaimKeepCheckpointAndAdvanceRevision() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val created = repository.create(
            baseSession().copy(
                deliveryLane = lane(13, "in2", SupplementActionLifecycle.TERMINAL_CONFIRMED)
                    .copy(boxDeductionApplied = true),
                mechanicalClaimState = MechanicalClaimState.CLAIMED,
                mechanicalClaimRevision = 2L
            )
        )

        val paused = repository::class.java
            .getMethod("pauseForRuntimeRecovery", String::class.java)
            .invoke(repository, created.sessionId) as SupplementPrefetchPipelineSession

        assertEquals("PAUSED_FOR_RUNTIME", paused.phase.name)
        assertNull(paused.mechanicalClaimState)
        assertEquals(created.revision + 1L, paused.revision)
        assertEquals(created.deliveryLane, paused.deliveryLane)
        assertEquals(created.prefetchLane, paused.prefetchLane)
    }

    @Test
    fun candidateSwitch_shouldResetOnlyFailedIn2EvidenceAndKeepTarget() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(baseSession().copy(
            deliveryLane = lane(13, "in2", SupplementActionLifecycle.TERMINAL_CONFIRMED)
        ))

        val switched = repository.restartLaneAfterPickFailure(
            role = SupplementLaneRole.DELIVERY,
            failedSourceBoxNo = 17,
            fallbackSourceBoxNo = 18,
            targetPanNo = 13,
            stockBefore = 8
        )
        val lane = requireNotNull(switched.deliveryLane)
        assertEquals(18, lane.sourceBoxNo)
        assertEquals(13, lane.targetPanNo)
        assertEquals("in1", lane.currentAction)
        assertEquals(SupplementActionLifecycle.PREPARED, lane.lifecycle)
        assertFalse(lane.boxDeductionApplied)
        assertEquals(7, lane.expectedStockAfter)
        assertEquals(listOf(17), lane.failedSourceBoxNos)
    }

    @Test
    fun candidateSwitch_shouldRejectPreviouslyFailedFallbackAndInvalidFailedEvidence() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(baseSession().copy(
            deliveryLane = lane(13, "in2", SupplementActionLifecycle.TERMINAL_CONFIRMED)
                .copy(sourceBoxNo = 18, failedSourceBoxNos = listOf(17))
        ))

        expectIllegalState {
            repository.restartLaneAfterPickFailure(
                role = SupplementLaneRole.DELIVERY,
                failedSourceBoxNo = 18,
                fallbackSourceBoxNo = 17,
                targetPanNo = 13,
                stockBefore = 8
            )
        }

        val invalid = SupplementPrefetchSessionRepository(MemoryStore())
        expectIllegalState {
            invalid.create(baseSession().copy(
                deliveryLane = lane(13, "in2", SupplementActionLifecycle.TERMINAL_CONFIRMED)
                    .copy(failedSourceBoxNos = listOf(22))
            ))
        }
    }

    @Test
    fun promotePrefetchAfterDelivery_shouldRejectIn3WithoutBoxDeductionEvidence() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(
            baseSession().copy(
                deliveryLane = lane(
                    target = 13,
                    action = "in7",
                    lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED
                ).copy(
                    boxDeductionApplied = true,
                    panPlacementApplied = true
                ),
                prefetchLane = lane(
                    target = 14,
                    action = "in3",
                    lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED
                ).copy(boxDeductionApplied = false)
            )
        )
        val beforePromotion = requireNotNull(repository.load())

        expectIllegalState { repository.promotePrefetchAfterDelivery() }

        assertEquals(beforePromotion, repository.load())
    }

    @Test
    fun pausedIn1Prefetch_shouldAtomicallyPromoteToSingleOrderWonDeliveryLane() {
        assertPausedPrefetchPromotion("in1", boxDeductionApplied = false)
    }

    @Test
    fun pausedIn2Prefetch_shouldAtomicallyPromoteToSingleOrderWonDeliveryLane() {
        assertPausedPrefetchPromotion("in2", boxDeductionApplied = true)
    }

    @Test
    fun pausedIn3Prefetch_shouldAtomicallyPromoteToSingleOrderWonDeliveryLane() {
        assertPausedPrefetchPromotion("in3", boxDeductionApplied = true)
    }

    @Test
    fun prepareAction_shouldRejectIn3WithoutBoxDeductionEvidence() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val in2WithoutDeduction = lane(
            target = 13,
            action = "in2",
            lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED
        ).copy(boxDeductionApplied = false)
        repository.create(baseSession().copy(deliveryLane = in2WithoutDeduction))
        val beforeAdvance = requireNotNull(repository.load())

        expectIllegalState {
            repository.prepareAction(
                role = SupplementLaneRole.DELIVERY,
                lane = in2WithoutDeduction,
                actionName = "in3"
            )
        }

        assertEquals(beforeAdvance, repository.load())
    }

    @Test
    fun prepareAction_shouldRejectForgedBoxDeductionEvidence() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val persistedIn2 = lane(
            target = 13,
            action = "in2",
            lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED
        ).copy(boxDeductionApplied = false)
        repository.create(baseSession().copy(deliveryLane = persistedIn2))
        val forgedNextLane = persistedIn2.copy(
            currentAction = "in3",
            lifecycle = SupplementActionLifecycle.PREPARED,
            lastConfirmedStage = null,
            boxDeductionApplied = true
        )

        expectIllegalState {
            repository.prepareAction(
                role = SupplementLaneRole.DELIVERY,
                lane = forgedNextLane,
                actionName = "in3"
            )
        }
        assertEquals(persistedIn2, repository.load()?.deliveryLane)
    }

    @Test
    fun sharedPreferenceStore_shouldFailClosedForCorruptionAndDurableWriteFailure() {
        var raw: String? = "{broken-json"
        val corruptedStore = SharedPreferenceSupplementPrefetchSessionStore(
            readRaw = { raw },
            writeRawDurably = { raw = it; true }
        )
        expectCorruptedSession { corruptedStore.load() }
        assertEquals("{broken-json", raw)

        raw = null
        val failedStore = SharedPreferenceSupplementPrefetchSessionStore(
            readRaw = { raw },
            writeRawDurably = { false }
        )
        expectIllegalState { failedStore.save(baseSession(revision = 1L)) }
        assertNull(raw)
    }

    @Test
    fun sharedPreferenceStore_shouldRoundTripSavedSession() {
        var raw: String? = null
        val store = SharedPreferenceSupplementPrefetchSessionStore(
            readRaw = { raw },
            writeRawDurably = { raw = it; true }
        )
        listOf(
            baseSession(),
            committedDeliveredPendingOrderSession(),
            baseSession().copy(
                mechanicalClaimState = MechanicalClaimState.CLAIMED,
                mechanicalClaimRevision = 2L
            )
        ).forEach { expected ->
            store.save(expected)
            assertEquals(expected, store.load())
        }
    }

    @Test
    fun sharedPreferenceStore_shouldReadLiveDeliveredCheckpoint() {
        val raw = LIVE_DELIVERED_CHECKPOINT
        val store = SharedPreferenceSupplementPrefetchSessionStore(
            readRaw = { raw },
            writeRawDurably = { true }
        )

        val session = requireNotNull(store.load())

        assertEquals("in_pipeline:1784513895477", session.sessionId)
        assertEquals(40L, session.revision)
        assertEquals(SupplementBarrierState.DELIVERY_COMMITTED, session.barrierState)
        assertEquals(SupplementPipelinePhase.RUNNING, session.phase)
        assertEquals(7, session.deliveryLane?.targetPanNo)
        assertEquals(9, session.deliveryLane?.sourceBoxNo)
        assertEquals("in7", session.deliveryLane?.lastConfirmedStage)
        assertTrue(session.deliveryLane?.panPlacementApplied == true)
        assertNull(session.mechanicalClaimState)
        assertEquals(0L, session.mechanicalClaimRevision)
    }

    @Test
    fun sharedPreferenceStore_shouldFailClosedWhenRequiredEvidenceFieldIsMissing() {
        val requiredFields = listOf(
            "schemaVersion",
            "revision",
            "sessionId",
            "startedAtMs",
            "resourceMask",
            "barrierState",
            "phase",
            "deliveryLane",
            "deliveryLane.lifecycle",
            "deliveryLane.boxDeductionApplied",
            "deliveryLane.panPlacementApplied"
        )

        requiredFields.forEach { fieldPath ->
            val json = JSON.parseObject(LIVE_DELIVERED_CHECKPOINT)
            if (fieldPath.startsWith("deliveryLane.")) {
                json.getJSONObject("deliveryLane").remove(fieldPath.substringAfter('.'))
            } else {
                json.remove(fieldPath)
            }
            var raw = JSON.toJSONString(json)
            val expectedRaw = raw
            val store = SharedPreferenceSupplementPrefetchSessionStore(
                readRaw = { raw },
                writeRawDurably = { raw = it; true }
            )

            val result = store.read()

            assertTrue("missing $fieldPath must fail closed", result is DurableEvidenceReadResult.Corrupted)
            result as DurableEvidenceReadResult.Corrupted
            assertEquals(sha256(expectedRaw), result.fingerprint)
            assertEquals(expectedRaw, raw)
        }
    }

    @Test
    fun revisionClear_shouldRejectStaleOwnerAndKeepSession() {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        val created = repository.create(baseSession())
        assertFalse(repository.clearIfRevision(created.sessionId, created.revision + 1))
        assertNotNull(repository.load())
        assertTrue(repository.clearIfRevision(created.sessionId, created.revision))
        assertNull(repository.load())
    }

    private fun baseSession(sessionId: String = "session-1", revision: Long = 0L) =
        SupplementPrefetchPipelineSession(
            revision = revision,
            sessionId = sessionId,
            batchId = "batch-1",
            startedAtMs = 100L,
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
            deliveryLane = lane(13, "in6")
        )

    private fun committedDeliveredPendingOrderSession(sessionId: String = "committed-delivery") =
        baseSession(sessionId).copy(
            deliveryLane = lane(
                target = 13,
                action = "in7",
                lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED
            ).copy(
                boxDeductionApplied = true,
                panPlacementApplied = true
            ),
            prefetchLane = null,
            barrierState = SupplementBarrierState.DELIVERY_COMMITTED,
            pendingOrderNo = "order-after-commit",
            phase = SupplementPipelinePhase.RUNNING
        )

    private fun assertPausedPrefetchPromotion(actionName: String, boxDeductionApplied: Boolean) {
        val repository = SupplementPrefetchSessionRepository(MemoryStore())
        repository.create(
            baseSession("paused-$actionName").copy(
                deliveryLane = lane(
                    target = 13,
                    action = "in7",
                    lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED
                ).copy(boxDeductionApplied = true, panPlacementApplied = true),
                prefetchLane = lane(
                    target = 14,
                    action = actionName,
                    lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED
                ).copy(
                    sourceBoxNo = 18,
                    sourceBoxCandidates = listOf(18),
                    boxDeductionApplied = boxDeductionApplied
                ),
                barrierState = SupplementBarrierState.DELIVERY_COMMITTED,
                pendingOrderNo = "order-$actionName"
            )
        )

        val promoted = repository.promotePrefetchAfterDelivery(allowPausedCheckpoint = true)

        assertEquals(14, promoted.deliveryLane?.targetPanNo)
        assertEquals(actionName, promoted.deliveryLane?.currentAction)
        assertNull(promoted.prefetchLane)
        assertEquals(SupplementBarrierState.ORDER_WON, promoted.barrierState)
        assertEquals(SupplementPipelinePhase.PAUSED_FOR_ORDER, promoted.phase)
        assertEquals("order-$actionName", promoted.pendingOrderNo)
    }

    private fun lane(
        target: Int,
        action: String?,
        lifecycle: SupplementActionLifecycle = SupplementActionLifecycle.PREPARED
    ) = SupplementPrefetchLaneCheckpoint(
        sourceBoxNo = 17,
        sourceBoxCandidates = listOf(17, 18),
        targetPanNo = target,
        currentAction = action,
        lifecycle = lifecycle,
        lastConfirmedStage = if (lifecycle.ordinal >= SupplementActionLifecycle.TERMINAL_CONFIRMED.ordinal) action else null,
        stockBefore = 10,
        expectedStockAfter = 9
    )

    private fun expectIllegalState(block: () -> Unit) {
        try { block(); fail("expected IllegalStateException") } catch (_: IllegalStateException) { }
    }

    private fun expectCorruptedSession(block: () -> Unit) {
        try { block(); fail("expected SupplementPrefetchSessionCorruptedException") }
        catch (_: SupplementPrefetchSessionCorruptedException) { }
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class MemoryStore : SupplementPrefetchSessionStore {
        private var value: SupplementPrefetchPipelineSession? = null
        override fun load() = value
        override fun save(session: SupplementPrefetchPipelineSession) { value = session }
        override fun clear() { value = null }
    }

    companion object {
        private const val LIVE_DELIVERED_CHECKPOINT = """{"barrierState":"DELIVERY_COMMITTED","batchId":"supplement:1784513735981:1-2-3-4-5-6-7-8-9","deliveryLane":{"boxDeductionApplied":true,"currentAction":"in7","expectedStockAfter":4,"lastConfirmedStage":"in7","lifecycle":"PHYSICAL_APPLIED","panPlacementApplied":true,"sourceBoxCandidates":[10,9],"sourceBoxNo":9,"stockBefore":5,"targetPanNo":7,"taste":"C"},"phase":"RUNNING","resourceMask":7,"revision":40,"schemaVersion":1,"sessionId":"in_pipeline:1784513895477","startedAtMs":1784513895478}"""
    }
}
