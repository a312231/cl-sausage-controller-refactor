package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryGateDecision
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupRecoveryGatePolicyTest {

    @Test
    fun evaluate_shouldDeferOutSellOnlyWhenOrderAndOwnerIdentitiesMatchExactly() {
        val session = outSellSession("CL-A")
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A",
            currentSessionOwnedMask = session.resourceMask,
            resourceOwners = setOf(session.sessionId)
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Defer)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceWhenSessionAHasLivePauseForOrderB() {
        val session = outSellSession("CL-A")
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-B",
            currentSessionOwnedMask = session.resourceMask,
            resourceOwners = setOf(session.sessionId)
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceForOldManualOutDespiteNewLiveOrderIdentity() {
        val session = outSellSession("CL-OLD").copy(type = ActionGroupSessionType.OUT_MANUAL)
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-NEW",
            livePauseOrderNo = "CL-NEW",
            currentSessionOwnedMask = session.resourceMask,
            resourceOwners = setOf(session.sessionId)
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceWhenForeignOwnerExistsWithLiveClaim() {
        val result = evaluate(
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A",
            resourceOwners = setOf("foreign-owner")
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceWhenSessionOwnerHasOnlyPartialMask() {
        val session = outSellSession("CL-A")
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A",
            currentSessionOwnedMask = ResourceMask.above,
            resourceOwners = setOf(session.sessionId)
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceForPersistedOutWithoutMatchingLivePauseIdentity() {
        val session = outSellSession("CL-A")
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-A",
            currentSessionOwnedMask = session.resourceMask,
            resourceOwners = setOf(session.sessionId)
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldDeferMatchingClaimBeforeOutSessionAtSupplementEntry() {
        val result = evaluate(claimOrderNo = "CL-A", livePauseOrderNo = "CL-A")
        assertTrue(result is ActionGroupRecoveryGateDecision.Defer)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceForClaimBeforeSessionWithoutMatchingLivePause() {
        val missingLive = evaluate(claimOrderNo = "CL-A")
        val mismatchedLive = evaluate(claimOrderNo = "CL-A", livePauseOrderNo = "CL-B")
        assertTrue(missingLive is ActionGroupRecoveryGateDecision.Maintenance)
        assertTrue(mismatchedLive is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceForMatchingClaimOutsideSupplementEntry() {
        val result = evaluate(
            context = ActionGroupRecoveryContext.FAIL_CLOSED,
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A"
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldKeepGuardedResidueAsHighestPriority() {
        val session = outSellSession("CL-A")
        val result = evaluate(
            currentSession = session,
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A",
            currentSessionOwnedMask = session.resourceMask,
            resourceOwners = setOf(session.sessionId),
            hasGuardedSupplementResidue = true
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun evaluate_shouldRequireMaintenanceWhenEvidenceCannotBeReadAtomically() {
        val result = evaluate(
            claimOrderNo = "CL-A",
            livePauseOrderNo = "CL-A",
            evidenceReadable = false
        )
        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun claimGrace_shouldDeferAtZeroAndInclusiveTenSecondBoundary() {
        val atCreation = evaluateClaimGrace(claimCreatedAtMs = 20_000L, nowMs = 20_000L)
        val atBoundary = evaluateClaimGrace(claimCreatedAtMs = 20_000L, nowMs = 30_000L)

        assertTrue(atCreation is ClaimBeforeOutSessionGraceDecision.Defer)
        assertTrue(atBoundary is ClaimBeforeOutSessionGraceDecision.Defer)
    }

    @Test
    fun claimGrace_shouldAttemptExactCloseoutOnlyAfterGraceExpires() {
        val result = evaluateClaimGrace(claimCreatedAtMs = 20_000L, nowMs = 30_001L)

        assertTrue(result is ClaimBeforeOutSessionGraceDecision.AttemptExactCloseout)
        val attempt = result as ClaimBeforeOutSessionGraceDecision.AttemptExactCloseout
        assertTrue(attempt.expectedClaim == claim("CL-A", 20_000L))
    }

    @Test
    fun claimGrace_shouldRequireMaintenanceForFutureOrInvalidCreationTime() {
        val future = evaluateClaimGrace(claimCreatedAtMs = 20_001L, nowMs = 20_000L)
        val invalid = evaluateClaimGrace(claimCreatedAtMs = 0L, nowMs = 20_000L)

        assertTrue(future is ClaimBeforeOutSessionGraceDecision.Maintenance)
        assertTrue(invalid is ClaimBeforeOutSessionGraceDecision.Maintenance)
    }

    @Test
    fun claimGrace_shouldRequireMaintenanceForWrongOrderOrMissingLivePause() {
        val wrongOrder = evaluateClaimGrace(livePauseOrderNo = "CL-B")
        val missingLive = evaluateClaimGrace(livePauseOrderNo = null)

        assertTrue(wrongOrder is ClaimBeforeOutSessionGraceDecision.Maintenance)
        assertTrue(missingLive is ClaimBeforeOutSessionGraceDecision.Maintenance)
    }

    @Test
    fun claimGrace_shouldRequireMaintenanceWhenSessionAlreadyExists() {
        val session = outSellSession("CL-A")
        val result = evaluateClaimGrace(currentSession = session)

        assertTrue(result is ClaimBeforeOutSessionGraceDecision.Maintenance)
    }

    @Test
    fun claimGrace_shouldRequireMaintenanceForOwnerOrMaskConflict() {
        val foreignOwner = evaluateClaimGrace(resourceOwners = setOf("foreign-owner"))
        val orphanMask = evaluateClaimGrace(currentSessionOwnedMask = ResourceMask.above)

        assertTrue(foreignOwner is ClaimBeforeOutSessionGraceDecision.Maintenance)
        assertTrue(orphanMask is ClaimBeforeOutSessionGraceDecision.Maintenance)
    }

    @Test
    fun expectedCloseout_shouldRejectReplacementClaimBWithoutClosingIt() {
        val expectedClaimA = claim("CL-A", 20_000L)
        val replacementClaimB = claim("CL-B", 21_000L)

        val result = ActionGroupRecoveryGatePolicy.evaluateExpectedClaimCloseoutGate(
            expectedClaim = expectedClaimA,
            context = ActionGroupRecoveryContext.SUPPLEMENT_ENTRY,
            evidence = evidence(
                orderDeliveryClaim = replacementClaimB,
                livePauseOrderNo = "CL-B",
                nowMs = 31_001L
            )
        )

        assertTrue(result is ActionGroupRecoveryGateDecision.Maintenance)
    }

    @Test
    fun expectedCloseout_shouldDeferWhenMatchingActiveOutAppears() {
        val expectedClaim = claim("CL-A", 20_000L)
        val session = outSellSession("CL-A")

        val result = ActionGroupRecoveryGatePolicy.evaluateExpectedClaimCloseoutGate(
            expectedClaim = expectedClaim,
            context = ActionGroupRecoveryContext.SUPPLEMENT_ENTRY,
            evidence = evidence(
                currentSession = session,
                orderDeliveryClaim = expectedClaim,
                livePauseOrderNo = "CL-A",
                currentSessionOwnedMask = session.resourceMask,
                resourceOwners = setOf(session.sessionId),
                nowMs = 31_001L
            )
        )

        assertTrue(result is ActionGroupRecoveryGateDecision.Defer)
    }

    private fun evaluate(
        context: ActionGroupRecoveryContext = ActionGroupRecoveryContext.SUPPLEMENT_ENTRY,
        expectedSession: ActionGroupSession? = null,
        currentSession: ActionGroupSession? = null,
        claimOrderNo: String? = null,
        claimCreatedAtMs: Long? = 15_000L,
        orderDeliveryClaim: OrderDeliveryClaim? = claimOrderNo?.let { claim(it, claimCreatedAtMs ?: 15_000L) },
        livePauseOrderNo: String? = null,
        nowMs: Long = 20_000L,
        moveStatus: Int = 2,
        currentSessionOwnedMask: Int? = null,
        resourceOwners: Set<String> = emptySet(),
        hasGuardedSupplementResidue: Boolean = false,
        evidenceReadable: Boolean = true
    ) = ActionGroupRecoveryGatePolicy.evaluate(
        context = context,
        evidence = evidence(
            expectedSession = expectedSession,
            currentSession = currentSession,
            orderDeliveryClaim = orderDeliveryClaim,
            livePauseOrderNo = livePauseOrderNo,
            nowMs = nowMs,
            moveStatus = moveStatus,
            currentSessionOwnedMask = currentSessionOwnedMask,
            resourceOwners = resourceOwners,
            hasGuardedSupplementResidue = hasGuardedSupplementResidue,
            evidenceReadable = evidenceReadable
        )
    )

    private fun evidence(
        expectedSession: ActionGroupSession? = null,
        currentSession: ActionGroupSession? = null,
        orderDeliveryClaim: OrderDeliveryClaim? = null,
        livePauseOrderNo: String? = null,
        nowMs: Long = 20_000L,
        moveStatus: Int = 2,
        currentSessionOwnedMask: Int? = null,
        resourceOwners: Set<String> = emptySet(),
        hasGuardedSupplementResidue: Boolean = false,
        evidenceReadable: Boolean = true
    ) = ActionGroupRecoveryGateEvidence(
        expectedSession = expectedSession,
        currentSession = currentSession,
        orderDeliveryClaim = orderDeliveryClaim,
        livePauseOrderNo = livePauseOrderNo,
        nowMs = nowMs,
        moveStatus = moveStatus,
        currentSessionOwnedMask = currentSessionOwnedMask,
        resourceOwners = resourceOwners,
        hasGuardedSupplementResidue = hasGuardedSupplementResidue,
        evidenceReadable = evidenceReadable
    )

    private fun evaluateClaimGrace(
        claimOrderNo: String = "CL-A",
        livePauseOrderNo: String? = "CL-A",
        claimCreatedAtMs: Long = 20_000L,
        nowMs: Long = 25_000L,
        currentSession: ActionGroupSession? = null,
        currentSessionOwnedMask: Int? = null,
        resourceOwners: Set<String> = emptySet()
    ) = ActionGroupRecoveryGatePolicy.evaluateClaimBeforeOutSessionGrace(
        ActionGroupRecoveryGateEvidence(
            expectedSession = null,
            currentSession = currentSession,
            orderDeliveryClaim = claim(claimOrderNo, claimCreatedAtMs),
            livePauseOrderNo = livePauseOrderNo,
            nowMs = nowMs,
            moveStatus = 2,
            currentSessionOwnedMask = currentSessionOwnedMask,
            resourceOwners = resourceOwners,
            hasGuardedSupplementResidue = false,
            evidenceReadable = true
        )
    )

    private fun claim(orderNo: String, createdAtMs: Long) = OrderDeliveryClaim(
        orderNo = orderNo,
        owner = "order:$orderNo",
        state = MechanicalClaimState.CLAIMED,
        revision = 2L,
        createdAtMs = createdAtMs
    )

    private fun outSellSession(orderNo: String) = ActionGroupSession(
        sessionId = "out:$orderNo",
        type = ActionGroupSessionType.OUT_SELL,
        sourceNo = 5,
        orderId = orderNo,
        orderContext = ActionGroupOrderContext(
            orderNo = orderNo,
            itemId = 1886,
            productId = 101,
            tasteCode = "B"
        ),
        currentAction = "out1",
        resourceMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift,
        checkpointVersion = 1L,
        mechanicalClaimState = MechanicalClaimState.CLAIMED,
        mechanicalClaimRevision = 2L
    )
}
