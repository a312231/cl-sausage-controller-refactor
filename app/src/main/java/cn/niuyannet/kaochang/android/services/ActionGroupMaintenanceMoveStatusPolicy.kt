package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim

data class ActionGroupMaintenanceMoveStatusEvidence(
    val callerRequestedPreserve: Boolean,
    val moveStatus: Int,
    val session: ActionGroupSession?,
    val orderClaim: OrderDeliveryClaim?,
    val resourceOwners: Map<String, Int>,
    val sessionEvidenceReadable: Boolean,
    val claimEvidenceReadable: Boolean,
    val resourceEvidenceReadable: Boolean
)

object ActionGroupMaintenanceMoveStatusPolicy {
    fun shouldPreserve(evidence: ActionGroupMaintenanceMoveStatusEvidence): Boolean {
        if (evidence.callerRequestedPreserve ||
            evidence.moveStatus != 0 ||
            !evidence.sessionEvidenceReadable ||
            !evidence.claimEvidenceReadable ||
            !evidence.resourceEvidenceReadable
        ) {
            return true
        }
        if (evidence.orderClaim != null || evidence.resourceOwners.isNotEmpty()) {
            return true
        }
        return evidence.session?.let { session ->
            session.mechanicalClaimState == MechanicalClaimState.CLAIMED &&
                session.type in setOf(ActionGroupSessionType.OUT_SELL, ActionGroupSessionType.OUT_MANUAL)
        } == true
    }
}
