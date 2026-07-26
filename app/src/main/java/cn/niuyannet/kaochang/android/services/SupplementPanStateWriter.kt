package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean

internal object SupplementPanStateWriter {
    data class CurrentSupplementState(
        val targetPan: KaoPan,
        val sourceBox: KaoPanBox
    )

    fun resolveCurrentTargetPan(
        plannedPan: KaoPan,
        currentPans: List<KaoPan>
    ): KaoPan? {
        return currentPans.firstOrNull { it.positionSn == plannedPan.positionSn }
    }

    fun resolveCurrentSourceBox(
        plannedBox: KaoPanBox,
        currentBoxes: List<KaoPanBox>
    ): KaoPanBox? {
        return currentBoxes.firstOrNull { it.positionSn == plannedBox.positionSn }
    }

    fun resolveCurrentSupplementState(
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        currentPans: List<KaoPan>,
        currentBoxes: List<KaoPanBox>
    ): CurrentSupplementState? {
        val targetPan = resolveCurrentTargetPan(plannedPan, currentPans) ?: return null
        val sourceBox = resolveCurrentSourceBox(plannedBox, currentBoxes) ?: return null
        return CurrentSupplementState(targetPan, sourceBox)
    }

    fun applySupplementPickedFromBox(
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        currentPans: List<KaoPan>,
        currentBoxes: List<KaoPanBox>
    ): Boolean {
        resolveCurrentTargetPan(plannedPan, currentPans) ?: return false
        val sourceBox = resolveCurrentSourceBox(plannedBox, currentBoxes) ?: return false
        if (sourceBox.num <= 0) {
            return false
        }
        sourceBox.num = maxOf(0, sourceBox.num - 1)
        if (sourceBox.cmdStatusTake != cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes.IR_PICK_FAILED) {
            sourceBox.cmdStatusTake = 0
        }
        return true
    }

    fun applySupplementPlacedOnPan(
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        currentPans: List<KaoPan>,
        currentBoxes: List<KaoPanBox>,
        nowMs: Long,
        clearPendingDiscard: Boolean,
        config: AppConfigBean,
        tasteBuilder: (KaoPanBox, Taste?) -> Taste
    ): Boolean {
        val state = resolveCurrentSupplementState(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = currentPans,
            currentBoxes = currentBoxes
        ) ?: return false
        KaoChangOperate.applySuccessfulSupplementPanStateWithoutBoxDeduction(
            kaoPan = state.targetPan,
            kaoPanBox = state.sourceBox,
            demandGateReason = "补肠动作级物理状态：in7成功",
            nowMs = nowMs,
            clearPendingDiscard = clearPendingDiscard,
            config = config,
            tasteBuilder = tasteBuilder
        )
        return true
    }

    fun applyOutRemovedFromPan(
        plannedPan: KaoPan,
        currentPans: List<KaoPan>
    ): Boolean {
        val sourcePan = resolveCurrentTargetPan(plannedPan, currentPans) ?: return false
        KaoChangOperate.applySuccessfulTakePanState(sourcePan)
        return true
    }
}
