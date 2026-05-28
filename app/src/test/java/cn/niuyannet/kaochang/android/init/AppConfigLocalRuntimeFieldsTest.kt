package cn.niuyannet.kaochang.android.init

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigLocalRuntimeFieldsTest {

    @Test
    fun isLocalRuntimeOnlyField_shouldProtectFoodSafetyRuntimeFieldsFromRemoteMerge() {
        val localOnlyFields = listOf(
            "faultHeatPauseActive",
            "faultHeatPauseStartedAtMs",
            "faultHeatPauseReason",
            "faultHeatPauseSource",
            "pendingDiscardPositions",
            "foodSafetyAlertCode",
            "foodSafetyAlertReason",
            "zone3Dirty",
            "zone3CleanPending",
            "zone3CleanSuppressedFor4F"
        )

        localOnlyFields.forEach { field ->
            assertTrue("$field should be local runtime only", AppConfig.isLocalRuntimeOnlyField(field))
        }
    }

    @Test
    fun isLocalRuntimeOnlyField_shouldNotBlockRemoteConfigThresholds() {
        assertFalse(AppConfig.isLocalRuntimeOnlyField("faultBakingPauseAbortMs"))
        assertFalse(AppConfig.isLocalRuntimeOnlyField("faultPauseAnomalyMs"))
        assertFalse(AppConfig.isLocalRuntimeOnlyField("httpFallbackMinIntervalMs"))
    }

    @Test
    fun mergeFaultSafetyRemoteConfigInto_shouldPreserveLocalRuntimeStateWhenRemotePushesEmptyValues() {
        val config = AppConfigBean().apply {
            deviceCode = "device-001"
            deviceName = "测试设备"
            status = 1
            onlineStatus = 1
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 123_456L
            faultHeatPauseReason = "modbus_error"
            faultHeatPauseSource = "stopHeatingForFault"
            pendingDiscardPositions.addAll(listOf(5, 8))
            foodSafetyAlertCode = "BAKING_PAUSE_ABORT"
            foodSafetyAlertReason = "烤制停热超阈值"
            zone3Dirty = 1
            zone3CleanPending = 0
            zone3CleanSuppressedFor4F = 1
            faultBakingPauseAbortMs = 2 * 60 * 60 * 1000L
        }
        val remote = JSONObject().apply {
            put("deviceCode", "device-001")
            put("name", "测试设备")
            put("status", 1)
            put("onlineStatus", 1)
            put("faultHeatPauseActive", 0)
            put("faultHeatPauseStartedAtMs", 0L)
            put("faultHeatPauseReason", null)
            put("faultHeatPauseSource", null)
            put("pendingDiscardPositions", emptyList<Int>())
            put("foodSafetyAlertCode", null)
            put("foodSafetyAlertReason", null)
            put("zone3Dirty", 0)
            put("zone3CleanPending", 1)
            put("zone3CleanSuppressedFor4F", 0)
            put("faultBakingPauseAbortMs", 60_000L)
        }

        AppConfig.mergeFaultSafetyRemoteConfigInto(config, remote, "unit-test", emitLogs = false)

        assertEquals(1, config.faultHeatPauseActive)
        assertEquals(123_456L, config.faultHeatPauseStartedAtMs)
        assertEquals("modbus_error", config.faultHeatPauseReason)
        assertEquals("stopHeatingForFault", config.faultHeatPauseSource)
        assertEquals(listOf(5, 8), config.pendingDiscardPositions)
        assertEquals("BAKING_PAUSE_ABORT", config.foodSafetyAlertCode)
        assertEquals("烤制停热超阈值", config.foodSafetyAlertReason)
        assertEquals(1, config.zone3Dirty)
        assertEquals(0, config.zone3CleanPending)
        assertEquals(1, config.zone3CleanSuppressedFor4F)
        assertEquals(60_000L, config.faultBakingPauseAbortMs)
    }

    @Test
    fun applyFaultLatchedRemoteStatusGuard_shouldPreserveActionGroupFaultFromRemoteNormalState() {
        val config = AppConfigBean().apply {
            onlineStatus = 3
            errorStatus = 2
            errorCode = "ACTION_GROUP_FAILED"
            errorCategory = "流程类"
            errorReason = "in7 动作组失败"
            isEnable = 0
        }
        val snapshot = AppConfig.FaultRuntimeSnapshot.from(config)

        val preservedOnlineStatus = AppConfig.applyFaultLatchedRemoteStatusGuard(
            config = config,
            snapshot = snapshot,
            field = "onlineStatus",
            remoteValue = 1,
            logPrefix = "unit-test",
            emitLogs = false
        ) { config.onlineStatus = it }
        val preservedErrorStatus = AppConfig.applyFaultLatchedRemoteStatusGuard(
            config = config,
            snapshot = snapshot,
            field = "errorStatus",
            remoteValue = 0,
            logPrefix = "unit-test",
            emitLogs = false
        ) { config.errorStatus = it }

        assertTrue(preservedOnlineStatus)
        assertTrue(preservedErrorStatus)
        assertEquals(3, config.onlineStatus)
        assertEquals(2, config.errorStatus)
        assertEquals(0, config.isEnable)
        assertEquals("ACTION_GROUP_FAILED", config.errorCode)
    }

    @Test
    fun applyFaultLatchedRemoteStatusGuard_shouldAllowRemoteMaintenanceAndAlgorithmOff() {
        val config = AppConfigBean().apply {
            onlineStatus = 3
            errorStatus = 2
            errorCode = "ACTION_GROUP_FAILED"
            isEnable = 0
        }
        val snapshot = AppConfig.FaultRuntimeSnapshot.from(config)

        val preservedOnlineStatus = AppConfig.applyFaultLatchedRemoteStatusGuard(
            config = config,
            snapshot = snapshot,
            field = "onlineStatus",
            remoteValue = 3,
            logPrefix = "unit-test",
            emitLogs = false
        ) { config.onlineStatus = it }

        assertFalse(preservedOnlineStatus)
        assertEquals(3, config.onlineStatus)
        assertEquals(0, config.isEnable)
    }
}
