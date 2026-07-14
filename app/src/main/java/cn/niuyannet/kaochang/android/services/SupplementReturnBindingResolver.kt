package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

internal object SupplementReturnBindingResolver {

    enum class FailureReason {
        NONE,
        NO_TASTE,
        NO_BINDING,
        TARGET_NOT_FOUND,
        TARGET_OCCUPIED,
        TARGET_STATUS_UNSAFE
    }

    data class Result(
        val sourcePan: KaoPan,
        val targetPan: KaoPan?,
        val returnPositionSn: Int?,
        val tasteCode: String?,
        val failureReason: FailureReason
    ) {
        val isSuccess: Boolean
            get() = targetPan != null && failureReason == FailureReason.NONE
    }

    fun resolveReturnTarget(
        sourcePan: KaoPan,
        frontPans: List<KaoPan>,
        activeIntent: SupplementBatchIntent?,
        allowedTypes: Set<SupplementBatchType>,
        returnPositionRange: IntRange,
        allowLegacyFallback: Boolean = false
    ): Result {
        val intentTarget = activeIntent?.let { intent ->
            intent.targets.firstOrNull {
                it.positionSn == sourcePan.positionSn &&
                    targetMatchesAllowedType(it, intent.type, allowedTypes) &&
                    it.returnPositionSn in returnPositionRange &&
                    !it.cancelledByFaultTimeout &&
                    !it.skippedBySupplyDegrade
            }
        }
        val tasteCode = sourcePan.taste?.tasteCode?.takeIf { it.isNotBlank() }
            ?: intentTarget?.tasteCode?.takeIf { it.isNotBlank() }
            ?: return Result(sourcePan, null, intentTarget?.returnPositionSn, null, FailureReason.NO_TASTE)

        val returnPositionSn = intentTarget?.returnPositionSn
        if (returnPositionSn != null) {
            return resolveBoundTarget(
                sourcePan = sourcePan,
                frontPans = frontPans,
                returnPositionSn = returnPositionSn,
                tasteCode = tasteCode,
                returnPositionRange = returnPositionRange
            )
        }

        if (!allowLegacyFallback) {
            return Result(sourcePan, null, null, tasteCode, FailureReason.NO_BINDING)
        }
        if (activeIntent != null) {
            return Result(sourcePan, null, null, tasteCode, FailureReason.NO_BINDING)
        }
        val legacyTarget = selectUniqueLegacyTarget(frontPans, tasteCode, returnPositionRange)
            ?: return Result(sourcePan, null, null, tasteCode, FailureReason.NO_BINDING)
        restoreReturnReservation(legacyTarget, tasteCode)
        return Result(sourcePan, legacyTarget, legacyTarget.positionSn, tasteCode, FailureReason.NONE)
    }

    private fun resolveBoundTarget(
        sourcePan: KaoPan,
        frontPans: List<KaoPan>,
        returnPositionSn: Int,
        tasteCode: String,
        returnPositionRange: IntRange
    ): Result {
        val targetPan = frontPans.firstOrNull {
            it.positionSn == returnPositionSn && it.positionSn in returnPositionRange
        } ?: return Result(sourcePan, null, returnPositionSn, tasteCode, FailureReason.TARGET_NOT_FOUND)
        if (targetPan.isHasSausage) {
            return Result(sourcePan, null, returnPositionSn, tasteCode, FailureReason.TARGET_OCCUPIED)
        }
        if (targetPan.status != KaoPan.STATUS_EMPTY) {
            return Result(sourcePan, null, returnPositionSn, tasteCode, FailureReason.TARGET_STATUS_UNSAFE)
        }
        restoreReturnReservation(targetPan, tasteCode)
        return Result(sourcePan, targetPan, returnPositionSn, tasteCode, FailureReason.NONE)
    }

    private fun selectUniqueLegacyTarget(
        frontPans: List<KaoPan>,
        tasteCode: String,
        returnPositionRange: IntRange
    ): KaoPan? {
        val reservedSameTasteTargets = frontPans.filter {
            it.positionSn in returnPositionRange &&
                !it.isHasSausage &&
                it.status == KaoPan.STATUS_EMPTY &&
                it.isHasGrilling &&
                it.taste?.tasteCode == tasteCode
        }
        if (reservedSameTasteTargets.size == 1) {
            return reservedSameTasteTargets.single()
        }

        val sameTasteTargets = frontPans.filter {
            it.positionSn in returnPositionRange &&
                !it.isHasSausage &&
                it.status == KaoPan.STATUS_EMPTY &&
                it.taste?.tasteCode == tasteCode
        }
        return sameTasteTargets.singleOrNull()
    }

    private fun restoreReturnReservation(
        targetPan: KaoPan,
        tasteCode: String
    ) {
        targetPan.isHasGrilling = true
        if (targetPan.taste?.tasteCode != tasteCode) {
            targetPan.taste = Taste(tasteCode)
        }
    }

    private fun targetMatchesAllowedType(
        target: SupplementBatchIntentTarget,
        intentType: SupplementBatchType,
        allowedTypes: Set<SupplementBatchType>
    ): Boolean {
        val originType = target.originBatchType
        return if (originType != null) {
            originType in allowedTypes
        } else {
            intentType in allowedTypes ||
                allowedTypes.any { type ->
                    SupplementBatchIntentRepository.positionMatchesType(target.positionSn, type)
                }
        }
    }
}
