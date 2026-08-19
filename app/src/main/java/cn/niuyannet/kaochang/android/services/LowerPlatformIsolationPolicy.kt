package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineResult
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementLaneRole
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession

object LowerPlatformIsolationPolicy {
    private val ISOLATABLE_SUPPLEMENT_ACTIONS = setOf("in1", "in2", "in3", "in4", "in5")
    private val UNSAFE_LIFT_TERMINAL_ERRORS = setOf(
        AtomicActionCodes.LIFT_FAILED,
        AtomicActionCodes.LIFT_DOWN_LOCKED_ROTOR,
        AtomicActionCodes.LIFT_UP_LOCKED_ROTOR,
        AtomicActionCodes.LIFT_NOT_BOTTOM
    )

    enum class Decision {
        NotIsolatable,
        IsolatedSilent,
        IsolatedRequiresManualConfirm
    }

    fun classifySupplementActionFailure(
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean
    ): Decision {
        if (actionName !in ISOLATABLE_SUPPLEMENT_ACTIONS) {
            return Decision.NotIsolatable
        }
        val failed = result as? AtomicActionResult.Failed ?: return Decision.NotIsolatable
        if (isUnsafeLiftTerminalError(failed.errorCode)) {
            return Decision.NotIsolatable
        }
        if (actionName == "in5" && failed.errorCode == AtomicActionCodes.IR_THROW_FAILED) {
            return Decision.IsolatedRequiresManualConfirm
        }
        if (actionName == "in3" || actionName == "in4") {
            return Decision.IsolatedRequiresManualConfirm
        }
        if (actionName == "in2" && failed.errorCode == AtomicActionCodes.IR_PICK_FAILED) {
            return Decision.NotIsolatable
        }
        return Decision.IsolatedSilent
    }

    fun classifyForSupplementBatch(
        batchType: SupplementBatchType?,
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean,
        parallelSessionActive: Boolean = false
    ): Decision {
        if (batchType != null && SupplementBatchIntentRepository.isInitializationBatch(batchType)) {
            return Decision.NotIsolatable
        }
        if (result is AtomicActionResult.ModbusError) {
            return Decision.NotIsolatable
        }
        if (result is AtomicActionResult.Timeout) {
            return Decision.NotIsolatable
        }
        if (parallelSessionActive &&
            actionName == "in2" &&
            (result as? AtomicActionResult.Failed)?.errorCode == AtomicActionCodes.IR_PICK_FAILED
        ) {
            return Decision.IsolatedSilent
        }
        if (shouldTreatAsLowerPlatformSemiFault(batchType, actionName, result, hasPrefetchedNext)) {
            return Decision.IsolatedRequiresManualConfirm
        }
        return classifySupplementActionFailure(
            actionName = actionName,
            result = result,
            hasPrefetchedNext = hasPrefetchedNext
        )
    }

    fun shouldTreatAsLowerPlatformSemiFault(
        batchType: SupplementBatchType?,
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean
    ): Boolean {
        if (SupplementPickFailurePolicy.isSerialIn2IrPickFailure(actionName, result)) {
            return false
        }
        if (batchType == null || !SupplementBatchIntentRepository.isHeatSupplementBatch(batchType)) {
            return false
        }
        if (actionName !in ISOLATABLE_SUPPLEMENT_ACTIONS) {
            return false
        }
        val failed = result as? AtomicActionResult.Failed ?: return false
        return !isUnsafeLiftTerminalError(failed.errorCode)
    }

    fun isUnsafeLiftTerminalError(errorCode: Int): Boolean = errorCode in UNSAFE_LIFT_TERMINAL_ERRORS

    fun isSupplementActionIsolatable(
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean
    ): Boolean = classifySupplementActionFailure(actionName, result, hasPrefetchedNext) != Decision.NotIsolatable

    fun requiresManualConfirmAfterIsolation(
        actionName: String,
        result: AtomicActionResult
    ): Boolean = classifySupplementActionFailure(
        actionName = actionName,
        result = result,
        hasPrefetchedNext = false
    ) == Decision.IsolatedRequiresManualConfirm

