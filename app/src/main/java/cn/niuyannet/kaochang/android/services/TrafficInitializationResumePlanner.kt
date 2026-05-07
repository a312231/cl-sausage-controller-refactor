package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object TrafficInitializationResumePlanner {
    data class ResumeTarget(
        val kaoPan: KaoPan,
        val tasteCode: String
    ) {
        val positionSn: Int
            get() = kaoPan.positionSn
    }

    fun planFrontInitResumeTargets(
        frontPans: List<KaoPan>,
        heatAreaPans: List<KaoPan>
    ): List<ResumeTarget> {
        val sortedFront = frontPans.sortedBy { it.positionSn }
        val occupiedFront = sortedFront.filter { it.isHasSausage }
        if (occupiedFront.isEmpty() || occupiedFront.size == sortedFront.size) {
            return emptyList()
        }
        if (!heatAreaPans.all { it.isInactiveEmptyPan() }) {
            return emptyList()
        }
        if (!occupiedFront.all { it.isInitialBakingPan() }) {
            return emptyList()
        }

        return sortedFront
            .filter { !it.isHasSausage }
            .mapNotNull { pan ->
                val tasteCode = pan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ResumeTarget(pan, tasteCode)
            }
    }

    private fun KaoPan.isInitialBakingPan(): Boolean {
        return status == 1 || startTime > 0L
    }

    private fun KaoPan.isInactiveEmptyPan(): Boolean {
        return !isHasSausage &&
            !isHasGrilling &&
            startTime <= 0L &&
            holdingTime <= 0L &&
            status == 0
    }
}
