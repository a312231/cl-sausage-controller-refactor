package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

internal class OrphanSupplementReturnCoordinator(
    private val repository: SupplementBatchIntentRepository,
    private val savePansDurable: (List<KaoPan>) -> Unit
) {

    enum class Failure {
        NO_TASTE,
        INSUFFICIENT_TARGETS,
        UNSAFE_SOURCE,
        CONFLICT,
        STALE_PREVIEW,
        INTENT_PERSIST_FAILED,
        PAN_PERSIST_FAILED
    }

    data class OperationResult(
        val bindings: List<OrphanSupplementReturnPlanner.Binding>,
        val failure: Failure?
    )

    fun preview(
        sources: List<KaoPan>,
        frontPans: List<KaoPan>,
        reservedFrontPositions: Set<Int>
    ): OperationResult = mapPlannerResult(
        OrphanSupplementReturnPlanner.plan(sources, frontPans, reservedFrontPositions)
    )

    fun commit(
        expectedBindings: List<OrphanSupplementReturnPlanner.Binding>,
        sources: List<KaoPan>,
        frontPans: List<KaoPan>,
        reservedFrontPositions: Set<Int>,
        reason: String
    ): OperationResult {
        val current = OrphanSupplementReturnPlanner.plan(sources, frontPans, reservedFrontPositions)
        val currentResult = mapPlannerResult(current)
        if (currentResult.failure != null) {
            return currentResult
        }
        if (currentResult.bindings != expectedBindings) {
            return OperationResult(emptyList(), Failure.STALE_PREVIEW)
        }

        val manualBindings = currentResult.bindings.map { binding ->
            ManualSupplementReturnBinding(
                sourcePositionSn = binding.sourcePositionSn,
                tasteCode = binding.tasteCode,
                returnPositionSn = binding.returnPositionSn,
                originBatchType = binding.originBatchType
            )
        }
        val persistedIntent = try {
            repository.rebindCompletedHeatTargets(manualBindings, reason)
        } catch (_: Exception) {
            return OperationResult(emptyList(), Failure.INTENT_PERSIST_FAILED)
        }
        if (persistedIntent == null) {
            return OperationResult(emptyList(), Failure.CONFLICT)
        }

        currentResult.bindings.forEach { binding ->
            frontPans.first { it.positionSn == binding.returnPositionSn }.apply {
                isHasGrilling = true
                if (taste?.tasteCode != binding.tasteCode) {
                    taste = Taste(binding.tasteCode)
                }
            }
        }
        try {
            savePansDurable(frontPans)
        } catch (_: Exception) {
            return OperationResult(currentResult.bindings, Failure.PAN_PERSIST_FAILED)
        }
        return OperationResult(currentResult.bindings, null)
    }

    private fun mapPlannerResult(
        result: OrphanSupplementReturnPlanner.Result
    ): OperationResult = OperationResult(
        bindings = result.bindings,
        failure = result.failure?.let { failure ->
            when (failure) {
                OrphanSupplementReturnPlanner.Failure.NO_TASTE -> Failure.NO_TASTE
                OrphanSupplementReturnPlanner.Failure.INSUFFICIENT_TARGETS -> Failure.INSUFFICIENT_TARGETS
                OrphanSupplementReturnPlanner.Failure.UNSAFE_SOURCE -> Failure.UNSAFE_SOURCE
            }
        }
    )
}
