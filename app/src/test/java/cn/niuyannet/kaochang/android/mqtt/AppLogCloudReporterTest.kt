package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogCloudReporterTest {

    @Test
    fun buildPayloadForTest_shouldUseAppLogEntryFormat() {
        val payload = AppLogCloudReporter.buildPayloadForTest(
            level = "I",
            tag = "KaoChang",
            content = "2026-05-06 12:00:00.000 I/KaoChang: 【订单】接单",
            reportedAt = 1778040000000L
        )

        assertEquals(MqttProtocol.TYPE_APP_LOG_ENTRY, payload.getString("messageType"))
        assertEquals("AppLog-I", payload.getString("title"))
        assertEquals("2026-05-06 12:00:00.000 I/KaoChang: 【订单】接单", payload.getString("content"))
        assertEquals("I", payload.getString("level"))
        assertEquals("KaoChang", payload.getString("tag"))
        assertEquals(1778040000000L, payload.getLongValue("reportedAt"))
    }
}
