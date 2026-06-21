package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

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
}
