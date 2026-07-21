package cn.niuyannet.kaochang.android.services.recovery

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
}
