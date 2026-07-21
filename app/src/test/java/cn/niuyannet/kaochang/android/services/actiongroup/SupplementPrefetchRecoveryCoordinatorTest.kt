package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPrefetchRecoveryCoordinatorTest {

    @Test
    fun noSession_shouldReturnIdleAndAllowGenericRecovery() = runBlocking {
        val coordinator = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(),
            SupplementPrefetchSessionRepository(MemoryStore()),
            betweenSnapshots = {}
        )

        assertEquals(SupplementPrefetchRecoveryResult.Idle, coordinator.recover())
    }

    @Test
    fun legalDualRunning_shouldOnlyMonitorAndRestoreRunningPhase() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(
                    17,
                    13,
                    "in6",
                    SupplementActionLifecycle.RUNNING_CONFIRMED,
                    boxDeductionApplied = true
                ),
                prefetch = lane(18, 14, "in1", SupplementActionLifecycle.RUNNING_CONFIRMED)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )
        val client = RecoveryClient(
            states = mutableMapOf("in6" to AtomicActionCodes.RUNNING, "in1" to AtomicActionCodes.RUNNING),
            completeAfterSnapshotReads = setOf("in6", "in1")
        )

        val result = SupplementPrefetchRecoveryCoordinator(client, repository, betweenSnapshots = {}).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.ReadyToResume)
        assertTrue(client.commandDispatches.isEmpty())
        assertEquals(SupplementPipelinePhase.RUNNING, repository.load()?.phase)
    }

    @Test
    fun illegalDualRunning_shouldPersistMaintenanceAndRetainSession() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in5", SupplementActionLifecycle.RUNNING_CONFIRMED),
                prefetch = lane(18, 14, "in2", SupplementActionLifecycle.RUNNING_CONFIRMED)
            )
        )
        val client = RecoveryClient(
            states = mutableMapOf("in5" to AtomicActionCodes.RUNNING, "in2" to AtomicActionCodes.RUNNING)
        )

        val result = SupplementPrefetchRecoveryCoordinator(client, repository, betweenSnapshots = {}).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertNotNull(repository.load())
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
        assertTrue(client.commandDispatches.isEmpty())
    }

    @Test
    fun completedWithoutRunningEvidence_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(delivery = lane(17, 13, "in6", SupplementActionLifecycle.COMMAND_WRITTEN))
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun deliveryAfterIn2WithoutDeductionEvidence_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(delivery = lane(17, 13, "in6", SupplementActionLifecycle.TERMINAL_CONFIRMED))
                .copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun unstableSnapshot_shouldRequireMaintenanceWithoutDispatch() = runBlocking {
        val repository = repository(
            session(delivery = lane(17, 13, "in6", SupplementActionLifecycle.RUNNING_CONFIRMED))
        )
        val client = RecoveryClient(
            states = mutableMapOf("in6" to AtomicActionCodes.RUNNING),
            secondSnapshotOverrides = mapOf("in6" to AtomicActionCodes.COMPLETED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            client,
            repository,
            betweenSnapshots = { client.useSecondSnapshot = true }
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertTrue(client.commandDispatches.isEmpty())
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun singlePrefetchIn4Running_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in6", SupplementActionLifecycle.TERMINAL_CONFIRMED, true),
                prefetch = lane(18, 14, "in4", SupplementActionLifecycle.RUNNING_CONFIRMED, true)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED, "in4" to AtomicActionCodes.RUNNING)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun singlePrefetchIn4Completed_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in6", SupplementActionLifecycle.TERMINAL_CONFIRMED, true),
                prefetch = lane(18, 14, "in4", SupplementActionLifecycle.RUNNING_CONFIRMED, true)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED, "in4" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun dualCheckpointWithDeliveryBeforeIn6_shouldRequireMaintenanceEvenWhenNothingRuns() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in5", SupplementActionLifecycle.TERMINAL_CONFIRMED, true),
                prefetch = lane(18, 14, "in2", SupplementActionLifecycle.TERMINAL_CONFIRMED, true)
            )
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in5" to AtomicActionCodes.COMPLETED, "in2" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun committedDeliveredPendingOrder_shouldFinalizeAsSafePausedDuringRecovery() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in7", SupplementActionLifecycle.PHYSICAL_APPLIED, true)
                    .copy(panPlacementApplied = true)
            ).copy(
                barrierState = SupplementBarrierState.DELIVERY_COMMITTED,
                pendingOrderNo = "ORDER-AFTER-COMMIT"
            )
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in7" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.PausedForOrder)
        assertTrue(SupplementPrefetchOrderPauseSafety.isSafe(requireNotNull(repository.load())))
    }

    @Test
    fun untrackedInRunning_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in6", SupplementActionLifecycle.RUNNING_CONFIRMED, true)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )
        val client = RecoveryClient(
            states = mutableMapOf("in6" to AtomicActionCodes.RUNNING, "in4" to AtomicActionCodes.RUNNING),
            completeAfterSnapshotReads = setOf("in6")
        )

        val result = SupplementPrefetchRecoveryCoordinator(client, repository, betweenSnapshots = {}).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun untrackedOutRunning_shouldRequireMaintenance() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(17, 13, "in6", SupplementActionLifecycle.TERMINAL_CONFIRMED, true)
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED, "out2" to AtomicActionCodes.RUNNING)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun terminalCheckpointDurableFailure_shouldReturnMaintenanceAndPersistMaintenancePhase() = runBlocking {
        val store = FailOnceStore(failSaveAt = 3)
        val repository = SupplementPrefetchSessionRepository(store).also {
            it.create(
                session(
                    delivery = lane(
                        17,
                        13,
                        "in6",
                        SupplementActionLifecycle.RUNNING_CONFIRMED,
                        boxDeductionApplied = true
                    )
                ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
            )
        }

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun postSnapshotDurableLoadFailure_shouldReturnMaintenanceAndPersistMaintenancePhase() = runBlocking {
        val store = FailOnceStore(failLoadAt = 4)
        val repository = SupplementPrefetchSessionRepository(store).also {
            it.create(
                session(
                    delivery = lane(
                        17,
                        13,
                        "in6",
                        SupplementActionLifecycle.TERMINAL_CONFIRMED,
                        boxDeductionApplied = true
                    )
                ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
            )
        }

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.COMPLETED)),
            repository,
            betweenSnapshots = {}
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun betweenSnapshotsFailure_shouldReturnMaintenanceInsteadOfEscaping() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(
                    17,
                    13,
                    "in6",
                    SupplementActionLifecycle.RUNNING_CONFIRMED,
                    boxDeductionApplied = true
                )
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        val result = SupplementPrefetchRecoveryCoordinator(
            RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.RUNNING)),
            repository,
            betweenSnapshots = { error("snapshot delay failed") }
        ).recover()

        assertTrue(result is SupplementPrefetchRecoveryResult.MaintenanceRequired)
        assertEquals(SupplementPipelinePhase.MAINTENANCE_REQUIRED, repository.load()?.phase)
    }

    @Test
    fun cancellation_shouldPropagateWithoutClearingRecoveryEvidence() = runBlocking {
        val repository = repository(
            session(
                delivery = lane(
                    17,
                    13,
                    "in6",
                    SupplementActionLifecycle.RUNNING_CONFIRMED,
                    boxDeductionApplied = true
                )
            ).copy(barrierState = SupplementBarrierState.DELIVERY_COMMITTED)
        )

        try {
            SupplementPrefetchRecoveryCoordinator(
                RecoveryClient(states = mutableMapOf("in6" to AtomicActionCodes.RUNNING)),
                repository,
                betweenSnapshots = { throw CancellationException("cancel recovery") }
            ).recover()
            throw AssertionError("CancellationException must propagate")
        } catch (_: CancellationException) {
            assertNotNull(repository.load())
            assertEquals(SupplementPipelinePhase.RECOVERING, repository.load()?.phase)
        }
    }

    private fun repository(initial: SupplementPrefetchPipelineSession): SupplementPrefetchSessionRepository {
        return SupplementPrefetchSessionRepository(MemoryStore()).also { it.create(initial) }
    }

    private fun session(
        delivery: SupplementPrefetchLaneCheckpoint,
        prefetch: SupplementPrefetchLaneCheckpoint? = null
    ) = SupplementPrefetchPipelineSession(
        sessionId = "recovery-session",
        resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
        deliveryLane = delivery,
        prefetchLane = prefetch
    )

    private fun lane(
        box: Int,
        pan: Int,
        action: String,
        lifecycle: SupplementActionLifecycle,
        boxDeductionApplied: Boolean = false
    ) = SupplementPrefetchLaneCheckpoint(
        sourceBoxNo = box,
        sourceBoxCandidates = listOf(box),
        targetPanNo = pan,
        currentAction = action,
        lifecycle = lifecycle,
        lastConfirmedStage = action.takeIf {
            lifecycle >= SupplementActionLifecycle.TERMINAL_CONFIRMED
        },
        boxDeductionApplied = boxDeductionApplied
    )

    private class RecoveryClient(
        private val states: MutableMap<String, Int> = mutableMapOf(),
        private val secondSnapshotOverrides: Map<String, Int> = emptyMap(),
        private val completeAfterSnapshotReads: Set<String> = emptySet()
    ) : AtomicActionClient, AtomicActionStateReader {
        val commandDispatches = mutableListOf<String>()
        private val readCounts = mutableMapOf<String, Int>()
        var useSecondSnapshot: Boolean = false

        override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
            val count = (readCounts[spec.name] ?: 0) + 1
            readCounts[spec.name] = count
            val value = when {
                completeAfterSnapshotReads.contains(spec.name) && count > 2 -> AtomicActionCodes.COMPLETED
                useSecondSnapshot -> secondSnapshotOverrides[spec.name] ?: states[spec.name]
                else -> states[spec.name]
            }
            return AtomicReadResult.Success(value ?: AtomicActionCodes.IDLE)
        }

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            commandDispatches += spec.name
            return AtomicActionResult.Success
        }
    }

    private class MemoryStore : SupplementPrefetchSessionStore {
        private var value: SupplementPrefetchPipelineSession? = null
        override fun load(): SupplementPrefetchPipelineSession? = value
        override fun save(session: SupplementPrefetchPipelineSession) { value = session }
        override fun clear() { value = null }
    }

    private class FailOnceStore(
        private val failSaveAt: Int = -1,
        private val failLoadAt: Int = -1
    ) : SupplementPrefetchSessionStore {
        private var value: SupplementPrefetchPipelineSession? = null
        private var saveCount = 0
        private var loadCount = 0

        override fun load(): SupplementPrefetchPipelineSession? {
            loadCount += 1
            if (loadCount == failLoadAt) error("injected load failure")
            return value
        }

        override fun save(session: SupplementPrefetchPipelineSession) {
            saveCount += 1
            if (saveCount == failSaveAt) error("injected save failure")
            value = session
        }

        override fun clear() {
            value = null
        }
    }
}
