package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

internal data class SupplementTargetAvailabilityResult(
    val executableTargets: List<KaoChangOperate.SupplementTarget>,
    val skippedTargets: List<SkippedSupplementTarget>
)

internal data class SkippedSupplementTarget(
    val targetPositionSn: Int,
    val tasteCode: String?,
    val reason: String,
    val candidateBoxNos: List<Int>,
    val reservedStockByBoxNo: Map<Int, Int>,
    val sameTasteBoxes: List<SupplyBoxAvailabilitySnapshot>
)

internal data class SupplyBoxAvailabilitySnapshot(
    val boxNo: Int,
    val tasteCode: String?,
    val num: Int,
    val cmdStatusTake: Int,
    val exclusionReasons: List<String>
)

internal fun filterExecutableSupplementTargets(
    targets: List<KaoChangOperate.SupplementTarget>,
    listBox: List<KaoPanBox>
): List<KaoChangOperate.SupplementTarget> =
    planExecutableSupplementTargets(targets, listBox).executableTargets

internal fun planExecutableSupplementTargets(
    targets: List<KaoChangOperate.SupplementTarget>,
    listBox: List<KaoPanBox>
): SupplementTargetAvailabilityResult {
    val reservedStockByBoxNo = listBox.associate { it.positionSn to it.num }.toMutableMap()
    val executableTargets = mutableListOf<KaoChangOperate.SupplementTarget>()
    val skippedTargets = mutableListOf<SkippedSupplementTarget>()
    targets.forEach { target ->
        val tasteCode = target.tasteCode?.takeIf { it.isNotBlank() }
            ?: target.kaoPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
        if (tasteCode == null) {
            skippedTargets += target.skipped(
                tasteCode = null,
                reason = "目标缺少口味",
                candidateBoxes = emptyList(),
                reservedStockByBoxNo = reservedStockByBoxNo,
                sameTasteBoxes = emptyList()
            )
            return@forEach
        }
        val sameTasteBoxes = listBox
            .filter { it.tasteCode == tasteCode }
            .sortedByDescending { it.positionSn }
        val sourceBox = TrafficModeHelper.findSupplyBoxesByTaste(listBox, tasteCode)
            .firstOrNull { box -> (reservedStockByBoxNo[box.positionSn] ?: 0) > 0 }
        if (sourceBox == null) {
            val candidateBoxes = TrafficModeHelper.findSupplyBoxesByTaste(listBox, tasteCode)
            skippedTargets += target.skipped(
                tasteCode = tasteCode,
                reason = if (candidateBoxes.isEmpty()) "无同口味可用箱" else "同口味可用箱预扣库存不足",
                candidateBoxes = candidateBoxes,
                reservedStockByBoxNo = reservedStockByBoxNo,
                sameTasteBoxes = sameTasteBoxes
            )
            return@forEach
        }
        reservedStockByBoxNo[sourceBox.positionSn] = (reservedStockByBoxNo[sourceBox.positionSn] ?: 0) - 1
        executableTargets += if (target.tasteCode == tasteCode) {
            target
        } else {
            target.copy(tasteCode = tasteCode)
        }
    }
    return SupplementTargetAvailabilityResult(executableTargets, skippedTargets)
}

internal fun SupplementTargetAvailabilityResult.describeSkippedTargets(): String =
    skippedTargets.joinToString("；") { skipped ->
        val sameTasteBoxText = skipped.sameTasteBoxes.joinToString("|") { box ->
            "箱${box.boxNo}(num=${box.num},cmdStatusTake=${box.cmdStatusTake},排除=${box.exclusionReasons.joinToString("&").ifBlank { "无" }})"
        }.ifBlank { "无" }
        "target=${skipped.targetPositionSn},taste=${skipped.tasteCode ?: "无"}," +
            "reason=${skipped.reason},candidateBoxes=${skipped.candidateBoxNos}," +
            "reservedStock=${skipped.reservedStockByBoxNo},sameTasteBoxes=$sameTasteBoxText"
    }.ifBlank { "无" }

private fun KaoChangOperate.SupplementTarget.skipped(
    tasteCode: String?,
    reason: String,
    candidateBoxes: List<KaoPanBox>,
    reservedStockByBoxNo: Map<Int, Int>,
    sameTasteBoxes: List<KaoPanBox>
): SkippedSupplementTarget {
    val relevantBoxNos = (candidateBoxes.map { it.positionSn } + sameTasteBoxes.map { it.positionSn })
        .distinct()
    return SkippedSupplementTarget(
        targetPositionSn = kaoPan.positionSn,
        tasteCode = tasteCode,
        reason = reason,
        candidateBoxNos = candidateBoxes.map { it.positionSn },
        reservedStockByBoxNo = relevantBoxNos.associateWith { reservedStockByBoxNo[it] ?: 0 },
        sameTasteBoxes = sameTasteBoxes.map { box ->
            SupplyBoxAvailabilitySnapshot(
                boxNo = box.positionSn,
                tasteCode = box.tasteCode,
                num = box.num,
                cmdStatusTake = box.cmdStatusTake,
                exclusionReasons = KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(box)
            )
        }
    )
}
