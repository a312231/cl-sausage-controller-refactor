package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCloseoutManualTakeoverTest {

    @Test
    fun manualTakeover_shouldOnlyAcceptBusinessCloseoutDiscardJournal() {
        val businessCloseout = DiscardJournal(
            owner = "closeout-owner",
            positionSn = 1,
            checkpoint = DiscardCheckpoint.COMMAND_ISSUED,
            createdAtMs = 1L,
            source = BUSINESS_CLOSEOUT_DISCARD_SOURCE
        )
        val expiredDiscard = businessCloseout.copy(source = "过保到期")

        assertTrue(canManuallyTakeOverBusinessCloseoutDiscard(businessCloseout))
        assertFalse(canManuallyTakeOverBusinessCloseoutDiscard(expiredDiscard))
        assertFalse(canManuallyTakeOverBusinessCloseoutDiscard(null))
    }

    @Test
    fun manualPanClearFold_shouldOnlyResetConcurrencyFields() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 2
            highConcurrencyResidualDrain = 1
            onlineStatus = 3
            errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE
            isEnable = 0
        }

        foldBusinessCloseoutAfterManualPanClear(config)

        assertEquals(1, config.modeType)
        assertEquals(0, config.transitionMode)
        assertEquals(0, config.highConcurrencyResidualDrain)
        assertEquals(3, config.onlineStatus)
        assertEquals(AppConfigBean.ERROR_STATUS_HARDWARE, config.errorStatus)
        assertEquals(0, config.isEnable)
    }

    @Test
    fun manualTakeoverFold_shouldEnterMaintenanceWithoutChangingConcurrencyOrError() {
        val config = AppConfigBean().apply {
            onlineStatus = 2
            isEnable = 1
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            modeType = 2
            transitionMode = 2
            highConcurrencyResidualDrain = 1
        }

        foldBusinessCloseoutManualTakeoverToMaintenance(config)

        assertEquals(3, config.onlineStatus)
        assertEquals(0, config.isEnable)
        assertEquals(AppConfigBean.ERROR_STATUS_NORMAL, config.errorStatus)
        assertEquals(2, config.modeType)
        assertEquals(2, config.transitionMode)
        assertEquals(1, config.highConcurrencyResidualDrain)
    }
}
