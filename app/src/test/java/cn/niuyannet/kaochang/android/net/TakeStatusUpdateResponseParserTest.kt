package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeStatusUpdateResponseParserTest {
    @Test
    fun parse_shouldReturnAuthoritativeStockFromAcceptedResponse() {
        val result = TakeStatusUpdateResponseParser.parse(
            """{"code":200,"data":{"accepted":true,"stocks":[{"positionSn":21,"cmdStatusTake":229,"takeStatusRevision":8}]}}"""
        )

        assertTrue(result.confirmed)
        assertEquals(21, result.authoritativeStock?.positionSn)
        assertEquals(229, result.authoritativeStock?.cmdStatusTake)
        assertEquals(8L, result.authoritativeStock?.takeStatusRevision)
    }

    @Test
    fun parse_shouldExposeAuthoritativeStockFromConflictResponse() {
        val result = TakeStatusUpdateResponseParser.parse(
            """{"code":500,"data":{"accepted":false,"reason":"CONFLICT","stocks":[{"positionSn":21,"cmdStatusTake":0,"takeStatusRevision":9}]}}"""
        )

        assertFalse(result.confirmed)
        assertEquals(9L, result.authoritativeStock?.takeStatusRevision)
    }
}
