package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SnapshotHttpErrorEvidenceCodecTest {

    @Test
    fun encode_bodyAndException_preservesBoundedStatusTypeMessageAndBody() {
        val content = SnapshotHttpErrorEvidenceCodec.encode(
            httpStatus = 502,
            exception = SocketTimeoutException("read timed out requestId=volatile"),
            responseBody = "B".repeat(600)
        )

        val evidence = requireNotNull(SnapshotHttpErrorEvidenceCodec.decode(content))
        assertEquals(502, evidence.httpStatus)
        assertEquals(SocketTimeoutException::class.java.name, evidence.exceptionType)
        assertEquals("read timed out requestId=volatile", evidence.exceptionMessage)
        assertEquals(SnapshotHttpErrorEvidenceCodec.RESPONSE_BODY_LIMIT, evidence.responseBody?.length)
        assertTrue(evidence.responseBody?.all { it == 'B' } == true)
    }

    @Test
    fun encode_onlyBody_preservesBodyWithoutInventingException() {
        val evidence = requireNotNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                SnapshotHttpErrorEvidenceCodec.encode(
                    httpStatus = null,
                    exception = null,
                    responseBody = "upstream unavailable"
                )
            )
        )

        assertNull(evidence.httpStatus)
        assertNull(evidence.exceptionType)
        assertNull(evidence.exceptionMessage)
        assertEquals("upstream unavailable", evidence.responseBody)
    }

    @Test
    fun encode_onlyException_preservesTypeAndBoundedMessageWithoutInventingBody() {
        val evidence = requireNotNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                SnapshotHttpErrorEvidenceCodec.encode(
                    httpStatus = null,
                    exception = IllegalStateException("M".repeat(400)),
                    responseBody = null
                )
            )
        )

        assertNull(evidence.httpStatus)
        assertEquals(IllegalStateException::class.java.name, evidence.exceptionType)
        assertEquals(SnapshotHttpErrorEvidenceCodec.EXCEPTION_MESSAGE_LIMIT, evidence.exceptionMessage?.length)
        assertNull(evidence.responseBody)
    }

    @Test
    fun decode_rejectsOrdinaryBusinessJsonMalformedEnvelopeAndWrongFieldTypesWithoutThrowing() {
        assertNull(SnapshotHttpErrorEvidenceCodec.decode("""{"code":500,"msg":"business error"}"""))
        assertNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                """{"kind":"snapshot_http_error","httpStatus":502,"responseBody":"forged"}"""
            )
        )
        assertNull(SnapshotHttpErrorEvidenceCodec.decode(SnapshotHttpErrorEvidenceCodec.PREFIX + "{broken"))
        assertNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                SnapshotHttpErrorEvidenceCodec.PREFIX + """{"httpStatus":"502"}"""
            )
        )
        assertNull(
            SnapshotHttpErrorEvidenceCodec.decode(
                SnapshotHttpErrorEvidenceCodec.PREFIX + """{"exceptionType":123}"""
            )
        )
    }
}
