package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer
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

    @Test
    fun buildUpdateDeviceInfoPayload_shouldIncludeSourceWhenProvided() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig(),
            source = "runtime_state:manual_recovery"
        )

        assertEquals("runtime_state:manual_recovery", payload.getString("source"))
    }

    @Test
    fun buildUpdateDeviceInfoPayload_shouldIncludeHighConcurrencyResidualDrainForHttpCompensation() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig().apply {
                modeType = 1
                highConcurrencyResidualDrain = 1
            },
            source = "maintenance:点击清空烤盘"
        )

        assertEquals(1, payload.getIntValue("highConcurrencyResidualDrain"))
    }

    @Test
    fun buildUpdateDeviceInfoPayload_shouldIncludeLowerPlatformRuntimeFieldsForHttpCompensation() {
        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = baseConfig().apply {
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                lowerPlatformIsolationActionName = "in5"
                supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
                orderBlockReason = ""
                lowerPlatformAggregateVersion = 4L
                lowerPlatformGeneration = "generation-device-001"
                lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                    eventId = "evt-box-3"
                    revision = 2L
                    faultType = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                    lifecycle = "OPEN"
                    sourceBoxNo = 3
                    occurrenceCount = 2
                })
            },
            source = "runtime_state:supply_degraded"
        )

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED, payload.getString("lowerPlatformStatus"))
        assertEquals("in5", payload.getString("lowerPlatformIsolationActionName"))
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_YES, payload.getIntValue("supplementEnabled"))
        assertTrue(payload.containsKey("orderBlockReason"))
        assertEquals("", payload.getString("orderBlockReason"))
        assertEquals(4L, payload.getLongValue("lowerPlatformAggregateVersion"))
        assertEquals("generation-device-001", payload.getString("lowerPlatformGeneration"))
        assertEquals("evt-box-3", payload.getJSONArray("lowerPlatformEpisodes").getJSONObject(0).getString("eventId"))
    }

    @Test
    fun buildUpdateDeviceInfoPayload_shouldUseProvidedFrozenLowerSnapshot() {
        val config = baseConfig().apply {
            lowerPlatformGeneration = "generation-frozen"
            lowerPlatformAggregateVersion = 11L
            lowerPlatformIsolationActionName = "in3"
            orderBlockReason = "frozen-block"
        }
        LowerPlatformEpisodeReducer.openIsolated(config, "in3", "frozen", 1L)
        val snapshot = LowerPlatformEpisodeReducer.snapshot(config)
        config.lowerPlatformIsolationActionName = "in5"
        config.orderBlockReason = "mutated-block"
        config.lowerPlatformEpisodes.clear()

        val payload = NetApi.buildUpdateDeviceInfoPayload(
            deviceCode = "device-001",
            config = config,
            source = "test",
            lowerPlatformSnapshot = snapshot
        )

        assertEquals("generation-frozen", payload.getString("lowerPlatformGeneration"))
        assertEquals(snapshot.aggregateVersion, payload.getLongValue("lowerPlatformAggregateVersion"))
        assertEquals("in3", payload.getString("lowerPlatformIsolationActionName"))
        assertEquals("frozen-block", payload.getString("orderBlockReason"))
        assertEquals(1, payload.getJSONArray("lowerPlatformEpisodes").size)
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
