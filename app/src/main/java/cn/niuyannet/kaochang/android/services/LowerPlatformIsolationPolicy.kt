package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult

object LowerPlatformIsolationPolicy {
    private val ISOLATABLE_SUPPLEMENT_ACTIONS = setOf("in1", "in2", "in3", "in4", "in5")

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
        if (actionName == "in5" && failed.errorCode == AtomicActionCodes.IR_THROW_FAILED) {
            return Decision.IsolatedRequiresManualConfirm
        }
        if (hasPrefetchedNext) {
            return Decision.NotIsolatable
        }
        if (actionName == "in2" && failed.errorCode == AtomicActionCodes.IR_PICK_FAILED) {
            return Decision.NotIsolatable
        }
        return Decision.IsolatedSilent
    }

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
