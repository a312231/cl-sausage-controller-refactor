package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementRuntimeResumeSafetyGateTest {

    @Test
    fun stableIdleOrCompletedSnapshots_shouldAllowResumeWithoutDispatchingAnything() = runBlocking {
        val reader = SnapshotReader(
            first = mapOf("in1" to AtomicActionCodes.COMPLETED, "in2" to AtomicActionCodes.COMPLETED),
            second = mapOf("in1" to AtomicActionCodes.COMPLETED, "in2" to AtomicActionCodes.COMPLETED)
        )

        val result = SupplementRuntimeResumeSafetyGate(reader, betweenSnapshots = {}).verify("in2")

        assertEquals(SupplementRuntimeResumeSafetyResult.Safe, result)
        assertTrue(reader.dispatchedActions.isEmpty())
    }

    @Test
    fun runningOrChangingSnapshot_shouldRejectResumeWithoutWaitingOrDispatching() = runBlocking {
        val running = SnapshotReader(
            first = mapOf("in3" to AtomicActionCodes.RUNNING),
            second = mapOf("in3" to AtomicActionCodes.RUNNING)
        )
        val changing = SnapshotReader(
            first = mapOf("in2" to AtomicActionCodes.COMPLETED),
            second = mapOf("in2" to AtomicActionCodes.IDLE)
        )

        val runningResult = SupplementRuntimeResumeSafetyGate(running, betweenSnapshots = {}).verify("in2")
        val changingResult = SupplementRuntimeResumeSafetyGate(changing, betweenSnapshots = {}).verify("in2")

        assertTrue(runningResult is SupplementRuntimeResumeSafetyResult.Unsafe)
        assertTrue(changingResult is SupplementRuntimeResumeSafetyResult.Unsafe)
        assertTrue(running.dispatchedActions.isEmpty())
        assertTrue(changing.dispatchedActions.isEmpty())
    }

    @Test
    fun readFailureOrActionError_shouldRejectResume() = runBlocking {
        val readFailure = SnapshotReader(failAction = "in4")
        val actionError = SnapshotReader(
            first = mapOf("in5" to AtomicActionCodes.PARAM_ERROR),
            second = mapOf("in5" to AtomicActionCodes.PARAM_ERROR)
        )

        assertTrue(
            SupplementRuntimeResumeSafetyGate(readFailure, betweenSnapshots = {}).verify("in3") is
                SupplementRuntimeResumeSafetyResult.Unsafe
        )
        assertTrue(
            SupplementRuntimeResumeSafetyGate(actionError, betweenSnapshots = {}).verify("in3") is
                SupplementRuntimeResumeSafetyResult.Unsafe
        )
    }

    @Test
    fun completedActionAfterDurableCheckpoint_shouldRejectResumeAsContradictory() = runBlocking {
        val reader = SnapshotReader(
            first = mapOf(
                "in1" to AtomicActionCodes.COMPLETED,
                "in2" to AtomicActionCodes.COMPLETED,
                "in3" to AtomicActionCodes.COMPLETED
            )
        )

        val result = SupplementRuntimeResumeSafetyGate(reader, betweenSnapshots = {}).verify("in2")

        assertTrue(result is SupplementRuntimeResumeSafetyResult.Unsafe)
        assertTrue(reader.dispatchedActions.isEmpty())
    }

    @Test
    fun idleAtOrBeforeDurableCheckpoint_shouldRejectResumeAsMissingCompletionProof() = runBlocking {
        val reader = SnapshotReader(
            first = mapOf("in1" to AtomicActionCodes.COMPLETED)
        )

        val result = SupplementRuntimeResumeSafetyGate(reader, betweenSnapshots = {}).verify("in2")

        assertTrue(result is SupplementRuntimeResumeSafetyResult.Unsafe)
        assertTrue(reader.dispatchedActions.isEmpty())
    }

    private class SnapshotReader(
        private val first: Map<String, Int> = emptyMap(),
        private val second: Map<String, Int> = first,
        private val failAction: String? = null
    ) : AtomicActionStateReader, AtomicActionClient {
        val dispatchedActions = mutableListOf<String>()
        private var reads = 0

        override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
            if (spec.name == failAction) return AtomicReadResult.Failure("injected")
            val snapshot = if (reads / ActionGroupRecoveryBootstrap.allSpecs.size == 0) first else second
            reads += 1
            return AtomicReadResult.Success(snapshot[spec.name] ?: AtomicActionCodes.IDLE)
        }

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            dispatchedActions += spec.name
            return AtomicActionResult.Success
        }
    }
}
