package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPanResetRuntimeStateHelperTest {

    @Test
    fun apply_shouldClearManualPanResetRuntimeFields() {
        val config = AppConfigBean().apply {
            modeType = 1
            available = 90_000L
            availableCountdownLatched = 1
            transitionMode = 2
            highConcurrencyResidualDrain = 1
            errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE
        }

        val result = ManualPanResetRuntimeStateHelper.apply(config)

        assertTrue(result.changed)
        assertEquals(90_000L, result.oldAvailable)
        assertEquals(1, result.oldAvailableCountdownLatched)
        assertEquals(2, result.oldTransitionMode)
        assertEquals(1, result.oldHighConcurrencyResidualDrain)
        assertEquals(0L, config.available)
        assertEquals(0, config.availableCountdownLatched)
        assertEquals(0, config.transitionMode)
        assertEquals(0, config.highConcurrencyResidualDrain)
        assertEquals(1, config.modeType)
        assertEquals(AppConfigBean.ERROR_STATUS_HARDWARE, config.errorStatus)
    }

    @Test
    fun apply_shouldChangeWhenOnlyResidualDrainNeedsClearing() {
        val config = AppConfigBean().apply {
            modeType = 1
            available = 0L
            availableCountdownLatched = 0
            transitionMode = 0
            highConcurrencyResidualDrain = 1
        }

        val result = ManualPanResetRuntimeStateHelper.apply(config)

        assertTrue(result.changed)
        assertEquals(0, config.highConcurrencyResidualDrain)
        assertEquals(1, config.modeType)
    }

    @Test
    fun apply_shouldNotChangeWhenRuntimeFieldsAlreadyCleared() {
        val config = AppConfigBean().apply {
            modeType = 2
            available = 0L
            availableCountdownLatched = 0
            transitionMode = 0
            highConcurrencyResidualDrain = 0
        }

        val result = ManualPanResetRuntimeStateHelper.apply(config)

        assertFalse(result.changed)
        assertEquals(2, config.modeType)
    }
}
