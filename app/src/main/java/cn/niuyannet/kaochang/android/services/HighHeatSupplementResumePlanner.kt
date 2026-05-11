package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
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

    fun isCompleteFinishedBatch(supplementGroup: List<KaoPan>): Boolean {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        if (!hasFullBatchStarted(sortedGroup)) {
            return false
        }
        return sortedGroup.all { it.status == 2 }
    }

    fun findFinishedPartialBatch(supplementGroup: List<KaoPan>, nowMs: Long): List<KaoPan> {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allFinished = occupiedPans.all { pan ->
            pan.startTime > 0L &&
                pan.bakingTime > 0L &&
                nowMs - pan.startTime >= pan.bakingTime
        }
        return if (allFinished) occupiedPans else emptyList()
    }

    fun markFinishedPartialBatchKeepWarm(supplementGroup: List<KaoPan>, nowMs: Long): List<KaoPan> {
        val finishedPans = findFinishedPartialBatch(supplementGroup, nowMs)
        finishedPans.forEach { pan ->
            pan.startTime = 0L
            pan.holdingTime = nowMs
            pan.status = 2
        }
        return finishedPans
    }

    fun findKeepWarmPartialBatchReadyToMove(supplementGroup: List<KaoPan>): List<KaoPan> {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allKeepWarm = occupiedPans.all { pan ->
            pan.startTime == 0L &&
                pan.holdingTime > 0L &&
                pan.status == 2
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
}
