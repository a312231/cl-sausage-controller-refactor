package cn.niuyannet.kaochang.android.services

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
