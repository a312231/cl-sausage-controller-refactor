package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState

/**
 * 串行补肠在 in1~in3 明确失败且没有物理提交时，才允许释放旧的补肠占有。
 * 这里是纯资格判断，不负责清 session、moveStatus 或资源锁。
 */
data class SupplementFailureCloseoutEvidence(
    val batchType: SupplementBatchType?,
    val actionName: String,
    val rawResult: AtomicActionResult,
    val prefetch: Boolean,
    val mechanicalClaimState: MechanicalClaimState?,
    val lastConfirmedStage: String? = null,
    val boxDeductionApplied: Boolean = false,
    val panPlacementApplied: Boolean = false,
    val physicalBoxDeducted: Boolean = false,
    val physicalPanPlaced: Boolean = false,
    val physicalStockInconsistent: Boolean = false
)

object SupplementFailureCloseoutPolicy {
    private val eligibleActions = setOf("in1", "in2", "in3")

    fun canAutoRelease(evidence: SupplementFailureCloseoutEvidence): Boolean {
        if (evidence.prefetch || evidence.actionName !in eligibleActions) {
            return false
        }
        val batchType = evidence.batchType ?: return false
        if (batchType == SupplementBatchType.UNKNOWN ||
            SupplementBatchIntentRepository.isInitializationBatch(batchType)
        ) {
            return false
        }
        if (evidence.mechanicalClaimState != MechanicalClaimState.CLAIMED) {
            return false
        }
        val failed = evidence.rawResult as? AtomicActionResult.Failed ?: return false
        if (LowerPlatformIsolationPolicy.isUnsafeLiftTerminalError(failed.errorCode)) {
            return false
        }
        if (evidence.actionName == "in2" &&
            failed.errorCode == AtomicActionCodes.IR_PICK_FAILED &&
            !failed.freshExecutionObserved
        ) return false
        if (evidence.boxDeductionApplied ||
            evidence.panPlacementApplied ||
            evidence.physicalBoxDeducted ||
            evidence.physicalPanPlaced ||
            evidence.physicalStockInconsistent
        ) {
            return false
        }
        return when (evidence.actionName) {
            "in1" -> evidence.lastConfirmedStage == null
            "in2" -> evidence.lastConfirmedStage == null || evidence.lastConfirmedStage == "in1"
            // in3 之前若已确认过任何动作，说明前序阶段有提交证据，保守保留。
            "in3" -> evidence.lastConfirmedStage == null
            else -> false
        }
    }
}
