package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object SupplementPanStateWriter {
    fun resolveCurrentTargetPan(
        plannedPan: KaoPan,
        currentPans: List<KaoPan>
    ): KaoPan? {
        return currentPans.firstOrNull { it.positionSn == plannedPan.positionSn }
    }
}
