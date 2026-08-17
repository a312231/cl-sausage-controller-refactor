package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotUploadFailureCompletionTest {

    @Test
    fun complete_malformedCodecInput_keepsPersistThenCallbackOrder() {
        val events = mutableListOf<String>()
        var callbackResult: Boolean? = null

        SnapshotUploadFailureCompletion.complete(
            persistFailure = {
                events += "persist"
                pendingPayload()
            },
            processEvidence = {
                events += "evidence"
                assertNull(
                    SnapshotHttpErrorEvidenceCodec.decode(
                        SnapshotHttpErrorEvidenceCodec.PREFIX + "{malformed"
                    )
                )
            },
            notifyResult = { success ->
                events += "callback"
                callbackResult = success
            }
        )

        assertEquals(listOf("persist", "evidence", "callback"), events)
        assertEquals(false, callbackResult)
    }

    @Test
    fun complete_evidenceProcessingThrows_stillNotifiesFailureAfterPersistence() {
        val events = mutableListOf<String>()
        var thrown = false

        try {
            SnapshotUploadFailureCompletion.complete(
                persistFailure = {
                    events += "persist"
                    pendingPayload()
                },
                processEvidence = {
                    events += "evidence"
                    throw IllegalArgumentException("decode failed")
                },
                notifyResult = { success ->
                    events += "callback:$success"
                }
            )
        } catch (_: IllegalArgumentException) {
            thrown = true
        }

        assertTrue(thrown)
        assertEquals(listOf("persist", "evidence", "callback:false"), events)
    }

    private fun pendingPayload(): CloudSyncRetryStore.PendingPayload = CloudSyncRetryStore.PendingPayload(
        type = CloudSyncRetryStore.Type.SNAPSHOT,
        payload = "snapshot",
        savedAtMillis = 1L,
        failureCount = 1,
        lastFailureAtMillis = 1L,
        lastError = "failure"
    )
}
