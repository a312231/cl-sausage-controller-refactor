package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SnapshotSyncFailureLogPolicyTest {

    @Test
    fun sameFailure_suppressesVolatileRetryRoundsButSummarizesPeriodically() {
        val intervalMs = 5 * 60 * 1000L
        val policy = SnapshotSyncFailureLogPolicy(summaryIntervalMs = intervalMs)
        val failure = conflictIdentity(physicalPanDeltaSeq = 890L, snapshotHash = "hash-a")

        val first = policy.evaluate(failure, nowMs = 1_000L)
        assertTrue(first?.stateChanged == true)
        assertNull(policy.evaluate(failure, nowMs = 11_000L))

        val summary = policy.evaluate(failure, nowMs = 301_000L)
        assertTrue(summary?.stateChanged == false)
        assertEquals(2, summary?.suppressedCount)
    }

    @Test
    fun criticalIdentityChanges_emitImmediatelyAndRecoveryResetRestoresFirstEdge() {
        val policy = SnapshotSyncFailureLogPolicy(summaryIntervalMs = 5 * 60 * 1000L)
        val base = conflictIdentity(physicalPanDeltaSeq = 890L, snapshotHash = "hash-a")

        assertTrue(policy.evaluate(base, nowMs = 1_000L)?.stateChanged == true)
        assertTrue(
            policy.evaluate(base.copy(snapshotReason = "poll_loop:fallback_full", forceFullSnapshot = true), 2_000L)
                ?.stateChanged == true
        )
        assertTrue(policy.evaluate(base.copy(snapshotHash = "hash-b"), 3_000L)?.stateChanged == true)
        assertTrue(policy.evaluate(base.copy(physicalPanDeltaSeq = 891L), 4_000L)?.stateChanged == true)
        assertTrue(policy.evaluate(base.copy(cloudReason = "full_state_hash_mismatch"), 5_000L)?.stateChanged == true)

        policy.reset()
        assertTrue(policy.evaluate(base, nowMs = 6_000L)?.stateChanged == true)
    }

    @Test
    fun transportStatusAndExceptionTypeChanges_emitEdgesButSameErrorStaysSuppressed() {
        val policy = SnapshotSyncFailureLogPolicy(summaryIntervalMs = 5 * 60 * 1000L)
        val base = transportIdentity(
            httpStatus = 500,
            exception = SocketTimeoutException("read timed out requestId=one"),
            responseBody = "{\"requestId\":\"one\"}"
        )
        val sameStableErrorWithDifferentVolatileEvidence = transportIdentity(
            httpStatus = 500,
            exception = SocketTimeoutException("read timed out requestId=two"),
            responseBody = "{\"requestId\":\"two\"}"
        )
        val changedStatus = transportIdentity(
            httpStatus = 503,
            exception = SocketTimeoutException("read timed out requestId=three"),
            responseBody = "temporarily unavailable"
        )
        val changedExceptionType = transportIdentity(
            httpStatus = 503,
            exception = IllegalStateException("connection reset requestId=four"),
            responseBody = "temporarily unavailable"
        )

        assertTrue(policy.evaluate(base, nowMs = 1_000L)?.stateChanged == true)
        assertNull(policy.evaluate(sameStableErrorWithDifferentVolatileEvidence, nowMs = 11_000L))
        assertTrue(policy.evaluate(changedStatus, nowMs = 21_000L)?.stateChanged == true)
        assertTrue(policy.evaluate(changedExceptionType, nowMs = 31_000L)?.stateChanged == true)
        assertNull(policy.evaluate(changedExceptionType, nowMs = 41_000L))
    }

    private fun transportIdentity(
        httpStatus: Int?,
        exception: Throwable?,
        responseBody: String?
    ): SnapshotSyncFailureIdentity {
        val evidence = requireNotNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                SnapshotHttpErrorEvidenceCodec.encode(httpStatus, exception, responseBody)
            )
        )
        return SnapshotSyncFailureIdentity.create(
            callbackCode = 1,
            cloudReason = "volatile cloud reason should not enter transport key",
            physicalPanDeltaSeq = 890L,
            snapshotHash = "hash-a",
            snapshotReason = "poll_loop",
            forceFullSnapshot = false,
            httpEvidence = evidence
        )
    }

    private fun conflictIdentity(
        physicalPanDeltaSeq: Long,
        snapshotHash: String
    ): SnapshotSyncFailureIdentity = SnapshotSyncFailureIdentity(
        callbackCode = 1,
        cloudReason = "conflicting_physical_pan_delta_seq",
        physicalPanDeltaSeq = physicalPanDeltaSeq,
        snapshotHash = snapshotHash,
        snapshotReason = "poll_loop",
        forceFullSnapshot = false,
        httpStatus = null,
        exceptionType = null
    )
}
