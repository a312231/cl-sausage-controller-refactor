package cn.niuyannet.kaochang.android.services

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
}
