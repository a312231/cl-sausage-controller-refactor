package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object OrphanSupplementReturnPlanner {

    enum class Failure {
        NO_TASTE,
        INSUFFICIENT_TARGETS,
        UNSAFE_SOURCE
    }

    data class Binding(
        val sourcePositionSn: Int,
        val tasteCode: String,
        val returnPositionSn: Int,
        val originBatchType: SupplementBatchType
    )

    data class Result(
        val bindings: List<Binding>,
        val failure: Failure?
    )

    fun plan(
        sources: List<KaoPan>,
        frontPans: List<KaoPan>,
        reservedFrontPositions: Set<Int>
    ): Result {
        if (sources.isEmpty()) {
            return Result(emptyList(), null)
        }
        val sortedSources = sources.sortedBy { it.positionSn }
        if (sortedSources.any { !isRecognizedSource(it) }) {
            return Result(emptyList(), Failure.UNSAFE_SOURCE)
        }
        if (sortedSources.any { it.taste?.tasteCode.isNullOrBlank() }) {
            return Result(emptyList(), Failure.NO_TASTE)
        }

        val usedTargets = reservedFrontPositions.toMutableSet()
        val bindings = mutableListOf<Binding>()
        for (source in sortedSources) {
            val tasteCode = source.taste.tasteCode
            val originType = source.originBatchType() ?: return Result(emptyList(), Failure.UNSAFE_SOURCE)
            val returnRange = if (originType == SupplementBatchType.HIGH_HEAT_25_33) 1..21 else 1..9
            val target = frontPans
                .asSequence()
                .filter {
                    it.positionSn in returnRange &&
                        it.positionSn !in usedTargets &&
                        !it.isHasSausage &&
                        it.status == KaoPan.STATUS_EMPTY
                }
                .sortedWith(compareBy<KaoPan> { it.targetPriority(tasteCode) }.thenBy { it.positionSn })
                .firstOrNull()
                ?: return Result(emptyList(), Failure.INSUFFICIENT_TARGETS)
            usedTargets += target.positionSn
            bindings += Binding(
                sourcePositionSn = source.positionSn,
                tasteCode = tasteCode,
                returnPositionSn = target.positionSn,
                originBatchType = originType
            )
        }
        return Result(bindings, null)
    }

    fun isRecognizedSource(pan: KaoPan): Boolean {
        val waitingReturn = pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        val legacyHighHeatReturn =
            pan.positionSn in 25..33 &&
                pan.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                pan.startTime <= 0L &&
                pan.holdingTime > 0L
        return (pan.positionSn in 13..18 || pan.positionSn in 25..33) &&
            pan.isHasSausage &&
            (waitingReturn || legacyHighHeatReturn)
    }

    private fun KaoPan.originBatchType(): SupplementBatchType? = when (positionSn) {
        in 13..15 -> SupplementBatchType.LOW_HEAT_13_15
        in 16..18 -> SupplementBatchType.LOW_HEAT_16_18
        in 25..33 -> SupplementBatchType.HIGH_HEAT_25_33
        else -> null
    }

    private fun KaoPan.targetPriority(tasteCode: String): Int = when {
        isHasGrilling && taste?.tasteCode == tasteCode -> 0
        !isHasGrilling && taste?.tasteCode.isNullOrBlank() -> 1
        !isHasGrilling -> 2
        else -> 3
    }
}
