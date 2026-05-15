package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste

internal object HighHeatSupplementResumePlanner {
    data class ResumeTarget(
        val kaoPan: KaoPan,
        val tasteCode: String
    ) {
        val positionSn: Int
            get() = kaoPan.positionSn
    }

    fun hasIncompleteBatch(supplementGroup: List<KaoPan>): Boolean {
        val occupiedCount = supplementGroup.count { it.isHasSausage }
        return occupiedCount in 1 until supplementGroup.size
    }

    fun hasFullBatchStarted(supplementGroup: List<KaoPan>): Boolean {
        return supplementGroup.isNotEmpty() && supplementGroup.all { it.isHasSausage }
    }

    fun hasBatchStarted(supplementGroup: List<KaoPan>): Boolean {
        return supplementGroup.any { it.isHasSausage }
    }

    fun planInitialTargets(
        frontPans: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        listBox: List<KaoPanBox>,
        requestedCount: Int = 9
    ): List<ResumeTarget> {
        val targetPans = supplementGroup
            .filter { !it.isHasSausage }
            .sortedBy { it.positionSn }
            .take(requestedCount)
        if (targetPans.isEmpty()) {
            return emptyList()
        }

        val reservedTasteCodes = frontPans
            .filter { !it.isHasSausage }
            .sortedBy { it.positionSn }
            .mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
        val stockRatioTasteCodes = expandTasteAllocation(
            KaoPanHelper.computeHighKaoPamRatio(listBox, targetPans.size)
        )

        return targetPans.mapIndexedNotNull { index, targetPan ->
            val tasteCode = reservedTasteCodes.getOrNull(index)
                ?: stockRatioTasteCodes.getOrNull(index)
                ?: stockRatioTasteCodes.firstOrNull()
                ?: return@mapIndexedNotNull null
            targetPan.taste = Taste(tasteCode)
            ResumeTarget(targetPan, tasteCode)
        }
    }

    fun planResumeTargets(frontPans: List<KaoPan>, supplementGroup: List<KaoPan>): List<ResumeTarget> {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedCount = sortedGroup.count { it.isHasSausage }
        val emptyPans = sortedGroup.filter { !it.isHasSausage }
        if (occupiedCount == 0 || emptyPans.isEmpty()) {
            return emptyList()
        }

        val reservedFrontPans = frontPans
            .filter { !it.isHasSausage && !it.taste?.tasteCode.isNullOrBlank() }
            .sortedBy { it.positionSn }
        val groupStart = sortedGroup.firstOrNull()?.positionSn ?: return emptyList()

        return emptyPans.mapNotNull { targetPan ->
            val tasteCode = targetPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                ?: reservedTasteForGroupPosition(targetPan, groupStart, reservedFrontPans)
            if (tasteCode.isNullOrBlank()) {
                return@mapNotNull null
            }
            if (targetPan.taste?.tasteCode.isNullOrBlank()) {
                targetPan.taste = Taste(tasteCode)
            }
            ResumeTarget(targetPan, tasteCode)
        }
    }

    fun isCompleteFinishedBatch(
        supplementGroup: List<KaoPan>,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): Boolean {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        if (!hasFullBatchStarted(sortedGroup)) {
            return false
        }
        return sortedGroup.all {
            it.status == 2 && it.positionSn !in pendingDiscardPositions
        }
    }

    fun findFinishedPartialBatch(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allFinished = occupiedPans.all { pan ->
            isKeepWarmReady(pan) || isBakingMatured(pan, config, nowMs)
        }
        return if (allFinished) occupiedPans else emptyList()
    }

    fun markFinishedPartialBatchKeepWarm(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        val finishedPans = findFinishedPartialBatch(supplementGroup, nowMs, config)
        finishedPans.forEach { pan ->
            if (pan.startTime > 0L) {
                pan.startTime = 0L
                pan.holdingTime = nowMs
                pan.status = 2
            }
        }
        return finishedPans
    }

    fun findKeepWarmPartialBatchReadyToMove(
        supplementGroup: List<KaoPan>,
        config: AppConfigBean = AppConfig.getAppConfig(),
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allKeepWarm = occupiedPans.all { pan ->
            isKeepWarmReady(pan, pendingDiscardPositions)
        }
        return if (allKeepWarm) occupiedPans else emptyList()
    }

    private fun reservedTasteForGroupPosition(
        targetPan: KaoPan,
        groupStart: Int,
        reservedFrontPans: List<KaoPan>
    ): String? {
        val offset = targetPan.positionSn - groupStart
        if (offset < 0) {
            return null
        }
        return reservedFrontPans.getOrNull(offset)?.taste?.tasteCode?.takeIf { it.isNotBlank() }
    }

    private fun expandTasteAllocation(allocation: Map<String, Int>): List<String> {
        return allocation.flatMap { (tasteCode, count) ->
            List(count.coerceAtLeast(0)) { tasteCode }
        }
    }

    private fun isKeepWarmReady(
        pan: KaoPan,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): Boolean {
        return KaoPanHelper.isSellable(pan, pendingDiscardPositions) &&
            pan.startTime == 0L &&
            pan.holdingTime > 0L
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
