package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSupplementBatchHandlingTest {
    @Test
    fun onlyResumed_shouldEndCurrentSchedulingRound() {
        KaoChangOperate.PendingSupplementBatchHandling.entries.forEach { handling ->
            assertTrue(
                handling.completedMechanicalWorkThisRound ==
                    (handling == KaoChangOperate.PendingSupplementBatchHandling.RESUMED)
            )
        }
    }

    @Test
    fun blockedAndManualConfirmation_shouldOnlyBlockNewSupplement() {
        assertTrue(KaoChangOperate.PendingSupplementBatchHandling.BLOCKED.blocksNewSupplement)
        assertTrue(KaoChangOperate.PendingSupplementBatchHandling.WAITING_MANUAL_CONFIRMATION.blocksNewSupplement)
        assertTrue(KaoChangOperate.PendingSupplementBatchHandling.RESUMED.blocksNewSupplement)
        assertFalse(KaoChangOperate.PendingSupplementBatchHandling.NONE.blocksNewSupplement)
    }
}
