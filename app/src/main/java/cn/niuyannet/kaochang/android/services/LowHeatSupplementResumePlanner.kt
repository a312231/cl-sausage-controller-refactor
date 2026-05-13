package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

internal object LowHeatSupplementResumePlanner {
    data class ResumeTarget(
        val kaoPan: KaoPan,
        val tasteCode: String
    ) {
        val positionSn: Int
            get() = kaoPan.positionSn
    }

    fun planResumeTargets(frontPans: List<KaoPan>, supplementGroup: List<KaoPan>): List<ResumeTarget> {
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

    fun findFinishedPans(supplementGroup: List<KaoPan>, nowMs: Long): List<KaoPan> {
        return supplementGroup
            .filter { pan ->
                pan.isHasSausage &&
                    pan.startTime > 0L &&
                    pan.bakingTime > 0L &&
                    nowMs - pan.startTime >= pan.bakingTime
            }
            .sortedBy { it.positionSn }
    }

    fun markFinishedPansKeepWarm(supplementGroup: List<KaoPan>, nowMs: Long): List<KaoPan> {
        val finishedPans = findFinishedPans(supplementGroup, nowMs)
        finishedPans.forEach { pan ->
            pan.startTime = 0L
            pan.holdingTime = nowMs
            pan.status = 2
        }
        return finishedPans
    }

    fun findKeepWarmPansReadyToMove(
        supplementGroup: List<KaoPan>,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): List<KaoPan> {
        return supplementGroup
            .filter { pan ->
                KaoPanHelper.isSellable(pan, pendingDiscardPositions) &&
                    pan.startTime == 0L &&
                    pan.holdingTime > 0L
            }
            .sortedBy { it.positionSn }
    }

    fun longestRemainingBakingMs(supplementGroup: List<KaoPan>, nowMs: Long): Long? {
        return supplementGroup
            .filter { it.isHasSausage && it.startTime > 0L && it.bakingTime > 0L }
            .map { pan -> (pan.bakingTime - (nowMs - pan.startTime)).coerceAtLeast(0L) }
            .maxOrNull()
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
