package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.DiscardJournal
import cn.niuyannet.kaochang.android.services.PanMoveJournal
import cn.niuyannet.kaochang.android.services.SharedPreferenceDiscardJournalStore
import cn.niuyannet.kaochang.android.services.SharedPreferencePanMoveJournalStore
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictReadinessStoreControlFlowTest {

    @Test
    fun realStoreDecoders_classifyOrdinaryExceptionsWithoutTreatingThemAsAbsent() {
        expectIllegalState {
            OrderDeliveryClaimStore(
                readRaw = { "raw" },
                writeRaw = { true },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).load()
        }
        expectIllegalState {
            EmergencyResetRecoveryFenceStore(
                readRaw = { "raw" },
                writeRawDurably = { true },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).load()
        }
        assertEquals(
            0,
            SharedPreferencePanMoveJournalStore(
                readRaw = { "raw" },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).load()?.sourcePositionSn
        )
        assertEquals(
            0,
            SharedPreferenceDiscardJournalStore(
                readRaw = { "raw" },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).load()?.positionSn
        )
        assertTrue(
            SharedPreferenceActionGroupSessionStore(
                readRaw = { "raw" },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).readEvidence() is DurableEvidenceReadResult.Corrupted
        )
        assertTrue(
            SharedPreferenceSupplementPrefetchSessionStore(
                readRaw = { "raw" },
                decodeRaw = { throw IllegalArgumentException("broken") }
            ).read() is DurableEvidenceReadResult.Corrupted
        )
    }

    @Test
    fun realStoreDecoders_preserveFatalCancellationAndInterruption() {
        verifyStrictDecoder<OrderDeliveryClaim> { decoder ->
            OrderDeliveryClaimStore(readRaw = { "raw" }, writeRaw = { true }, decodeRaw = decoder).load()
        }
        verifyStrictDecoder<EmergencyResetRecoveryFence> { decoder ->
            EmergencyResetRecoveryFenceStore(
                readRaw = { "raw" },
                writeRawDurably = { true },
                decodeRaw = decoder
            ).load()
        }
        verifyStrictDecoder<PanMoveJournal> { decoder ->
            SharedPreferencePanMoveJournalStore(readRaw = { "raw" }, decodeRaw = decoder).load()
        }
        verifyStrictDecoder<DiscardJournal> { decoder ->
            SharedPreferenceDiscardJournalStore(readRaw = { "raw" }, decodeRaw = decoder).load()
        }
        verifyStrictDecoder<ActionGroupSession> { decoder ->
            SharedPreferenceActionGroupSessionStore(readRaw = { "raw" }, decodeRaw = decoder).readEvidence()
        }
        verifyStrictDecoder<SupplementPrefetchPipelineSession> { decoder ->
            SharedPreferenceSupplementPrefetchSessionStore(readRaw = { "raw" }, decodeRaw = decoder).read()
        }
    }

    @Test
    fun sessionStoreValidators_preserveFatalCancellationAndInterruption() {
        val actionSession = ActionGroupSession("session", ActionGroupSessionType.IN_SUPPLEMENT, 1)
        verifyStrictValidator<ActionGroupSession> { validator ->
            SharedPreferenceActionGroupSessionStore(
                readRaw = { "raw" },
                decodeRaw = { actionSession },
                evidenceValidator = validator
            ).readEvidence()
        }
        val prefetchSession = SupplementPrefetchPipelineSession(sessionId = "prefetch")
        verifyStrictValidator<SupplementPrefetchPipelineSession> { validator ->
            SharedPreferenceSupplementPrefetchSessionStore(
                readRaw = { "raw" },
                decodeRaw = { prefetchSession },
                evidenceValidator = validator
            ).read()
        }
    }

    private fun <T> verifyStrictDecoder(load: ((String) -> T) -> Unit) {
        verifyControlFlow { failure -> load { failure() } }
    }

    private fun <T> verifyStrictValidator(load: ((T) -> Boolean) -> Unit) {
        verifyControlFlow { failure -> load { failure() } }
    }

    private fun verifyControlFlow(run: (() -> Nothing) -> Unit) {
        val fatal = AssertionError("fatal")
        assertSame(fatal, captureThrowable { run { throw fatal } })
        val cancellation = CancellationException("cancel")
        assertSame(cancellation, captureThrowable { run { throw cancellation } })
        Thread.interrupted()
        try {
            val interruption = InterruptedException("interrupt")
            assertSame(interruption, captureThrowable { run { throw interruption } })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun captureThrowable(block: () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        fail("expected throwable")
        throw AssertionError("unreachable")
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }
}
