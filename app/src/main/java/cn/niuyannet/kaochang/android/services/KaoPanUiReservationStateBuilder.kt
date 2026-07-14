package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

object KaoPanUiReservationStateBuilder {

    fun apply(
        pans: List<KaoPan>,
        activeIntent: SupplementBatchIntent?,
        recoveryBlocked: Boolean = false
    ): List<KaoPan> {
        pans.forEach { pan ->
            pan.uiReservationState = KaoPan.UI_RESERVATION_NONE
            pan.uiReservationTaste = null
        }

        if (recoveryBlocked) {
            return pans
        }
        applyActiveIntent(pans, activeIntent)
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
            val sourcePan = pans.firstOrNull { it.positionSn == target.positionSn }
            val originType = target.resolvedOriginBatchType()
            val sourceStatusValid = sourcePan?.status in setOf(
                KaoPan.STATUS_BAKING,
                KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            ) || (
                originType == SupplementBatchType.HIGH_HEAT_25_33 &&
                    sourcePan?.status == KaoPan.STATUS_SELLABLE_KEEP_WARM
                )
            val hasValidCompletedSource = !target.completed || (
                sourcePan != null &&
                    sourcePan.isHasSausage &&
                    sourceStatusValid &&
                    originType != null &&
                    SupplementBatchIntentRepository.positionMatchesType(sourcePan.positionSn, originType)
                )
            if (!hasValidCompletedSource) {
                return@forEach
            }
            pans.firstOrNull { it.positionSn == returnPositionSn && it.status == 0 && !it.isHasSausage }
                ?.apply {
                    uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
                    uiReservationTaste = taste
                }
            }
    }

    private fun String?.toUiTaste(): Taste? {
        val code = this?.takeIf { it.isNotBlank() } ?: return null
        return Taste(code)
    }
}
