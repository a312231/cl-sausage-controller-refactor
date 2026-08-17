package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.utils.RepeatedLogGate

internal data class SnapshotSyncFailureIdentity(
    val callbackCode: Int,
    val cloudReason: String,
    val physicalPanDeltaSeq: Long,
    val snapshotHash: String,
    val snapshotReason: String?,
    val forceFullSnapshot: Boolean,
    val httpStatus: Int?,
    val exceptionType: String?
) {
    fun stableKey(): String = listOf(
        callbackCode,
        cloudReason,
        physicalPanDeltaSeq,
        snapshotHash,
        snapshotReason ?: "null",
        forceFullSnapshot,
        httpStatus ?: "null",
        exceptionType ?: "null"
    ).joinToString("\u001f")

    companion object {
        fun create(
            callbackCode: Int,
            cloudReason: String,
            physicalPanDeltaSeq: Long,
            snapshotHash: String,
            snapshotReason: String?,
            forceFullSnapshot: Boolean,
            httpEvidence: SnapshotHttpErrorEvidenceCodec.Evidence?
        ): SnapshotSyncFailureIdentity = SnapshotSyncFailureIdentity(
            callbackCode = callbackCode,
            cloudReason = if (httpEvidence == null) {
                cloudReason
            } else {
                SnapshotHttpErrorEvidenceCodec.IDENTITY_REASON
            },
            physicalPanDeltaSeq = physicalPanDeltaSeq,
            snapshotHash = snapshotHash,
            snapshotReason = snapshotReason,
            forceFullSnapshot = forceFullSnapshot,
            httpStatus = httpEvidence?.httpStatus,
            exceptionType = httpEvidence?.exceptionType
        )
    }
}

internal class SnapshotSyncFailureLogPolicy(
    summaryIntervalMs: Long = SUMMARY_INTERVAL_MS
) {
    data class Decision(
        val stateChanged: Boolean,
        val suppressedCount: Int
    )

    private val gate = RepeatedLogGate(summaryIntervalMs)

    fun evaluate(identity: SnapshotSyncFailureIdentity, nowMs: Long): Decision? {
        val decision = gate.evaluate(identity.stableKey(), nowMs) ?: return null
        return Decision(decision.stateChanged, decision.suppressedCount)
    }

    fun reset() {
        gate.reset()
    }

    companion object {
        const val SUMMARY_INTERVAL_MS = 5L * 60L * 1000L
    }
}
