package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

internal object LowHeatSupplementResumePlanner {
    data class ResumeTarget(
        val kaoPan: KaoPan,
        val tasteCode: String,
        val returnPositionSn: Int? = null
    ) {
        val positionSn: Int
            get() = kaoPan.positionSn
    }

    fun planResumeTargets(
        frontPans: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        activeIntent: SupplementBatchIntent? = null
    ): List<ResumeTarget> {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedCount = sortedGroup.count { it.isHasSausage }
        val emptyPans = sortedGroup.filter { !it.isHasSausage }
        if (occupiedCount == 0 || emptyPans.isEmpty()) {
            return emptyList()
        }

        val reservedFrontPans = frontPans
            .filter { !it.isHasSausage && it.isHasGrilling && !it.taste?.tasteCode.isNullOrBlank() }
            .sortedBy { it.positionSn }
        val groupStart = sortedGroup.firstOrNull()?.positionSn ?: return emptyList()
        val groupPositions = sortedGroup.map { it.positionSn }.toSet()
        val intentTargetsByPosition = activeIntent
            ?.takeIf { it.type in setOf(SupplementBatchType.LOW_HEAT_13_15, SupplementBatchType.LOW_HEAT_16_18) }
            ?.targets
            ?.filter {
                !it.cancelledByFaultTimeout &&
                    !it.skippedBySupplyDegrade &&
                    it.positionSn in groupPositions
            }
            ?.associateBy { it.positionSn }
            .orEmpty()

        return emptyPans.mapNotNull { targetPan ->
            val intentTarget = intentTargetsByPosition[targetPan.positionSn]
            val reservedFrontPan = reservedFrontPanForGroupPosition(targetPan, groupStart, reservedFrontPans)
            val tasteCode = targetPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                ?: intentTarget?.tasteCode?.takeIf { it.isNotBlank() }
                ?: reservedFrontPan?.taste?.tasteCode?.takeIf { it.isNotBlank() }
            if (tasteCode.isNullOrBlank()) {
                return@mapNotNull null
            }
            if (targetPan.taste?.tasteCode.isNullOrBlank()) {
                targetPan.taste = Taste(tasteCode)
            }
            ResumeTarget(
                kaoPan = targetPan,
                tasteCode = tasteCode,
                returnPositionSn = intentTarget?.returnPositionSn?.takeIf { it in 1..9 }
                    ?: reservedFrontPan?.positionSn
            )
        }
    }

    fun findFinishedPans(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        val occupiedPans = occupiedPans(supplementGroup)
        if (occupiedPans.isEmpty()) {
            return emptyList()
        }
        val allReady = occupiedPans.all { pan ->
            isKeepWarmReady(pan) || isBakingMatured(pan, config, nowMs)
        }
        return if (allReady) occupiedPans else emptyList()
    }

    fun markFinishedPansKeepWarm(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        val finishedPans = findFinishedPans(supplementGroup, nowMs, config)
        finishedPans.forEach { pan ->
            if (pan.startTime > 0L) {
                pan.startTime = 0L
                pan.holdingTime = nowMs
                pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            }
        }
        return finishedPans
    }

    fun findKeepWarmPansReadyToMove(
        supplementGroup: List<KaoPan>,
        config: AppConfigBean = AppConfig.getAppConfig(),
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        normalizeLegacySellableKeepWarmSources(supplementGroup)
        val occupiedPans = occupiedPans(supplementGroup)
        if (occupiedPans.isEmpty()) {
            return emptyList()
        }
        return if (occupiedPans.all { isKeepWarmReady(it, pendingDiscardPositions) }) {
            occupiedPans
        } else {
            emptyList()
        }
    }

    fun longestRemainingBakingMs(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): Long? {
        return supplementGroup
            .filter { it.isHasSausage && it.startTime > 0L && it.bakingTime > 0L }
            .map { pan ->
                (pan.bakingTime - FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs)).coerceAtLeast(0L)
            }
            .maxOrNull()
    }

    fun normalizeLegacySellableKeepWarmSources(supplementGroup: List<KaoPan>): List<Int> {
        val normalizedPositions = mutableListOf<Int>()
        supplementGroup
            .filter { pan ->
                pan.positionSn in 13..18 &&
                    pan.isHasSausage &&
                    pan.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                    pan.startTime <= 0L &&
                    pan.holdingTime > 0L
            }
            .forEach { pan ->
                pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                normalizedPositions += pan.positionSn
            }
        return normalizedPositions.sorted()
    }

    private fun reservedFrontPanForGroupPosition(
        targetPan: KaoPan,
        groupStart: Int,
        reservedFrontPans: List<KaoPan>
    ): KaoPan? {
        val offset = targetPan.positionSn - groupStart
        if (offset < 0) {
            return null
        }
        return reservedFrontPans.getOrNull(offset)
    }

    private fun occupiedPans(supplementGroup: List<KaoPan>): List<KaoPan> {
        return supplementGroup
            .filter { it.isHasSausage }
            .sortedBy { it.positionSn }
    }

    private fun isKeepWarmReady(
        pan: KaoPan,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): Boolean {
        return pan.isHasSausage &&
            pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM &&
            pan.startTime == 0L &&
            pan.holdingTime > 0L &&
            pan.positionSn !in pendingDiscardPositions
    }

    private fun isBakingMatured(
        pan: KaoPan,
        config: AppConfigBean,
        nowMs: Long
    ): Boolean {
        return pan.startTime > 0L &&
            pan.bakingTime > 0L &&
            FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs) >= pan.bakingTime
    }
}
