package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSnapshotReportResponseParserTest {

    @Test
    fun parse_shouldAcceptBusinessSuccessResponse() {
        val result = CloudSnapshotReportResponseParser.parse("""{"code":200,"msg":"操作成功"}""")

        assertTrue(result.accepted)
    }

    @Test
    fun parse_shouldRejectIgnoredOldSnapshotResponse() {
        val result = CloudSnapshotReportResponseParser.parse("""{"code":200,"msg":"已忽略旧快照"}""")

        assertFalse(result.accepted)
    }

    @Test
    fun parse_shouldRejectIncomingSequenceBehindCurrentSequenceResponse() {
        val result = CloudSnapshotReportResponseParser.parse(
            """{"code":200,"msg":"incomingSequence < currentSequence, snapshot ignored"}"""
        )

        assertFalse(result.accepted)
    }

    @Test
    fun parse_shouldRejectBusinessFailureResponse() {
        val result = CloudSnapshotReportResponseParser.parse("""{"code":500,"msg":"系统错误"}""")

        assertFalse(result.accepted)
    }

    @Test
    fun parse_shouldRejectInvalidJsonResponse() {
        val result = CloudSnapshotReportResponseParser.parse("ok")

        assertFalse(result.accepted)
    }
}