    fun buildIsolationReason(
        actionName: String,
        result: AtomicActionResult,
        targetPanNo: Int?,
        sourceBoxNo: Int?,
        hasPrefetchedNext: Boolean
    ): String {
        val errorText = when (result) {
            is AtomicActionResult.Failed ->
                "${AtomicActionExecutor.formatCode(result.errorCode)}（${result.message}）"
            is AtomicActionResult.ModbusError -> "MODBUS_ERROR（${result.message}）"
            is AtomicActionResult.Timeout -> "TIMEOUT（${result.message}）"
            AtomicActionResult.Success -> "SUCCESS"
        }
        return "补肠下平台隔离：action=$actionName，error=$errorText，" +
            "targetPan=${targetPanNo ?: "未知"}，sourceBox=${sourceBoxNo ?: "未知"}，" +
            "hasPrefetchedNext=$hasPrefetchedNext"
    }
}

object ParallelSupplementE5ClosurePolicy {
    fun canRelease(
        result: InActionGroupPipelineResult.Failed,
        session: SupplementPrefetchPipelineSession?,
        expectedSessionId: String
    ): Boolean {
        val failed = result.result as? AtomicActionResult.Failed ?: return false
        if (result.actionName != "in2" || failed.errorCode != AtomicActionCodes.IR_PICK_FAILED) {
            return false
        }
        if (session == null || session.sessionId != expectedSessionId) {
            return false
        }
        val failedLane = when (result.laneRole) {
            SupplementLaneRole.DELIVERY -> session.deliveryLane
            SupplementLaneRole.PREFETCH -> session.prefetchLane
        } ?: return false
        if (failedLane.currentAction != "in2" ||
            failedLane.lifecycle != SupplementActionLifecycle.TERMINAL_CONFIRMED ||
            failedLane.boxDeductionApplied ||
            failedLane.panPlacementApplied
        ) {
            return false
        }
        return when (result.laneRole) {
            SupplementLaneRole.DELIVERY -> session.prefetchLane == null
            SupplementLaneRole.PREFETCH -> session.deliveryLane?.let { delivery ->
                delivery.currentAction == "in7" &&
                    delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
                    delivery.boxDeductionApplied &&
                    delivery.panPlacementApplied
            } == true
        }
    }
}

object ParallelSupplementSemiFaultClosurePolicy {
    private val SAFE_ACTIONS = setOf("in1", "in2", "in3")
    private val EXPECTED_RESOURCE_MASK = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above

    fun canClose(
        actionName: String,
        sourceBoxNo: Int?,
        targetPanNo: Int?,
        result: AtomicActionResult,
        session: SupplementPrefetchPipelineSession?,
        expectedSessionId: String,
        laneRole: SupplementLaneRole,
        durableStateSaved: Boolean
    ): Boolean {
        val failed = result as? AtomicActionResult.Failed ?: return false
        if (!durableStateSaved || actionName !in SAFE_ACTIONS || !failed.freshExecutionObserved) return false
        if (session == null || session.sessionId != expectedSessionId ||
            session.mechanicalClaimState != MechanicalClaimState.CLAIMED ||
            session.mechanicalClaimRevision <= 0L ||
            session.resourceMask != EXPECTED_RESOURCE_MASK
        ) {
            return false
        }
        val failedLane = when (laneRole) {
            SupplementLaneRole.DELIVERY -> session.deliveryLane
            SupplementLaneRole.PREFETCH -> session.prefetchLane
        } ?: return false
        if (sourceBoxNo == null || targetPanNo == null ||
            failedLane.sourceBoxNo != sourceBoxNo || failedLane.targetPanNo != targetPanNo ||
            failedLane.currentAction != actionName || failedLane.lastConfirmedStage != actionName ||
            failedLane.lifecycle != SupplementActionLifecycle.TERMINAL_CONFIRMED ||
            failedLane.panPlacementApplied
        ) {
            return false
        }
        val inventoryFactsMatch = when (actionName) {
            "in1", "in2" -> !failedLane.boxDeductionApplied
            "in3" -> failedLane.boxDeductionApplied
            else -> false
        }
        if (!inventoryFactsMatch) return false

        return when (laneRole) {
            SupplementLaneRole.DELIVERY -> session.prefetchLane == null
            SupplementLaneRole.PREFETCH -> session.deliveryLane?.let { delivery ->
                delivery.currentAction == "in7" &&
                    delivery.lastConfirmedStage == "in7" &&
                    delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
                    delivery.boxDeductionApplied &&
                    delivery.panPlacementApplied
            } == true
        }
    }
}
