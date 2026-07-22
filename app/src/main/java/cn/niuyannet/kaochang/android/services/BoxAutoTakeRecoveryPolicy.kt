package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes

data class BoxAutoTakeRecoveryResult(
    val recoveredBoxNos: List<Int>,
    val message: String
)

object BoxAutoTakeRecoveryPolicy {
    fun isAutoTakeUnavailable(box: KaoPanBox): Boolean {
        return box.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED
    }

    fun statusLabel(box: KaoPanBox): String {
        return if (isAutoTakeUnavailable(box)) "不可自动取用" else ""
    }

    fun statusDetail(box: KaoPanBox): String {
        return if (isAutoTakeUnavailable(box)) "取肠失败" else ""
    }

    fun restoreAutoTakeForSelected(boxes: List<KaoPanBox>): BoxAutoTakeRecoveryResult {
        val selectedUnavailableBoxes = boxes
            .filter { it.checkStatus == true }
            .filter { isAutoTakeUnavailable(it) }

        if (selectedUnavailableBoxes.isEmpty()) {
            return BoxAutoTakeRecoveryResult(
                recoveredBoxNos = emptyList(),
                message = "请先选择不可自动取用的烤肠箱"
            )
        }

        selectedUnavailableBoxes.forEach { box ->
            box.cmdStatusTake = 0
            box.checkStatus = false
        }
        return BoxAutoTakeRecoveryResult(
            recoveredBoxNos = selectedUnavailableBoxes.map { it.positionSn },
            message = "已恢复${selectedUnavailableBoxes.size}个烤肠箱自动取用"
        )
    }
}
