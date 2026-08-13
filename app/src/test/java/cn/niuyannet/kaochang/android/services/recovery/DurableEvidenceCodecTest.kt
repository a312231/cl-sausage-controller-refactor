package cn.niuyannet.kaochang.android.services.recovery

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableEvidenceCodecTest {
    @Test
    fun blankRaw_shouldBeAbsent() {
        assertEquals(DurableEvidenceReadResult.Absent, DurableEvidenceCodec.read<String>(null) { true })
    }

    @Test
    fun malformedRaw_shouldBeCorruptedWithoutExposingPayload() {
        val result = DurableEvidenceCodec.read<String>("{broken") { true }
        assertTrue(result is DurableEvidenceReadResult.Corrupted)
        result as DurableEvidenceReadResult.Corrupted
        assertEquals(7, result.rawLength)
        assertEquals(64, result.fingerprint.length)
        assertTrue(!result.reason.contains("{broken"))
    }

    @Test
    fun invariantFailure_shouldBeCorrupted() {
        val result = DurableEvidenceCodec.read<String>("{\"value\":1}") { false }
        assertTrue(result is DurableEvidenceReadResult.Corrupted)
    }

    @Test
    fun validRaw_shouldBeValid() {
        val result = DurableEvidenceCodec.read<String>("\"valid\"") { it == "valid" }
        assertEquals(DurableEvidenceReadResult.Valid("valid"), result)
    }

    @Test
    fun parser_shouldPreserveFatalCancellationAndInterruption() {
        assertPropagates<AssertionError> {
            DurableEvidenceCodec.read("raw", { throw AssertionError("fatal") }) { true }
        }
        assertPropagates<CancellationException> {
            DurableEvidenceCodec.read("raw", { throw CancellationException("cancel") }) { true }
        }
        Thread.interrupted()
        try {
            assertPropagates<InterruptedException> {
                DurableEvidenceCodec.read("raw", { throw InterruptedException("interrupt") }) { true }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun validator_shouldPreserveFatalCancellationAndInterruption() {
        assertPropagates<AssertionError> {
            DurableEvidenceCodec.read("raw", { "parsed" }) { throw AssertionError("fatal") }
        }
        assertPropagates<CancellationException> {
            DurableEvidenceCodec.read("raw", { "parsed" }) { throw CancellationException("cancel") }
        }
        Thread.interrupted()
        try {
            assertPropagates<InterruptedException> {
                DurableEvidenceCodec.read("raw", { "parsed" }) { throw InterruptedException("interrupt") }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private inline fun <reified T : Throwable> assertPropagates(block: () -> Unit) {
        var captured: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            captured = error
        }
        if (captured == null) throw AssertionError("expected ${T::class.java.simpleName}")
        if (captured !is T) throw captured
    }
}
