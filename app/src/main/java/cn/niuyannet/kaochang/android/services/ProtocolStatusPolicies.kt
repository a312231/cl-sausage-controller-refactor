package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryBootstrap
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResidueEvidence
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession

enum class PanMove203Decision {
    WaitingForStart,
    Running,
    Success,
    Failed,
    ReadFailed
}

object PanMove203Policy {
    const val pollDelayMs: Long = 500L
    const val waitOtherActionDelayMs: Long = 2_000L

    fun evaluate(
        code: Int,
        hasObservedRunning: Boolean
    ): PanMove203Decision {
        return when {
            code == -1 -> PanMove203Decision.ReadFailed
            code == 0x0001 -> PanMove203Decision.Running
            code == 0x0002 && hasObservedRunning -> PanMove203Decision.Success
            code == 0x0000 && hasObservedRunning -> PanMove203Decision.Failed
            code == 0x0000 || code == 0x0002 -> PanMove203Decision.WaitingForStart
            code in 0x00F8..0x00FB -> PanMove203Decision.Failed
            else -> PanMove203Decision.Failed
        }
    }
}

object TestPagePanMoveCommandPolicy {
    data class ValidationResult(
        val ok: Boolean,
        val message: String
    )

    fun validate(sourcePosition: Int, targetPosition: Int): ValidationResult {
        return when {
            sourcePosition !in 1..33 -> ValidationResult(false, "源烤盘位必须在 1~33 之间")
            targetPosition !in 1..33 -> ValidationResult(false, "目标烤盘位必须在 1~33 之间")
            sourcePosition == targetPosition -> ValidationResult(false, "源烤盘位和目标烤盘位不能相同")
            else -> ValidationResult(true, "OK")
        }
    }

    fun encodeCommand(sourcePosition: Int, targetPosition: Int): Int {
        return (sourcePosition shl 8) or targetPosition
    }
}

object FourFSelfCleanNoOpPolicy {
    const val message = "4f_self_clean_no_op"

    fun isNoOp(value: String?): Boolean {
        return value == message
    }
}

object Zone3SelfCleanPolicy {
    fun shouldMarkPending(
        zone3Dirty: Int,
        zone3CleanPending: Int,
        zone3CleanSuppressedFor4F: Int,
        zone3HasSausage: Boolean
    ): Boolean {
        return zone3Dirty == 1 &&
            zone3CleanPending != 1 &&
            zone3CleanSuppressedFor4F != 1 &&
            !zone3HasSausage
    }
}

enum class Legacy201Decision {
    WaitingForStart,
    Running,
    Success,
    BusyOrError,
    ReadFailed
}

object Legacy201Policy {
    fun evaluateAfterCommand(status201: Int, hasObservedRunning: Boolean): Legacy201Decision {
        return when (status201) {
            -1 -> Legacy201Decision.ReadFailed
            0 -> if (hasObservedRunning) Legacy201Decision.Success else Legacy201Decision.WaitingForStart
            1 -> Legacy201Decision.Running
            2 -> Legacy201Decision.BusyOrError
            else -> Legacy201Decision.BusyOrError
        }
    }
}

object MoveStatusRecoveryPolicy {
    fun canRecoverIdleFromLegacy201(
        statusReadings: List<Int>,
        actionGroupIdle: Boolean = true
    ): Boolean {
        return actionGroupIdle && statusReadings.size >= 2 && statusReadings.all { status201 ->
            Legacy201Policy.evaluateAfterCommand(status201, hasObservedRunning = false) == Legacy201Decision.WaitingForStart
        }
    }
}

object ManualPanClearPolicy {
    data class Evidence(
        val onlineStatus: Int,
        val isEnable: Int,
        val statusReadings: List<Int>,
        val hasValidPrefetchSession: Boolean,
        val hasActionGroupSession: Boolean,
        val hasPausedSupplementSession: Boolean,
        val hasPanMoveJournal: Boolean,
        val hasPendingOrder: Boolean
    )

    fun canAbandonSupplementPrefetch(evidence: Evidence): Boolean {
        return evidence.onlineStatus == 3 &&
            evidence.isEnable == 0 &&
            MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(evidence.statusReadings) &&
            evidence.hasValidPrefetchSession &&
            !evidence.hasActionGroupSession &&
            !evidence.hasPausedSupplementSession &&
            !evidence.hasPanMoveJournal &&
            !evidence.hasPendingOrder
    }
}

object ManualPanClearCommitter {
    fun commit(
        persistIdle: () -> Boolean,
        clearSession: () -> Boolean,
        releaseOwner: () -> Unit
    ): Boolean {
        if (!runCatching(persistIdle).getOrDefault(false)) return false
        if (!runCatching(clearSession).getOrDefault(false)) return false
        releaseOwner()
        return true
    }
}

