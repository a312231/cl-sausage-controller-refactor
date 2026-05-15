package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminOperationLoggerTest {

    @Test
    fun buildMessage_shouldUseChineseReadableFields() {
        val message = AdminOperationLogger.buildMessageForTest(
            action = "手动取肠",
            page = "烤盘信息",
            result = "开始",
            detail = "烤盘位=12，口味=原味"
        )

        assertTrue(message.contains("【管理员操作】手动取肠"))
        assertTrue(message.contains("页面=烤盘信息"))
        assertTrue(message.contains("操作结果=开始"))
        assertTrue(message.contains("烤盘位=12"))
        assertTrue(message.contains("口味=原味"))
        assertFalse(message.contains("sessionId"))
        assertFalse(message.contains("panNo"))
        assertFalse(message.contains("boxNo"))
        assertFalse(message.contains("taste"))
        assertFalse(message.contains("detail="))
    }

    @Test
    fun mqttConfigDetail_shouldNotExposePasswordPlaintext() {
        val detail = AdminOperationLogger.mqttConfigDetailForTest(
            oldHost = "tcp://old.example.com",
            newHost = "tcp://new.example.com",
            oldPort = 1883,
            newPort = 1884,
            oldUsername = "old_user",
            newUsername = "new_user",
            oldPassword = "old_secret",
            newPassword = "new_secret"
        )

        assertTrue(detail.contains("服务器地址=tcp://old.example.com->tcp://new.example.com"))
        assertTrue(detail.contains("端口=1883->1884"))
        assertTrue(detail.contains("账号=old_user->new_user"))
        assertTrue(detail.contains("密码是否变更=是"))
        assertFalse(detail.contains("old_secret"))
        assertFalse(detail.contains("new_secret"))
    }
}
