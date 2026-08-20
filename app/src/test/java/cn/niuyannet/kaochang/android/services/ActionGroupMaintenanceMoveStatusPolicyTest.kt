package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaimStore
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupMaintenanceMoveStatusPolicyTest {

    @Test
    fun shouldPreserveMatchingClaimedOutSellSessionClaimAndResourceOwner() {
        val session = outSession(ActionGroupSessionType.OUT_SELL)

        assertTrue(
            evaluate(
                session = session,
                orderClaim = orderClaim(),
                resourceOwners = mapOf(session.sessionId to session.resourceMask)
            )
        )
    }

    @Test
    fun shouldPreserveMatchingClaimedOutManualSessionAndResourceOwnerWithoutOrderClaim() {
        val session = outSession(ActionGroupSessionType.OUT_MANUAL)

        assertTrue(
            evaluate(
                session = session,
                resourceOwners = mapOf(session.sessionId to session.resourceMask)
            )
        )
    }

    @Test
    fun shouldFailClosedWhenSessionEvidenceIsUnreadable() {
        assertTrue(evaluate(sessionEvidenceReadable = false))
    }

    @Test
    fun shouldFailClosedWhenOrderClaimEvidenceIsUnreadable() {
        assertTrue(evaluate(claimEvidenceReadable = false))
    }

    @Test
    fun shouldFailClosedWhenResourceOwnerEvidenceIsUnreadable() {
        assertTrue(evaluate(resourceEvidenceReadable = false))
    }

    @Test
    fun shouldPreserveEveryNonIdleMoveStatusEvenBeforeOtherMechanicalEvidenceAppears() {
        (1..5).forEach { moveStatus ->
            assertTrue("moveStatus=$moveStatus", evaluate(moveStatus = moveStatus))
        }
    }

    @Test
    fun shouldNeedNoPreserveWhenMoveStatusIsAlreadyIdleAndNoMechanicalEvidenceExists() {
        assertFalse(evaluate(moveStatus = 0))
    }

    @Test
    fun shouldPreserveWhenForeignResourceOwnerExists() {
        val session = outSession(ActionGroupSessionType.OUT_SELL)

        assertTrue(
            evaluate(
                session = session,
                orderClaim = orderClaim(),
                resourceOwners = mapOf("foreign-owner" to session.resourceMask)
            )
        )
    }

    @Test
    fun shouldPreserveWhenSessionOwnerHasOnlyPartialResourceMask() {
        val session = outSession(ActionGroupSessionType.OUT_SELL)

        assertTrue(
            evaluate(
                session = session,
                orderClaim = orderClaim(),
                resourceOwners = mapOf(session.sessionId to ResourceMask.above)
            )
        )
    }

    private fun evaluate(
        callerRequestedPreserve: Boolean = false,
        moveStatus: Int = 2,
        session: ActionGroupSession? = null,
        orderClaim: OrderDeliveryClaim? = null,
        resourceOwners: Map<String, Int> = emptyMap(),
        sessionEvidenceReadable: Boolean = true,
        claimEvidenceReadable: Boolean = true,
        resourceEvidenceReadable: Boolean = true
    ): Boolean = ActionGroupMaintenanceMoveStatusPolicy.shouldPreserve(
        ActionGroupMaintenanceMoveStatusEvidence(
            callerRequestedPreserve = callerRequestedPreserve,
            moveStatus = moveStatus,
            session = session,
            orderClaim = orderClaim,
            resourceOwners = resourceOwners,
            sessionEvidenceReadable = sessionEvidenceReadable,
            claimEvidenceReadable = claimEvidenceReadable,
            resourceEvidenceReadable = resourceEvidenceReadable
        )
    )

    private fun outSession(type: ActionGroupSessionType) = ActionGroupSession(
        sessionId = "out:CL-A",
        type = type,
        sourceNo = 5,
        orderId = "CL-A",
        orderContext = ActionGroupOrderContext(
            orderNo = "CL-A",
            itemId = 1886,
            productId = 101,
            tasteCode = "B"
        ),
        currentAction = "out2",
        resourceMask = ResourceMask.lift or ResourceMask.above or ResourceMask.gate or ResourceMask.stick,
        mechanicalClaimState = MechanicalClaimState.CLAIMED,
        mechanicalClaimRevision = 2L
    )

    private fun orderClaim() = OrderDeliveryClaim(
        orderNo = "CL-A",
        owner = OrderDeliveryClaimStore.OWNER_PREFIX + "CL-A",
        state = MechanicalClaimState.CLAIMED,
        revision = 2L,
        createdAtMs = 1L
    )
}
