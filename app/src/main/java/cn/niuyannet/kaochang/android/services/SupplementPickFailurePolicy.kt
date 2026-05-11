package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor

object SupplementPickFailurePolicy {

    data class EmptyBoxMarkResult(
        val boxNo: Int,
        val previousNum: Int,
        val remainingCandidateBoxNos: List<Int>
    )

    fun markIrPickFailedBoxAsEmpty(
        failedBox: KaoPanBox,
        allCandidates: List<KaoPanBox>
    ): EmptyBoxMarkResult {
        val previousNum = failedBox.num
        failedBox.num = 0
        failedBox.cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
        val remainingCandidateBoxNos = allCandidates
            .filter { it.positionSn != failedBox.positionSn && it.num > 0 }
            .map { it.positionSn }
        return EmptyBoxMarkResult(
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
}
