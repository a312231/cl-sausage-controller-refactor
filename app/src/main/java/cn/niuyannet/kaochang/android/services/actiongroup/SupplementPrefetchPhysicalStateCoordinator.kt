package cn.niuyannet.kaochang.android.services.actiongroup

/** 物理状态变更的结果，区分真正执行、已执行和无法确认。 */
enum class SupplementPhysicalMutation {
    APPLIED,
    ALREADY_APPLIED,
    FAILED
}
sealed class SupplementPhysicalCheckpointResult {
    data object Applied : SupplementPhysicalCheckpointResult()
    data object AlreadyApplied : SupplementPhysicalCheckpointResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : SupplementPhysicalCheckpointResult()
}

/**
 * 双 lane 物理检查点的单写者边界。
 *
 * 物理对象保存成功前不写 lane 成功标记，lane 标记保存失败时也不发布成功增量；
 * 重复回调首先检查持久化标记，避免重复扣箱或重复落盘。
 */
class SupplementPrefetchPhysicalStateCoordinator(
    private val repository: SupplementPrefetchSessionRepository,
    private val persistPhysicalState: (SupplementLaneRole, String) -> Unit,
    private val publishPhysicalDelta: (SupplementLaneRole, String) -> Unit
) {
    private val lock = Any()

    fun applySuccessfulPhysicalCheckpoint(
        role: SupplementLaneRole,
        actionName: String,
        mutatePhysicalState: (SupplementPrefetchLaneCheckpoint) -> SupplementPhysicalMutation
    ): SupplementPhysicalCheckpointResult = synchronized(lock) {
        val current = repository.load()
            ?: return@synchronized SupplementPhysicalCheckpointResult.Failed("补肠双 lane 会话不存在")
        var lane = laneOf(current, role)
            ?: return@synchronized SupplementPhysicalCheckpointResult.Failed("补肠 $role lane 不存在")
        if (isAlreadyApplied(lane, actionName)) {
            return@synchronized SupplementPhysicalCheckpointResult.AlreadyApplied
        }

        if (lane.lifecycle != SupplementActionLifecycle.TERMINAL_CONFIRMED) {
            try {
                repository.markTerminalConfirmed(
                    role = role,
                    actionName = actionName,
                    allowWithoutRunningEvidence = true
                )
                lane = laneOf(requireNotNull(repository.load()), role)
                    ?: return@synchronized SupplementPhysicalCheckpointResult.Failed("补肠 $role lane 终态确认后丢失")
            } catch (throwable: Throwable) {
                return@synchronized SupplementPhysicalCheckpointResult.Failed(
                    "补肠动作终态确认失败：role=$role，action=$actionName",
                    throwable
                )
            }
        }

        val mutation = try {
            mutatePhysicalState(lane)
        } catch (throwable: Throwable) {
            return@synchronized SupplementPhysicalCheckpointResult.Failed(
                "补肠物理状态变更异常：role=$role，action=$actionName",
                throwable
            )
        }
        if (mutation == SupplementPhysicalMutation.FAILED) {
            return@synchronized SupplementPhysicalCheckpointResult.Failed(
                "补肠物理状态未确认：role=$role，action=$actionName"
            )
        }

        try {
            persistPhysicalState(role, actionName)
        } catch (throwable: Throwable) {
            return@synchronized SupplementPhysicalCheckpointResult.Failed(
                "补肠物理状态保存失败：role=$role，action=$actionName",
                throwable
            )
        }

        try {
            repository.markPhysicalApplied(
                role = role,
                actionName = actionName,
                boxDeductionApplied = actionName == "in2",
                panPlacementApplied = actionName == "in7"
            )
        } catch (throwable: Throwable) {
            return@synchronized SupplementPhysicalCheckpointResult.Failed(
                "补肠物理检查点标记保存失败：role=$role，action=$actionName",
                throwable
            )
        }

        try {
            publishPhysicalDelta(role, actionName)
        } catch (throwable: Throwable) {
            // lane 已经耐久标记成功，发布失败交给后续补偿，不重复执行物理变更。
            return@synchronized SupplementPhysicalCheckpointResult.Failed(
                "补肠物理增量发布失败：role=$role，action=$actionName",
                throwable
            )
        }
        if (mutation == SupplementPhysicalMutation.ALREADY_APPLIED) {
            SupplementPhysicalCheckpointResult.AlreadyApplied
        } else {
            SupplementPhysicalCheckpointResult.Applied
        }
    }

    private fun laneOf(
        session: SupplementPrefetchPipelineSession,
        role: SupplementLaneRole
    ): SupplementPrefetchLaneCheckpoint? = when (role) {
        SupplementLaneRole.DELIVERY -> session.deliveryLane
        SupplementLaneRole.PREFETCH -> session.prefetchLane
    }

    private fun isAlreadyApplied(lane: SupplementPrefetchLaneCheckpoint, actionName: String): Boolean = when (actionName) {
        "in2" -> lane.boxDeductionApplied
        "in7" -> lane.panPlacementApplied
        else -> false
    }
}
