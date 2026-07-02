package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun shouldReportToCloudForTest_shouldDropNormalNoChangeAndActionCommunicationNoise() {
        assertFalse(
            AppLogCloudReporter.shouldReportToCloudForTest(
                "D",
                "2026-07-02 23:50:37.800 D/KaoChang: 【云端同步】设备快照投影未变化，跳过HTTP同步：reason=poll_loop，skipCount=1"
            )
        )
        assertFalse(
            AppLogCloudReporter.shouldReportToCloudForTest(
                "I",
                "2026-07-02 17:10:11.000 I/KaoChang: [300/400动作组] 【动作组通信】下发完成，动作=in4，结果寄存器=341"
            )
        )
        assertFalse(
            AppLogCloudReporter.shouldReportToCloudForTest(
                "I",
                "2026-07-02 17:10:11.000 I/KaoChang: 【取肠语音】 取肠提醒播放完成"
            )
        )
        assertFalse(
            AppLogCloudReporter.shouldReportToCloudForTest(
                "I",
                "2026-07-02 17:10:11.000 I/KaoChang: 【管理员操作】管理员无操作超时，自动退出管理界面"
            )
        )
    }

    @Test
    fun shouldReportToCloudForTest_shouldKeepKeyFailuresAndMaintenanceEvents() {
        assertTrue(AppLogCloudReporter.shouldReportToCloudForTest("W", "【云端同步】云端未应用设备状态：状态=维护中，原因=conflicting_runtime_seq"))
        assertTrue(AppLogCloudReporter.shouldReportToCloudForTest("E", "动作组恢复失败：读取 out1 结果寄存器 411 失败"))
        assertTrue(AppLogCloudReporter.shouldReportToCloudForTest("I", "【维护操作】恢复正常状态：页面=烤盘信息，操作结果=完成"))
        assertTrue(AppLogCloudReporter.shouldReportToCloudForTest("I", "【动作组通信】动作结束，动作=in5，结果=失败，结果值=0x00E5"))
    }

    @Test
    fun cloudPublishPolicyForTest_shouldAvoidCoalescingWarningAndErrorLogs() {
        assertEquals(MqttPublishPriority.HIGH, AppLogCloudReporter.cloudPriorityForTest("W", "【故障停热】开始故障停热暂停"))
        assertNull(AppLogCloudReporter.cloudCoalesceKeyForTest("W", "【故障停热】开始故障停热暂停"))
        assertEquals(MqttPublishPriority.HIGH, AppLogCloudReporter.cloudPriorityForTest("E", "动作组恢复失败"))
        assertNull(AppLogCloudReporter.cloudCoalesceKeyForTest("E", "动作组恢复失败"))
        assertEquals(MqttPublishPriority.LOW, AppLogCloudReporter.cloudPriorityForTest("I", "【订单】接单"))
        assertEquals("app_log", AppLogCloudReporter.cloudCoalesceKeyForTest("I", "【订单】接单"))
    }
}
