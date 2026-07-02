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
    fun parse_shouldReadPhysicalPanDeltaSeqFromSuccessData() {
        val result = CloudSnapshotReportResponseParser.parse(
            """{"code":200,"msg":"快照同步成功","data":{"panDeltaSeq":7}}"""
        )

        assertTrue(result.accepted)
        org.junit.Assert.assertEquals(7L, result.panDeltaSeq)
    }

    @Test
    fun parse_shouldRejectIgnoredOldSnapshotResponse() {
        val result = CloudSnapshotReportResponseParser.parse("""{"code":200,"msg":"已忽略旧快照"}""")

        assertFalse(result.accepted)
    }

    @Test
    fun parse_shouldAcceptSameHashIgnoredResponse() {
        val result = CloudSnapshotReportResponseParser.parse(
            """{"code":200,"msg":"相同快照已忽略","data":{"ignored":true,"reason":"same_hash"}}"""
        )

        assertTrue(result.accepted)
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
