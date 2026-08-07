package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSupplementResumePolicyTest {

    @Test
    fun completedWholeOrder_shouldBeSuccessfulAndResumeSupplement() {
        val outcome = OrderSupplementResumePolicy.completedOutcome(
            deliveredTotal = 3,
            totalCount = 3
        )

        assertEquals(OrderExecutionOutcome.SUCCESS, outcome)
        assertTrue(OrderSupplementResumePolicy.shouldResume(outcome))
        assertFalse(OrderSupplementResumePolicy.shouldEnterMaintenance(outcome))
    }

    @Test
    fun incompleteOrder_shouldFailAndRetainSupplementEvidence() {
        val outcome = OrderSupplementResumePolicy.completedOutcome(
            deliveredTotal = 2,
            totalCount = 3
        )

        assertEquals(OrderExecutionOutcome.FAILED, outcome)
        assertFalse(OrderSupplementResumePolicy.shouldResume(outcome))
        assertTrue(OrderSupplementResumePolicy.shouldEnterMaintenance(outcome))
    }

    @Test
    fun unknownOutcome_shouldNotResumeAndShouldEnterMaintenance() {
        assertFalse(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.UNKNOWN))
        assertTrue(OrderSupplementResumePolicy.shouldEnterMaintenance(OrderExecutionOutcome.UNKNOWN))
    }

    @Test
    fun emptyOrder_shouldNotBeTreatedAsSuccessful() {
        assertEquals(
            OrderExecutionOutcome.FAILED,
            OrderSupplementResumePolicy.completedOutcome(deliveredTotal = 0, totalCount = 0)
        )
    }

    @Test
    fun successfulOrderWithFailedSupplementResume_shouldEnterMaintenance() {
        assertTrue(
            OrderSupplementResumePolicy.shouldEnterMaintenance(
                OrderExecutionOutcome.SUCCESS,
                supplementResumeSucceeded = false
            )
        )
        assertFalse(
            OrderSupplementResumePolicy.shouldEnterMaintenance(
                OrderExecutionOutcome.SUCCESS,
                supplementResumeSucceeded = true
            )
        )
    }

    @Test
    fun noDispatch_shouldResumeWithoutMaintenance() {
        assertTrue(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.NO_DISPATCH))
        assertFalse(OrderSupplementResumePolicy.shouldEnterMaintenance(OrderExecutionOutcome.NO_DISPATCH))
    }

    @Test
    fun evidenceUnknown_shouldRetainEvidenceAndEnterMaintenance() {
        assertFalse(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.EVIDENCE_UNKNOWN))
        assertTrue(OrderSupplementResumePolicy.shouldEnterMaintenance(OrderExecutionOutcome.EVIDENCE_UNKNOWN))
    }
}
