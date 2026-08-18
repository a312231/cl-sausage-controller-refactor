package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnBindingConflictCloseoutPolicyTest {

    @Test
    fun closeout_shouldAllowOnlyWhenAllOrderSessionJournalAndLockEvidenceIsClear() {
        assertNull(KaoChangOperate.returnBindingConflictCloseoutRejection(clearEvidence()))
    }

    @Test
    fun closeout_shouldRejectEveryUnsafeEvidenceDimension() {
        val unsafe = listOf(
            clearEvidence().copy(moveStatus = 1),
            clearEvidence().copy(hasProcessingOrder = true),
            clearEvidence().copy(hasOrderPause = true),
            clearEvidence().copy(hasOrderDeliveryClaim = true),
            clearEvidence().copy(actionSessionAbsent = false),
            clearEvidence().copy(pausedSessionAbsent = false),
            clearEvidence().copy(prefetchEvidenceAbsent = false),
            clearEvidence().copy(hasPanMoveJournal = true),
            clearEvidence().copy(hasDiscardJournal = true),
            clearEvidence().copy(resourceLocksIdle = false)
        )

        unsafe.forEach { evidence ->
            assertNotNull(evidence.toString(), KaoChangOperate.returnBindingConflictCloseoutRejection(evidence))
        }
    }

    private fun clearEvidence() = KaoChangOperate.ReturnBindingConflictCloseoutEvidence(
        moveStatus = 0,
        hasProcessingOrder = false,
        hasOrderPause = false,
        hasOrderDeliveryClaim = false,
        actionSessionAbsent = true,
        pausedSessionAbsent = true,
        prefetchEvidenceAbsent = true,
        hasPanMoveJournal = false,
        hasDiscardJournal = false,
        resourceLocksIdle = true
    )
}
