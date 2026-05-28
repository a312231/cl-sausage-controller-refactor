package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDeviceInfoPayloadBuilderTest {

    @Test
    fun buildUpdateDeviceInfoPayload_shouldIncludeFaultFieldsWhenFaultActive() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig().apply {
                errorStatus = 2
                errorCode = "ACTION_GROUP_FAILED"
                errorCategory = "流程类"
                errorReason = "正式出餐 动作组失败：out4"
            }
        )

        assertEquals(2, payload.getIntValue("errorStatus"))
        assertEquals("ACTION_GROUP_FAILED", payload.getString("errorCode"))
        assertEquals("流程类", payload.getString("errorCategory"))
        assertEquals("正式出餐 动作组失败：out4", payload.getString("errorReason"))
    }

    @Test
    fun buildUpdateDeviceInfoPayload_shouldOmitFaultFieldsWhenFaultCleared() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig().apply {
                errorStatus = 0
                errorCode = "ACTION_GROUP_FAILED"
                errorCategory = "流程类"
                errorReason = "旧故障原因"
            }
        )

        assertEquals(0, payload.getIntValue("errorStatus"))
        assertFalse(payload.containsKey("errorCode"))
        assertFalse(payload.containsKey("errorCategory"))
        assertFalse(payload.containsKey("errorReason"))
    }

    @Test
    fun buildUpdateDeviceInfoPayload_shouldSkipBlankFaultFields() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig().apply {
                errorStatus = 2
                errorCode = " "
                errorCategory = ""
                errorReason = "动作组失败"
            }
        )

        assertFalse(payload.containsKey("errorCode"))
        assertFalse(payload.containsKey("errorCategory"))
        assertTrue(payload.containsKey("errorReason"))
    }

    private fun baseConfig(): AppConfigBean =
        AppConfigBean().apply {
            errorStatus = 0
            onlineStatus = 1
            supplyStatus = AppConfigBean.SUPPLY_STATUS_NORMAL
            homeQrHidden = AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            modeType = 2
            transitionMode = 0
            available = 0L
            availableCountdownLatched = 0
            isEnable = 1
        }
}
