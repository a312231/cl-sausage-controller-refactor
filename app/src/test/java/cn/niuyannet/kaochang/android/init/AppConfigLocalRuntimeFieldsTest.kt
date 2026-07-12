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
            "lastRuntimeAliveAtMs",
            "lowerPlatformStatus",
            "lowerPlatformIsolationActionName",
            "lowerPlatformGeneration",
            "lowerPlatformAggregateVersion",
            "lowerPlatformEpisodes",
            "supplementEnabled",
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
    fun applyLowerPlatformRuntimeFieldsFromRemote_shouldPreserveLocalOpenEpisodesAndReduceProjection() {
        val config = AppConfigBean().apply {
            lowerPlatformIsolationActionName = "in4"
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING
        }
        cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer.openIsolated(
            config, "in4", "本地下平台隔离", 1_000L
        )
        val remoteDegraded = JSONObject().apply {
            put("lowerPlatformStatus", AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL)
            put("lowerPlatformIsolationActionName", "")
            put("supplementEnabled", AppConfigBean.SUPPLEMENT_ENABLED_YES)
            put("orderBlockReason", "")
            put("lowerPlatformGeneration", "remote-generation")
            put("lowerPlatformAggregateVersion", 0L)
            put("lowerPlatformEpisodes", emptyList<Any>())
        }

        AppConfig.applyLowerPlatformRuntimeFieldsFromRemote(config, remoteDegraded)

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals("in4", config.lowerPlatformIsolationActionName)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, config.supplementEnabled)
        assertEquals(1, config.lowerPlatformEpisodes.size)
        assertTrue(config.lowerPlatformGeneration != "remote-generation")
        assertTrue(config.lowerPlatformAggregateVersion > 0L)
    }

    @Test
    fun applyLowerPlatformRuntimeFieldsFromRemote_shouldAdoptServerGenerationForFreshLocalState() {
        val config = AppConfigBean().apply {
            lowerPlatformGeneration = "local-generated-before-config"
            lowerPlatformAggregateVersion = 0L
            lowerPlatformEpisodes.clear()
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        }
        val remote = JSONObject().apply {
            put("lowerPlatformGeneration", "server-authoritative-generation")
            put("lowerPlatformStatus", AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED)
            put("lowerPlatformIsolationActionName", "in4")
            put("supplementEnabled", AppConfigBean.SUPPLEMENT_ENABLED_NO)
            put("lowerPlatformAggregateVersion", 8L)
            put("lowerPlatformEpisodes", emptyList<Any>())
        }

        AppConfig.applyLowerPlatformRuntimeFieldsFromRemote(config, remote)

        assertEquals("server-authoritative-generation", config.lowerPlatformGeneration)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals("in4", config.lowerPlatformIsolationActionName)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, config.supplementEnabled)
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
            lastRuntimeAliveAtMs = 123_000L
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
            put("lastRuntimeAliveAtMs", 0L)
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
        assertEquals(123_000L, config.lastRuntimeAliveAtMs)
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