data class ManualSupplementPrefetchRecoveryDecision(
    val canRecover: Boolean,
    val reason: String
)

data class ManualSupplementConfirmResult(
    val ok: Boolean,
    val reason: String
)

object ManualSupplementPrefetchRecoveryPolicy {
    fun evaluate(
        pendingBatchId: String,
        failedTargetPanNo: Int?,
        failedActionName: String?,
        session: SupplementPrefetchPipelineSession,
        hasPausedSupplementSession: Boolean,
        hasPanMoveJournal: Boolean,
        snapshots: List<ActionGroupRecoveryResidueEvidence>,
        algorithmEnabled: Boolean = false
    ): ManualSupplementPrefetchRecoveryDecision {
        val matchingLane = listOfNotNull(session.deliveryLane, session.prefetchLane).any { lane ->
            lane.targetPanNo == failedTargetPanNo && lane.currentAction == failedActionName
        }
        val expectedActions = ActionGroupRecoveryBootstrap.allSpecs.map { it.name }.toSet()
        return when {
            algorithmEnabled -> rejectManualSupplementPrefetchRecovery("烤制算法仍开启，请先关闭算法")
            pendingBatchId.isBlank() || session.batchId != pendingBatchId ->
                rejectManualSupplementPrefetchRecovery(
                    "待确认批次与双 lane 会话批次不一致：pending=$pendingBatchId，session=${session.batchId}"
                )
            failedTargetPanNo == null || failedActionName.isNullOrBlank() || !matchingLane ->
                rejectManualSupplementPrefetchRecovery(
                    "待确认失败动作与双 lane 会话坐标不一致：target=$failedTargetPanNo，action=$failedActionName"
                )
            !session.pendingOrderNo.isNullOrBlank() ->
                rejectManualSupplementPrefetchRecovery("双 lane 会话仍绑定订单，禁止人工收口")
            hasPausedSupplementSession ->
                rejectManualSupplementPrefetchRecovery("仍存在订单暂停补肠会话，禁止人工收口")
            hasPanMoveJournal ->
                rejectManualSupplementPrefetchRecovery("仍存在搬盘 journal，禁止人工收口")
            snapshots.size < 2 ->
                rejectManualSupplementPrefetchRecovery("下位机状态尚未完成连续两次空闲确认")
            snapshots.any { !it.modbusConnected } ->
                rejectManualSupplementPrefetchRecovery("Modbus 未稳定连接")
            snapshots.any { it.moveStatus !in 0..1 } ->
                rejectManualSupplementPrefetchRecovery("当前存在非补肠机械状态，禁止人工收口")
            snapshots.any { it.session != null } ->
                rejectManualSupplementPrefetchRecovery("仍存在通用动作组会话，禁止人工收口")
            snapshots.any { it.legacyActionStatus201 != AtomicActionCodes.IDLE } ->
                rejectManualSupplementPrefetchRecovery("201（旧动作状态寄存器）连续读数存在非空闲值")
            snapshots.any { snapshot -> snapshot.resultStates.map { it.actionName }.toSet() != expectedActions } ->
                rejectManualSupplementPrefetchRecovery("300/400动作组结果寄存器快照不完整")
            snapshots.any { snapshot -> snapshot.resultStates.any { it.value == null } } ->
                rejectManualSupplementPrefetchRecovery("300/400动作组结果寄存器存在读取失败")
            snapshots.any { snapshot -> snapshot.resultStates.any { it.value != AtomicActionCodes.IDLE } } ->
                rejectManualSupplementPrefetchRecovery("300/400动作组结果寄存器连续读数存在非空闲值")
            else -> ManualSupplementPrefetchRecoveryDecision(
                canRecover = true,
                reason = "同批次双 lane 失败动作已由人工确认且下位机连续空闲"
            )
        }
    }

    private fun rejectManualSupplementPrefetchRecovery(
        reason: String
    ): ManualSupplementPrefetchRecoveryDecision = ManualSupplementPrefetchRecoveryDecision(
        canRecover = false,
        reason = reason
    )
}

object SourceBoxPositionPolicy {
    const val POLICY_VERSION = 2
    private val disabledSourceBoxes = setOf(1, 11, 12, 22)

    fun isValidSourceBox(positionSn: Int): Boolean {
        return positionSn in 2..10 || positionSn in 13..21
    }

    fun validRangeText(): String = "2~10、13~21"

    fun validRangePayloadText(): String = "2-10,13-21"

    fun disabledBoxes(): List<Int> = disabledSourceBoxes.sorted()
}

object FourFTemperaturePolicy {
    fun clampTargetTemperature(value: Int): Int {
        return when {
            value <= 0 -> 0
            else -> value
        }
    }
}
