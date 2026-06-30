package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor

object SupplementPickFailurePolicy {

    data class IrPickFailureRecordResult(
        val boxNo: Int,
        val previousNum: Int,
        val remainingCandidateBoxNos: List<Int>
    )

    fun recordIrPickFailedBox(
        failedBox: KaoPanBox,
        allCandidates: List<KaoPanBox>
    ): IrPickFailureRecordResult {
        val previousNum = failedBox.num
        failedBox.cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
        val remainingCandidateBoxNos = allCandidates
            .filter {
                it.positionSn != failedBox.positionSn &&
                    KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it)
            }
            .map { it.positionSn }
        return IrPickFailureRecordResult(
            boxNo = failedBox.positionSn,
            previousNum = previousNum,
            remainingCandidateBoxNos = remainingCandidateBoxNos
        )
    }

    fun buildAllCandidatesFailedFaultReason(
        targetPanNo: Int,
        tasteCode: String?,
        failedBoxNos: List<Int>,
        lastErrorCode: Int
    ): String {
        val failedBoxesText = failedBoxNos.joinToString(",")
        return "同口味烤肠箱均未检测到肠，设备进入维护中：" +
            "targetPan=$targetPanNo，tasteCode=${tasteCode ?: "未知"}，" +
            "failedBoxes=$failedBoxesText，lastError=${AtomicActionExecutor.formatCode(lastErrorCode)}"
    }

    fun buildSupplyDegradedReason(
        targetPanNo: Int?,
        tasteCode: String?,
        failedBoxNos: List<Int>,
        actionName: String = "in2"
    ): String {
        val failedBoxesText = failedBoxNos.distinct().joinToString(",")
        return "补肠供给降级跳过：action=$actionName，error=${AtomicActionExecutor.formatCode(AtomicActionCodes.IR_PICK_FAILED)}，" +
            "targetPan=${targetPanNo ?: "未知"}，tasteCode=${tasteCode ?: "未知"}，failedBoxes=$failedBoxesText"
    }

    fun isSerialIn2IrPickFailure(actionName: String, result: AtomicActionResult): Boolean {
        return actionName == "in2" &&
            result is AtomicActionResult.Failed &&
            result.errorCode == AtomicActionCodes.IR_PICK_FAILED
    }

    fun isPipelineSupplyDegradableFailure(
        actionName: String,
        result: AtomicActionResult,
        hasPrefetchedNext: Boolean
    ): Boolean {
        return !hasPrefetchedNext && isSerialIn2IrPickFailure(actionName, result)
    }
}
