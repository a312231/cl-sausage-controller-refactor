package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object LowInitializationMaturityCloseoutPolicy {
    data class Closeout(
        val batchId: String,
        val maturedPositions: List<Int>,
        val remainingPositions: List<Int>,
        val requiresPanSettlement: Boolean,
        val preparedBeforeEvaluation: Boolean
    )

    fun evaluate(
        intent: SupplementBatchIntent,
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        recoveryBlocked: Boolean,
        unfinishedSupplementPlacementEvidence: Boolean
    ): Closeout? {
        if (!recoveryBlocked || unfinishedSupplementPlacementEvidence) return null
        if (intent.type != SupplementBatchType.LOW_INITIALIZATION_1_9) return null
        if (config.modeType != 1 || config.transitionMode != 0 || config.highConcurrencyResidualDrain == 1) return null
        if (config.pendingDiscardPositions.isNotEmpty() || FaultHeatPauseManager.shouldSkipBakingCompletion(config)) return null
        if (intent.batchId.isBlank() || intent.targets.any {
                !matchesInitializationOrigin(intent, it) ||
                    it.resolvedOriginBatchType() != SupplementBatchType.LOW_INITIALIZATION_1_9
            }
        ) return null

        val frontPans = pans.filter { it.positionSn in 1..9 }
        if (frontPans.map { it.positionSn }.distinct().size != 9) return null
        val remaining = intent.remainingTargets()
        if (remaining.isEmpty()) return null
        val remainingPositions = remaining.map { it.positionSn }.distinct().sorted()
        if (remainingPositions.any { it !in 1..9 }) return null
        if (frontPans.filter { it.positionSn in remainingPositions }.any { it.isHasSausage || it.isHasGrilling }) return null

        val completedPositions = intent.targets.filter { it.completed }.map { it.positionSn }.toSet()
        val occupiedFront = frontPans.filter { it.isHasSausage }
        if (occupiedFront.isEmpty() || occupiedFront.any { it.positionSn !in completedPositions }) return null
        val allMaturedBaking = occupiedFront.all {
            it.status == 1 &&
                it.startTime > 0L &&
                it.holdingTime == 0L &&
                it.bakingTime > 0L &&
                FaultHeatPauseManager.effectiveBakingElapsedMs(it, config, nowMs) >= it.bakingTime
        }
        val allAlreadySettled = occupiedFront.all {
            it.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                it.startTime == 0L &&
                it.holdingTime > 0L
        }
        val closeoutMarkerMatches = intent.maturityCloseoutPreparedAtMs > 0L &&
            intent.maturityCloseoutMaturedPositions.distinct().sorted() == occupiedFront.map { it.positionSn }.sorted() &&
            intent.maturityCloseoutRemainingPositions.distinct().sorted() == remainingPositions
        if (!allMaturedBaking && !closeoutMarkerMatches) return null
        if (closeoutMarkerMatches && !allMaturedBaking && !allAlreadySettled && occupiedFront.any {
                it.status != 1 || it.startTime <= 0L || it.holdingTime > 0L
            }
        ) return null

        return Closeout(
            batchId = intent.batchId,
            maturedPositions = occupiedFront.map { it.positionSn }.sorted(),
            remainingPositions = remainingPositions,
            requiresPanSettlement = !allAlreadySettled,
            preparedBeforeEvaluation = closeoutMarkerMatches
        )
    }

    private fun matchesInitializationOrigin(
        intent: SupplementBatchIntent,
        target: SupplementBatchIntentTarget
    ): Boolean {
        if (target.originBatchId == intent.batchId) return true
        return intent.createdAtMs > 0L &&
            target.originBatchId ==
            "legacy:${intent.createdAtMs}:LOW_INITIALIZATION_1_9:${target.positionSn}"
    }
}
