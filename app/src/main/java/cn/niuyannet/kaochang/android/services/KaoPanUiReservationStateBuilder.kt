package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

object KaoPanUiReservationStateBuilder {

    fun apply(
        pans: List<KaoPan>,
        activeIntent: SupplementBatchIntent?
    ): List<KaoPan> {
        pans.forEach { pan ->
            pan.uiReservationState = KaoPan.UI_RESERVATION_NONE
            pan.uiReservationTaste = null
        }

        applyActiveIntent(pans, activeIntent)
        inferLowHeatReturnTargets(pans, activeIntent)
        inferHighHeatReturnTargets(pans)
        return pans
    }

    private fun applyActiveIntent(
        pans: List<KaoPan>,
        activeIntent: SupplementBatchIntent?
    ) {
        val intent = activeIntent ?: return
        val remainingTargetPositions = intent.remainingTargets().map { it.positionSn }.toSet()
        intent.targets
            .filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade }
            .forEach { target ->
            val taste = target.tasteCode.toUiTaste()
            if (target.positionSn in remainingTargetPositions) {
                pans.firstOrNull { it.positionSn == target.positionSn && it.status == 0 && !it.isHasSausage }
                    ?.apply {
                        uiReservationState = KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT
                        uiReservationTaste = taste
                    }
            }

            val returnPositionSn = target.returnPositionSn ?: return@forEach
            pans.firstOrNull { it.positionSn == returnPositionSn && it.status == 0 && !it.isHasSausage }
                ?.apply {
                    uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
                    uiReservationTaste = taste
                }
            }
    }

    private fun inferLowHeatReturnTargets(
        pans: List<KaoPan>,
        activeIntent: SupplementBatchIntent?
    ) {
        val frontPans = pans.filter { it.positionSn in 1..9 }
        val oldLowHeatPans = pans.filter { it.positionSn in 13..18 }
        collectOldLowHeatReturnReservations(
            frontPans = frontPans,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = activeIntent
        ).forEach { reservation ->
            val frontPan = frontPans.firstOrNull {
                it.positionSn == reservation.positionSn &&
                    it.status == 0 &&
                    !it.isHasSausage &&
                    it.uiReservationState == KaoPan.UI_RESERVATION_NONE
            } ?: return@forEach
            frontPan.uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
            frontPan.uiReservationTaste = reservation.tasteCode.toUiTaste()
        }

        val activeLowHeatTastes = pans
            .filter { it.positionSn in 13..18 && it.isHasSausage && it.status == 1 }
            .mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            .toSet()
        if (activeLowHeatTastes.isEmpty()) {
            return
        }

        pans.filter {
            it.positionSn in 1..9 &&
                it.status == 0 &&
                !it.isHasSausage &&
                it.isHasGrilling &&
                !it.taste?.tasteCode.isNullOrBlank() &&
                it.taste.tasteCode in activeLowHeatTastes &&
                it.uiReservationState == KaoPan.UI_RESERVATION_NONE
        }.forEach { pan ->
            pan.uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
            pan.uiReservationTaste = pan.taste
        }
    }

    private fun inferHighHeatReturnTargets(pans: List<KaoPan>) {
        val frontPans = pans
            .filter { it.positionSn in 1..21 }
            .sortedBy { it.positionSn }
        val usedTargetPositions = frontPans
            .filter { it.uiReservationState != KaoPan.UI_RESERVATION_NONE }
            .map { it.positionSn }
            .toMutableSet()
        pans
            .filter {
                it.positionSn in 25..33 &&
                    it.isHasSausage &&
                    it.status in setOf(
                        KaoPan.STATUS_BAKING,
                        KaoPan.STATUS_SELLABLE_KEEP_WARM,
                        KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                    ) &&
                    !it.taste?.tasteCode.isNullOrBlank()
            }
            .sortedBy { it.positionSn }
            .forEach { sourcePan ->
                val tasteCode = sourcePan.taste?.tasteCode?.takeIf { it.isNotBlank() } ?: return@forEach
                val targetPan = selectHighHeatReturnTargetForUi(frontPans, tasteCode, usedTargetPositions)
                    ?: return@forEach
                usedTargetPositions += targetPan.positionSn
                targetPan.uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
                targetPan.uiReservationTaste = sourcePan.taste
            }
    }

    private fun selectHighHeatReturnTargetForUi(
        frontPans: List<KaoPan>,
        tasteCode: String,
        usedTargetPositions: Set<Int>
    ): KaoPan? {
        return frontPans.firstOrNull {
            it.positionSn !in usedTargetPositions &&
                it.status == 0 &&
                !it.isHasSausage &&
                it.taste?.tasteCode == tasteCode
        } ?: frontPans.firstOrNull {
            it.positionSn !in usedTargetPositions &&
                it.status == 0 &&
                !it.isHasSausage &&
                it.taste?.tasteCode.isNullOrBlank()
        } ?: frontPans.firstOrNull {
            it.positionSn !in usedTargetPositions &&
                it.status == 0 &&
                !it.isHasSausage
        }
    }

    private fun String?.toUiTaste(): Taste? {
        val code = this?.takeIf { it.isNotBlank() } ?: return null
        return Taste(code)
    }
}
