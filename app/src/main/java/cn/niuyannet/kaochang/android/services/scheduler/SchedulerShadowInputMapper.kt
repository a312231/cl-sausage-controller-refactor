package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.scheduler.PanLifecycle
import cn.niuyannet.kaochang.scheduler.PanSnapshot
import cn.niuyannet.kaochang.scheduler.SellCandidateInput

object SchedulerShadowInputMapper {
    fun map(
        nowMs: Long,
        tasteCode: String,
        productId: Int,
        sellRange: IntRange,
        pendingDiscardPositions: Collection<Int>,
        pans: Collection<KaoPan>
    ): SellCandidateInput {
        val pendingSnapshot = pendingDiscardPositions.toSet()
        val panSnapshots = pans.map { pan ->
            val taste = pan.taste
            PanSnapshot(
                positionSn = pan.positionSn,
                hasSausage = pan.isHasSausage,
                lifecycle = pan.status.toLifecycle(),
                holdingTime = pan.holdingTime,
                closeTime = pan.closeTime,
                tasteCode = taste?.tasteCode,
                productId = taste?.productId
            )
        }
        return SellCandidateInput(
            nowMs = nowMs,
            tasteCode = tasteCode,
            productId = productId,
            sellRange = sellRange,
            pendingDiscardPositions = pendingSnapshot,
            pans = panSnapshots
        )
    }

    private fun Int.toLifecycle(): PanLifecycle = when (this) {
        KaoPan.STATUS_EMPTY -> PanLifecycle.EMPTY
        KaoPan.STATUS_BAKING -> PanLifecycle.BAKING
        KaoPan.STATUS_SELLABLE_KEEP_WARM -> PanLifecycle.SELLABLE_KEEP_WARM
        KaoPan.STATUS_WAITING_RETURN_KEEP_WARM -> PanLifecycle.WAITING_RETURN_KEEP_WARM
        else -> PanLifecycle.UNKNOWN
    }
}
