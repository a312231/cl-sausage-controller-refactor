package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfigBean
import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRuntimeConfigPatchApplierTest {
    @Test
    fun apply_shouldApplyPositiveBakingTime() {
        val config = AppConfigBean().apply { bakingTime = 20 }
        val patch = JSONObject().apply { put("bakingTime", 40) }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertEquals(40, config.bakingTime)
        assertEquals(listOf("bakingTime"), result.appliedFields)
    }

    @Test
    fun apply_shouldRejectNonPositiveBakingTime() {
        val config = AppConfigBean().apply { bakingTime = 40 }
        val patch = JSONObject().apply { put("bakingTime", 0) }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertFalse(result.hasApplied)
        assertEquals(40, config.bakingTime)
        assertEquals(listOf("bakingTime"), result.rejectedFields)
    }

    @Test
    fun apply_shouldMarkTemperatureChangedForHeatingPatch() {
        val config = AppConfigBean().apply {
            heatingTemperature = 110
        }
        val patch = JSONObject().apply {
            put("heatingTemperature", 150)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertTrue(result.temperatureChanged)
        assertEquals(150, config.heatingTemperature)
        assertEquals(listOf("heatingTemperature"), result.appliedFields)
    }

    @Test
    fun apply_shouldAllowRemoteTemperatureTargetsAbove199AndClampNonPositiveToZero() {
        val config = AppConfigBean().apply {
            keepWarmTemperature = 60
            heatingTemperature = 160
        }
        val patch = JSONObject().apply {
            put("keepWarmTemperature", 250)
            put("heatingTemperature", -5)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertTrue(result.isPartial)
        assertTrue(result.temperatureChanged)
        assertEquals(250, config.keepWarmTemperature)
        assertEquals(0, config.heatingTemperature)
        assertEquals(
            mapOf(
                "heatingTemperature" to 0
            ),
            result.normalizedFields
        )
    }

    @Test
    fun apply_shouldNotMarkTemperatureChangedForRuntimePatch() {
        val config = AppConfigBean().apply {
            onlineStatus = 1
        }
        val patch = JSONObject().apply {
            put("onlineStatus", 3)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertFalse(result.temperatureChanged)
        assertEquals(3, config.onlineStatus)
    }

    @Test
    fun apply_shouldMapConcurrencyModeToModeType() {
        val config = AppConfigBean().apply {
            modeType = 1
        }
        val patch = JSONObject().apply {
            put("concurrencyMode", 2)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertEquals(2, config.modeType)
        assertEquals(listOf("concurrencyMode"), result.appliedFields)
    }

    @Test
    fun apply_shouldClampHeatingOffsetAndReportPartial() {
        val config = AppConfigBean().apply {
            zone1HeatingOffset = 0
        }
        val patch = JSONObject().apply {
            put("zone1HeatingOffset", 55)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertTrue(result.isPartial)
        assertTrue(result.temperatureChanged)
        assertEquals(50, config.zone1HeatingOffset)
        assertEquals(mapOf("zone1HeatingOffset" to 50), result.normalizedFields)
    }

    @Test
    fun apply_shouldAllowHeatingOffsetWithinFiftyDegrees() {
        val config = AppConfigBean().apply {
            zone1HeatingOffset = 0
        }
        val patch = JSONObject().apply {
            put("zone1HeatingOffset", 40)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertFalse(result.isPartial)
        assertTrue(result.temperatureChanged)
        assertEquals(40, config.zone1HeatingOffset)
        assertTrue(result.normalizedFields.isEmpty())
    }

    @Test
    fun apply_shouldRejectUnknownOnlyPatch() {
        val config = AppConfigBean()
        val patch = JSONObject().apply {
            put("price", 12)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertFalse(result.hasApplied)
        assertEquals(listOf("price"), result.unknownFields)
    }

    @Test
    fun apply_shouldReturnPartialForKnownAndInvalidFields() {
        val config = AppConfigBean().apply {
            onlineStatus = 1
        }
        val patch = JSONObject().apply {
            put("onlineStatus", 2)
            put("isEnable", 9)
        }

        val result = RemoteRuntimeConfigPatchApplier.apply(config, patch)

        assertTrue(result.hasApplied)
        assertTrue(result.isPartial)
        assertEquals(2, config.onlineStatus)
        assertEquals(listOf("isEnable"), result.rejectedFields)
    }
}
