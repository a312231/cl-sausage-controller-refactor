package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

internal fun filterExecutableSupplementTargets(
    targets: List<KaoChangOperate.SupplementTarget>,
    listBox: List<KaoPanBox>
): List<KaoChangOperate.SupplementTarget> {
    val reservedStockByBoxNo = listBox.associate { it.positionSn to it.num }.toMutableMap()
    return targets.mapNotNull { target ->
        val tasteCode = target.tasteCode?.takeIf { it.isNotBlank() }
            ?: target.kaoPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val sourceBox = TrafficModeHelper.findSupplyBoxesByTaste(listBox, tasteCode)
            .firstOrNull { box -> (reservedStockByBoxNo[box.positionSn] ?: 0) > 0 }
            ?: return@mapNotNull null
        reservedStockByBoxNo[sourceBox.positionSn] = (reservedStockByBoxNo[sourceBox.positionSn] ?: 0) - 1
        if (target.tasteCode == tasteCode) {
            target
        } else {
            target.copy(tasteCode = tasteCode)
        }
    }
}
