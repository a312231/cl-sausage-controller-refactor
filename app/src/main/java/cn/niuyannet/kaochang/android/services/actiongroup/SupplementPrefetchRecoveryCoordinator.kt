package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException

sealed class SupplementPrefetchRecoveryResult {
    data object Idle : SupplementPrefetchRecoveryResult()
    data class ReadyToResume(val session: SupplementPrefetchPipelineSession) : SupplementPrefetchRecoveryResult()
    data class PausedForOrder(val session: SupplementPrefetchPipelineSession) : SupplementPrefetchRecoveryResult()
    data class PreparedClaimIdle(val session: SupplementPrefetchPipelineSession) : SupplementPrefetchRecoveryResult()
    data class MaintenanceRequired(val reason: String) : SupplementPrefetchRecoveryResult()
}

class SupplementPrefetchRecoveryCoordinator(
    private val client: AtomicActionClient,
    private val repository: SupplementPrefetchSessionRepository,
    private val betweenSnapshots: suspend () -> Unit,
    private val logger: (String) -> Unit = {}
) {
    suspend fun recover(): SupplementPrefetchRecoveryResult {
        return try {
            recoverInternal()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            maintenance(
                "双 lane 恢复异常，已保留恢复证据：" +
                    "${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private suspend fun recoverInternal(): SupplementPrefetchRecoveryResult {
        val initial = repository.load() ?: return SupplementPrefetchRecoveryResult.Idle
        val reader = client as? AtomicActionStateReader
            ?: return maintenance("双 lane 恢复失败：AtomicActionClient 不支持读取结果寄存器")
        runCatching { repository.markRecoveryStarted() }
            .onFailure { return maintenance("双 lane 恢复阶段落盘失败：${it.message}") }
        validatePhysicalEvidence(initial)?.let { return maintenance(it) }

        val first = readSnapshot(reader)
            ?: return maintenance("双 lane 恢复失败：第一次结果寄存器快照读取失败")
        betweenSnapshots()
        val second = readSnapshot(reader)
            ?: return maintenance("双 lane 恢复失败：第二次结果寄存器快照读取失败")
        if (first != second) {
            return maintenance("双 lane 恢复失败：两次结果寄存器快照不稳定")
        }

        val session = requireNotNull(repository.load())
        val lanes = buildList {
            session.deliveryLane?.let { add(SupplementLaneRole.DELIVERY to it) }
            session.prefetchLane?.let { add(SupplementLaneRole.PREFETCH to it) }
        }
        if (session.mechanicalClaimState == MechanicalClaimState.PREPARED &&
            lanes.isNotEmpty() &&
            lanes.all { (_, lane) ->
                lane.currentAction == null && lane.lifecycle == SupplementActionLifecycle.PREPARED
            }
        ) {
            if (second.values.all { it == AtomicActionCodes.IDLE }) {
                return SupplementPrefetchRecoveryResult.PreparedClaimIdle(session)
            }
            return maintenance("双 lane PREPARED owner 出现非空闲寄存器，拒绝猜测命令是否下发")
        }
        validateLaneRoles(session)?.let { return maintenance(it) }
        for ((role, lane) in lanes) {
            val action = lane.currentAction ?: continue
            val code = second[action] ?: return maintenance("双 lane 恢复缺少动作状态：role=$role，action=$action")
            if (code == AtomicActionCodes.IDLE && lane.lifecycle < SupplementActionLifecycle.TERMINAL_CONFIRMED) {
                return maintenance("双 lane 动作已回到空闲但未确认终态：role=$role，action=$action")
            }
            if (code == AtomicActionCodes.COMPLETED &&
                lane.lifecycle.ordinal < SupplementActionLifecycle.RUNNING_CONFIRMED.ordinal
            ) {
                return maintenance("双 lane 完成态缺少 RUNNING_CONFIRMED 证据：role=$role，action=$action")
            }
            if (code !in VALID_STATES) {
                val kind = if (code >= AtomicActionCodes.PARAM_ERROR) "错误" else "未知"
                return maintenance("双 lane 读取到下位机${kind}状态：role=$role，action=$action，code=${AtomicActionExecutor.formatCode(code)}")
            }
        }

        val running = lanes.filter { (_, lane) ->
            lane.currentAction?.let { second[it] } == AtomicActionCodes.RUNNING
        }
        val allRunningNames = second
            .filterValues { it == AtomicActionCodes.RUNNING }
            .keys
        val trackedRunningNames = running.mapNotNull { (_, lane) -> lane.currentAction }.toSet()
        if (allRunningNames != trackedRunningNames) {
            return maintenance(
                "双 lane RUNNING 与 durable lane 不一致：" +
                    "all=${allRunningNames.sorted()}，tracked=${trackedRunningNames.sorted()}"
            )
        }
        if (!isLegalRunningCombination(running)) {
            return maintenance(
                "双 lane RUNNING 组合非法：${running.joinToString { (role, lane) -> "$role:${lane.currentAction}" }}"
            )
        }
        running.forEach { (role, lane) ->
            if (lane.lifecycle != SupplementActionLifecycle.RUNNING_CONFIRMED) {
                return maintenance("双 lane RUNNING 缺少持久化运行证据：role=$role，action=${lane.currentAction}")
            }
        }

        val monitor = AtomicExistingActionMonitor(reader)
        val observations = supervisorScope {
            running.map { (role, lane) ->
                async {
                    val actionName = requireNotNull(lane.currentAction)
                    val spec = ActionGroupRecoveryBootstrap.inSpecs.first { it.name == actionName }
                    role to monitor.awaitExisting(spec)
                }
            }.map { it.await() }
        }
        for ((role, observation) in observations) {
            val lane = lanes.first { it.first == role }.second
            val actionName = requireNotNull(lane.currentAction)
            if (observation != ExistingActionObservation.Completed) {
                return maintenance("双 lane 既有动作只读监视未安全完成：role=$role，action=$actionName，observation=$observation")
            }
            repository.markTerminalConfirmed(role, actionName)
            physicalEvidenceFailure(role, lane, actionName)?.let { return maintenance(it) }
        }

        lanes.filter { (_, lane) ->
            lane.currentAction?.let { second[it] } == AtomicActionCodes.COMPLETED &&
                lane.lifecycle == SupplementActionLifecycle.RUNNING_CONFIRMED
        }.forEach { (role, lane) ->
            val actionName = requireNotNull(lane.currentAction)
            repository.markTerminalConfirmed(role, actionName)
            physicalEvidenceFailure(role, lane, actionName)?.let { return maintenance(it) }
        }

        var recovered = runCatching { repository.markRecoveryFinished() }
            .getOrElse { return maintenance("双 lane 恢复完成阶段落盘失败：${it.message}") }
        if (shouldFinalizeCommittedDeliveryPause(recovered)) {
            recovered = runCatching { repository.pauseCommittedDeliveryForPendingOrder() }
                .getOrElse { return maintenance("双 lane 已提交 delivery 转订单暂停失败：${it.message}") }
        }
        logger("双 lane 恢复快照已安全收口：session=${recovered.sessionId}，barrier=${recovered.barrierState}")
        return if (recovered.barrierState == SupplementBarrierState.ORDER_WON ||
            !recovered.pendingOrderNo.isNullOrBlank()
        ) {
            if (SupplementPrefetchOrderPauseSafety.isSafe(recovered)) {
                SupplementPrefetchRecoveryResult.PausedForOrder(recovered)
            } else {
                maintenance("双 lane 订单屏障未处于可暂停安全点")
            }
        } else {
            SupplementPrefetchRecoveryResult.ReadyToResume(recovered)
        }
    }

    private suspend fun readSnapshot(reader: AtomicActionStateReader): Map<String, Int>? {
        val snapshot = linkedMapOf<String, Int>()
        for (spec in ActionGroupRecoveryBootstrap.allSpecs) {
            when (val value = reader.readResult(spec)) {
                is AtomicReadResult.Success -> snapshot[spec.name] = value.value
                is AtomicReadResult.Failure -> return null
            }
        }
        return snapshot
    }

    private fun shouldFinalizeCommittedDeliveryPause(
        session: SupplementPrefetchPipelineSession
    ): Boolean {
        val delivery = session.deliveryLane
        return session.barrierState == SupplementBarrierState.DELIVERY_COMMITTED &&
            !session.pendingOrderNo.isNullOrBlank() &&
            session.prefetchLane == null &&
            delivery?.currentAction == "in7" &&
            delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
            delivery.boxDeductionApplied &&
            delivery.panPlacementApplied
    }

    private fun isLegalRunningCombination(
        running: List<Pair<SupplementLaneRole, SupplementPrefetchLaneCheckpoint>>
    ): Boolean {
        if (running.size <= 1) return true
        if (running.size != 2) return false
        val delivery = running.firstOrNull { it.first == SupplementLaneRole.DELIVERY }?.second ?: return false
        val prefetch = running.firstOrNull { it.first == SupplementLaneRole.PREFETCH }?.second ?: return false
        return delivery.currentAction in setOf("in6", "in7") &&
            prefetch.currentAction in setOf("in1", "in2", "in3")
    }

    private fun validateLaneRoles(session: SupplementPrefetchPipelineSession): String? {
        val delivery = session.deliveryLane
            ?: return "双 lane 恢复会话缺少 delivery lane"
        if (delivery.currentAction !in ACTION_ORDER) {
            return "双 lane delivery checkpoint 非法：action=${delivery.currentAction}"
        }
        val prefetch = session.prefetchLane ?: return null
        if (prefetch.currentAction !in PREFETCH_ACTIONS) {
            return "双 lane prefetch checkpoint 非法：action=${prefetch.currentAction}"
        }
        if (delivery.currentAction !in DELIVERY_OVERLAP_ACTIONS) {
            return "双 lane checkpoint 组合非法：delivery=${delivery.currentAction}，prefetch=${prefetch.currentAction}"
        }
        return null
    }

    private fun validatePhysicalEvidence(session: SupplementPrefetchPipelineSession): String? {
        listOfNotNull(session.deliveryLane, session.prefetchLane).forEach { lane ->
            val actionIndex = ACTION_ORDER.indexOf(lane.currentAction)
            if (lane.boxDeductionApplied && actionIndex < ACTION_ORDER.indexOf("in2")) {
                return "双 lane 扣箱标记与确认阶段矛盾：target=${lane.targetPanNo}"
            }
            if (!lane.boxDeductionApplied &&
                (actionIndex > ACTION_ORDER.indexOf("in2") ||
                    (actionIndex == ACTION_ORDER.indexOf("in2") &&
                        lane.lifecycle >= SupplementActionLifecycle.TERMINAL_CONFIRMED))
            ) {
                return "双 lane 当前动作缺少 in2 扣箱证据：action=${lane.currentAction}，target=${lane.targetPanNo}"
            }
            if (lane.panPlacementApplied && actionIndex < ACTION_ORDER.indexOf("in7")) {
                return "双 lane 落盘标记与确认阶段矛盾：target=${lane.targetPanNo}"
            }
        }
        return null
    }

    private fun physicalEvidenceFailure(
        role: SupplementLaneRole,
        lane: SupplementPrefetchLaneCheckpoint,
        actionName: String
    ): String? = when {
        actionName == "in2" && !lane.boxDeductionApplied -> "in2 已收口但扣箱物理标记缺失：role=$role"
        actionName == "in7" && !lane.panPlacementApplied -> "in7 已收口但落盘物理标记缺失：role=$role"
        else -> null
    }

    private fun maintenance(reason: String): SupplementPrefetchRecoveryResult.MaintenanceRequired {
        val persistedReason = runCatching { repository.markMaintenanceRequired() }
            .exceptionOrNull()
            ?.let { "$reason；维护阶段落盘失败：${it.message}" }
            ?: reason
        logger(persistedReason)
        return SupplementPrefetchRecoveryResult.MaintenanceRequired(persistedReason)
    }

    private companion object {
        val ACTION_ORDER = (1..7).map { "in$it" }
        val PREFETCH_ACTIONS = setOf("in1", "in2", "in3")
        val DELIVERY_OVERLAP_ACTIONS = setOf("in6", "in7")
        val VALID_STATES = setOf(AtomicActionCodes.IDLE, AtomicActionCodes.RUNNING, AtomicActionCodes.COMPLETED)
    }
}
