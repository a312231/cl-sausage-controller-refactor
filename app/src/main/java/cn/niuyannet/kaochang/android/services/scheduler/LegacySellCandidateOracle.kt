package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.PanLifecycle
import cn.niuyannet.kaochang.scheduler.PanSnapshot
import cn.niuyannet.kaochang.scheduler.SellCandidateInput

object LegacySellCandidateOracle {
    fun selectPosition(input: SellCandidateInput): Int? {
        var selected: PanSnapshot? = null
        for (pan in input.pans) {
            if (!isEligible(pan, input)) continue
            val current = selected
            if (current == null ||
                pan.holdingTime < current.holdingTime ||
                (pan.holdingTime == current.holdingTime && pan.positionSn < current.positionSn)
            ) {
                selected = pan
            }
        }
        return selected?.positionSn
    }

    private fun isEligible(pan: PanSnapshot, input: SellCandidateInput): Boolean {
        if (pan.positionSn !in input.sellRange ||
            !pan.hasSausage ||
            pan.lifecycle != PanLifecycle.SELLABLE_KEEP_WARM ||
            pan.holdingTime <= 0L ||
            pan.closeTime <= 0L ||
            pan.positionSn in input.pendingDiscardPositions ||
            pan.tasteCode != input.tasteCode ||
            pan.productId != input.productId
        ) {
            return false
        }
        val elapsedMs = input.nowMs - pan.holdingTime
        return elapsedMs >= 0L && elapsedMs <= pan.closeTime
    }
}
