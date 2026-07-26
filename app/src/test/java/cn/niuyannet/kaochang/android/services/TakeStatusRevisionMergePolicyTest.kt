package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeStatusRevisionMergePolicyTest {

    @Test
    fun newerCloudRecovery_shouldReplaceLocalPickFailureAfterLostResponse() {
        val local = box(AtomicActionCodes.IR_PICK_FAILED, 7L)
        val cloud = box(0, 8L)

        val result = TakeStatusRevisionMergePolicy.merge(local, cloud)

        assertTrue(result.useCloud)
        assertFalse(result.requiresPush)
        assertEquals(0, cloud.cmdStatusTake)
        assertEquals(8L, cloud.takeStatusRevision)
    }

    @Test
    fun equalRevision_shouldPreserveUnconfirmedLocalPickFailure() {
        val local = box(AtomicActionCodes.IR_PICK_FAILED, 7L)
        val cloud = box(0, 7L)

        val result = TakeStatusRevisionMergePolicy.merge(local, cloud)

        assertFalse(result.useCloud)
        assertTrue(result.requiresPush)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, cloud.cmdStatusTake)
        assertEquals(7L, cloud.takeStatusRevision)
    }

    @Test
    fun stalePickFailureIntent_shouldNotRetryAfterNewerManualRecoveryIntent() {
        val registry = TakeStatusIntentRegistry()
        val stalePickFailureIntent = registry.begin(positionSn = 21)
        val manualRecoveryIntent = registry.begin(positionSn = 21)

        assertFalse(registry.isCurrent(positionSn = 21, token = stalePickFailureIntent))
        assertTrue(registry.isCurrent(positionSn = 21, token = manualRecoveryIntent))
    }

    @Test
    fun stockRefreshPush_shouldNotSupersedeCompletedManualRecoveryGeneration() {
        val registry = TakeStatusIntentRegistry()
        val refreshGeneration = registry.observeAutomaticGeneration(positionSn = 21)!!
        val manualRecoveryIntent = registry.beginExplicit(positionSn = 21)
        registry.finishExplicit(positionSn = 21, token = manualRecoveryIntent)

        val automaticPushIntent = registry.beginAutomaticIfUnchanged(
            positionSn = 21,
            observedGeneration = refreshGeneration
        )

        assertEquals(null, automaticPushIntent)
        assertTrue(registry.isCurrent(positionSn = 21, token = manualRecoveryIntent))
    }

    @Test
    fun stockRefreshPush_shouldNotStartWhileManualRecoveryIntentIsInFlight() {
        val registry = TakeStatusIntentRegistry()
        val refreshGeneration = registry.observeAutomaticGeneration(positionSn = 21)!!
        val manualRecoveryIntent = registry.beginExplicit(positionSn = 21)

        val automaticPushIntent = registry.beginAutomaticIfUnchanged(
            positionSn = 21,
            observedGeneration = refreshGeneration
        )

        assertEquals(null, automaticPushIntent)
        assertTrue(registry.isCurrent(positionSn = 21, token = manualRecoveryIntent))
    }

    @Test
    fun automaticPushCallback_shouldBecomeStaleAfterNewerExplicitIntent() {
        val registry = TakeStatusIntentRegistry()
        val refreshGeneration = registry.observeAutomaticGeneration(positionSn = 21)!!
        val automaticPushIntent = registry.beginAutomaticIfUnchanged(
            positionSn = 21,
            observedGeneration = refreshGeneration
        )!!

        val physicalIntent = registry.beginExplicit(positionSn = 21)

        assertFalse(registry.isCurrent(positionSn = 21, token = automaticPushIntent))
        assertTrue(registry.isCurrent(positionSn = 21, token = physicalIntent))
    }

    @Test
    fun conflictRetryClaim_shouldAdvanceGenerationBeforeTransportDispatch() {
        val registry = TakeStatusIntentRegistry()
        val originalToken = registry.beginExplicit(positionSn = 21)

        val retryToken = registry.claimRetryIfCurrent(positionSn = 21, token = originalToken)

        assertTrue(retryToken != null && retryToken > originalToken)
        assertFalse(registry.isCurrent(positionSn = 21, token = originalToken))
        assertTrue(registry.isCurrent(positionSn = 21, token = retryToken!!))

        val newerPhysicalIntent = registry.beginExplicit(positionSn = 21)
        assertTrue(newerPhysicalIntent > retryToken)
        assertFalse(registry.isCurrent(positionSn = 21, token = retryToken))
    }

    @Test
    fun olderCloudState_shouldPreserveLocalStateAndRequirePush() {
        val local = box(AtomicActionCodes.IR_PICK_FAILED, 8L)
        val cloud = box(0, 7L)

        val result = TakeStatusRevisionMergePolicy.merge(local, cloud)

        assertFalse(result.useCloud)
        assertTrue(result.requiresPush)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, cloud.cmdStatusTake)
        assertEquals(8L, cloud.takeStatusRevision)
    }

    @Test
    fun equalRevisionWithSameState_shouldNotRequirePush() {
        val local = box(0, 7L)
        val cloud = box(0, 7L)

        val result = TakeStatusRevisionMergePolicy.merge(local, cloud)

        assertFalse(result.useCloud)
        assertFalse(result.requiresPush)
    }

    @Test
    fun equalRevision_shouldKeepCloudPickFailureOverLocalNormalState() {
        val local = box(0, 7L)
        val cloud = box(AtomicActionCodes.IR_PICK_FAILED, 7L)

        val result = TakeStatusRevisionMergePolicy.merge(local, cloud)

        assertTrue(result.useCloud)
        assertFalse(result.requiresPush)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, cloud.cmdStatusTake)
    }

    @Test
    fun delayedOlderResponse_shouldNotRollbackNewerLocalFailure() {
        val current = box(AtomicActionCodes.IR_PICK_FAILED, 10L)
        val delayed = box(0, 9L)

        assertFalse(TakeStatusRevisionMergePolicy.applyAuthoritative(current, delayed))
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, current.cmdStatusTake)
        assertEquals(10L, current.takeStatusRevision)
        assertFalse(
            TakeStatusRevisionMergePolicy.isRequestedStateCurrent(
                current = current,
                requestedStatus = 0,
                authoritativeRevision = delayed.takeStatusRevision
            )
        )
    }

    @Test
    fun equalRevisionFailure_shouldProtectAgainstNormalLocalState() {
        val current = box(0, 10L)
        val authoritative = box(AtomicActionCodes.IR_PICK_FAILED, 10L)

        assertTrue(TakeStatusRevisionMergePolicy.applyAuthoritative(current, authoritative))
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, current.cmdStatusTake)
        assertEquals(10L, current.takeStatusRevision)
    }

    private fun box(status: Int, revision: Long): KaoPanBox = KaoPanBox().apply {
        positionSn = 21
        cmdStatusTake = status
        takeStatusRevision = revision
    }
}
