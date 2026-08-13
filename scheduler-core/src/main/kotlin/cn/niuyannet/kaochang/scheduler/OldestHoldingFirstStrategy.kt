package cn.niuyannet.kaochang.scheduler

class OldestHoldingFirstStrategy : SellCandidateStrategy {
    override val id: StrategyId = ID

    override fun selectPosition(input: SellCandidateInput): Int? {
        return input.pans
            .asSequence()
            .filter { pan -> pan.isSellableFor(input) }
            .minWithOrNull(compareBy<PanSnapshot>({ it.holdingTime }, { it.positionSn }))
            ?.positionSn
    }

    private fun PanSnapshot.isSellableFor(input: SellCandidateInput): Boolean {
        if (positionSn !in input.sellRange ||
            !hasSausage ||
            lifecycle != PanLifecycle.SELLABLE_KEEP_WARM ||
            holdingTime <= 0L ||
            closeTime <= 0L ||
            positionSn in input.pendingDiscardPositions ||
            tasteCode != input.tasteCode ||
            productId != input.productId ||
            input.nowMs < holdingTime
        ) {
            return false
        }
        return input.nowMs - holdingTime <= closeTime
    }

    companion object {
        val ID = StrategyId("legacy-oldest-holding-v1")
    }
}
