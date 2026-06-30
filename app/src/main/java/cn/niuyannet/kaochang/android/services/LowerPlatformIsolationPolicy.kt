package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult

object LowerPlatformIsolationPolicy {
    private val ISOLATABLE_SUPPLEMENT_ACTIONS = setOf("in1", "in2", "in3", "in4", "in5")

    fun isSupplementActionIsolatable(
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean
    ): Boolean {
        if (hasPrefetchedNext || actionName !in ISOLATABLE_SUPPLEMENT_ACTIONS) {
            return false
        }
        val failed = result as? AtomicActionResult.Failed ?: return false
        return !(actionName == "in2" && failed.errorCode == AtomicActionCodes.IR_PICK_FAILED)
    }

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
