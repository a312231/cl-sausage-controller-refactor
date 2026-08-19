package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.OrderStateManager
import cn.niuyannet.kaochang.android.mqtt.ConsumableEventPayloadBuilder
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupResult
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryBootstrap
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResult
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderEventPayloadBuilder
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderStatus
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderRecoveryDecision
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderRecoveryGatePolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupManualResidueClearPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ManualDeviceRecoveryCloseoutPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ManualDeviceRecoveryCloseoutResult
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOperationLogContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResidueEvidence
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRegisterState
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionStateReader
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetryContext
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionClient
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicReadResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.AutomaticMechanicalCommandAdmissionCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.AutomaticMechanicalCommandAdmissionResult
import cn.niuyannet.kaochang.android.services.actiongroup.CompletedInActionGroupPipelineTask
import cn.niuyannet.kaochang.android.services.actiongroup.EmergencyResetForceCloseoutPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.EmergencyResetRecoveryFence
import cn.niuyannet.kaochang.android.services.actiongroup.EmergencyResetRecoveryFenceStore
import cn.niuyannet.kaochang.android.services.actiongroup.EmergencyResetRecoveryPhase
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineActionContext
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineResult
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineTask
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPrefetchPipelineRunner
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupRunner
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimOwnerSnapshot
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimOwnerStore
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalMoveClaimCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalMoveClaimResult
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalMoveReleaseResult
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalMoveStatusStore
import cn.niuyannet.kaochang.android.services.actiongroup.GuardedSupplementCloseoutCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.GuardedSupplementCloseoutResult
import cn.niuyannet.kaochang.android.services.actiongroup.NonParallelSupplementOrderHandoffCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaimRecovery
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaimStore
import cn.niuyannet.kaochang.android.services.actiongroup.Out1DispatchEvidence
import cn.niuyannet.kaochang.android.services.actiongroup.OutActionGroupRunner
import cn.niuyannet.kaochang.android.services.actiongroup.OrphanDeliveryBusyRecoveryPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.OrphanDeliveryBusyRegisterState
import cn.niuyannet.kaochang.android.services.actiongroup.OrphanDeliveryBusySnapshot
import cn.niuyannet.kaochang.android.services.actiongroup.PrefetchSupplementPlanner
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceLocks
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceReleaseResult
import cn.niuyannet.kaochang.android.services.actiongroup.PendingActionGroupOrderEvent
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupOrderEventStore
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupSessionStore
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceSupplementPrefetchSessionStore
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementBarrierState
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementDeliveryCommitCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementLaneRole
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPhysicalCheckpointResult
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPhysicalMutation
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutRequest
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutResult
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPhysicalStateCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.claimOrderDeliveryMove
import cn.niuyannet.kaochang.android.services.actiongroup.closeOrderDeliveryMoveForMaintenance
import cn.niuyannet.kaochang.android.services.actiongroup.releaseOrderDeliveryMove
import cn.niuyannet.kaochang.android.services.actiongroup.restoreClaimedOrderMoveStatusIfNeeded
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchOrderGatePolicy
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchOrderPauseSafety
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchLaneCheckpoint
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPipelinePhase
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPipelineOrderCloseout
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPipelineOrderCloseoutPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchRecoveryCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchRecoveryResult
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePlanner
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePreparation
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePlan
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchSessionRepository
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementResumeSingleFlight
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementRuntimeResumeSafetyGate
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementRuntimeResumeSafetyResult
import cn.niuyannet.kaochang.android.services.actiongroup.TakeSausageVoiceReminderPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
import cn.niuyannet.kaochang.android.services.reset.ResetCloseoutPolicy
import cn.niuyannet.kaochang.android.services.reset.ResetCloseoutStore
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchConfigEvidence
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchEvidence
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchOwnerEvidence
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchReadinessIssue
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchReadinessMapper
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileSwitchReadinessResult
import cn.niuyannet.kaochang.android.services.scheduler.captureSchedulerProfileSwitchEvidence
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryCoordinator
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryResult
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryState
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryStateStore
import cn.niuyannet.kaochang.android.ui.OrderMoveStatusPolicy
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.OrderProcessingStage
import cn.niuyannet.kaochang.android.utils.OrderProcessingStageBridge
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

internal fun formatActionGroupFailureReasonForUpload(
    actionName: String,
    result: ActionGroupResult,
    resourceLocks: String
): String {
    val rawReason = when (result) {
        ActionGroupResult.Success -> "$actionName 动作组失败：动作组完成，resourceLocks=$resourceLocks"
        ActionGroupResult.Discarded -> "$actionName 动作组失败：历史兼容结果，resourceLocks=$resourceLocks"
        is ActionGroupResult.Failed ->
            "$actionName 动作组失败：${result.actionName}，${result.message}，resourceLocks=$resourceLocks"
    }
    return if (rawReason.length <= ACTION_GROUP_FAILURE_REASON_MAX_LENGTH) {
        rawReason
    } else {
        rawReason.take(ACTION_GROUP_FAILURE_REASON_MAX_LENGTH - 3) + "..."
    }
}

private const val ACTION_GROUP_FAILURE_REASON_MAX_LENGTH = 300

/**
 * 上位机机械动作封装。
 *
 * 当前项目里和下位机交互最核心的寄存器语义是：
 * - 200：动作指令寄存器。上位机写入动作号，请求下位机执行动作。
 * - 201：动作状态寄存器。
 *   - 0 = 空闲 / 上一次动作已完成
 *   - 1 = 动作执行中
 *   - 2 = 设备忙，本次动作未被接收
 * - 202 / 203：烤盘搬移专用指令和结果寄存器，语义独立于 200 / 201。
 *
 * 因此：
 * - “是否断连”依赖 VMModbusHelper.connectStatus() 判断；
 * - “201=2”只表示设备忙，不等价于通信断开。
 */
object KaoChangOperate {
    private const val ORDER_CLAIM_MAINTENANCE_STATUS = 3
    private const val LOG_ORDER = "订单履约"
    private const val LOG_ACTION = "动作执行"
    private const val LOG_WINDOW = "窗口收尾"
    private const val LOG_SUPPLEMENT = "补肠流程"
    private const val LOG_ACTION_GROUP = "300/400动作组"
    private const val LOG_PAN_MOVE = "烤盘搬移"
    private const val LOG_HEAT = "加热控制"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_SELF_CLEAN = "【自清洁】"
    private const val DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS = 5
    private const val DEFAULT_POST_SELL_IDLE_STABLE_SECONDS = 2
    private const val DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS = 12
    private var lastTemperatureApplyLogSignature: String? = null
    private var lastPhysicalPanHasAnySausage: Boolean? = null

    data class SupplementTarget(
        val kaoPan: KaoPan,
        val tasteCode: String?,
        val returnPositionSn: Int? = null
    )

    private sealed class SupplementMoveResult {
        data object Success : SupplementMoveResult()
        data class SupplyDegraded(
            val reason: String,
            val targetPanNo: Int,
            val failedBoxNos: List<Int>
        ) : SupplementMoveResult()
        data class LowerPlatformIsolated(
            val reason: String,
            val targetPanNo: Int,
            val sourceBoxNo: Int?,
            val actionName: String,
            val code: Int,
            val requiresManualConfirm: Boolean
        ) : SupplementMoveResult()
        data class HardFailure(
            val reason: String,
            val code: Int
        ) : SupplementMoveResult()
    }

    private data class SingleSupplementMoveResult(
        val success: Boolean,
        val actionGroupResult: ActionGroupResult? = null,
        val lowerPlatformIsolationReason: String? = null,
        val lowerPlatformIsolationRequiresManualConfirm: Boolean = false
    )

    /** 仅存在于当前进程的本次串行补肠失败证据，重启后故意丢失。 */
    private data class SerialSupplementFailureProof(
        val owner: String,
        val sessionId: String,
        val mechanicalClaimRevision: Long,
        val resourceMask: Int,
        val actionName: String,
        val sourceNo: Int,
        val targetNo: Int,
        val batchId: String?,
        val batchType: SupplementBatchType?,
        val rawResult: AtomicActionResult,
        val lastConfirmedStage: String?,
        val boxDeductionApplied: Boolean,
        val panPlacementApplied: Boolean,
        val physicalBoxDeducted: Boolean,
        val physicalPanPlaced: Boolean,
        val physicalStockInconsistent: Boolean
    )

    internal data class FaultHeatingRestoreOutcome(
        val succeeded: Boolean,
        val pauseEndResult: FaultHeatPauseManager.EndPauseResult?
    )

    internal data class SupplementBatchAttemptResult(
        val admitted: Boolean,
        val movedCount: Int,
        val batchId: String?
    )

    internal data class ReturnBindingConflictCloseoutEvidence(
        val moveStatus: Int,
        val hasProcessingOrder: Boolean,
        val hasOrderPause: Boolean,
        val hasOrderDeliveryClaim: Boolean,
        val actionSessionAbsent: Boolean,
        val pausedSessionAbsent: Boolean,
        val prefetchEvidenceAbsent: Boolean,
        val hasPanMoveJournal: Boolean,
        val hasDiscardJournal: Boolean,
        val resourceLocksIdle: Boolean
    )

    internal fun returnBindingConflictCloseoutRejection(
        evidence: ReturnBindingConflictCloseoutEvidence
    ): String? = when {
        evidence.moveStatus != 0 -> "机械状态非空闲"
        evidence.hasProcessingOrder -> "存在处理中订单"
        evidence.hasOrderPause -> "订单正在等待机械动作"
        evidence.hasOrderDeliveryClaim -> "存在订单出餐claim"
        !evidence.actionSessionAbsent -> "存在或损坏的动作组会话"
        !evidence.pausedSessionAbsent -> "存在或损坏的暂停补肠会话"
        !evidence.prefetchEvidenceAbsent -> "存在或损坏的双lane预取证据"
        evidence.hasPanMoveJournal -> "存在搬盘journal"
        evidence.hasDiscardJournal -> "存在丢弃journal"
        !evidence.resourceLocksIdle -> "动作组资源锁未释放"
        else -> null
    }

    private const val MANUAL_TAKE_IDLE_TIMEOUT_MS = 15_000L
    /**
     * 机械动作对象内部的主线程作用域。
     *
     * 这里只承接“保存配置、同步烤盘、上报设备状态”这类不属于页面生命周期、
     * 但又需要切回主线程执行的任务，避免继续使用 GlobalScope 造成悬挂协程。
     */
    private val operateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manualTakeTaskCoordinator = ManualTakeTaskCoordinator(operateScope)
    private val manualOutOrderRecoveryCoordinator = ManualOutOrderRecoveryCoordinator()
    private val faultHeatTransitionLock = Any()
    private val emergencyResetRecoveryFenceStore = EmergencyResetRecoveryFenceStore()
    private val actionGroupLocks = ResourceLocks()
    @Volatile
    private var serialSupplementFailureProof: SerialSupplementFailureProof? = null
    private val actionGroupSessionStore = SharedPreferenceActionGroupSessionStore()
    private val supplementPrefetchSessionStore = SharedPreferenceSupplementPrefetchSessionStore()
    private val supplementPrefetchSessionRepository = SupplementPrefetchSessionRepository(
        supplementPrefetchSessionStore
    )
    private val supplementResumeSingleFlight = SupplementResumeSingleFlight()
    private val pausedSupplementSessionStore = SharedPreferenceActionGroupSessionStore(
        key = SharedPreferenceActionGroupSessionStore.KEY_PAUSED_SUPPLEMENT_SESSION
    )
    private val actionGroupOrderEventStore = SharedPreferenceActionGroupOrderEventStore()
    private val orderDeliveryClaimStore = OrderDeliveryClaimStore()
    private val panMoveJournalStore = SharedPreferencePanMoveJournalStore()
    private val supplementBatchIntentStore = SharedPreferenceSupplementBatchIntentStore()
    private val supplementBatchIntentRepository =
        SupplementBatchIntentRepository(supplementBatchIntentStore)
    @Volatile
    private var lastReturnBindingConflictMaintenanceFingerprint: String? = null
    private val orphanSupplementReturnCoordinator = OrphanSupplementReturnCoordinator(
        repository = supplementBatchIntentRepository,
        savePansDurable = { pans -> KaoPanHelper.saveKaoPanListDurable(pans) }
    )

    private val mechanicalMoveStatusStore = object : MechanicalMoveStatusStore {
        override fun load(): Int = AppConfig.getAppConfig().moveStatus

        override fun saveDurable(moveStatus: Int) {
            val config = AppConfig.getAppConfig()
            val previous = config.moveStatus
            config.moveStatus = moveStatus
            try {
                AppConfig.saveAppConfigDurable(config)
            } catch (error: Throwable) {
                config.moveStatus = previous
                throw error
            }
        }
    }

    private val discardJournalStore = SharedPreferenceDiscardJournalStore()

    private fun discardClaimOwnerStore(
        preparedJournal: DiscardJournal? = null
    ): MechanicalClaimOwnerStore = object : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? = discardJournalStore.load()?.let { journal ->
            MechanicalClaimOwnerSnapshot(
                owner = journal.owner,
                state = journal.mechanicalClaimState,
                revision = journal.mechanicalClaimRevision
            )
        }

        override fun savePrepared(owner: String) {
            val current = discardJournalStore.load()
            val base = current ?: requireNotNull(preparedJournal) { "缺少丢弃 PREPARED journal" }
            check(base.owner == owner) { "丢弃 PREPARED owner 不匹配" }
            check(discardJournalStore.saveDurable(
                base.copy(
                    mechanicalClaimState = MechanicalClaimState.PREPARED,
                    mechanicalClaimRevision = maxOf(1L, base.mechanicalClaimRevision + 1L)
                )
            )) { "丢弃 PREPARED owner 落盘失败" }
        }

        override fun saveClaimed(owner: String) {
            val current = requireNotNull(discardJournalStore.load()) { "丢弃 CLAIMED journal 不存在" }
            check(current.owner == owner) { "丢弃 CLAIMED owner 已变化" }
            check(discardJournalStore.saveDurable(
                current.copy(
                    mechanicalClaimState = MechanicalClaimState.CLAIMED,
                    mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
                )
            )) { "丢弃 CLAIMED owner 落盘失败" }
        }

        override fun clearIfOwner(owner: String): Boolean {
            if (discardJournalStore.load()?.owner != owner) return false
            return discardJournalStore.clearDurable()
        }
    }

    private fun panMoveClaimOwnerStore(
        preparedJournal: PanMoveJournal? = null
    ): MechanicalClaimOwnerStore = object : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? = panMoveJournalStore.load()?.let { journal ->
            MechanicalClaimOwnerSnapshot(
                owner = journal.moveId,
                state = journal.mechanicalClaimState,
                revision = journal.mechanicalClaimRevision
            )
        }

        override fun savePrepared(owner: String) {
            val current = panMoveJournalStore.load()
            val base = current ?: requireNotNull(preparedJournal) { "缺少搬盘 PREPARED journal" }
            check(base.moveId == owner) { "搬盘 PREPARED owner 不匹配" }
            check(panMoveJournalStore.saveDurable(
                base.copy(
                    mechanicalClaimState = MechanicalClaimState.PREPARED,
                    mechanicalClaimRevision = maxOf(1L, base.mechanicalClaimRevision + 1L)
                )
            )) { "搬盘 PREPARED owner 落盘失败" }
        }

        override fun saveClaimed(owner: String) {
            val current = requireNotNull(panMoveJournalStore.load()) { "搬盘 CLAIMED journal 不存在" }
            check(current.moveId == owner) { "搬盘 CLAIMED owner 已变化" }
            check(panMoveJournalStore.saveDurable(
                current.copy(
                    mechanicalClaimState = MechanicalClaimState.CLAIMED,
                    mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
                )
            )) { "搬盘 CLAIMED owner 落盘失败" }
        }

        override fun clearIfOwner(owner: String): Boolean {
            if (panMoveJournalStore.load()?.moveId != owner) return false
            return panMoveJournalStore.clearDurable()
        }
    }

    private fun actionGroupClaimOwnerStore(
        preparedSession: ActionGroupSession? = null
    ): MechanicalClaimOwnerStore = object : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? = actionGroupSessionStore.load()?.let { session ->
            MechanicalClaimOwnerSnapshot(
                owner = session.sessionId,
                state = session.mechanicalClaimState,
                revision = session.mechanicalClaimRevision
            )
        }

        override fun savePrepared(owner: String) {
            val session = requireNotNull(preparedSession) { "缺少 PREPARED 动作组会话" }
            check(session.sessionId == owner) { "PREPARED owner 不匹配" }
            actionGroupSessionStore.save(
                session.copy(
                    currentAction = null,
                    mechanicalClaimState = MechanicalClaimState.PREPARED,
                    mechanicalClaimRevision = maxOf(1L, session.mechanicalClaimRevision + 1L)
                )
            )
        }

        override fun saveClaimed(owner: String) {
            val current = requireNotNull(actionGroupSessionStore.load()) { "CLAIMED owner 不存在" }
            check(current.sessionId == owner) { "CLAIMED owner 已变化" }
            actionGroupSessionStore.save(
                current.copy(
                    mechanicalClaimState = MechanicalClaimState.CLAIMED,
                    mechanicalClaimRevision = current.mechanicalClaimRevision + 1L
                )
            )
        }

        override fun clearIfOwner(owner: String): Boolean {
            if (actionGroupSessionStore.load()?.sessionId != owner) {
                return false
            }
            actionGroupSessionStore.clear()
            return true
        }

        override fun clearIfOwnerAndRevision(owner: String, revision: Long): Boolean {
            val current = actionGroupSessionStore.load() ?: return false
            if (current.sessionId != owner || current.mechanicalClaimRevision != revision) {
                return false
            }
            actionGroupSessionStore.clear()
            return true
        }
    }

    private fun actionGroupCloseoutClaimOwnerStore(expectedActionName: String): MechanicalClaimOwnerStore =
        object : MechanicalClaimOwnerStore {
            override fun snapshot(): MechanicalClaimOwnerSnapshot? = actionGroupSessionStore.load()?.let { session ->
                MechanicalClaimOwnerSnapshot(session.sessionId, session.mechanicalClaimState, session.mechanicalClaimRevision)
            }

            override fun savePrepared(owner: String) = error("closeout owner store 不允许创建 PREPARED")
            override fun saveClaimed(owner: String) = error("closeout owner store 不允许创建 CLAIMED")
            override fun clearIfOwner(owner: String): Boolean = false

            override fun clearIfOwnerAndRevision(owner: String, revision: Long): Boolean {
                val current = actionGroupSessionStore.load() ?: return false
                if (current.sessionId != owner || current.mechanicalClaimRevision != revision ||
                    current.currentAction != expectedActionName
                ) return false
                actionGroupSessionStore.clear()
                return true
            }
        }

    private fun mechanicalMoveClaimCoordinator(
        ownerStore: MechanicalClaimOwnerStore
    ) = MechanicalMoveClaimCoordinator(
        transitionLock = faultHeatTransitionLock,
        ownerStore = ownerStore,
        moveStore = mechanicalMoveStatusStore
    )

    /** 仅在旧会话 owner 和 revision 都未变化时收口，避免恢复线程误清新订单。 */
    private fun clearActionGroupSessionIfCurrent(expected: ActionGroupSession): Boolean =
        withFaultHeatTransitionLock {
            val current = actionGroupSessionStore.load()
            if (current == null ||
                current.sessionId != expected.sessionId ||
                current.mechanicalClaimRevision != expected.mechanicalClaimRevision
            ) {
                logW(
                    LOG_ACTION_GROUP,
                    "动作组旧会话收口拒绝：owner/revision 已变化，" +
                        "expected=${expected.sessionId}/${expected.mechanicalClaimRevision}，" +
                        "actual=${current?.sessionId}/${current?.mechanicalClaimRevision}"
                )
                return@withFaultHeatTransitionLock false
            }
            val otherOwners = actionGroupLocks.ownerIds() - expected.sessionId
            if (otherOwners.isNotEmpty()) {
                logW(
                    LOG_ACTION_GROUP,
                    "动作组旧会话收口拒绝：存在其他资源 owner=$otherOwners，" +
                        "sessionId=${expected.sessionId}"
                )
                return@withFaultHeatTransitionLock false
            }
            val release = mechanicalMoveClaimCoordinator(actionGroupClaimOwnerStore()).release(
                owner = expected.sessionId,
                expectedMoveStatuses = setOf(0, 1, 2),
                idleMoveStatus = 0,
                expectedRevision = expected.mechanicalClaimRevision
            )
            if (release !is MechanicalMoveReleaseResult.Released) {
                logW(
                    LOG_ACTION_GROUP,
                    "动作组旧会话收口拒绝清理：$release，sessionId=${expected.sessionId}"
                )
                return@withFaultHeatTransitionLock false
            }
            when (actionGroupLocks.releaseIfOwned(expected.sessionId, expected.resourceMask)) {
                ResourceReleaseResult.RELEASED,
                ResourceReleaseResult.ALREADY_ABSENT -> Unit
                ResourceReleaseResult.MASK_MISMATCH -> {
                    logW(
                        LOG_ACTION_GROUP,
                        "动作组旧会话收口后资源 mask 不匹配，恢复失败：" +
                            "expectedOwner=${expected.sessionId}，" +
                            "expectedMask=${ResourceLocks.describe(expected.resourceMask)}，" +
                            "locks=${actionGroupLocks.snapshot()}"
                    )
                    return@withFaultHeatTransitionLock false
                }
            }
            true
        }

    private fun supplementPipelineClaimOwnerStore(
        preparedSession: SupplementPrefetchPipelineSession? = null
    ): MechanicalClaimOwnerStore = object : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? =
            supplementPrefetchSessionRepository.load()?.let { session ->
                MechanicalClaimOwnerSnapshot(
                    owner = session.sessionId,
                    state = session.mechanicalClaimState,
                    revision = session.mechanicalClaimRevision
                )
            }

        override fun savePrepared(owner: String) {
            val current = supplementPrefetchSessionRepository.load()
            if (current == null) {
                val session = requireNotNull(preparedSession) { "缺少双 lane PREPARED 会话" }
                check(session.sessionId == owner) { "双 lane PREPARED owner 不匹配" }
                supplementPrefetchSessionRepository.create(
                    session.copy(
                        mechanicalClaimState = MechanicalClaimState.PREPARED,
                        mechanicalClaimRevision = maxOf(1L, session.mechanicalClaimRevision + 1L)
                    )
                )
            } else {
                supplementPrefetchSessionRepository.markMechanicalClaimPrepared(owner)
            }
        }

        override fun saveClaimed(owner: String) {
            supplementPrefetchSessionRepository.markMechanicalClaimed(owner)
        }

        override fun clearIfOwner(owner: String): Boolean {
            val current = supplementPrefetchSessionRepository.load() ?: return false
            if (current.sessionId != owner) return false
            return supplementPrefetchSessionRepository.clearIfRevision(current.sessionId, current.revision)
        }

        override fun clearIfOwnerAndRevision(owner: String, revision: Long): Boolean {
            return supplementPrefetchSessionRepository.clearIfMechanicalClaimRevision(owner, revision)
        }
    }

    @Volatile
    private var lastActionGroupRecoveryFailureReason: String? = null

    @Volatile
    private var actionGroupRecoveryVerified: Boolean = false

    @Volatile
    private var supplementBlockedUntilMs: Long = 0L

    private fun isParallelSupplementPrefetchEnabled(): Boolean {
        val config = AppConfig.getAppConfig()
        return config.enableActionGroupPrefetch == 1 &&
            config.enableParallelSupplementPrefetch == 1
    }

    fun isActionGroupBusyForOrderGate(): Boolean {
        val evidence = readSupplementPrefetchEvidence()
        val safePausedForOrder = evidence is DurableEvidenceReadResult.Valid &&
            SupplementPrefetchOrderPauseSafety.isSafe(evidence.value)
        return SupplementPrefetchOrderGatePolicy.isBusy(
            locksIdle = actionGroupLocks.isIdle(),
            evidencePresent = evidence !is DurableEvidenceReadResult.Absent,
            safePausedForOrder = safePausedForOrder
        )
    }

    /**
     * 订单接管出餐的唯一 moveStatus=2 写入口。
     * 只在锁内重新读取状态和动作组忙闲，避免补肠收口把订单接管状态覆盖回 0。
     */
    fun tryClaimOrderDeliveryMoveStatus(orderNo: String): OrderDeliveryClaim? {
        val claim = claimOrderDeliveryMove(
            orderNo = orderNo,
            transitionLock = faultHeatTransitionLock,
            claimStore = orderDeliveryClaimStore,
            moveStore = mechanicalMoveStatusStore
        ) {
            !isEmergencyResetRecoveryPending() && !isActionGroupBusyForOrderGate()
        }
        if (claim == null) {
            logW(
                LOG_ORDER,
                "订单未能取得出餐 owner：orderNo=$orderNo，" +
                    "moveStatus=${AppConfig.getAppConfig().moveStatus}"
            )
        }
        return claim
    }

    fun shouldRecoverManualOutBeforeIncomingOrder(orderNo: String? = null): Boolean {
        val config = AppConfig.getAppConfig()
        return ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
            moveStatus = config.moveStatus,
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            sessionType = actionGroupSessionStore.load()?.type,
            manualTakeTaskActive = manualTakeTaskCoordinator.hasActiveTask(),
            recoveryReservedForOrder = orderNo?.let {
                manualOutOrderRecoveryCoordinator.isReservedFor(it)
            } == true
        )
    }

    fun releaseManualOutOrderRecovery(orderNo: String) {
        manualOutOrderRecoveryCoordinator.release(orderNo)
    }

    fun isActionGroupIdleForMoveStatusRecovery(): Boolean {
        return withFaultHeatTransitionLock {
            val orderClaimRecovery = restoreClaimedOrderMoveStatusIfNeeded(
                transitionLock = faultHeatTransitionLock,
                claimStore = orderDeliveryClaimStore
            )
            orderClaimRecovery == OrderDeliveryClaimRecovery.NO_CLAIM &&
                isActionGroupIdleForMoveStatusRecoveryWithoutOrderClaim()
        }
    }

    /**
     * 只读、非原子的调度 Profile 切换就绪采样。
     *
     * Available 仅表示本次顺序采样未发现 blocker；它不是切换授权，不能消除返回后的启动竞态。
     */
    fun readSchedulerProfileSwitchReadiness(): SchedulerProfileSwitchReadinessResult =
        withFaultHeatTransitionLock {
            val issues = mutableListOf<SchedulerProfileSwitchReadinessIssue>()
            val config = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.CONFIG_READ_FAILED,
                issues
            ) { AppConfig.peekInitializedAppConfig() }
            if (config == null) {
                if (SchedulerProfileSwitchReadinessIssue.CONFIG_READ_FAILED !in issues) {
                    issues += SchedulerProfileSwitchReadinessIssue.CONFIG_UNAVAILABLE
                }
                return@withFaultHeatTransitionLock SchedulerProfileSwitchReadinessResult.unavailable(issues)
            }

            val pendingDiscardPositions = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.CONFIG_READ_FAILED,
                issues
            ) {
                requireNotNull(config.pendingDiscardPositions) { "pendingDiscardPositions 不能为空" }.toSet()
            }.orEmpty()
            val panActivity = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PAN_CACHE_READ_FAILED,
                issues
            ) {
                KaoPanHelper.peekSchedulerSwitchPanActivitySnapshot(pendingDiscardPositions)
            }
            if (panActivity == null && SchedulerProfileSwitchReadinessIssue.PAN_CACHE_READ_FAILED !in issues) {
                issues += SchedulerProfileSwitchReadinessIssue.PAN_CACHE_UNAVAILABLE
            }

            val actionSessionRead = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.ACTION_GROUP_SESSION_READ_FAILED,
                issues
            ) { actionGroupSessionStore.readEvidence() }
            val actionSession = actionSessionRead.valueOrRecordCorruption(
                SchedulerProfileSwitchReadinessIssue.ACTION_GROUP_SESSION_CORRUPTED,
                issues
            )
            val pausedSupplementRead = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PAUSED_SUPPLEMENT_SESSION_READ_FAILED,
                issues
            ) { pausedSupplementSessionStore.readEvidence() }
            val pausedSupplementSession = pausedSupplementRead.valueOrRecordCorruption(
                SchedulerProfileSwitchReadinessIssue.PAUSED_SUPPLEMENT_SESSION_CORRUPTED,
                issues
            )
            val prefetchRead = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PREFETCH_SESSION_READ_FAILED,
                issues
            ) { supplementPrefetchSessionStore.read() }
            val prefetchSession = prefetchRead.valueOrRecordCorruption(
                SchedulerProfileSwitchReadinessIssue.PREFETCH_SESSION_CORRUPTED,
                issues
            )

            val orderClaim = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.ORDER_CLAIM_READ_FAILED,
                issues
            ) { orderDeliveryClaimStore.load() }
            val hasProcessingOrder = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.ORDER_STATE_READ_FAILED,
                issues
            ) { OrderStateManager.hasProcessingOrderSnapshot() } ?: false
            val hasOrderPause = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.ORDER_STATE_READ_FAILED,
                issues
            ) { SupplementPauseController.shouldPauseForOrder() } ?: false
            val hasSupplementBatchIntent = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.SUPPLEMENT_BATCH_INTENT_READ_FAILED,
                issues
            ) { supplementBatchIntentStore.hasRawEvidence() } ?: false

            val panMoveJournal = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PAN_MOVE_JOURNAL_READ_FAILED,
                issues
            ) { panMoveJournalStore.load() }
            if (panMoveJournal?.let { it.moveId.isBlank() || it.sourcePositionSn <= 0 || it.targetPositionSn <= 0 } == true) {
                issues += SchedulerProfileSwitchReadinessIssue.PAN_MOVE_JOURNAL_READ_FAILED
            }
            val discardJournal = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.DISCARD_JOURNAL_READ_FAILED,
                issues
            ) { discardJournalStore.load() }
            if (discardJournal?.let { it.owner.isBlank() || it.positionSn <= 0 } == true) {
                issues += SchedulerProfileSwitchReadinessIssue.DISCARD_JOURNAL_READ_FAILED
            }
            val emergencyResetFence = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.EMERGENCY_RESET_FENCE_READ_FAILED,
                issues
            ) { emergencyResetRecoveryFenceStore.load() }

            val resourceLocksIdle = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.RESOURCE_LOCKS_READ_FAILED,
                issues
            ) { actionGroupLocks.isIdle() } ?: false
            val actionSessionOwnedMask = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.RESOURCE_LOCKS_READ_FAILED,
                issues
            ) { actionSession?.let { actionGroupLocks.ownedMask(it.sessionId) } }
            val processOwners = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PROCESS_OWNER_READ_FAILED,
                issues
            ) {
                SchedulerSwitchProcessOwnerSnapshot(
                    manualTakeTaskActive = manualTakeTaskCoordinator.hasActiveTask(),
                    manualOutRecoveryReserved = manualOutOrderRecoveryCoordinator.hasReservation(),
                    supplementResumeRunning = supplementResumeSingleFlight.isRunning()
                )
            }

            val guardedSupplementResidue = captureSchedulerProfileSwitchEvidence(
                SchedulerProfileSwitchReadinessIssue.PROCESS_OWNER_READ_FAILED,
                issues
            ) {
                hasGuardedSupplementCloseoutResidue(
                    config = config,
                    session = actionSession,
                    sessionOwnedMask = actionSessionOwnedMask
                )
            } ?: true
            SchedulerProfileSwitchReadinessMapper.evaluate(
                SchedulerProfileSwitchEvidence(
                    config = SchedulerProfileSwitchConfigEvidence(
                        moveStatus = config.moveStatus,
                        onlineStatus = config.onlineStatus,
                        inspectionMode = config.inspectionMode,
                        errorStatus = config.errorStatus,
                        isEnable = config.isEnable,
                        modeType = config.modeType,
                        transitionMode = config.transitionMode,
                        highConcurrencyResidualDrain = config.highConcurrencyResidualDrain,
                        faultHeatPauseActive = config.faultHeatPauseActive,
                        hasFaultHeatRecoveryJournal = !config.faultHeatRecoveryJournalJson.isNullOrBlank(),
                        hasPendingDiscardPositions = pendingDiscardPositions.isNotEmpty(),
                        pendingDisableActionGroupV2 = config.pendingDisableActionGroupV2,
                        useActionGroupV2 = config.useActionGroupV2
                    ),
                    owners = SchedulerProfileSwitchOwnerEvidence(
                        algorithmIsEnabled = KaoChangAlgorithm.isEnabled,
                        hasActiveCookingBatch = panActivity?.hasActiveCookingBatch ?: true,
                        hasProcessingOrder = hasProcessingOrder,
                        hasOrderPause = hasOrderPause,
                        hasWholeOrderClaim = orderClaim != null,
                        actionGroupSessionType = actionSession?.type,
                        hasPausedSupplementSession = pausedSupplementSession != null,
                        hasPrefetchSession = prefetchSession != null,
                        hasSupplementBatchIntentEvidence = hasSupplementBatchIntent,
                        resourceLocksIdle = resourceLocksIdle,
                        hasPanMoveJournal = panMoveJournal != null,
                        hasDiscardJournal = discardJournal != null,
                        hasEmergencyResetFence = emergencyResetFence != null,
                        manualTakeTaskActive = processOwners?.manualTakeTaskActive ?: true,
                        manualOutRecoveryReserved = processOwners?.manualOutRecoveryReserved ?: true,
                        supplementResumeRunning = processOwners?.supplementResumeRunning ?: true,
                        actionGroupRecoveryVerified = actionGroupRecoveryVerified,
                        hasGuardedSupplementCloseoutResidue = guardedSupplementResidue
                    ),
                    issues = issues
                )
            )
        }

    private data class SchedulerSwitchProcessOwnerSnapshot(
        val manualTakeTaskActive: Boolean,
        val manualOutRecoveryReserved: Boolean,
        val supplementResumeRunning: Boolean
    )

    private fun <T> DurableEvidenceReadResult<T>?.valueOrRecordCorruption(
        corruptedIssue: SchedulerProfileSwitchReadinessIssue,
        issues: MutableList<SchedulerProfileSwitchReadinessIssue>
    ): T? = when (this) {
        null,
        DurableEvidenceReadResult.Absent -> null
        is DurableEvidenceReadResult.Valid -> value
        is DurableEvidenceReadResult.Corrupted -> {
            issues += corruptedIssue
            null
        }
    }

    /**
     * 旧 201 空闲恢复只能在现有短临界区内重查订单 owner 后写 0。
     * 这避免“检查空闲后订单取得 owner、旧恢复又把 2 清成 0”的竞态。
     */
    fun clearMoveStatusForLegacy201Recovery(statusReadings: List<Int>): Boolean =
        withFaultHeatTransitionLock {
            val orderClaimRecovery = restoreClaimedOrderMoveStatusIfNeeded(
                transitionLock = faultHeatTransitionLock,
                claimStore = orderDeliveryClaimStore
            )
            if (orderClaimRecovery != OrderDeliveryClaimRecovery.NO_CLAIM ||
                !MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(
                    statusReadings,
                    isActionGroupIdleForMoveStatusRecoveryWithoutOrderClaim()
                )
            ) {
                return@withFaultHeatTransitionLock false
            }
            if (mechanicalMoveStatusStore.load() == 0) {
                return@withFaultHeatTransitionLock true
            }
            runCatching { mechanicalMoveStatusStore.saveDurable(0) }
                .onFailure { error ->
                    logW(LOG_ACTION, "旧 201 空闲恢复写入 moveStatus=0 失败：${error.message}")
                }
                .isSuccess
        }

    private fun isActionGroupIdleForMoveStatusRecoveryWithoutOrderClaim(): Boolean {
        return actionGroupSessionStore.readEvidence() is DurableEvidenceReadResult.Absent &&
            pausedSupplementSessionStore.readEvidence() is DurableEvidenceReadResult.Absent &&
            panMoveJournalStore.load() == null &&
            discardJournalStore.load() == null &&
            !hasBlockingSupplementPrefetchEvidence() &&
            actionGroupLocks.isIdle()
    }

    data class OrphanDeliveryBusyRecoveryResult(
        val recovered: Boolean,
        val reason: String
    )

    data class OrderClaimMaintenanceCloseoutResult(
        val ok: Boolean,
        val reason: String
    )

    private data class EmergencyResetIdleSnapshot(
        val actionGroupEvidence: ActionGroupRecoveryResidueEvidence,
        val panMoveResult203: Int?
    )

    fun currentOrderDeliveryClaimForMaintenance(): OrderDeliveryClaim? = orderDeliveryClaimStore.load()

    fun prepareEmergencyResetRecoveryFence(reason: String): EmergencyResetRecoveryFence {
        val fence = withFaultHeatTransitionLock {
            KaoChangAlgorithm.disableRoastAlgorithm(reason = reason, syncToServer = false)
            val config = AppConfig.getAppConfig()
            config.onlineStatus = ORDER_CLAIM_MAINTENANCE_STATUS
            config.isEnable = 0
            AppConfig.saveAppConfigDurable(config)
            HomeUiRefreshBridge.requestRefresh("emergency_reset:maintenance_locked")
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            val prepared = EmergencyResetRecoveryFence.prepared(
                resetId = java.util.UUID.randomUUID().toString(),
                originProcessInstanceId = AppProcessIdentity.currentId,
                createdAtMs = now
            )
            emergencyResetRecoveryFenceStore.save(prepared)
            actionGroupRecoveryVerified = false
            logW(
                LOG_ACTION_GROUP,
                "已建立紧急复位恢复栅栏：resetId=${prepared.resetId}，" +
                    "process=${prepared.originProcessInstanceId}，reason=$reason"
            )
            prepared
        }
        runCatching { publishRuntimeStateWithHttpFallback("紧急复位已锁定维护") }
            .onFailure { error ->
                logW(LOG_ACTION_GROUP, "紧急复位维护态即时上报失败，设备继续保持维护：${error.message}")
            }
        return fence
    }

    fun writeEmergencyResetAndBlockMechanicalCommands(): VMModbusHelper.ModbusOperationResult =
        VMModbusHelper.writeEmergencyResetAndBlockMechanicalCommands(
            serverAddress = modbus_address,
            resetRegister = 999,
            resetValue = 1
        )

    fun completeEmergencyResetWrite(
        preparedFence: EmergencyResetRecoveryFence,
        writeSucceeded: Boolean,
        reason: String
    ): String = withFaultHeatTransitionLock {
        val current = requireNotNull(emergencyResetRecoveryFenceStore.load()) {
            "紧急复位恢复栅栏不存在，拒绝确认写入结果"
        }
        check(current.matchesReset(preparedFence.resetId) &&
            current.phase == EmergencyResetRecoveryPhase.PREPARED
        ) { "紧急复位恢复栅栏已变化，拒绝确认写入结果" }
        if (!writeSucceeded) {
            return@withFaultHeatTransitionLock "999 写入失败，恢复栅栏保持待重试状态，设备继续维护"
        }
        emergencyResetRecoveryFenceStore.save(current.markResetWritten())
        logW(
            LOG_ACTION_GROUP,
            "紧急复位 999=1 已写入，旧机械协调状态等待人工确认后强制收口：" +
                "resetId=${current.resetId}，process=${current.originProcessInstanceId}，reason=$reason"
        )
        "已建立一次性紧急复位强制恢复栅栏"
    }

    fun hasPendingEmergencyResetRecovery(): Boolean = isEmergencyResetRecoveryPending()

    fun enforceEmergencyResetRecoveryOnStartup(): Boolean {
        if (!isEmergencyResetRecoveryPending()) return false
        VMModbusHelper.blockMechanicalWritesForPendingEmergencyReset()
        withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            config.onlineStatus = ORDER_CLAIM_MAINTENANCE_STATUS
            config.isEnable = 0
            AppConfig.saveAppConfigDurable(config)
            actionGroupRecoveryVerified = false
        }
        HomeUiRefreshBridge.requestRefresh("emergency_reset:cold_start_maintenance")
        return true
    }

    fun closeMechanicalStateAfterLowerBoardReset(): Boolean {
        val committed = withFaultHeatTransitionLock {
            val saved = AppConfig.saveLowerBoardResetLocalCloseoutDurable(
                claimKey = OrderDeliveryClaimStore.KEY_ORDER_DELIVERY_CLAIM,
                actionGroupSessionKey = SharedPreferenceActionGroupSessionStore.KEY_ACTION_GROUP_SESSION,
                pausedSupplementSessionKey =
                    SharedPreferenceActionGroupSessionStore.KEY_PAUSED_SUPPLEMENT_SESSION,
                prefetchSessionKey =
                    SharedPreferenceSupplementPrefetchSessionStore.KEY_SUPPLEMENT_PREFETCH_SESSION
            )
            if (!saved) return@withFaultHeatTransitionLock false
            actionGroupLocks.clear()
            serialSupplementFailureProof = null
            supplementResumeSingleFlight.exit()
            true
        }
        if (!committed) return false
        OrderStateManager.releaseAllProcessingForEmergencyReset()
        manualOutOrderRecoveryCoordinator.clearForEmergencyReset()
        SupplementPauseController.clearForEmergencyReset()
        lastActionGroupRecoveryFailureReason = null
        HomeUiRefreshBridge.requestRefresh("lower_board_reset:local_closed")
        logW(
            LOG_ACTION_GROUP,
            "下位机复位后已清理旧机械协调状态；烤盘、库存、搬盘和丢弃 journal 保持不变"
        )
        return true
    }

    private fun isEmergencyResetRecoveryPending(): Boolean =
        try {
            emergencyResetRecoveryFenceStore.load() != null
        } catch (_: Throwable) {
            true
        }
    suspend fun closeStaleOrderDeliveryClaimForMaintenance(
        claim: OrderDeliveryClaim,
        reason: String
    ): OrderClaimMaintenanceCloseoutResult = closeStaleOrderDeliveryClaim(
        claim = claim,
        reason = reason,
        enterMaintenance = true
    )

    private suspend fun tryAutoCloseStaleOrderDeliveryClaim(
        reason: String
    ): OrderClaimMaintenanceCloseoutResult? {
        val claim = try {
            withFaultHeatTransitionLock { orderDeliveryClaimStore.load() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return OrderClaimMaintenanceCloseoutResult(false, "订单 claim 读取失败或损坏：${error.message}")
        } ?: return null
        return closeStaleOrderDeliveryClaim(
            claim = claim,
            reason = reason,
            enterMaintenance = false
        )
    }

    private suspend fun closeMechanicalStateAfterEmergencyReset(
        administratorConfirmed: Boolean,
        reason: String
    ): OrderClaimMaintenanceCloseoutResult? {
        val initialFence = try {
            withFaultHeatTransitionLock { emergencyResetRecoveryFenceStore.load() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return OrderClaimMaintenanceCloseoutResult(false, "紧急复位恢复栅栏读取失败：${error.message}")
        } ?: return null
        if (initialFence.phase != EmergencyResetRecoveryPhase.RESET_WRITTEN) {
            return OrderClaimMaintenanceCloseoutResult(false, "紧急复位 999=1 尚未确认写入成功")
        }

        val callerContext = coroutineContext
        val snapshots = try {
            val firstEvidence = buildEmergencyResetIdleSnapshot("$reason:紧急复位后第一次空闲复核")
            delay(500L)
            val secondEvidence = buildEmergencyResetIdleSnapshot("$reason:紧急复位后第二次空闲复核")
            listOf(firstEvidence, secondEvidence)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return OrderClaimMaintenanceCloseoutResult(false, "紧急复位后读取下位机状态失败：${error.message}")
        }
        callerContext.ensureActive()

        var rejectionReason = "紧急复位恢复栅栏已变化，设备继续保持维护"
        val closed = try {
            withFaultHeatTransitionLock {
                callerContext.ensureActive()
                val config = AppConfig.getAppConfig()
                val currentFence = emergencyResetRecoveryFenceStore.load()
                val fenceMatches = currentFence?.matchesReset(initialFence.resetId) == true &&
                    currentFence.phase == EmergencyResetRecoveryPhase.RESET_WRITTEN
                val processBoundaryCrossed = initialFence.hasCrossedProcessBoundary(
                    AppProcessIdentity.currentId
                )
                val snapshotDecisions = snapshots.map { snapshot ->
                    ManualDeviceRecoveryCloseoutPolicy.evaluate(
                        evidence = snapshot.actionGroupEvidence.copy(moveStatus = 0, session = null),
                        hasProcessingOrder = false,
                        hasPanMoveJournal = false,
                        hasDiscardJournal = false,
                        durableEvidenceCorrupted = false
                    )
                }
                val decision = EmergencyResetForceCloseoutPolicy.evaluate(
                    administratorConfirmed = administratorConfirmed,
                    maintenanceLocked =
                        config.onlineStatus == ORDER_CLAIM_MAINTENANCE_STATUS && config.isEnable == 0,
                    resetFenceMatches = fenceMatches,
                    processBoundaryCrossed = fenceMatches && processBoundaryCrossed,
                    snapshotDecisions = snapshotDecisions,
                    panMoveResults203 = snapshots.map { it.panMoveResult203 }
                )
                if (!decision.allowed) {
                    rejectionReason = decision.reason
                    return@withFaultHeatTransitionLock false
                }

                val committed = AppConfig.saveEmergencyResetForceCloseoutDurable(
                    claimKey = OrderDeliveryClaimStore.KEY_ORDER_DELIVERY_CLAIM,
                    actionGroupSessionKey = SharedPreferenceActionGroupSessionStore.KEY_ACTION_GROUP_SESSION,
                    pausedSupplementSessionKey =
                        SharedPreferenceActionGroupSessionStore.KEY_PAUSED_SUPPLEMENT_SESSION,
                    prefetchSessionKey =
                        SharedPreferenceSupplementPrefetchSessionStore.KEY_SUPPLEMENT_PREFETCH_SESSION,
                    fenceKey = EmergencyResetRecoveryFenceStore.KEY_EMERGENCY_RESET_RECOVERY_FENCE
                )
                if (!committed) {
                    rejectionReason = "紧急复位旧机械协调状态原子清理失败"
                    return@withFaultHeatTransitionLock false
                }

                actionGroupLocks.clear()
                serialSupplementFailureProof = null
                supplementResumeSingleFlight.exit()
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return OrderClaimMaintenanceCloseoutResult(
                false,
                "紧急复位恢复本地状态清理失败：${error.message}"
            )
        }
        if (!closed) {
            return OrderClaimMaintenanceCloseoutResult(false, rejectionReason)
        }

        OrderStateManager.releaseAllProcessingForEmergencyReset()
        manualOutOrderRecoveryCoordinator.clearForEmergencyReset()
        SupplementPauseController.clearForEmergencyReset()
        VMModbusHelper.clearEmergencyResetMechanicalWriteBlock()
        lastActionGroupRecoveryFailureReason = null
        markActionGroupRecoveryVerified("人工紧急复位后强制收口：$reason")
        HomeUiRefreshBridge.requestRefresh("emergency_reset:force_closed")
        logE(
            LOG_ACTION_GROUP,
            "人工紧急复位后已强制清除复位前机械协调状态；烤盘、库存、搬盘和丢弃 journal 保持不变：" +
                "resetId=${initialFence.resetId}，originProcess=${initialFence.originProcessInstanceId}，" +
                "currentProcess=${AppProcessIdentity.currentId}，reason=$reason"
        )
        runCatching { publishRuntimeStateWithHttpFallback("紧急复位旧机械协调状态已收口") }
            .onFailure { error ->
                logW(LOG_ACTION_GROUP, "紧急复位收口完成，但状态上报失败：${error.message}")
            }
        return OrderClaimMaintenanceCloseoutResult(
            true,
            "紧急复位前的机械协调状态已清理；烤盘和库存未自动修改"
        )
    }

    private suspend fun closeStaleOrderDeliveryClaim(
        claim: OrderDeliveryClaim,
        reason: String,
        enterMaintenance: Boolean
    ): OrderClaimMaintenanceCloseoutResult {
        if (!OrderStateManager.canCloseStaleClaim(claim.orderNo)) {
            return OrderClaimMaintenanceCloseoutResult(
                false,
                "存在其他处理中订单，或当前订单尚无耐久终态"
            )
        }
        val callerContext = coroutineContext
        if (enterMaintenance) {
            val config = AppConfig.getAppConfig()
            val previousOnlineStatus = config.onlineStatus
            val previousIsEnable = config.isEnable
            config.onlineStatus = ORDER_CLAIM_MAINTENANCE_STATUS
            config.isEnable = 0
            try {
                AppConfig.saveAppConfigDurable(config)
            } catch (error: Throwable) {
                config.onlineStatus = previousOnlineStatus
                config.isEnable = previousIsEnable
                return OrderClaimMaintenanceCloseoutResult(false, "维护状态同步落盘失败：${error.message}")
            }
            HomeUiRefreshBridge.requestRefresh("order_claim:maintenance")
            publishRuntimeStateWithHttpFallback("订单 claim 人工收口进入维护")
        }

        val precheck = withFaultHeatTransitionLock {
            staleOrderClaimCloseoutRejectionSafely(claim, requireMaintenance = enterMaintenance)
        }
        if (precheck != null) return OrderClaimMaintenanceCloseoutResult(false, precheck)

        val snapshots = try {
            listOf(
                readOrphanDeliveryBusySnapshot(),
                run {
                    delay(500L)
                    readOrphanDeliveryBusySnapshot()
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logW(LOG_ORDER, "订单 claim 收口读取下位机状态失败：orderNo=${claim.orderNo}，${error.message}")
            return OrderClaimMaintenanceCloseoutResult(
                false,
                "订单 claim 收口读取下位机状态失败：${error.message}"
            )
        }
        callerContext.ensureActive()
        val idleDecision = OrphanDeliveryBusyRecoveryPolicy.evaluateIdleSnapshots(snapshots)
        if (!idleDecision.canRecover) {
            return OrderClaimMaintenanceCloseoutResult(false, idleDecision.reason)
        }

        var atomicCommitError: Exception? = null
        val closed = withFaultHeatTransitionLock {
            callerContext.ensureActive()
            if (staleOrderClaimCloseoutRejectionSafely(claim, requireMaintenance = enterMaintenance) != null) {
                return@withFaultHeatTransitionLock false
            }
            val terminalState = OrderStateManager.ensureTerminalForStaleCloseoutDurable(claim.orderNo)
                ?: return@withFaultHeatTransitionLock false
            closeOrderDeliveryMoveForMaintenance(
                claim = claim,
                transitionLock = faultHeatTransitionLock,
                claimStore = orderDeliveryClaimStore,
                moveStore = mechanicalMoveStatusStore,
                commitIdleAndClearClaim = {
                    try {
                        orderDeliveryClaimStore.commitIdleMoveStatusAndClearIfCurrent(
                            expected = claim,
                            orderFailed = terminalState == OrderStateManager.STATE_FAILED
                        )
                    } catch (error: Exception) {
                        atomicCommitError = error
                        throw error
                    }
                }
            ) {
                true
            }
        }
        if (!closed) {
            atomicCommitError?.let { error ->
                logW(LOG_ORDER, "订单 claim 原子收口提交异常：orderNo=${claim.orderNo}，${error.message}")
                return OrderClaimMaintenanceCloseoutResult(
                    false,
                    "订单 claim 原子收口提交异常：${error.message}"
                )
            }
            return OrderClaimMaintenanceCloseoutResult(false, "订单号或本地恢复证据已变化，claim 保持阻断")
        }

        OrderStateManager.releaseProcessing(claim.orderNo)
        manualOutOrderRecoveryCoordinator.release(claim.orderNo)
        runCatching { SupplementPauseController.clearOrderPause(claim.orderNo) }
            .onFailure { error ->
                logW(LOG_ORDER, "订单 claim 已释放，但清理订单暂停失败：orderNo=${claim.orderNo}，${error.message}")
            }
        HomeUiRefreshBridge.requestRefresh("order_claim:closed")
        val closeoutSource = if (enterMaintenance) "维护人员" else "自动恢复"
        logE(LOG_ORDER, "$closeoutSource 已精确收口残留订单 claim：orderNo=${claim.orderNo}，reason=$reason")
        runCatching { publishRuntimeStateWithHttpFallback("订单 claim ${closeoutSource}收口完成") }
            .onFailure { error ->
                logW(LOG_ORDER, "订单 claim 已释放，但状态上报失败：orderNo=${claim.orderNo}，${error.message}")
            }
        return OrderClaimMaintenanceCloseoutResult(true, "订单 ${claim.orderNo} 的残留占用已整体释放")
    }

    private fun staleOrderClaimCloseoutRejectionSafely(
        claim: OrderDeliveryClaim,
        requireMaintenance: Boolean
    ): String? {
        return try {
            staleOrderClaimCloseoutRejection(claim, requireMaintenance)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logW(LOG_ORDER, "订单 claim 收口读取本地证据失败：orderNo=${claim.orderNo}，${error.message}")
            "订单 claim 收口读取本地证据失败：${error.message}"
        }
    }

    private fun staleOrderClaimCloseoutRejection(
        claim: OrderDeliveryClaim,
        requireMaintenance: Boolean
    ): String? {
        val config = AppConfig.getAppConfig()
        if (requireMaintenance && (config.onlineStatus != 3 || config.isEnable != 0)) {
            return "设备尚未进入维护或算法未关闭"
        }
        if (config.moveStatus !in setOf(0, 2)) return "moveStatus=${config.moveStatus} 不是订单出餐状态"
        if (!OrderStateManager.canCloseStaleClaim(claim.orderNo)) {
            return "存在其他处理中订单，或当前订单尚无耐久终态"
        }
        val current = orderDeliveryClaimStore.load()
        if (current?.owner != claim.owner || current.revision != claim.revision) return "订单 claim 已变化"
        if (actionGroupSessionStore.readEvidence() !is DurableEvidenceReadResult.Absent) return "存在或损坏的动作组会话"
        if (pausedSupplementSessionStore.readEvidence() !is DurableEvidenceReadResult.Absent) return "存在或损坏的暂停补肠会话"
        if (readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent) return "存在或损坏的双 lane 预取证据"
        if (panMoveJournalStore.load() != null) return "存在搬盘 journal"
        if (discardJournalStore.load() != null) return "存在丢弃 journal"
        if (!actionGroupLocks.isIdle()) return "动作组资源仍被占用"
        val pauseOrderNo = SupplementPauseController.currentOrderNo()
        if (!pauseOrderNo.isNullOrBlank() && pauseOrderNo != claim.orderNo) return "另一个订单正在等待机械动作"
        return null
    }

    fun clearSettledOrderPause(orderNo: String) {
        SupplementPauseController.clearOrderPause(orderNo)
    }

    /**
     * 待丢弃烤盘不能一直被无 owner 的历史出餐忙态卡住。这里绝不收口订单 claim、会话、库存或下位机寄存器。
     */
    suspend fun recoverOrphanDeliveryBusyForPendingDiscard(): OrphanDeliveryBusyRecoveryResult {
        val callerContext = coroutineContext
        val initialConfig = AppConfig.getAppConfig()
        if (initialConfig.pendingDiscardPositions.isEmpty()) {
            return OrphanDeliveryBusyRecoveryResult(false, "没有待丢弃烤盘")
        }
        if (initialConfig.moveStatus != 2) {
            return OrphanDeliveryBusyRecoveryResult(false, "moveStatus=${initialConfig.moveStatus}，无需孤儿出餐忙态恢复")
        }
        val precheckRejection = withFaultHeatTransitionLock { orphanDeliveryBusyRecoveryLocalRejection() }
        if (precheckRejection != null) {
            return OrphanDeliveryBusyRecoveryResult(false, precheckRejection)
        }

        val firstSnapshot = readOrphanDeliveryBusySnapshot()
        delay(500L)
        val secondSnapshot = readOrphanDeliveryBusySnapshot()
        callerContext.ensureActive()
        val snapshotDecision = OrphanDeliveryBusyRecoveryPolicy.evaluate(
            moveStatus = initialConfig.moveStatus,
            snapshots = listOf(firstSnapshot, secondSnapshot)
        )
        if (!snapshotDecision.canRecover) {
            return OrphanDeliveryBusyRecoveryResult(false, snapshotDecision.reason)
        }

        return withFaultHeatTransitionLock {
            callerContext.ensureActive()
            val rejection = orphanDeliveryBusyRecoveryLocalRejection()
            if (rejection != null) {
                return@withFaultHeatTransitionLock OrphanDeliveryBusyRecoveryResult(false, rejection)
            }
            val lockedDecision = OrphanDeliveryBusyRecoveryPolicy.evaluate(
                moveStatus = mechanicalMoveStatusStore.load(),
                snapshots = listOf(firstSnapshot, secondSnapshot)
            )
            if (!lockedDecision.canRecover) {
                return@withFaultHeatTransitionLock OrphanDeliveryBusyRecoveryResult(false, lockedDecision.reason)
            }
            try {
                mechanicalMoveStatusStore.saveDurable(0)
                logW(
                    LOG_ORDER,
                    "待丢弃烤盘恢复无 owner 出餐忙态成功：moveStatus=2->0，" +
                        "pending=${AppConfig.getAppConfig().pendingDiscardPositions}"
                )
                OrphanDeliveryBusyRecoveryResult(true, "下位机连续空闲且本地无订单/动作证据")
            } catch (error: Throwable) {
                logW(LOG_ORDER, "待丢弃烤盘恢复无 owner 出餐忙态写入失败，保持阻断：${error.message}")
                OrphanDeliveryBusyRecoveryResult(false, "moveStatus 耐久写入失败")
            }
        }
    }

    private fun orphanDeliveryBusyRecoveryLocalRejection(): String? {
        return try {
            orphanDeliveryBusyRecoveryLocalRejectionSafely()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logW(LOG_ORDER, "孤儿出餐忙态本地证据读取异常，拒绝恢复：${error.message}")
            "本地恢复证据读取异常"
        }
    }

    private fun orphanDeliveryBusyRecoveryLocalRejectionSafely(): String? {
        val config = AppConfig.getAppConfig()
        if (config.pendingDiscardPositions.isEmpty()) return "待丢弃烤盘已变化"
        if (!actionGroupRecoveryVerified) return "动作组恢复尚未验证"
        if (config.moveStatus != 2) return "moveStatus 已变化为${config.moveStatus}"
        if (config.onlineStatus != 1 || config.isEnable != 1 ||
            config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
            config.inspectionMode != AppConfigBean.INSPECTION_MODE_NORMAL
        ) {
            return "设备不处于正常自动调度状态"
        }
        if (LowerPlatformEpisodeReducer.hasOpenGuardedIsolation(config)) return "存在下平台 guarded 隔离"
        if (OrderStateManager.hasProcessingOrder()) return "存在处理中订单"
        if (SupplementPauseController.shouldPauseForOrder()) return "订单正在等待机械出餐"
        val orderClaim = try {
            orderDeliveryClaimStore.load()
        } catch (error: Throwable) {
            logW(LOG_ORDER, "订单出餐 claim 损坏，拒绝恢复孤儿出餐忙态：${error.message}")
            return "订单出餐 claim 损坏"
        }
        if (orderClaim != null) return "存在订单出餐 claim"
        if (actionGroupSessionStore.readEvidence() !is DurableEvidenceReadResult.Absent) {
            return "存在或损坏的动作组会话"
        }
        if (pausedSupplementSessionStore.readEvidence() !is DurableEvidenceReadResult.Absent) {
            return "存在或损坏的暂停补肠会话"
        }
        if (readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent) return "存在双 lane 预取证据"
        if (panMoveJournalStore.load() != null) return "存在搬盘 journal"
        if (discardJournalStore.load() != null) return "存在丢弃 journal"
        if (!actionGroupLocks.isIdle()) return "动作组资源锁未释放"
        return null
    }

    private fun readOrphanDeliveryBusySnapshot(): OrphanDeliveryBusySnapshot {
        val modbusConnected = VMModbusHelper.connectStatus()
        if (!modbusConnected) {
            return OrphanDeliveryBusySnapshot(
                modbusConnected = false,
                legacyActionStatus201 = null,
                commandStates = emptyList(),
                resultStates = emptyList()
            )
        }
        val status201 = readHoldingRegisters(action_address_status, 1)?.singleOrNull()
        val commandStates = mutableListOf<OrphanDeliveryBusyRegisterState>()
        val resultStates = mutableListOf<OrphanDeliveryBusyRegisterState>()
        ActionGroupRecoveryBootstrap.allSpecs.forEach { spec ->
            val pair = readHoldingRegisters(spec.commandRegister, 2)
            if (pair?.size == 2) {
                commandStates += OrphanDeliveryBusyRegisterState(spec.name, spec.commandRegister, pair[0])
                resultStates += OrphanDeliveryBusyRegisterState(spec.name, spec.resultRegister, pair[1])
            } else {
                val error = "寄存器${spec.commandRegister}/${spec.resultRegister}读取失败"
                commandStates += OrphanDeliveryBusyRegisterState(spec.name, spec.commandRegister, null, error)
                resultStates += OrphanDeliveryBusyRegisterState(spec.name, spec.resultRegister, null, error)
            }
        }
        return OrphanDeliveryBusySnapshot(modbusConnected, status201, commandStates, resultStates)
    }

    fun abandonSupplementPrefetchForManualPanClear(
        statusReadings: List<Int>,
        reason: String
    ): Boolean {
        if (discardJournalStore.load() != null) {
            logW(LOG_ACTION_GROUP, "丢弃 journal 存在，拒绝人工清理补肠残留：reason=$reason")
            return false
        }
        val session = when (val read = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> return false
            is DurableEvidenceReadResult.Valid -> read.value
            is DurableEvidenceReadResult.Corrupted -> {
                logW(
                    LOG_ACTION_GROUP,
                    "人工清盘拒绝放弃损坏的双 lane 会话：reason=$reason，" +
                        "fingerprint=${read.fingerprint}，detail=${read.reason}"
                )
                return false
            }
        }
        val config = AppConfig.getAppConfig()
        val evidence = runCatching {
            ManualPanClearPolicy.Evidence(
                onlineStatus = config.onlineStatus,
                isEnable = config.isEnable,
                statusReadings = statusReadings,
                hasValidPrefetchSession = true,
                hasActionGroupSession = actionGroupSessionStore.load() != null,
                hasPausedSupplementSession = pausedSupplementSessionStore.load() != null,
                hasPanMoveJournal = panMoveJournalStore.load() != null,
                hasPendingOrder = !session.pendingOrderNo.isNullOrBlank()
            )
        }.getOrElse { error ->
            logW(LOG_ACTION_GROUP, "人工清盘读取其他恢复证据失败，拒绝放弃双 lane 会话：reason=$reason，error=${error.message}")
            return false
        }
        if (!ManualPanClearPolicy.canAbandonSupplementPrefetch(evidence)) {
            logW(
                LOG_ACTION_GROUP,
                "人工清盘不满足双 lane 放弃条件：reason=$reason，sessionId=${session.sessionId}，" +
                    "onlineStatus=${config.onlineStatus}，isEnable=${config.isEnable}，" +
                    "status201连续读数=$statusReadings，pendingOrderNo=${session.pendingOrderNo}"
            )
            return false
        }
        val oldMoveStatus = config.moveStatus
        val committed = ManualPanClearCommitter.commit(
            persistIdle = {
                config.moveStatus = 0
                runCatching {
                    AppConfig.saveAppConfigDurable(config)
                    true
                }.getOrElse { error ->
                    config.moveStatus = oldMoveStatus
                    logW(LOG_ACTION_GROUP, "人工清盘耐久归零 moveStatus 失败，保留双 lane 会话和锁：reason=$reason，error=${error.message}")
                    false
                }
            },
            clearSession = {
                runCatching {
                    supplementPrefetchSessionRepository.clearIfRevision(session.sessionId, session.revision)
                }.getOrElse { error ->
                    logW(LOG_ACTION_GROUP, "人工清盘清除双 lane 会话失败：reason=$reason，error=${error.message}")
                    false
                }
            },
            releaseOwner = { actionGroupLocks.release(session.sessionId) }
        )
        if (!committed) {
            logW(LOG_ACTION_GROUP, "人工清盘放弃双 lane 残留未完成，恢复证据或锁仍保留：reason=$reason，sessionId=${session.sessionId}")
            return false
        }
        lastActionGroupRecoveryFailureReason = null
        markActionGroupRecoveryVerified("人工清盘放弃无订单双 lane 残留：$reason")
        logW(
            LOG_ACTION_GROUP,
            "人工清盘已放弃无订单双 lane 残留：reason=$reason，sessionId=${session.sessionId}，" +
                "moveStatus=${moveStatusDesc(oldMoveStatus)}->${moveStatusDesc(config.moveStatus)}"
        )
        return true
    }

    fun isPanMoveRecoveryBlocked(): Boolean = panMoveJournalStore.load() != null

    fun isActionGroupRecoveryBlocked(): Boolean =
        !actionGroupRecoveryVerified ||
            actionGroupSessionStore.load() != null ||
            panMoveJournalStore.load() != null ||
            discardJournalStore.load() != null ||
            hasBlockingSupplementPrefetchEvidence()

    fun pendingDiscardJournalForRecovery(): DiscardJournal? = discardJournalStore.load()

    data class DiscardJournalRecoveryResult(
        val ok: Boolean,
        val reason: String,
        val positionSn: Int? = null,
        val panCleared: Boolean = false
    )

    suspend fun takeOverBusinessCloseoutDiscardForManualPanClear(
        reason: String
    ): DiscardJournalRecoveryResult {
        val journal = pendingDiscardJournalForRecovery()
            ?: return DiscardJournalRecoveryResult(true, "没有营业收口丢弃残留")
        if (!canManuallyTakeOverBusinessCloseoutDiscard(journal)) {
            return DiscardJournalRecoveryResult(
                false,
                "存在非营业收口丢弃残留，拒绝自动接管",
                journal.positionSn
            )
        }
        return confirmDiscardJournalRecovery(DiscardPhysicalResult.DISCARDED, reason)
    }

    suspend fun confirmDiscardJournalRecovery(
        physicalResult: DiscardPhysicalResult?,
        reason: String
    ): DiscardJournalRecoveryResult {
        val initialJournal = discardJournalStore.load()
            ?: return DiscardJournalRecoveryResult(false, "没有待收口的丢弃记录")
        if (!VMModbusHelper.connectStatus()) {
            return DiscardJournalRecoveryResult(false, "下位机串口未连接", initialJournal.positionSn)
        }
        val firstStatus = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
        delay(500L)
        val secondStatus = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
        val status201Readings = listOf(firstStatus, secondStatus)
        val reader = createAtomicActionClient() as? AtomicActionStateReader
            ?: return DiscardJournalRecoveryResult(false, "动作组结果读取器不可用", initialJournal.positionSn)
        val actionGroupResultStates = mutableListOf<Int>()
        for (spec in ActionGroupRecoveryBootstrap.allSpecs) {
            when (val result = reader.readResult(spec)) {
                is AtomicReadResult.Success -> actionGroupResultStates += result.value
                is AtomicReadResult.Failure -> return DiscardJournalRecoveryResult(
                    false,
                    "读取 ${spec.name} 结果寄存器 ${spec.resultRegister} 失败：${result.message}",
                    initialJournal.positionSn
                )
            }
        }
        val confirmedResult = initialJournal.confirmedPhysicalResult ?: physicalResult
        if (
            initialJournal.confirmedPhysicalResult != null &&
            physicalResult != null &&
            initialJournal.confirmedPhysicalResult != physicalResult
        ) {
            return DiscardJournalRecoveryResult(false, "现场确认结果与已保存结果不一致", initialJournal.positionSn)
        }
        val initialConfig = AppConfig.getAppConfig()
        val resolvedSource = DiscardJournalRecoveryPolicy.resolveSource(
            initialJournal,
            initialConfig.pendingDiscardPositions.toSet()
        )
        val decision = DiscardJournalRecoveryPolicy.evaluate(
            journal = initialJournal,
            onlineStatus = initialConfig.onlineStatus,
            isEnable = initialConfig.isEnable,
            moveStatus = initialConfig.moveStatus,
            status201Readings = status201Readings,
            actionGroupResultStates = actionGroupResultStates,
            physicalResult = confirmedResult
        )
        if (!decision.canClose) {
            return DiscardJournalRecoveryResult(false, decision.reason, initialJournal.positionSn)
        }
        val wasteConsumableEvent = if (decision.clearPan) {
            initialJournal.wasteConsumableEvent ?: ConsumableEventPayloadBuilder.nextEvent(
                consumableType = "WASTE",
                eventType = "WASTE_DISCARD_EXPIRED",
                deltaValue = 1L,
                source = "discard_journal_manual_closeout",
                detail = "pan=${initialJournal.positionSn}, source=${initialJournal.source ?: "unknown"}"
            )
        } else {
            initialJournal.wasteConsumableEvent
        }
        val committedJournal = initialJournal.copy(
            confirmedPhysicalResult = confirmedResult,
            source = resolvedSource,
            wasteConsumableEvent = wasteConsumableEvent
        )
        if (committedJournal != initialJournal) {
            val latest = discardJournalStore.load()
            if (latest != initialJournal) {
                return DiscardJournalRecoveryResult(false, "丢弃记录已变化，请重新核对", initialJournal.positionSn)
            }
            if (!discardJournalStore.saveDurable(committedJournal)) {
                return DiscardJournalRecoveryResult(false, "丢弃恢复记录落盘失败", initialJournal.positionSn)
            }
        }
        if (decision.clearPan) {
            val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == initialJournal.positionSn }
                ?: return DiscardJournalRecoveryResult(false, "找不到目标烤盘位", initialJournal.positionSn)
            val previousCmdStatusTake = pan.cmdStatusTake
            val previousHasSausage = pan.isHasSausage
            val previousHasGrilling = pan.isHasGrilling
            val previousHoldingTime = pan.holdingTime
            val previousStartTime = pan.startTime
            val previousStatus = pan.status
            val previousCmdStatusDiscard = pan.cmdStatusDiscard
            applySuccessfulTakePanState(pan)
            pan.cmdStatusDiscard = 0
            try {
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            } catch (error: Throwable) {
                pan.cmdStatusTake = previousCmdStatusTake
                pan.isHasSausage = previousHasSausage
                pan.isHasGrilling = previousHasGrilling
                pan.holdingTime = previousHoldingTime
                pan.startTime = previousStartTime
                pan.status = previousStatus
                pan.cmdStatusDiscard = previousCmdStatusDiscard
                return DiscardJournalRecoveryResult(
                    false,
                    "目标烤盘状态耐久收口失败：${error.message ?: error.javaClass.simpleName}",
                    initialJournal.positionSn
                )
            }
            try {
                clearReturnBindingForSourceIfAny(initialJournal.positionSn)
            } catch (error: Throwable) {
                return DiscardJournalRecoveryResult(
                    false,
                    "目标烤盘回搬绑定收口失败：${error.message ?: error.javaClass.simpleName}",
                    initialJournal.positionSn
                )
            }
            try {
                handleSuccessfulDiscardDemandGate(
                    initialJournal.positionSn,
                    resolvedSource.orEmpty()
                )
                FaultHeatPauseManager.clearDiscardPositionStateAndAlertIfFinished(initialJournal.positionSn)
                SendServerHelper.publishConsumableEvent(requireNotNull(wasteConsumableEvent))
            } catch (error: Throwable) {
                return DiscardJournalRecoveryResult(
                    false,
                    "丢弃业务状态耐久收口失败：${error.message ?: error.javaClass.simpleName}",
                    initialJournal.positionSn
                )
            }
        }
        if (
            initialJournal.checkpoint in setOf(
                DiscardCheckpoint.COMMAND_INTENT,
                DiscardCheckpoint.COMMAND_ISSUED
            ) && confirmedResult == DiscardPhysicalResult.NOT_DISCARDED
        ) {
            val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == initialJournal.positionSn }
                ?: return DiscardJournalRecoveryResult(false, "找不到目标烤盘位", initialJournal.positionSn)
            val previousCmdStatusDiscard = pan.cmdStatusDiscard
            pan.cmdStatusDiscard = 0
            try {
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            } catch (error: Throwable) {
                pan.cmdStatusDiscard = previousCmdStatusDiscard
                return DiscardJournalRecoveryResult(
                    false,
                    "确认未丢弃后的烤盘状态耐久收口失败：${error.message ?: error.javaClass.simpleName}",
                    initialJournal.positionSn
                )
            }
        }
        if (!releaseMechanicalMove(
                "人工确认丢弃残留",
                initialJournal.owner,
                discardClaimOwnerStore(),
                expectedMoveStatus = 4,
                idleMoveStatus = 0
            )
        ) {
            return DiscardJournalRecoveryResult(false, "丢弃 owner/moveStatus 条件释放失败", initialJournal.positionSn)
        }
        if (decision.clearPan) {
            FaultHeatPauseManager.publishDiscardPositionCleared(initialJournal.positionSn)
        }
        PanPhysicalStateDeltaReporter.reportPansChanged(
            changedPans = KaoPanHelper.getKaoPanList(),
            reason = "discard_journal_manual_closeout:pan=${initialJournal.positionSn}",
            source = "discard_journal_manual_closeout"
        )
        lastActionGroupRecoveryFailureReason = null
        actionGroupRecoveryVerified = false
        logW(
            LOG_ACTION_GROUP,
            "人工确认丢弃残留已安全收口：reason=$reason，pan=${initialJournal.positionSn}，" +
                "checkpoint=${initialJournal.checkpoint}，physicalResult=${confirmedResult ?: "命令未下发"}，" +
                "status201=$status201Readings，actionGroupResults=$actionGroupResultStates"
        )
        return DiscardJournalRecoveryResult(true, "丢弃残留已收口", initialJournal.positionSn, decision.clearPan)
    }

    private fun readSupplementPrefetchEvidence(): DurableEvidenceReadResult<SupplementPrefetchPipelineSession> =
        runCatching { supplementPrefetchSessionStore.read() }
            .getOrElse { error ->
                DurableEvidenceReadResult.Corrupted(
                    fingerprint = error::class.java.simpleName,
                    reason = "双 lane 会话读取异常：${error.message}",
                    rawLength = 0
                )
            }

    private fun hasBlockingSupplementPrefetchEvidence(): Boolean =
        readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent

    private fun activeSupplementPrefetchSessionOrNull(context: String): SupplementPrefetchPipelineSession? {
        return when (val read = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> null
            is DurableEvidenceReadResult.Valid -> read.value
            is DurableEvidenceReadResult.Corrupted -> {
                enterActionGroupMaintenance(
                    "双 lane 会话读取失败，已禁止相关动作：context=$context，" +
                        "fingerprint=${read.fingerprint}，reason=${read.reason}"
                )
                null
            }
        }
    }

    fun hasActiveSupplementReturnBinding(): Boolean = supplementBatchIntentRepository
        .loadActive()
        ?.retainedCompletedHeatReturnTargets()
        ?.isNotEmpty() == true

    fun hasPausedSupplementContinuation(): Boolean {
        val parallelPaused = activeSafePausedSupplementSession() != null
        return (parallelPaused || hasPausedSupplementSession()) && actionGroupLocks.isIdle()
    }

    private fun activeSafePausedSupplementSession(): SupplementPrefetchPipelineSession? {
        return when (val read = readSupplementPrefetchEvidence()) {
            is DurableEvidenceReadResult.Valid -> read.value.takeIf(SupplementPrefetchOrderPauseSafety::isSafe)
            else -> null
        }
    }

    fun isParallelSupplementPausedForRuntimeRecovery(): Boolean {
        return when (val read = readSupplementPrefetchEvidence()) {
            is DurableEvidenceReadResult.Valid -> read.value.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME &&
                read.value.barrierState == SupplementBarrierState.OPEN &&
                read.value.pendingOrderNo == null &&
                read.value.mechanicalClaimState == null &&
                actionGroupRecoveryVerified &&
                actionGroupSessionStore.load() == null &&
                panMoveJournalStore.load() == null &&
                actionGroupLocks.isIdle()
            else -> false
        }
    }

    private fun parallelSupplementRuntimeAvailable(): Boolean {
        val config = AppConfig.getAppConfig()
        return config.onlineStatus == 1 &&
            config.errorStatus == 0 &&
            config.inspectionMode == 0 &&
            config.supplyStatus == 0 &&
            config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED &&
            config.supplementEnabled == AppConfigBean.SUPPLEMENT_ENABLED_YES &&
            config.isEnable == 1 &&
            VMModbusHelper.connectStatus()
    }

    private fun deferParallelSupplementForRuntimeRecovery(
        plan: SupplementPrefetchResumePlan,
        reason: String,
        lockOwner: String? = null,
        singleFlightAlreadyOwned: Boolean = false
    ): Boolean {
        var deferredSessionId: String? = null
        val deferred = withFaultHeatTransitionLock {
            if (supplementResumeSingleFlight.isRunning() && !singleFlightAlreadyOwned) {
                logW(
                    LOG_SUPPLEMENT,
                    "双 lane 延迟续作遇到正在执行的运行态恢复，保留当前会话和资源锁：" +
                        "sessionId=${plan.sessionId}，reason=$reason"
                )
                return@withFaultHeatTransitionLock false
            }
            val ownerToRelease = lockOwner ?: plan.sessionId
            val session = when (val read = readSupplementPrefetchEvidence()) {
                DurableEvidenceReadResult.Absent -> {
                    actionGroupLocks.release(ownerToRelease)
                    return@withFaultHeatTransitionLock false
                }
                is DurableEvidenceReadResult.Valid -> read.value
                is DurableEvidenceReadResult.Corrupted -> {
                    enterActionGroupMaintenance(
                        "双 lane 会话读取失败，已禁止相关动作：context=双 lane 延迟续作，" +
                            "fingerprint=${read.fingerprint}，reason=${read.reason}"
                    )
                    return@withFaultHeatTransitionLock false
                }
            }
            val lane = session.deliveryLane
            if (session.sessionId != plan.sessionId ||
                lane?.sourceBoxNo != plan.sourceBoxNo ||
                lane.targetPanNo != plan.targetPanNo ||
                lane.lastConfirmedStage != plan.resumeAfterAction
            ) {
                enterActionGroupMaintenance("双 lane 延迟续作计划与会话不一致：plan=$plan")
                return@withFaultHeatTransitionLock false
            }
            if (session.mechanicalClaimState == null && session.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME) {
                actionGroupLocks.release(ownerToRelease)
                markActionGroupRecoveryVerified("双 lane 已验证但等待运行态续作：" + reason)
                deferredSessionId = session.sessionId
                return@withFaultHeatTransitionLock true
            }
            val currentMoveStatus = AppConfig.getAppConfig().moveStatus
            if (currentMoveStatus !in setOf(0, 1, 2)) {
                enterActionGroupMaintenance(
                    "双 lane 延迟续作发现未知 moveStatus，保留会话：moveStatus=" + currentMoveStatus +
                        "，reason=" + reason,
                    preserveMoveStatus = true
                )
                return@withFaultHeatTransitionLock false
            }
            var moveStatusCleared = false
            runCatching {
                if (currentMoveStatus != 0) {
                    mechanicalMoveStatusStore.saveDurable(0)
                    moveStatusCleared = true
                }
                supplementPrefetchSessionRepository.pauseForRuntimeRecovery(session.sessionId)
                actionGroupLocks.release(ownerToRelease)
                markActionGroupRecoveryVerified("双 lane 已验证但等待运行态续作：" + reason)
                deferredSessionId = session.sessionId
            }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    if (moveStatusCleared) {
                        runCatching { mechanicalMoveStatusStore.saveDurable(currentMoveStatus) }
                    }
                    enterActionGroupMaintenance(
                        "双 lane 转运行态延迟续作失败，保留会话：reason=" + reason +
                            "，error=" + error.message,
                        preserveMoveStatus = true
                    )
                    false
                }
            )
        }
        if (deferred) {
            publishRuntimeStateWithHttpFallback("双 lane 等待运行态续作")
            logI(
                LOG_SUPPLEMENT,
                "双 lane 已转为运行态延迟续作：session=" + deferredSessionId + "，reason=" + reason
            )
        }
        return deferred
    }

    suspend fun resumeParallelSupplementForRuntimeRecovery(reason: String): Boolean {
        if (!isParallelSupplementPausedForRuntimeRecovery()) return true
        if (!supplementResumeSingleFlight.tryEnter()) {
            logW(LOG_SUPPLEMENT, "双 lane 运行态恢复已有执行中的尝试，跳过重复恢复：reason=$reason")
            return false
        }
        return try {
            val preparation = runCatching {
                SupplementPrefetchResumePlanner.prepareRuntimeRecovery(
                    supplementPrefetchSessionRepository
                )
            }.getOrElse { error ->
                enterActionGroupMaintenance(
                    "双 lane 运行态自动续作准备失败：reason=$reason，error=${error.message}",
                    preserveMoveStatus = true
                )
                return false
            }
            if (preparation !is SupplementPrefetchResumePreparation.Ready) {
                return handleParallelSupplementResumePreparation(
                    preparation = preparation,
                    reason = "双 lane 运行态自动续作：$reason",
                    lockAlreadyOwned = false,
                    allowRuntimeDefer = false,
                    singleFlightAlreadyOwned = true
                )
            }
            val reader = createAtomicActionClient() as? AtomicActionStateReader
            if (reader == null) {
                enterActionGroupMaintenance(
                    "双 lane 运行态自动续作缺少动作结果寄存器读取能力：reason=$reason",
                    preserveMoveStatus = true
                )
                return false
            }
            val safety = SupplementRuntimeResumeSafetyGate(
                reader = reader,
                betweenSnapshots = { delay(500L) }
            ).verify(preparation.plan.resumeAfterAction)
            if (safety is SupplementRuntimeResumeSafetyResult.Unsafe) {
                enterActionGroupMaintenance(
                    "双 lane 运行态自动续作安全复核失败：reason=$reason，detail=${safety.reason}",
                    preserveMoveStatus = true
                )
                return false
            }
            handleParallelSupplementResumePreparation(
                preparation = preparation,
                reason = "双 lane 运行态自动续作：$reason",
                lockAlreadyOwned = false,
                allowRuntimeDefer = false,
                singleFlightAlreadyOwned = true
            )
        } finally {
            supplementResumeSingleFlight.exit()
        }
    }

    private fun supplementPipelineOutcome(
        result: InActionGroupPipelineResult?
    ): SupplementPipelineOutcome = when (result) {
        is InActionGroupPipelineResult.Success -> SupplementPipelineOutcome.SUCCESS
        is InActionGroupPipelineResult.Paused -> SupplementPipelineOutcome.PAUSED_FOR_ORDER
        is InActionGroupPipelineResult.ReplanRequired -> SupplementPipelineOutcome.REPLAN_REQUIRED
        is InActionGroupPipelineResult.Failed -> SupplementPipelineOutcome.FAILED
        null -> SupplementPipelineOutcome.CANCELLED
    }

    enum class IncomingOrderMechanicalAdmission {
        ACCEPTED,
        DUPLICATE,
        BECAME_BUSY
    }

    private fun hasGuardedSupplementCloseoutResidue(config: AppConfigBean): Boolean {
        val session = actionGroupSessionStore.load()
        return hasGuardedSupplementCloseoutResidue(
            config = config,
            session = session,
            sessionOwnedMask = session?.let { actionGroupLocks.ownedMask(it.sessionId) }
        )
    }

    private fun hasGuardedSupplementCloseoutResidue(
        config: AppConfigBean,
        session: ActionGroupSession?,
        sessionOwnedMask: Int?
    ): Boolean {
        val guardedEpisode = config.lowerPlatformEpisodes.any {
            it.lifecycle == "OPEN" &&
                it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED &&
                it.closeoutGuarded
        }
        if (guardedEpisode || serialSupplementFailureProof != null) return true

        session ?: return false
        if (session.type != ActionGroupSessionType.IN_SUPPLEMENT || session.prefetch) return false
        return config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED &&
            session.currentAction in setOf("in1", "in2", "in3") &&
            sessionOwnedMask != session.resourceMask
    }

    fun acceptIncomingOrderForMechanicalDispatch(orderNo: String): IncomingOrderMechanicalAdmission {
        return withFaultHeatTransitionLock {
            if (hasPendingEmergencyResetRecovery()) {
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.BECAME_BUSY
            }
            val config = AppConfig.getAppConfig()
            if (config.orderBlockReason == AppConfigBean.ORDER_BLOCK_REASON_SYSTEM_RESET_IN_PROGRESS) {
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.BECAME_BUSY
            }
            if (hasGuardedSupplementCloseoutResidue(config)) {
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.BECAME_BUSY
            }
            val manualOutRecoveryPending = shouldRecoverManualOutBeforeIncomingOrder(orderNo)
            val recoveryAlreadyReserved = manualOutOrderRecoveryCoordinator.isReservedFor(orderNo)
            val recoveryReserved = if (manualOutRecoveryPending) {
                manualOutOrderRecoveryCoordinator.tryReserve(orderNo)
            } else {
                false
            }
            val ownsRecoveryReservation = recoveryReserved && !recoveryAlreadyReserved
            if (manualOutRecoveryPending && !recoveryReserved) {
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.BECAME_BUSY
            }
            if (!OrderMoveStatusPolicy.canAcceptIncomingOrder(
                    moveStatus = config.moveStatus,
                    onlineStatus = config.onlineStatus,
                    actionGroupBusy = isActionGroupBusyForOrderGate(),
                    errorStatus = config.errorStatus,
                    inspectionMode = config.inspectionMode
                ) && !manualOutRecoveryPending
            ) {
                if (ownsRecoveryReservation) {
                    manualOutOrderRecoveryCoordinator.release(orderNo)
                }
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.BECAME_BUSY
            }
            if (!OrderStateManager.checkAndAcceptOrder(orderNo)) {
                if (ownsRecoveryReservation) {
                    manualOutOrderRecoveryCoordinator.release(orderNo)
                }
                return@withFaultHeatTransitionLock IncomingOrderMechanicalAdmission.DUPLICATE
            }
            requestSupplementPauseForOrder(orderNo)
            IncomingOrderMechanicalAdmission.ACCEPTED
        }
    }

    fun requestSupplementPauseForOrder(orderNo: String) {
        if (orderNo.isBlank()) return
        var parallelSession: SupplementPrefetchPipelineSession? = null
        var corruptedEvidence: DurableEvidenceReadResult.Corrupted? = null
        var barrierWriteError: Throwable? = null
        val pauseRecorded = withFaultHeatTransitionLock {
            val recorded = SupplementPauseController.requestPauseForOrder(orderNo)
            when (val read = readSupplementPrefetchEvidence()) {
                DurableEvidenceReadResult.Absent -> Unit
                is DurableEvidenceReadResult.Valid -> {
                    parallelSession = read.value
                    barrierWriteError = runCatching {
                        supplementPrefetchSessionRepository.requestOrderBarrier(orderNo)
                    }.exceptionOrNull()
                }
                is DurableEvidenceReadResult.Corrupted -> corruptedEvidence = read
            }
            recorded
        }
        if (pauseRecorded) {
            LogUtils.i("【订单优先】收到顾客订单，自动补肠将在当前单根安全完成后暂停：orderNo=$orderNo")
        }
        corruptedEvidence?.let { evidence ->
            enterActionGroupMaintenance(
                "订单暂停读取双 lane 会话失败，已保留订单暂停：orderNo=$orderNo，" +
                    "fingerprint=${evidence.fingerprint}，reason=${evidence.reason}",
                preserveMoveStatus = true
            )
            return
        }
        val session = parallelSession ?: return
        barrierWriteError?.let { error ->
            val lockError = runCatching {
                actionGroupLocks.forceAcquire(session.resourceMask, session.sessionId)
            }.exceptionOrNull()
            enterActionGroupMaintenance(
                "订单暂停补肠 barrier 落盘失败，保留双 lane 会话：orderNo=$orderNo，" +
                    "error=${error.message}，lockError=${lockError?.message}",
                preserveMoveStatus = true
            )
        }
    }

    suspend fun clearSupplementPauseAndResume(orderNo: String, reason: String): Boolean {
        if (orderNo.isBlank()) return true
        if (SupplementPauseController.isPausedForDifferentOrder(orderNo)) {
            logI(
                LOG_SUPPLEMENT,
                "订单结束时已有后续订单等待，保留补肠暂停并把续作交给后续订单：" +
                    "completedOrder=$orderNo，pendingOrder=${SupplementPauseController.currentOrderNo()}"
            )
            return true
        }
        val parallelSession = when (val read = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> null
            is DurableEvidenceReadResult.Valid -> read.value
            is DurableEvidenceReadResult.Corrupted -> {
                enterActionGroupMaintenance(
                    "订单结束读取双 lane 会话失败，已保留订单暂停：" +
                        "fingerprint=${read.fingerprint}，reason=${read.reason}"
                )
                return false
            }
        }
        if (parallelSession?.pendingOrderNo == orderNo) {
            val completedDeliveryNeedsOnlyCloseout =
                hasCompletedParallelDeliveryCloseoutEvidence(parallelSession)
            if (!completedDeliveryNeedsOnlyCloseout &&
                !actionGroupLocks.tryAcquire(parallelSession.resourceMask, parallelSession.sessionId)
            ) {
                logW(LOG_SUPPLEMENT, "双 lane 订单结束续作资源忙，保留 barrier：session=${parallelSession.sessionId}")
                return false
            }
            val preparation = runCatching {
                SupplementPrefetchResumePlanner.prepareAfterOrder(supplementPrefetchSessionRepository, orderNo)
            }.getOrElse { error ->
                enterActionGroupMaintenance("双 lane 订单结束续作准备失败：reason=$reason，error=${error.message}")
                return false
            }
            if (preparation !is SupplementPrefetchResumePreparation.Unsafe) {
                SupplementPauseController.clearOrderPause(orderNo)
            }
            val resumed = handleParallelSupplementResumePreparation(
                preparation,
                reason,
                lockAlreadyOwned = !completedDeliveryNeedsOnlyCloseout,
                lockOwner = parallelSession.sessionId,
                allowRuntimeDefer = true
            )
            return resumed || SupplementPauseController.isPausedForDifferentOrder(orderNo)
        }
        SupplementPauseController.clearOrderPause(orderNo)
        val resumed = resumePausedSupplementAfterOrder(reason)
        return resumed || SupplementPauseController.isPausedForDifferentOrder(orderNo)
    }

    fun releaseOrderDeliveryMoveStatus(claim: OrderDeliveryClaim, reason: String): Boolean {
        val released = releaseOrderDeliveryMove(
            claim = claim,
            transitionLock = faultHeatTransitionLock,
            claimStore = orderDeliveryClaimStore,
            moveStore = mechanicalMoveStatusStore
        ) {
            actionGroupSessionStore.load() == null &&
                panMoveJournalStore.load() == null &&
                discardJournalStore.load() == null &&
                actionGroupLocks.isIdle()
        }
        if (!released) {
            logW(
                LOG_ORDER,
                "订单 owner/revision 条件释放失败，保留 moveStatus=2：" +
                    "reason=$reason，owner=${claim.owner}，revision=${claim.revision}"
            )
        }
        return released
    }

    private fun restoreParallelSupplementBusyMoveStatus() {
        val config = AppConfig.getAppConfig()
        config.moveStatus = 1
        AppConfig.saveAppConfigDurable(config)
    }

    private suspend fun handleParallelSupplementResumePreparation(
        preparation: SupplementPrefetchResumePreparation,
        reason: String,
        lockAlreadyOwned: Boolean,
        lockOwner: String? = null,
        allowRuntimeDefer: Boolean = false,
        singleFlightAlreadyOwned: Boolean = false
    ): Boolean = when (preparation) {
        SupplementPrefetchResumePreparation.NoSession -> {
            if (lockAlreadyOwned && lockOwner != null) {
                actionGroupLocks.release(lockOwner)
            }
            true
        }
        is SupplementPrefetchResumePreparation.Unsafe -> {
            enterActionGroupMaintenance("双 lane 续作检查失败：reason=$reason，${preparation.reason}")
            false
        }
        is SupplementPrefetchResumePreparation.ReplanRemaining -> {
            runCatching {
                val closeout = withFaultHeatTransitionLock {
                    val session = requireNotNull(supplementPrefetchSessionRepository.load()) {
                        "双 lane 已完成会话不存在"
                    }
                    check(session.sessionId == preparation.sessionId) {
                        "双 lane 已完成会话 owner 已变化"
                    }
                    check(session.revision == preparation.revision) {
                        "双 lane 已完成会话 revision 已变化"
                    }
                    check(session.batchId == preparation.batchId) {
                        "双 lane 已完成会话 batch 已变化"
                    }
                    check(session.deliveryLane?.targetPanNo == preparation.targetPanNo) {
                        "双 lane 已完成会话目标盘已变化"
                    }
                    check(hasCompletedParallelDeliveryCloseoutEvidence(session)) {
                        "双 lane 已完成会话缺少完整物理落盘证据"
                    }
                    supplementBatchIntentRepository.markCompleted(
                        preparation.batchId,
                        preparation.targetPanNo
                    )
                    val result = SupplementMechanicalCloseoutCoordinator(
                        transitionLock = faultHeatTransitionLock,
                        supplementOwnerStore = supplementPipelineClaimOwnerStore(),
                        orderOwnerStore = orderDeliveryClaimStore,
                        moveStore = mechanicalMoveStatusStore,
                        resourceLocks = actionGroupLocks
                    ).closeOrConfirmAlreadyClosed(
                        SupplementMechanicalCloseoutRequest(
                            owner = preparation.sessionId,
                            revision = session.mechanicalClaimRevision,
                            resourceMask = session.resourceMask
                        )
                    )
                    if (result == SupplementMechanicalCloseoutResult.ClosedIdle ||
                        result == SupplementMechanicalCloseoutResult.ClosedKeepingOrder
                    ) {
                        supplementPrefetchSessionRepository.load()?.let { current ->
                            check(current.sessionId == session.sessionId && current.revision == session.revision) {
                                "双 lane 已完成会话在收口后发生变化"
                            }
                            check(
                                supplementPrefetchSessionRepository.clearIfRevision(
                                    current.sessionId,
                                    current.revision
                                )
                            ) { "双 lane 已完成会话清理失败" }
                        }
                    }
                    result
                }
                check(
                    closeout == SupplementMechanicalCloseoutResult.ClosedIdle ||
                        closeout == SupplementMechanicalCloseoutResult.ClosedKeepingOrder
                ) { "双 lane 当前根精确收口失败：$closeout" }
                publishRuntimeStateWithHttpFallback("双 lane 当前根已完成，等待重新规划")
            }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    enterActionGroupMaintenance(
                        "双 lane 已完成会话收口失败：reason=$reason，error=${error.message}",
                        preserveMoveStatus = true
                    )
                    false
                }
            )
        }
        is SupplementPrefetchResumePreparation.Ready -> {
            if (!parallelSupplementRuntimeAvailable() && allowRuntimeDefer) {
                deferParallelSupplementForRuntimeRecovery(
                    preparation.plan,
                    reason,
                    lockOwner = lockOwner,
                    singleFlightAlreadyOwned = singleFlightAlreadyOwned
                )
            } else {
                resumePreparedParallelSupplement(
                    preparation.plan,
                    reason,
                    lockAlreadyOwned,
                    allowRuntimeDefer,
                    singleFlightAlreadyOwned
                )
            }
        }
    }

    private suspend fun resumePreparedParallelSupplement(
        plan: SupplementPrefetchResumePlan,
        reason: String,
        lockAlreadyOwned: Boolean,
        allowRuntimeDefer: Boolean,
        singleFlightAlreadyOwned: Boolean = false
    ): Boolean {
        if (singleFlightAlreadyOwned) {
            return resumePreparedParallelSupplementOnce(plan, reason, lockAlreadyOwned, allowRuntimeDefer)
        }
        if (!supplementResumeSingleFlight.tryEnter()) {
            logW(LOG_SUPPLEMENT, "双 lane 续作已有执行中的尝试，拒绝重复下发：sessionId=${plan.sessionId}，reason=$reason")
            return false
        }
        return try {
            resumePreparedParallelSupplementOnce(plan, reason, lockAlreadyOwned, allowRuntimeDefer)
        } finally {
            supplementResumeSingleFlight.exit()
        }
    }

    private suspend fun resumePreparedParallelSupplementOnce(
        plan: SupplementPrefetchResumePlan,
        reason: String,
        lockAlreadyOwned: Boolean,
        allowRuntimeDefer: Boolean
    ): Boolean {
        var lockOwnedByResume = lockAlreadyOwned
        fun releaseResumeLock() {
            if (lockOwnedByResume) {
                actionGroupLocks.release(plan.sessionId)
                lockOwnedByResume = false
            }
        }

        val session = when (val read = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> {
                releaseResumeLock()
                return false
            }
            is DurableEvidenceReadResult.Valid -> read.value
            is DurableEvidenceReadResult.Corrupted -> {
                enterActionGroupMaintenance(
                    "双 lane 续作执行前读取会话失败，保留会话和资源锁：" +
                        "fingerprint=${read.fingerprint}，reason=${read.reason}",
                    preserveMoveStatus = true
                )
                return false
            }
        }
        val lane = session.deliveryLane
        if (session.sessionId != plan.sessionId || lane?.sourceBoxNo != plan.sourceBoxNo ||
            lane.targetPanNo != plan.targetPanNo || lane.lastConfirmedStage != plan.resumeAfterAction
        ) {
            enterActionGroupMaintenance(
                "双 lane 续作计划与持久化会话不一致，保留会话和资源锁：plan=$plan",
                preserveMoveStatus = true
            )
            return false
        }
        val runtime = AppConfig.getAppConfig()
        if (runtime.onlineStatus != 1 || runtime.errorStatus != 0 || runtime.inspectionMode != 0 ||
            runtime.supplyStatus != 0 ||
            runtime.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED ||
            runtime.supplementEnabled != AppConfigBean.SUPPLEMENT_ENABLED_YES ||
            runtime.isEnable != 1 || !VMModbusHelper.connectStatus()
        ) {
            logW(LOG_SUPPLEMENT, "双 lane 续作运行态门禁拒绝下发，保留会话并释放本次资源锁：reason=$reason")
            if (allowRuntimeDefer) {
                return deferParallelSupplementForRuntimeRecovery(
                    plan,
                    reason,
                    lockOwner = plan.sessionId,
                    singleFlightAlreadyOwned = true
                )
            }
            releaseResumeLock()
            return false
        }
        if (!lockOwnedByResume) {
            if (!actionGroupLocks.tryAcquire(plan.resourceMask, plan.sessionId)) {
                return false
            }
            lockOwnedByResume = true
        }
        val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == plan.targetPanNo }
        val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == plan.sourceBoxNo }
        if (pan == null || box == null) {
            enterActionGroupMaintenance(
                "双 lane 续作找不到目标盘或来源箱，保留会话和资源锁：plan=$plan",
                preserveMoveStatus = true
            )
            return false
        }
        val runtimePaused = session.phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME
        var resumedFromRuntimePause = false
        val claimed = if (!runtimePaused) {
            claimMechanicalMove(
                stage = "双 lane 续作下发前",
                owner = plan.sessionId,
                ownerStore = supplementPipelineClaimOwnerStore(),
                // 2 仅用于已完成订单向匹配双 lane 会话交还机械控制权。
                allowedMoveStatuses = setOf(0, 1, 2),
                claimedMoveStatus = 1,
                requireSupplementGate = true
            )
        } else {
            withFaultHeatTransitionLock {
                if (!parallelSupplementRuntimeAvailable()) {
                    false
                } else {
                    runCatching {
                        supplementPrefetchSessionRepository.resumeFromRuntimeRecovery(plan.sessionId)
                        resumedFromRuntimePause = true
                        val result = claimMechanicalMove(
                            stage = "双 lane 运行态恢复续作下发前",
                            owner = plan.sessionId,
                            ownerStore = supplementPipelineClaimOwnerStore(),
                            allowedMoveStatuses = setOf(0),
                            claimedMoveStatus = 1,
                            requireSupplementGate = true
                        )
                        if (!result) {
                            supplementPrefetchSessionRepository.pauseForRuntimeRecovery(plan.sessionId)
                        }
                        result
                    }.getOrElse { error ->
                        if (resumedFromRuntimePause) {
                            runCatching { supplementPrefetchSessionRepository.pauseForRuntimeRecovery(plan.sessionId) }
                        }
                        logW(LOG_SUPPLEMENT, "双 lane 运行态恢复认领失败：reason=$reason，error=${error.message}")
                        false
                    }
                }
            }
        }
        if (!claimed) {
            releaseResumeLock()
            return false
        }

        var physicalFailure: String? = null
        val physicalCoordinator = SupplementPrefetchPhysicalStateCoordinator(
            repository = supplementPrefetchSessionRepository,
            persistPhysicalState = { _, _ ->
                KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            },
            publishPhysicalDelta = { _, actionName ->
                val currentLane = requireNotNull(supplementPrefetchSessionRepository.load()?.deliveryLane)
                if (actionName == "in2") {
                    KaoPanHelper.getKaoPanBoxList()
                        .firstOrNull { it.positionSn == currentLane.sourceBoxNo }
                        ?.let { changed ->
                            PanPhysicalStateDeltaReporter.reportStockChanged(
                                changed,
                                "action_physical:parallel_resume_in2:box=${changed.positionSn}:pan=${currentLane.targetPanNo}",
                                "parallel_resume_action_physical_in2"
                            )
                        }
                } else if (actionName == "in7") {
                    KaoPanHelper.getKaoPanList()
                        .firstOrNull { it.positionSn == currentLane.targetPanNo }
                        ?.let { changed ->
                            PanPhysicalStateDeltaReporter.reportPanChanged(
                                changed,
                                "action_physical:parallel_resume_in7:box=${currentLane.sourceBoxNo}:pan=${changed.positionSn}",
                                "parallel_resume_action_physical_in7"
                            )
                        }
                }
            }
        )
        val client = createAtomicActionClient(
            ActionGroupTelemetryContext(
                sessionId = plan.sessionId,
                boxNo = plan.sourceBoxNo,
                panNo = plan.targetPanNo,
                resourceLocks = actionGroupLocks.snapshot()
            )
        ) { telemetry ->
            if (telemetry.eventPhase == AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED &&
                telemetry.resultCode == AtomicActionCodes.COMPLETED &&
                telemetry.actionName in setOf("in2", "in7")
            ) {
                val checkpoint = physicalCoordinator.applySuccessfulPhysicalCheckpoint(
                    SupplementLaneRole.DELIVERY,
                    telemetry.actionName
                ) { currentLane ->
                    val currentPan = KaoPanHelper.getKaoPanList()
                        .firstOrNull { it.positionSn == currentLane.targetPanNo }
                    val currentBox = KaoPanHelper.getKaoPanBoxList()
                        .firstOrNull { it.positionSn == currentLane.sourceBoxNo }
                    when {
                        telemetry.actionName == "in2" && currentBox?.num == currentLane.expectedStockAfter ->
                            SupplementPhysicalMutation.ALREADY_APPLIED
                        telemetry.actionName == "in7" && currentPan?.isHasSausage == true ->
                            SupplementPhysicalMutation.ALREADY_APPLIED
                        else -> {
                            val applied = applySupplementPhysicalTelemetry(
                                telemetry,
                                pan,
                                box,
                                "订单结束后双 lane 续作",
                                currentLane.boxDeductionApplied,
                                currentLane.panPlacementApplied,
                                persistAndPublish = false
                            )
                            when {
                                applied.stockInconsistent -> SupplementPhysicalMutation.FAILED
                                applied.boxDeducted || applied.panPlaced -> SupplementPhysicalMutation.APPLIED
                                else -> SupplementPhysicalMutation.ALREADY_APPLIED
                            }
                        }
                    }
                }
                if (checkpoint is SupplementPhysicalCheckpointResult.Failed) {
                    physicalFailure = checkpoint.reason
                }
            }
        }
        val result = try {
            InActionGroupRunner(
                client = client,
                logger = { logD(LOG_ACTION_GROUP, it) },
                operationLogger = LogUtils::i,
                failureLogger = LogUtils::e,
                onCurrentActionChanged = { actionName ->
                    val currentLane = requireNotNull(supplementPrefetchSessionRepository.load()?.deliveryLane)
                    if (currentLane.currentAction != actionName) {
                        supplementPrefetchSessionRepository.prepareAction(
                            SupplementLaneRole.DELIVERY,
                            currentLane,
                            actionName
                        )
                    }
                },
                onActionCommandWritten = { actionName ->
                    val currentLane = supplementPrefetchSessionRepository.load()?.deliveryLane
                    if (currentLane?.lifecycle == SupplementActionLifecycle.PREPARED) {
                        supplementPrefetchSessionRepository.markCommandWritten(SupplementLaneRole.DELIVERY, actionName)
                    }
                },
                onActionRunningConfirmed = { actionName ->
                    val currentLane = supplementPrefetchSessionRepository.load()?.deliveryLane
                    if (currentLane?.lifecycle == SupplementActionLifecycle.COMMAND_WRITTEN) {
                        supplementPrefetchSessionRepository.markRunningConfirmed(SupplementLaneRole.DELIVERY, actionName)
                    }
                },
                onActionTerminalConfirmed = { actionName, actionResult ->
                    physicalFailure?.let { error(it) }
                    if (actionResult == AtomicActionResult.Success) {
                        val currentLane = requireNotNull(supplementPrefetchSessionRepository.load()?.deliveryLane)
                        if (currentLane.lifecycle in setOf(
                                SupplementActionLifecycle.COMMAND_WRITTEN,
                                SupplementActionLifecycle.RUNNING_CONFIRMED
                            )
                        ) {
                            supplementPrefetchSessionRepository.markTerminalConfirmed(
                                SupplementLaneRole.DELIVERY,
                                actionName,
                                allowWithoutRunningEvidence = true
                            )
                        }
                    }
                }
            ).run(plan.sourceBoxNo, plan.targetPanNo, startAfterCompletedAction = plan.resumeAfterAction)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            enterActionGroupMaintenance("双 lane 续作异常，保留会话和锁：reason=$reason，error=${error.message}")
            return false
        }
        if (result != ActionGroupResult.Success) {
            enterActionGroupMaintenance("双 lane 续作失败，保留会话和锁：reason=$reason，result=$result")
            return false
        }
        return finishParallelSupplementResume(plan, pan, box, reason, allowRuntimeDefer)
    }

    private suspend fun finishParallelSupplementResume(
        plan: SupplementPrefetchResumePlan,
        pan: KaoPan,
        box: KaoPanBox,
        reason: String,
        allowRuntimeDefer: Boolean
    ): Boolean {
        val session = supplementPrefetchSessionRepository.load()
            ?: return false
        val lane = session.deliveryLane
        if (lane?.currentAction != "in7" || !lane.panPlacementApplied) {
            enterActionGroupMaintenance("双 lane 续作成功但缺少 in7 落盘证据：reason=$reason")
            return false
        }
        return try {
            applySuccessfulSupplementMove(
                pan,
                box,
                boxAlreadyDeducted = lane.boxDeductionApplied,
                panAlreadyPlaced = lane.panPlacementApplied
            ) ?: error("双 lane 续作完成后盘箱缓存缺失")
            KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            supplementBatchIntentRepository.markCompleted(plan.batchId, plan.targetPanNo)
            if (session.prefetchLane != null) {
                supplementPrefetchSessionRepository.promotePrefetchAfterDelivery(
                    allowPausedCheckpoint = true
                )
                val next = SupplementPrefetchResumePlanner.prepareRecovered(
                    supplementPrefetchSessionRepository
                )
                if (next !is SupplementPrefetchResumePreparation.Ready) {
                    error("双 lane 当前根完成后预取 lane 无法续作：$next")
                }
                return resumePreparedParallelSupplementOnce(
                    next.plan,
                    "$reason：当前根完成后续作预取根",
                    lockAlreadyOwned = true,
                    allowRuntimeDefer = allowRuntimeDefer
                )
            }
            val latest = requireNotNull(supplementPrefetchSessionRepository.load())
            if (!releaseMechanicalMove(
                    "双 lane 订单结束续作完成",
                    latest.sessionId,
                    supplementPipelineClaimOwnerStore(),
                    expectedMoveStatus = 1,
                    idleMoveStatus = 0
                )
            ) {
                enterActionGroupMaintenance("双 lane 续作 owner/moveStatus 耐久收口失败", preserveMoveStatus = true)
                false
            } else {
                actionGroupLocks.release(plan.sessionId)
                publishRuntimeStateWithHttpFallback("双 lane 订单结束续作完成")
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val restoreError = runCatching { restoreParallelSupplementBusyMoveStatus() }.exceptionOrNull()
            enterActionGroupMaintenance(
                "双 lane 续作耐久收口失败，保留证据：${error.message}，" +
                    "restoreError=${restoreError?.message}",
                preserveMoveStatus = true
            )
            false
        }
    }

    private fun hasPausedSupplementSession(): Boolean {
        val session = pausedSupplementSessionStore.load()
            ?: actionGroupSessionStore.load()?.takeIf { it.pausedForOrder }
            ?: return false
        return session.type == ActionGroupSessionType.IN_SUPPLEMENT &&
            session.pausedForOrder &&
            session.resumeAfterAction in setOf("in1", "in2", "in3")
    }

    suspend fun resumePausedSupplementAfterOrder(reason: String): Boolean {
        val session = pausedSupplementSessionStore.load()
            ?: actionGroupSessionStore.load()?.takeIf { it.pausedForOrder }
            ?: return true
        if (!session.pausedForOrder || session.resumeAfterAction !in setOf("in1", "in2", "in3")) {
            return true
        }
        val config = AppConfig.getAppConfig()
        if (config.onlineStatus != 1 || config.errorStatus != 0 || config.inspectionMode != 0 ||
            config.supplyStatus != 0 || config.isEnable != 1
        ) {
            logW(
                LOG_SUPPLEMENT,
                "暂停补肠暂不自动续作：reason=$reason，onlineStatus=${config.onlineStatus}，" +
                    "errorStatus=${config.errorStatus}，inspectionMode=${config.inspectionMode}，" +
                    "supplyStatus=${config.supplyStatus}，isEnable=${config.isEnable}"
            )
            return false
        }
        if (!VMModbusHelper.connectStatus()) {
            logW(LOG_SUPPLEMENT, "暂停补肠暂不自动续作：Modbus 未连接，reason=$reason")
            return false
        }
        val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == session.targetNo }
        val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == session.sourceNo }
        if (pan == null || box == null) {
            enterActionGroupMaintenance("暂停补肠续作失败：找不到目标烤盘或来源肠箱，session=$session")
            return false
        }
        if (!claimMechanicalMove(
                stage = "暂停补肠续作下发前",
                owner = session.sessionId,
                ownerStore = actionGroupClaimOwnerStore(session.copy(pausedForOrder = false)),
                allowedMoveStatuses = setOf(0),
                claimedMoveStatus = 1,
                requireSupplementGate = true
            )
        ) {
            return false
        }
        val resumedClaimRevision = actionGroupSessionStore.load()
            ?.takeIf { it.sessionId == session.sessionId }
            ?.mechanicalClaimRevision
            ?: return false
        if (!actionGroupLocks.tryAcquire(session.resourceMask, session.sessionId)) {
            releaseSupplementMoveClaimIfOwned(
                owner = session.sessionId,
                ownerStore = actionGroupClaimOwnerStore(),
                resourceMask = session.resourceMask,
                expectedRevision = resumedClaimRevision
            )
            logW(LOG_SUPPLEMENT, "暂停补肠续作资源忙：session=${session.sessionId}，held=${actionGroupLocks.snapshot()}")
            return false
        }

        var resumeThrowable: Throwable? = null
        val result = try {
            pausedSupplementSessionStore.clear()
            InActionGroupRunner(
                client = createAtomicActionClient(
                    ActionGroupTelemetryContext(
                        sessionId = session.sessionId,
                        boxNo = session.sourceNo,
                        panNo = session.targetNo,
                        resourceLocks = actionGroupLocks.snapshot()
                    )
                ) { telemetry ->
                    val currentSession = actionGroupSessionStore.load() ?: session
                    val applied = applySupplementPhysicalTelemetry(
                        telemetry = telemetry,
                        plannedPan = pan,
                        plannedBox = box,
                        source = "订单完成后续作暂停补肠",
                        boxAlreadyDeducted = currentSession.boxDeductionApplied,
                        panAlreadyPlaced = currentSession.panPlacementApplied
                    )
                    if (!applied.stockInconsistent) {
                        recordConfirmedSupplementSessionStage(
                            sessionId = session.sessionId,
                            actionName = telemetry.actionName,
                            sourceBoxNo = session.sourceNo,
                            targetPanNo = session.targetNo ?: 0,
                            resultCode = telemetry.resultCode
                        )
                    }
                },
                logger = { message -> logD(LOG_ACTION_GROUP, message) },
                operationLogger = { message -> LogUtils.i(message) },
                failureLogger = { message -> LogUtils.e(message) },
                onCurrentActionChanged = { actionName ->
                    actionGroupSessionStore.load()?.let { current ->
                        actionGroupSessionStore.save(
                            current.copy(
                                currentAction = actionName,
                                checkpointVersion = current.checkpointVersion + 1L
                            )
                        )
                    }
                }
            ).run(
                sourceBoxNo = session.sourceNo,
                targetPanNo = session.targetNo ?: 0,
                startAfterCompletedAction = session.resumeAfterAction
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            resumeThrowable = throwable
            null
        } finally {
            actionGroupLocks.release(session.sessionId)
        }

        resumeThrowable?.let { throwable ->
            val detail = "暂停补肠续作异常：reason=$reason，session=${session.sessionId}，" +
                "error=${throwable::class.java.simpleName}: ${throwable.message ?: "无详情"}"
            logE(LOG_SUPPLEMENT, detail, throwable)
            enterActionGroupMaintenance(detail)
            return false
        }

        val handled = handleRecoveredActionGroupSession(
            actionGroupSessionStore.load() ?: session,
            requireNotNull(result),
            reason
        )
        if (!handled) {
            enterActionGroupMaintenance("暂停补肠续作失败：reason=$reason，session=${session.sessionId}，result=$result")
        }
        return handled
    }

    private fun normalizePausedSupplementHandoff() {
        val active = actionGroupSessionStore.load()
        if (active?.type == ActionGroupSessionType.IN_SUPPLEMENT && active.pausedForOrder &&
            active.resumeAfterAction in setOf("in1", "in2", "in3")
        ) {
            val persisted = pausedSupplementSessionStore.load()
            if (persisted == null || persisted.sessionId != active.sessionId ||
                persisted.checkpointVersion < active.checkpointVersion
            ) {
                pausedSupplementSessionStore.save(active)
            }
            actionGroupSessionStore.clear()
            return
        }
        val paused = pausedSupplementSessionStore.load()
        if (active != null && !active.pausedForOrder && paused != null &&
            active.sessionId == paused.sessionId
        ) {
            pausedSupplementSessionStore.clear()
        }
    }

    fun actionGroupRuntimeSnapshotForLog(): String {
        val session = actionGroupSessionStore.load()
        val pausedSupplement = pausedSupplementSessionStore.load()
        val discardJournal = discardJournalStore.load()
        return "session=${session?.sessionId ?: "无"}，" +
            "pausedSupplement=${pausedSupplement?.sessionId ?: "无"}/${pausedSupplement?.resumeAfterAction ?: "无"}，" +
            "discard=${discardJournal?.owner ?: "无"}/${discardJournal?.checkpoint ?: "无"}，" +
            "resourceLocks=${actionGroupLocks.snapshot()}"
    }

    private fun logD(category: String, message: String) = LogUtils.d("[$category] $message")
    private fun logI(category: String, message: String) = LogUtils.i("[$category] $message")
    private fun logW(category: String, message: String) = LogUtils.w("[$category] $message")
    private fun logE(category: String, message: String) = LogUtils.e("[$category] $message")
    private fun logE(category: String, message: String, tr: Throwable) = LogUtils.e("[$category] $message", tr)

    fun invalidateActionGroupRecoveryVerification(reason: String) {
        val wasVerified = actionGroupRecoveryVerified
        actionGroupRecoveryVerified = false
        if (wasVerified) {
            logW(LOG_ACTION_GROUP, "动作组恢复验证失效：reason=$reason")
        }
    }

    private fun markActionGroupRecoveryVerified(reason: String) {
        if (!actionGroupRecoveryVerified) {
            logD(LOG_ACTION_GROUP, "动作组恢复验证通过：reason=$reason")
        }
        actionGroupRecoveryVerified = true
    }

    private fun onlineStatusDesc(status: Int): String = when (status) {
        0 -> "0(待投运)"
        1 -> "1(运营中)"
        2 -> "2(休息中)"
        3 -> "3(维护中)"
        4 -> "4(工作中)"
        else -> "$status(未知)"
    }

    private fun restStatusSourceDesc(source: String?): String = AppConfig.restStatusSourceText(source)

    private fun moveStatusDesc(status: Int): String = when (status) {
        0 -> "0(空闲)"
        1 -> "1(补肠中)"
        2 -> "2(出餐中)"
        3 -> "3(烤盘搬移中)"
        4 -> "4(丢弃中)"
        5 -> "5(自清洁中)"
        else -> "$status(未知)"
    }

    private fun sellPriorityWaitTimeoutMs(status: Int): Long = when (status) {
        1 -> 240_000L
        3 -> 90_000L
        4 -> 150_000L
        5 -> 0L
        else -> 0L
    }

    private fun modeTypeDesc(value: Int): String = when (value) {
        1 -> "1(低并发)"
        2 -> "2(高并发)"
        else -> "$value(未知)"
    }

    private fun transitionModeDesc(value: Int): String = when (value) {
        0 -> "0(无过渡态)"
        1 -> "1(低转高过渡)"
        2 -> "2(高转低过渡)"
        else -> "$value(未知)"
    }

    private fun actionExecutionResultDesc(result: ActionExecutionResult): String = when (result) {
        ActionExecutionResult.SUCCESS -> "SUCCESS(成功)"
        ActionExecutionResult.COMMAND_REJECTED -> "COMMAND_REJECTED(指令被拒绝)"
        ActionExecutionResult.BUSY_TIMEOUT -> "BUSY_TIMEOUT(设备忙超时)"
        ActionExecutionResult.DISCONNECTED -> "DISCONNECTED(通信断开)"
        ActionExecutionResult.RUNNING_TIMEOUT -> "RUNNING_TIMEOUT(执行超时)"
    }

    enum class TakeSausageResult {
        TAKEN_BY_USER,
        DISCARDED,
        NO_SELLABLE_STOCK,
        NO_DISPATCH,
        EVIDENCE_UNKNOWN,
        ERROR
    }

    data class SelfCleanExecutionReport(
        val success: Boolean,
        val command: Int,
        val targetLabel: String,
        val status201: Int,
        val message: String
    )

    private fun tryRecoverIdleMoveStatus(reason: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus == 0) {
            return true
        }
        if (!VMModbusHelper.connectStatus()) {
            return false
        }
        val first = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
        Thread.sleep(500L)
        val second = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
        val statusReadings = listOf(first, second)
        val oldMoveStatus = config.moveStatus
        if (!clearMoveStatusForLegacy201Recovery(statusReadings)) {
            val actionGroupIdle = isActionGroupIdleForMoveStatusRecovery()
            logW(
                LOG_ACTION,
                "跳过本地机械状态自动收口：reason=$reason，" +
                    "moveStatus（机械动作状态）=${moveStatusDesc(config.moveStatus)}，" +
                    "status201连续读数=$statusReadings，actionGroupIdle=$actionGroupIdle，" +
                    "actionGroup=${actionGroupRuntimeSnapshotForLog()}"
            )
            return false
        }
        logW(
            LOG_ACTION,
            "检测到本地机械状态残留，已按下位机连续空闲状态自动收口：" +
                "reason=$reason，moveStatus（机械动作状态）=${moveStatusDesc(oldMoveStatus)}->${moveStatusDesc(config.moveStatus)}，" +
                "status201连续读数=$statusReadings，actionGroup=${actionGroupRuntimeSnapshotForLog()}"
        )
        return true
    }

    private suspend fun waitUntilMoveStatusIdleOrTimeout(
        actionName: String,
        timeoutMs: Long = MANUAL_TAKE_IDLE_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var appState = AppConfig.getAppConfig().moveStatus
        while (appState != 0) {
            if (tryRecoverIdleMoveStatus(actionName)) {
                return true
            }
            if (System.currentTimeMillis() >= deadline) {
                logW(
                    LOG_ACTION,
                    "$actionName 等待机械动作归零超时：" +
                        "moveStatus（机械动作状态）=${moveStatusDesc(appState)}，timeoutMs=$timeoutMs"
                )
                return false
            }
            delay(1_000L)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "$actionName 等待机械动作收尾：moveStatus=${moveStatusDesc(appState)}")
        }
        return true
    }

    private enum class ActionStatus201 {
        IDLE,
        RUNNING,
        BUSY,
        DISCONNECTED
    }

    private enum class ActionExecutionResult {
        SUCCESS,
        COMMAND_REJECTED,
        BUSY_TIMEOUT,
        DISCONNECTED,
        RUNNING_TIMEOUT
    }

    private class AutomaticMechanicalDispatchPreemptedException : RuntimeException()

    private fun publishRuntimeStateWithHttpFallback(reason: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus()
        if (!mqttOk) {
            logW(LOG_DEVICE, "$reason：MQTT 发送队列拒绝，回退 HTTP 补偿")
            DataManagementAPI.uploadDeviceInfo()
        }
    }

    private fun markDeviceErrorAndUpload(code: FaultCode, logMessage: String) {
        invalidateActionGroupRecoveryVerification("设备故障锁存：errorCode=${code.name}")
        val config = AppConfig.getAppConfig()
        val previousOnlineStatus = config.onlineStatus
        val previousErrorStatus = config.errorStatus
        config.errorStatus = 2
        config.onlineStatus = 3
        config.errorCode = code.name
        config.errorCategory = code.category.display
        config.errorReason = logMessage.ifBlank { code.defaultReason }
        LogUtils.e(
            "【动作组故障锁存】已锁存设备故障，禁止自动补肠/续补继续启动：" +
                "onlineStatus（设备营业状态）=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                "errorStatus（设备故障状态）=$previousErrorStatus(0=正常, 非0=故障)->${config.errorStatus}(2=通信/硬件级故障)，" +
                "errorCode=${code.name}（${code.category.display}），" +
                "moveStatus（机械动作状态）=${moveStatusDesc(config.moveStatus)}，reason=$logMessage"
        )
        HomeUiRefreshBridge.requestRefresh("device_fault:${code.name}")
        AppConfig.saveAppConfigDurable(config)
        operateScope.launch {
            publishRuntimeStateWithHttpFallback("设备故障状态上报")
        }
        LogUtils.e(
            "【状态切换】设备进入维护：onlineStatus（设备营业状态）=" +
                "${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                "errorStatus（设备故障状态）=$previousErrorStatus(0=正常, 非0=故障)->${config.errorStatus}(2=通信/硬件级故障)，" +
                "errorCode=${code.name}"
        )
        logE(LOG_DEVICE, logMessage)
    }

    private fun markSupplyDegradedRuntimeState(
        reason: String,
        failedBoxNos: List<Int>,
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails()
    ) {
        LowerPlatformRuntimeStateReporter.markSupplyDegraded(reason, failedBoxNos, details)
    }

    private fun markLowerPlatformIsolatedRuntimeState(
        reason: String,
        actionName: String,
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails(requiresManualConfirm = true)
    ) {
        LowerPlatformRuntimeStateReporter.markIsolated(reason, actionName, details)
    }

    private fun persistLowerPlatformIsolatedRuntimeState(
        reason: String,
        actionName: String,
        details: LowerPlatformEpisodeDetails
    ): LowerPlatformEpisodeReducer.StateSnapshot =
        LowerPlatformRuntimeStateReporter.persistIsolated(reason, actionName, details)

    private fun publishPersistedLowerPlatformIsolation(
        snapshot: LowerPlatformEpisodeReducer.StateSnapshot
    ) {
        LowerPlatformRuntimeStateReporter.publishPersistedIsolation(snapshot)
    }

    private fun recordConfirmedSupplementSessionStage(
        sessionId: String,
        actionName: String,
        sourceBoxNo: Int,
        targetPanNo: Int,
        resultCode: Int?
    ) {
        if (resultCode != AtomicActionCodes.COMPLETED) {
            return
        }
        val session = actionGroupSessionStore.load() ?: return
        if (session.sessionId != sessionId ||
            session.currentAction != actionName ||
            session.sourceNo != sourceBoxNo ||
            session.targetNo != targetPanNo
        ) {
            return
        }
        actionGroupSessionStore.save(
            session.copy(
                checkpointVersion = session.checkpointVersion + 1L,
                lastConfirmedStage = actionName,
                boxDeductionApplied = session.expectedStockAfter?.let { expected ->
                    KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == sourceBoxNo }?.num == expected
                } ?: session.boxDeductionApplied,
                panPlacementApplied = KaoPanHelper.getKaoPanList()
                    .firstOrNull { it.positionSn == targetPanNo }
                    ?.isHasSausage == true
            )
        )
    }

    private fun armPostSellSupplementGuard(
        reason: String,
        durationMs: Long = getPostSellSupplementGuardMs()
    ) {
        val blockUntil = System.currentTimeMillis() + durationMs
        if (blockUntil > supplementBlockedUntilMs) {
            supplementBlockedUntilMs = blockUntil
        }
        logD(LOG_WINDOW, "$reason，进入售卖收尾保护窗：${durationMs}ms")
    }

    private suspend fun waitForPostSellSupplementGuardIfNeeded() {
        val blockUntil = supplementBlockedUntilMs
        if (blockUntil <= 0L) {
            return
        }

        val remaining = blockUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            logD(LOG_SUPPLEMENT, "售卖收尾后延迟补肠：remaining=${remaining}ms")
            delay(remaining)
        }

        supplementBlockedUntilMs = 0L

        if (!VMModbusHelper.connectStatus()) {
            logW(LOG_SUPPLEMENT, "售卖收尾保护结束时检测到下位机未连接，跳过空闲稳定检查")
            return
        }

        var idleSince = -1L
        val idleStableMs = getPostSellIdleStableMs()
        val checkDeadline = System.currentTimeMillis() + getPostSellIdleMaxWaitMs()
        while (System.currentTimeMillis() < checkDeadline) {
            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionStatus201.IDLE -> {
                    val now = System.currentTimeMillis()
                    if (idleSince < 0L) {
                        idleSince = now
                    }
                    if (now - idleSince >= idleStableMs) {
                        logD(LOG_SUPPLEMENT, "售卖收尾后下位机已连续空闲 ${now - idleSince}ms，允许补肠")
                        return
                    }
                }
                ActionStatus201.RUNNING, ActionStatus201.BUSY -> {
                    idleSince = -1L
                }
                ActionStatus201.DISCONNECTED -> {
                    logW(LOG_SUPPLEMENT, "售卖收尾后读取 201 失败，跳过空闲稳定检查")
                    return
                }
            }
            delay(500L)
        }

        logW(LOG_SUPPLEMENT, "售卖收尾后等待下位机稳定空闲超时，继续按常规补肠流程处理")
    }

    private fun getPostSellSupplementGuardMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellSupplementGuardSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleStableMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleStableSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_STABLE_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleMaxWaitMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleMaxWaitSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS
        return seconds * 1000L
    }

    private fun getBoxTasteLabel(kaoPanBox: KaoPanBox): String {
        return kaoPanBox.tasteName?.takeIf { it.isNotBlank() } ?: "未知口味"
    }

    private fun getPanTasteLabel(kaoPan: KaoPan): String {
        return kaoPan.taste.productName?.takeIf { it.isNotBlank() }
            ?: kaoPan.taste.tasteName?.takeIf { it.isNotBlank() }
            ?: "未知口味"
    }

    private fun getBoxToPlatformTimeoutMs(): Long {
        val seconds = AppConfig.getAppConfig().boxToPlatformTimeoutSeconds
            .takeIf { it > 0 }
            ?: 90
        return seconds * 1000L
    }

    // Modbus 设备地址与关键寄存器地址。
    val modbus_address = 1
    val action_address = 200
    val action_address_status = 201
    val action_address_move = 202
    val action_address_move_result = 203
    val action_address_1_temperature = 500
    val action_address_2_temperature = 501
    val action_address_3_temperature = 502
    val action_address_1_temperature_write = 503
    val action_address_2_temperature_write = 504
    val action_address_3_temperature_write = 505
    private const val self_clean_command_min = 501
    private const val self_clean_command_max = 533
    private const val self_clean_sell_platform_command = 534

    val keepWarmTemperature = 90
    val heatingTemperature = 150

    val action_debug = 255

    /**
     * 仅按当前和嵌入式确认的 201 协议语义解析动作状态。
     * 这里把读寄存器失败映射成 DISCONNECTED，避免和 201=2 的“设备忙”混淆。
     */
    private fun parseActionStatus201(value: Int): ActionStatus201 {
        return when (value) {
            0 -> ActionStatus201.IDLE
            1 -> ActionStatus201.RUNNING
            2 -> ActionStatus201.BUSY
            else -> ActionStatus201.DISCONNECTED
        }
    }

    /**
     * 在真正下发动作前做一次通信层预检。
     * 这里只解决“上下位机通不通”的问题，不负责判断设备忙闲。
     */
    private suspend fun ensureModbusReady(actionName: String): Boolean {
        if (VMModbusHelper.connectStatus()) {
            return true
        }
        logW(LOG_ACTION, "$actionName 前检测到下位机通信断开，开始短时重连")
        repeat(3) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                logD(LOG_ACTION, "$actionName 前重连下位机结果：attempt=$attempt/3, status=$status")
            }
            delay(1500L)
            if (VMModbusHelper.connectStatus()) {
                logD(LOG_ACTION, "$actionName 前下位机重连成功：attempt=$attempt/3")
                return true
            }
        }
        logE(LOG_ACTION, "$actionName 前下位机持续断开，放弃执行动作")
        return false
    }

    /**
     * 统一封装 200/201 协议下的动作执行逻辑。
     *
     * 约束：
     * - 下发动作前先做 connectStatus() 预检
     * - 201=2 按“设备忙”处理，有限次等待后重试
     * - 201=1 长时间不回到 0，按执行超时处理
     * - 通信层失败才走 DISCONNECTED
     */
    private suspend fun executeActionWithRetry(
        actionName: String,
        maxBusyRetry: Int = 3,
        busyRetryDelayMs: Long = 1000L,
        runningPollDelayMs: Long = 1000L,
        runningTimeoutMs: Long = 60_000L,
        requireRunningBeforeIdleSuccess: Boolean = false,
        issueAction: suspend () -> VMModbusHelper.ModbusOperationResult
    ): ActionExecutionResult {
        var busyRetryCount = 0

        retryLoop@ while (busyRetryCount <= maxBusyRetry) {
            if (!ensureModbusReady(actionName)) {
                return ActionExecutionResult.DISCONNECTED
            }

            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionStatus201.IDLE -> Unit
                ActionStatus201.RUNNING, ActionStatus201.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        logE(LOG_ACTION, "$actionName 前连续检测到设备忙，超过重试阈值：retry=$busyRetryCount")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    logW(LOG_ACTION, "$actionName 前检测到设备忙(201!=0)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                ActionStatus201.DISCONNECTED -> {
                    logE(LOG_ACTION, "$actionName 前读取动作状态失败")
                    return ActionExecutionResult.DISCONNECTED
                }
            }

            val issueResult = issueAction()
            when (issueResult.status) {
                VMModbusHelper.ModbusOperationStatus.SUCCESS -> Unit
                VMModbusHelper.ModbusOperationStatus.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        logE(LOG_ACTION, "$actionName 下发指令时下位机持续返回忙，超过重试阈值")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    logW(LOG_ACTION, "$actionName 下发指令时下位机返回忙，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                VMModbusHelper.ModbusOperationStatus.DISCONNECTED -> {
                    logE(LOG_ACTION, "$actionName 下发指令失败：下位机通信断开")
                    return ActionExecutionResult.DISCONNECTED
                }
                VMModbusHelper.ModbusOperationStatus.PARAM_ERROR,
                VMModbusHelper.ModbusOperationStatus.DEVICE_FAILURE,
                VMModbusHelper.ModbusOperationStatus.PROTOCOL_ERROR,
                VMModbusHelper.ModbusOperationStatus.UNKNOWN_ERROR -> {
                    logE(LOG_ACTION, "$actionName 下发指令失败：${issueResult.status} ${issueResult.message ?: ""}".trim())
                    return ActionExecutionResult.COMMAND_REJECTED
                }
            }
            val start = System.currentTimeMillis()
            var hasObservedRunning = false

            while (true) {
                delay(runningPollDelayMs)
                if (!ensureModbusReady(actionName)) {
                    return ActionExecutionResult.DISCONNECTED
                }
                val status201 = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
                if (requireRunningBeforeIdleSuccess) {
                    when (Legacy201Policy.evaluateAfterCommand(status201, hasObservedRunning)) {
                        Legacy201Decision.Running -> {
                            hasObservedRunning = true
                            if (System.currentTimeMillis() - start > runningTimeoutMs) {
                                logE(LOG_ACTION, "$actionName 长时间处于执行中(201=1)，已超时")
                                return ActionExecutionResult.RUNNING_TIMEOUT
                            }
                        }
                        Legacy201Decision.Success -> return ActionExecutionResult.SUCCESS
                        Legacy201Decision.WaitingForStart -> {
                            if (System.currentTimeMillis() - start > runningTimeoutMs) {
                                logE(LOG_ACTION, "$actionName 未观察到 201=1（执行中），不能把 201=0（空闲/完成）当成本轮成功")
                                return ActionExecutionResult.RUNNING_TIMEOUT
                            }
                        }
                        Legacy201Decision.BusyOrError -> {
                            logE(LOG_ACTION, "$actionName 下发后收到 201=$status201（旧式错误/忙），本轮不清库存")
                            return ActionExecutionResult.BUSY_TIMEOUT
                        }
                        Legacy201Decision.ReadFailed -> {
                            logE(LOG_ACTION, "$actionName 执行过程中读取动作状态失败")
                            return ActionExecutionResult.DISCONNECTED
                        }
                    }
                } else {
                    when (parseActionStatus201(status201)) {
                        ActionStatus201.RUNNING -> {
                            if (System.currentTimeMillis() - start > runningTimeoutMs) {
                                logE(LOG_ACTION, "$actionName 长时间处于执行中(201=1)，已超时")
                                return ActionExecutionResult.RUNNING_TIMEOUT
                            }
                        }
                        ActionStatus201.IDLE -> return ActionExecutionResult.SUCCESS
                        ActionStatus201.BUSY -> {
                            busyRetryCount++
                            if (busyRetryCount > maxBusyRetry) {
                                logE(LOG_ACTION, "$actionName 下发后连续收到设备忙(201=2)，超过重试阈值")
                                return ActionExecutionResult.BUSY_TIMEOUT
                            }
                            logW(LOG_ACTION, "$actionName 下发后收到设备忙(201=2)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                            delay(busyRetryDelayMs)
                            continue@retryLoop
                        }
                        ActionStatus201.DISCONNECTED -> {
                            logE(LOG_ACTION, "$actionName 执行过程中读取动作状态失败")
                            return ActionExecutionResult.DISCONNECTED
                        }
                    }
                }
            }
        }

        return ActionExecutionResult.BUSY_TIMEOUT
    }

    private fun markActionFailure(actionName: String, result: ActionExecutionResult) {
        val code = when (result) {
            ActionExecutionResult.SUCCESS -> return
            ActionExecutionResult.COMMAND_REJECTED -> FaultCode.MODBUS_CMD_REJECTED
            ActionExecutionResult.BUSY_TIMEOUT -> FaultCode.ACTION_BUSY_TIMEOUT
            ActionExecutionResult.DISCONNECTED -> FaultCode.MODBUS_COMM_DROP
            ActionExecutionResult.RUNNING_TIMEOUT -> FaultCode.ACTION_RUNNING_TIMEOUT
        }
        val logMessage = when (result) {
            ActionExecutionResult.SUCCESS -> return
            ActionExecutionResult.COMMAND_REJECTED -> "$actionName 下发指令失败，设备进入维护中"
            ActionExecutionResult.BUSY_TIMEOUT -> "$actionName 连续收到设备忙(201=2)，超过重试阈值，设备进入维护中"
            ActionExecutionResult.DISCONNECTED -> "$actionName 过程中与下位机通信断开，设备进入维护中"
            ActionExecutionResult.RUNNING_TIMEOUT -> "$actionName 长时间处于执行中(201=1)，设备进入维护中"
        }
        markDeviceErrorAndUpload(code, logMessage)
    }

    private fun createAtomicActionClient(
        telemetryContext: ActionGroupTelemetryContext = ActionGroupTelemetryContext(),
        onTelemetry: (ActionGroupTelemetry) -> Unit = {}
    ): AtomicActionClient {
        return AtomicActionExecutor(
            gateway = VmAtomicModbusGateway(modbus_address),
            logger = { message -> logD(LOG_ACTION_GROUP, message) },
            telemetryContext = telemetryContext,
            telemetrySink = { telemetry ->
                publishActionGroupTelemetry(telemetry)
                onTelemetry(telemetry)
            },
            diagnosisSnapshotProvider = { VMModbusHelper.currentDiagnosisSnapshot() },
            modbusServerAddress = modbus_address
        )
    }

    private fun publishActionGroupTelemetry(telemetry: ActionGroupTelemetry) {
        SendServerHelper.publishActionGroupTelemetry(telemetry)
    }

    private fun actionGroupResultDesc(result: ActionGroupResult): String {
        return when (result) {
            ActionGroupResult.Success -> "SUCCESS（动作组完成）"
            ActionGroupResult.Discarded -> "DISCARDED（历史兼容结果，4F 主流程不再产生）"
            is ActionGroupResult.Failed -> "FAILED（${result.actionName}：${result.message}）"
        }
    }

    private fun atomicFailureCode(result: ActionGroupResult): Int {
        return when (result) {
            ActionGroupResult.Success,
            ActionGroupResult.Discarded -> 0
            is ActionGroupResult.Failed -> when (val atomicResult = result.result) {
                is AtomicActionResult.Failed -> atomicResult.errorCode
                is AtomicActionResult.ModbusError -> 2
                is AtomicActionResult.Timeout -> 2
                AtomicActionResult.Success -> 0
            }
        }
    }

    private fun isActionGroupEnabledOrEnterMaintenance(actionName: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.useActionGroupV2 == 1) {
            return true
        }
        markDeviceErrorAndUpload(
            FaultCode.ACTION_GROUP_DISABLED,
            "$actionName 被维护开关 useActionGroupV2=0 拦截；新 Android 12 主流程不回退旧视觉链路，设备进入维护/暂停新单"
        )
        return false
    }

    private fun applyPendingActionGroupDisableIfIdle(reason: String) {
        val config = AppConfig.getAppConfig()
        if (config.pendingDisableActionGroupV2 != 1 || config.moveStatus != 0) {
            return
        }
        config.pendingDisableActionGroupV2 = 0
        config.useActionGroupV2 = 0
        config.onlineStatus = 3
        config.isEnable = 0
        AppConfig.saveAppConfig(config)
        HomeUiRefreshBridge.requestRefresh("action_group:disable_if_idle")
        logW(
            LOG_ACTION_GROUP,
            "$reason：已执行 pendingDisableActionGroupV2=1（待会话收口后关闭），" +
                "useActionGroupV2=0（动作组关闭），onlineStatus=3（维护中），isEnable=0（算法关闭）"
        )
        publishRuntimeStateWithHttpFallback("动作组维护开关延迟关闭完成")
    }

    private fun markActionGroupFailure(actionName: String, result: ActionGroupResult) {
        if (result is ActionGroupResult.Failed) {
            LogUtils.e("【失败原因】$actionName 失败：${result.message}")
        }
        markDeviceErrorAndUpload(
            FaultCode.ACTION_GROUP_FAILED,
            formatActionGroupFailureReasonForUpload(
                actionName = actionName,
                result = result,
                resourceLocks = actionGroupLocks.snapshot()
            )
        )
    }

    private fun enterActionGroupMaintenance(
        reason: String,
        code: FaultCode = FaultCode.ACTION_GROUP_FAILED,
        preserveMoveStatus: Boolean = false
    ) {
        markDeviceErrorAndUpload(code, reason)
        val config = AppConfig.getAppConfig()
        if (preserveMoveStatus || hasBlockingSupplementPrefetchEvidence()) {
            logW(LOG_ACTION_GROUP, "进入维护时保留双 lane 恢复证据和 moveStatus：reason=$reason")
        } else {
            config.moveStatus = 0
        }
        config.onlineStatus = 3
        config.isEnable = 0
        AppConfig.saveAppConfig(config)
        LogUtils.e(
            "【动作组故障锁存】动作组进入维护收口完成：onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}，" +
                "errorStatus（设备故障状态）=${config.errorStatus}(2=通信/硬件级故障)，" +
                "isEnable（烤肠算法开关）=${config.isEnable}(0=关闭)，moveStatus（机械动作状态）=${moveStatusDesc(config.moveStatus)}，" +
                "reason=$reason"
        )
    }

    private fun orderEventStatusDesc(status: Int): String = when (status) {
        1 -> "1(开始夹取)"
        ActionGroupOrderStatus.FINISHED -> "2(单次履约完成：out4已收口)"
        5 -> "5(单次履约失败)"
        ActionGroupOrderStatus.CUSTOMER_TIMEOUT_DISCARDED -> "6(历史状态：顾客超时未取已丢弃，4F主流程不再产生)"
        else -> "$status(未知)"
    }

    private fun isSamePendingOrderEvent(
        pending: PendingActionGroupOrderEvent,
        orderContext: ActionGroupOrderContext,
        status: Int
    ): Boolean {
        return pending.status == status &&
            pending.orderContext.orderNo == orderContext.orderNo &&
            pending.orderContext.itemId == orderContext.itemId &&
            pending.orderContext.productId == orderContext.productId &&
            pending.orderContext.tasteCode == orderContext.tasteCode
    }

    private fun savePendingActionGroupOrderEvent(
        orderContext: ActionGroupOrderContext,
        status: Int,
        panNo: Int,
        discarded: Boolean,
        failureMetadata: cn.niuyannet.kaochang.android.services.actiongroup.OrderFailureMetadata? = null
    ) {
        actionGroupOrderEventStore.save(
            PendingActionGroupOrderEvent(
                orderContext = orderContext,
                status = status,
                panNo = panNo,
                discarded = discarded,
                failureReason = failureMetadata?.failureReason,
                deliveryEvidence = failureMetadata?.deliveryEvidence,
                out1Dispatched = failureMetadata?.out1Dispatched
            )
        )
        logW(
            LOG_ACTION_GROUP,
            "已持久化动作组订单终态待补偿事件：orderNo=${orderContext.orderNo}，" +
                "itemId=${orderContext.itemId}，panNo=$panNo，status=${orderEventStatusDesc(status)}"
        )
    }

    fun reportActionGroupOrderTerminalEvent(
        orderContext: ActionGroupOrderContext,
        status: Int,
        source: String,
        failureMetadata: cn.niuyannet.kaochang.android.services.actiongroup.OrderFailureMetadata? = null
    ): Boolean {
        if (!orderContext.isValid()) {
            logE(LOG_ORDER, "动作组订单终态上报失败：订单上下文不完整，source=$source，orderContext=$orderContext")
            return false
        }
        val ok = SendServerHelper.publishServiceUpdateOrder(
            ActionGroupOrderEventPayloadBuilder.build(
                orderContext = orderContext,
                status = status,
                failureReason = failureMetadata?.failureReason,
                deliveryEvidence = failureMetadata?.deliveryEvidence,
                out1Dispatched = failureMetadata?.out1Dispatched
            )
        )
        if (ok) {
            logD(
                LOG_ORDER,
                "动作组订单终态已进入 MQTT 发送队列：source=$source，orderNo=${orderContext.orderNo}，" +
                    "itemId=${orderContext.itemId}，productId=${orderContext.productId}，" +
                    "tasteCode=${orderContext.tasteCode}，status=${orderEventStatusDesc(status)}"
            )
            val pending = actionGroupOrderEventStore.load()
            if (pending != null && isSamePendingOrderEvent(pending, orderContext, status)) {
                actionGroupOrderEventStore.clear()
                logD(LOG_ACTION_GROUP, "已清理动作组订单终态待补偿事件：orderNo=${orderContext.orderNo}，itemId=${orderContext.itemId}")
            }
        } else {
            logE(
                LOG_ORDER,
                "动作组订单终态进入 MQTT 发送队列失败，保留待补偿事件：source=$source，" +
                    "orderNo=${orderContext.orderNo}，itemId=${orderContext.itemId}，status=${orderEventStatusDesc(status)}"
            )
        }
        return ok
    }

    private fun reportActionGroupOrderStartedEvent(orderContext: ActionGroupOrderContext, panNo: Int) {
        if (!orderContext.isValid()) return
        val ok = SendServerHelper.publishServiceUpdateOrder(
            ActionGroupOrderEventPayloadBuilder.build(orderContext, ActionGroupOrderStatus.DELIVERING)
        )
        if (ok) {
            logD(
                LOG_ORDER,
                "out1 命令已下发，开始夹取事件已入队：orderNo=${orderContext.orderNo}，" +
                    "itemId=${orderContext.itemId}，panNo=$panNo"
            )
        } else {
            logE(
                LOG_ORDER,
                "out1 命令已下发，但开始夹取事件入队失败：orderNo=${orderContext.orderNo}，" +
                    "itemId=${orderContext.itemId}，panNo=$panNo"
            )
        }
    }

    fun savePendingOrderErrorEventAndTryFlush(
        orderContext: ActionGroupOrderContext,
        panNo: Int,
        source: String,
        failureMetadata: cn.niuyannet.kaochang.android.services.actiongroup.OrderFailureMetadata? = null
    ): Boolean {
        if (!orderContext.isValid()) {
            logE(LOG_ORDER, "出餐失败订单终态补偿保存失败：订单上下文不完整，source=$source，orderContext=$orderContext")
            return false
        }
        savePendingActionGroupOrderEvent(
            orderContext = orderContext,
            status = ActionGroupOrderStatus.ERROR,
            panNo = panNo,
            discarded = false,
            failureMetadata = failureMetadata
        )
        return flushPendingActionGroupOrderEvent(source)
    }

    private fun flushPendingActionGroupOrderEvent(reason: String): Boolean {
        val pending = actionGroupOrderEventStore.load() ?: return true
        logW(
            LOG_ACTION_GROUP,
            "发现动作组订单终态待补偿事件，准备补发：reason=$reason，orderNo=${pending.orderContext.orderNo}，" +
                "itemId=${pending.orderContext.itemId}，panNo=${pending.panNo}，status=${orderEventStatusDesc(pending.status)}"
        )
        return reportActionGroupOrderTerminalEvent(
            orderContext = pending.orderContext,
            status = pending.status,
            source = "动作组恢复补偿:$reason",
            failureMetadata = pending.failureReason?.let { failureReason ->
                cn.niuyannet.kaochang.android.services.actiongroup.OrderFailureMetadata(
                    failureReason = failureReason,
                    deliveryEvidence = pending.deliveryEvidence
                        ?: cn.niuyannet.kaochang.android.services.actiongroup.DeliveryEvidence.UNKNOWN,
                    out1Dispatched = pending.out1Dispatched
                )
            }
        )
    }

    private fun persistOutTerminalEventFromTelemetry(
        telemetry: ActionGroupTelemetry,
        orderContext: ActionGroupOrderContext?,
        panNo: Int
    ) {
        if (
            telemetry.actionName != "out4" ||
            telemetry.eventPhase != AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED ||
            orderContext == null ||
            !orderContext.isValid()
        ) {
            return
        }
        val status = when (telemetry.errorCode) {
            null -> ActionGroupOrderStatus.FINISHED
            AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED -> ActionGroupOrderStatus.ERROR
            else -> return
        }
        if (ResetCloseoutPolicy.isConfirmedOut4Delivery(telemetry)) {
            val evidenceSaved = withFaultHeatTransitionLock {
                ResetCloseoutStore.sharedPreferences().confirmOut4Durable(
                    orderContext.orderNo,
                    orderContext.itemId.toLong()
                )
            }
            if (!evidenceSaved) {
                enterActionGroupMaintenance(
                    "out4 已成功但复位退款证据落盘失败：orderNo=${orderContext.orderNo}，itemId=${orderContext.itemId}",
                    preserveMoveStatus = true
                )
                return
            }
        }
        savePendingActionGroupOrderEvent(
            orderContext = orderContext,
            status = status,
            panNo = panNo,
            discarded = false
        )
    }

    suspend fun ensureActionGroupReadyForOrderDispatch(reason: String): Boolean {
        val modbusConnected = VMModbusHelper.connectStatus()
        normalizePausedSupplementHandoff()
        if (modbusConnected && SupplementPauseController.shouldPauseForOrder() && hasPausedSupplementContinuation()) {
            logI(
                LOG_ACTION_GROUP,
                "订单出餐暂不接管已暂停补肠会话，保留续作检查点：" +
                    "session=${pausedSupplementSessionStore.load()?.sessionId}，reason=$reason"
            )
            return true
        }
        val hasPendingSession = actionGroupSessionStore.load() != null ||
            panMoveJournalStore.load() != null ||
            discardJournalStore.load() != null
        val resourceLocksIdle = actionGroupLocks.isIdle()
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = modbusConnected,
            recoveryVerified = actionGroupRecoveryVerified,
            hasPendingSession = hasPendingSession,
            resourceLocksIdle = resourceLocksIdle
        )
        if (decision == ActionGroupOrderRecoveryDecision.FAST_PATH) {
            lastActionGroupRecoveryFailureReason = null
            logD(
                LOG_ACTION_GROUP,
                "订单出餐动作组恢复短路：Modbus 已连接，恢复已验证，无本地会话且资源锁空闲，" +
                    "reason=$reason，resourceLocks=${actionGroupLocks.snapshot()}"
            )
            return true
        }
        logD(
            LOG_ACTION_GROUP,
            "订单出餐动作组恢复门控未短路：decision=$decision，" +
                "modbusConnected=$modbusConnected，recoveryVerified=$actionGroupRecoveryVerified，" +
                "hasPendingSession=$hasPendingSession，resourceLocksIdle=$resourceLocksIdle，" +
                "resourceLocks=${actionGroupLocks.snapshot()}，reason=$reason"
        )
        return recoverActionGroupStateAfterModbusReady(reason)
    }

    suspend fun recoverActionGroupStateAfterModbusReady(
        reason: String,
        stopAfterParallelSessionHandled: Boolean = false
    ): Boolean {
        if (!VMModbusHelper.connectStatus()) {
            val evidence = buildActionGroupRecoveryResidueEvidence(reason)
            val detail = "动作组恢复早退：Modbus 未连接，reason=$reason，evidence=${evidence.summary()}"
            lastActionGroupRecoveryFailureReason = detail
            invalidateActionGroupRecoveryVerification(detail)
            enterActionGroupMaintenance(detail, FaultCode.MODBUS_COMM_DROP)
            return false
        }
        val autoClaimCloseout = tryAutoCloseStaleOrderDeliveryClaim("$reason:claim-only自动收口")
        if (autoClaimCloseout != null) {
            if (!autoClaimCloseout.ok) {
                enterActionGroupMaintenance(
                    "订单出餐 claim 自动收口失败：${autoClaimCloseout.reason}，reason=$reason",
                    preserveMoveStatus = true
                )
                return false
            }
            logW(LOG_ORDER, "订单出餐 claim 自动收口完成：${autoClaimCloseout.reason}，reason=$reason")
        }
        val initialClaimBlockReason = withFaultHeatTransitionLock {
            automaticRecoveryOrderClaimBlockReason()
        }
        if (initialClaimBlockReason != null) {
            enterActionGroupMaintenance(
                "订单出餐 claim 阻断通用动作组恢复：$initialClaimBlockReason，reason=$reason",
                preserveMoveStatus = true
            )
            return false
        }
        discardJournalStore.load()?.let { journal ->
            enterActionGroupMaintenance(
                "发现未收口丢弃 journal，物理结果未知，拒绝进入通用动作组恢复：" +
                    "owner=${journal.owner}，pan=${journal.positionSn}，checkpoint=${journal.checkpoint}",
                preserveMoveStatus = true
            )
            return false
        }
        val doubleLaneSession = when (val read = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> null
            is DurableEvidenceReadResult.Valid -> read.value
            is DurableEvidenceReadResult.Corrupted -> {
                enterActionGroupMaintenance(
                    "双 lane 会话损坏，禁止进入通用动作组恢复：fingerprint=${read.fingerprint}，reason=${read.reason}"
                )
                return false
            }
        }
        runCatching { panMoveJournalStore.load() }
            .getOrElse { error ->
                enterActionGroupMaintenance("搬盘 journal 读取失败，禁止恢复：${error.message}")
                return false
        }
        if (doubleLaneSession != null) {
            enterActionGroupMaintenance(
                "已有双 lane 会话仅允许人工恢复：session=${doubleLaneSession.sessionId}，" +
                    "禁止自动接管、续作、释放资源或修改 moveStatus",
                preserveMoveStatus = true
            )
            return false
        }
        val guardedResidue = withFaultHeatTransitionLock {
            AppConfig.getAppConfig().lowerPlatformEpisodes.any {
                it.lifecycle == "OPEN" && it.closeoutGuarded
            }
        }
        if (guardedResidue) {
            enterActionGroupMaintenance(
                "检测到串行补肠 guarded 收口残留，仅允许精确人工恢复；禁止通用动作组自动接管",
                preserveMoveStatus = true
            )
            return false
        }
        // 双 lane 会话已在上方 fail-closed，不能自动续作或释放。
        val panMoveRecovered = runCatching { recoverPanMoveJournalAfterModbusReady(reason) }
            .getOrElse { error ->
                enterActionGroupMaintenance(
                    "搬盘 journal 启动恢复异常，保持维护门禁：reason=$reason，${error.message}"
                )
                false
            }
        if (!panMoveRecovered) {
            return false
        }
        normalizePausedSupplementHandoff()
        if (actionGroupSessionStore.load() == null && hasPausedSupplementSession()) {
            if (SupplementPauseController.shouldPauseForOrder() || AppConfig.getAppConfig().onlineStatus != 1) {
                logI(LOG_SUPPLEMENT, "动作组恢复检测到订单暂停补肠会话，暂缓续作：reason=$reason")
                return true
            }
            return resumePausedSupplementAfterOrder(reason)
        }
        val client = createAtomicActionClient()
        val result = ActionGroupRecoveryBootstrap(
            client = client,
            sessionStore = actionGroupSessionStore,
            resourceLocks = actionGroupLocks,
            logger = { message -> logD(LOG_ACTION_GROUP, message) },
            closeKnownIsolatedTerminalFailure = ::closeKnownIsolatedSupplementFailure,
            clearSessionIfCurrent = ::clearActionGroupSessionIfCurrent,
            abandonPreparedClaim = { session ->
                mechanicalMoveClaimCoordinator(actionGroupClaimOwnerStore())
                    .abandonPrepared(session.sessionId, setOf(0, 1, 2), 0) is
                    MechanicalMoveReleaseResult.Released
            },
            moveStatusProvider = mechanicalMoveStatusStore::load,
            allowAutomaticTakeover = {
                withFaultHeatTransitionLock {
                    AppConfig.getAppConfig().lowerPlatformEpisodes.none {
                        it.lifecycle == "OPEN" && it.closeoutGuarded
                    }
                }
            },
            recheckAutomaticTakeover = { expectedSession ->
                withFaultHeatTransitionLock {
                    val config = AppConfig.getAppConfig()
                    if (automaticRecoveryOrderClaimBlockReason() != null ||
                        hasGuardedSupplementCloseoutResidue(config)
                    ) {
                        false
                    } else {
                        val currentSession = actionGroupSessionStore.load()
                        currentSession != null &&
                            currentSession.sessionId == expectedSession.sessionId &&
                            currentSession.mechanicalClaimRevision == expectedSession.mechanicalClaimRevision &&
                            currentSession.type == expectedSession.type &&
                            currentSession.resourceMask == expectedSession.resourceMask
                    }
                }
            },
            performAutomaticRecoveryMutation = { expectedSession, mutation ->
                withFaultHeatTransitionLock {
                    val config = AppConfig.getAppConfig()
                    val currentSession = actionGroupSessionStore.load()
                    val allowed = automaticRecoveryOrderClaimBlockReason() == null &&
                        !hasGuardedSupplementCloseoutResidue(config) &&
                        currentSession != null &&
                        currentSession.sessionId == expectedSession.sessionId &&
                        currentSession.mechanicalClaimRevision == expectedSession.mechanicalClaimRevision &&
                        currentSession.type == expectedSession.type &&
                        currentSession.resourceMask == expectedSession.resourceMask
                    if (!allowed) {
                        false
                    } else {
                        mutation()
                        true
                    }
                }
            }
        ).recover()
        return when (result) {
            ActionGroupRecoveryResult.Idle -> {
                lastActionGroupRecoveryFailureReason = null
                val idleClaimBlockReason = withFaultHeatTransitionLock {
                    automaticRecoveryOrderClaimBlockReason()?.let { return@withFaultHeatTransitionLock it }
                    val config = AppConfig.getAppConfig()
                    if (config.moveStatus in listOf(1, 2)) {
                        if (AutoMechanicalDispatchPolicy.shouldPreserveDeliveringMoveStatusDuringRecovery(config.onlineStatus, config.moveStatus)) {
                            logW(
                                LOG_ACTION_GROUP,
                                "动作组恢复：下位机无执行中动作，但整单仍在出餐中，保留 moveStatus=2（取肠/出餐中），防止自动调度插队，reason=$reason"
                            )
                        } else {
                            config.moveStatus = 0
                            AppConfig.saveAppConfig(config)
                            logW(LOG_ACTION_GROUP, "动作组恢复：下位机无执行中动作，已清理本地 moveStatus=${config.moveStatus}，reason=$reason")
                        }
                    }
                    null
                }
                if (idleClaimBlockReason != null) {
                    enterActionGroupMaintenance(
                        "订单出餐 claim 在动作组恢复期间出现，拒绝清理忙态：$idleClaimBlockReason，reason=$reason",
                        preserveMoveStatus = true
                    )
                    return false
                }
                flushPendingActionGroupOrderEvent(reason)
                markActionGroupRecoveryVerified(reason)
                true
            }
            is ActionGroupRecoveryResult.StaleSessionCleared -> {
                lastActionGroupRecoveryFailureReason = null
                val session = result.session
                if (session.type == ActionGroupSessionType.OUT_SELL) {
                    val orderContext = session.orderContext
                    if (orderContext != null && orderContext.isValid()) {
                        savePendingActionGroupOrderEvent(
                            orderContext = orderContext,
                            status = ActionGroupOrderStatus.ERROR,
                            panNo = session.sourceNo,
                            discarded = false
                        )
                        flushPendingActionGroupOrderEvent("出餐动作组旧会话收口:$reason")
                    } else {
                        logW(
                            LOG_ACTION_GROUP,
                            "出餐动作组旧会话已清理但缺少有效订单上下文，无法补偿订单终态：" +
                                "reason=$reason，session=$session"
                        )
                    }
                }
                logW(LOG_ACTION_GROUP, "${result.reason}，reason=$reason")
                markActionGroupRecoveryVerified(reason)
                true
            }
            is ActionGroupRecoveryResult.PreparedClaimAbandoned -> {
                lastActionGroupRecoveryFailureReason = null
                logW(
                    LOG_ACTION_GROUP,
                    "动作组 PREPARED 会话未下发且寄存器全空闲，已按 owner 安全清理：" +
                        "sessionId=${result.session.sessionId}，reason=$reason"
                )
                markActionGroupRecoveryVerified(reason)
                true
            }
            is ActionGroupRecoveryResult.MaintenanceRequired -> {
                val evidence = buildActionGroupRecoveryResidueEvidence(reason)
                val manualClearDecision = ActionGroupManualResidueClearPolicy.evaluate(evidence)
                val detail = "动作组恢复失败，设备进入维护：reason=$reason，detail=${result.reason}，" +
                    "recoverableResidueSession=${result.recoverableResidueSession?.sessionId ?: "无"}，" +
                    "manualClearAllowed=${manualClearDecision.canShowManualClear}，" +
                    "manualClearReason=${manualClearDecision.reason}，evidence=${evidence.summary()}"
                lastActionGroupRecoveryFailureReason = detail
                invalidateActionGroupRecoveryVerification(detail)
                enterActionGroupMaintenance(detail)
                false
            }
            is ActionGroupRecoveryResult.IsolatedSupplementFailurePreserved -> {
                lastActionGroupRecoveryFailureReason = null
                markActionGroupRecoveryVerified("已保持下平台半故障：$reason")
                publishRuntimeStateWithHttpFallback("动作组恢复保持下平台半故障")
                logW(
                    LOG_ACTION_GROUP,
                    "动作组恢复保持下平台半故障，不升级整机维护：" +
                        "action=${result.actionName}，code=${AtomicActionExecutor.formatCode(result.errorCode)}，" +
                        "sessionId=${result.session.sessionId}，reason=$reason"
                )
                true
            }
            is ActionGroupRecoveryResult.TakeoverFinished -> {
                val handled = handleRecoveredActionGroupSession(result.session, result.actionResult, reason)
                if (handled) {
                    lastActionGroupRecoveryFailureReason = null
                    markActionGroupRecoveryVerified(reason)
                } else if (lastActionGroupRecoveryFailureReason == null) {
                    lastActionGroupRecoveryFailureReason =
                        "动作组恢复接管失败：reason=$reason，sessionId=${result.session.sessionId}，result=${result.actionResult}"
                }
                if (!handled) {
                    invalidateActionGroupRecoveryVerification(
                        lastActionGroupRecoveryFailureReason
                            ?: "动作组恢复接管失败：reason=$reason，sessionId=${result.session.sessionId}"
                    )
                }
                handled
            }
        }
    }

    private fun automaticRecoveryOrderClaimBlockReason(): String? = try {
        orderDeliveryClaimStore.load()?.let { claim ->
            "orderNo=${claim.orderNo}，owner=${claim.owner}，revision=${claim.revision}"
        }
    } catch (error: Throwable) {
        "claim 读取失败或损坏：${error.message}"
    }

    private fun recoverPanMoveJournalAfterModbusReady(reason: String): Boolean {
        val journal = panMoveJournalStore.load() ?: return true
        if (actionGroupSessionStore.load() != null) {
            enterActionGroupMaintenance(
                "搬盘 journal 与 IN/OUT session 同时存在，拒绝猜测动作先后：reason=$reason，moveId=${journal.moveId}"
            )
            return false
        }
        val pans = KaoPanHelper.getKaoPanList()
        val source = pans.firstOrNull { it.positionSn == journal.sourcePositionSn }
        val target = pans.firstOrNull { it.positionSn == journal.targetPositionSn }
        if (source == null || target == null) {
            enterActionGroupMaintenance("搬盘 journal 恢复找不到源盘或目标盘：reason=$reason，journal=$journal")
            return false
        }
        val panAlreadyApplied = !source.isHasSausage && target.isHasSausage
        val bindingAlreadyCleared = journal.originBatchId == null || journal.originBatchType == null ||
            supplementBatchIntentRepository.loadActive()
                ?.targets
                ?.none {
                    it.originBatchId == journal.originBatchId &&
                        it.originBatchType == journal.originBatchType &&
                        it.positionSn == journal.sourcePositionSn
                } != false
        val moveResult = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_move_result)
        return when (
            PanMoveRecoveryPolicy.decide(
                journal = journal,
                moveResult = moveResult,
                panAlreadyApplied = panAlreadyApplied,
                bindingAlreadyCleared = bindingAlreadyCleared
            )
        ) {
            PanMoveRecoveryDecision.CLEAR_SETTLED_JOURNAL -> {
                KaoPanHelper.saveKaoPanListDurable(pans)
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
                check(panMoveJournalStore.clearDurable()) { "已收口搬盘 journal 清理失败" }
                logW(LOG_PAN_MOVE, "启动恢复清理已收口搬盘 journal：reason=$reason，journal=$journal")
                true
            }
            PanMoveRecoveryDecision.FINALIZE_SUCCESS -> {
                if (!panAlreadyApplied) {
                    target.isHasSausage = true
                    target.isHasGrilling = false
                    target.taste = KaoPanHelper.copyResolvedTaste(source.taste) ?: source.taste
                    target.holdingTime = journal.targetHoldingTime ?: source.holdingTime
                    target.status = KaoPan.STATUS_SELLABLE_KEEP_WARM
                    source.cmdStatusMove = 0
                    source.isHasSausage = false
                    source.startTime = 0
                    source.status = KaoPan.STATUS_EMPTY
                }
                KaoPanHelper.saveKaoPanListDurable(pans)
                if (!bindingAlreadyCleared && journal.originBatchId != null && journal.originBatchType != null) {
                    check(
                        supplementBatchIntentRepository.markReturned(
                            originBatchId = journal.originBatchId,
                            originBatchType = journal.originBatchType,
                            sourcePositionSn = journal.sourcePositionSn,
                            returnPositionSn = journal.targetPositionSn
                        )
                    ) { "恢复搬盘时精确 binding 收口失败" }
                }
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
                check(panMoveJournalStore.clearDurable()) { "恢复搬盘 journal 清理失败" }
                logW(LOG_PAN_MOVE, "启动恢复完成搬盘耐久收口：reason=$reason，journal=$journal")
                true
            }
            PanMoveRecoveryDecision.WAIT_FOR_RUNNING,
            PanMoveRecoveryDecision.REQUIRE_MANUAL_CONFIRMATION -> {
                enterActionGroupMaintenance(
                    "搬盘 journal 物理结果不足以自动收口，需人工确认且禁止重发202：" +
                        "reason=$reason，result203=$moveResult，journal=$journal"
                )
                false
            }
        }
    }

    suspend fun buildActionGroupRecoveryResidueEvidence(reason: String): ActionGroupRecoveryResidueEvidence {
        val modbusConnected = VMModbusHelper.connectStatus()
        val config = AppConfig.getAppConfig()
        val session = actionGroupSessionStore.load()
        val legacyActionStatus201 = if (modbusConnected) {
            VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
        } else {
            null
        }
        val reader = if (modbusConnected) createAtomicActionClient() as? AtomicActionStateReader else null
        val resultStates = ActionGroupRecoveryBootstrap.allSpecs.map { spec ->
            if (reader == null) {
                ActionGroupRegisterState(
                    actionName = spec.name,
                    resultRegister = spec.resultRegister,
                    value = null,
                    errorMessage = if (modbusConnected) "AtomicActionClient 不支持读取结果寄存器" else "Modbus 未连接"
                )
            } else {
                when (val result = reader.readResult(spec)) {
                    is AtomicReadResult.Success -> ActionGroupRegisterState(
                        actionName = spec.name,
                        resultRegister = spec.resultRegister,
                        value = result.value
                    )
                    is AtomicReadResult.Failure -> ActionGroupRegisterState(
                        actionName = spec.name,
                        resultRegister = spec.resultRegister,
                        value = null,
                        errorMessage = result.message
                    )
                }
            }
        }
        return ActionGroupRecoveryResidueEvidence(
            reason = reason,
            modbusConnected = modbusConnected,
            legacyActionStatus201 = legacyActionStatus201,
            moveStatus = config.moveStatus,
            session = session,
            resultStates = resultStates
        )
    }

    private suspend fun buildEmergencyResetIdleSnapshot(reason: String): EmergencyResetIdleSnapshot =
        EmergencyResetIdleSnapshot(
            actionGroupEvidence = buildActionGroupRecoveryResidueEvidence(reason),
            panMoveResult203 = if (VMModbusHelper.connectStatus()) {
                VMModbusHelper.readHoldingRegisters(modbus_address, action_address_move_result)
            } else {
                null
            }
        )

    suspend fun confirmClearActionGroupRecoveryResidue(reason: String): Boolean {
        return confirmManualDeviceRecoveryResidue(
            reason,
            hasProcessingOrder = OrderStateManager.hasProcessingOrder()
        ).ok
    }

    suspend fun confirmManualDeviceRecoveryResidue(
        reason: String,
        hasProcessingOrder: Boolean,
        emergencyResetAdministratorConfirmed: Boolean = false
    ): ManualDeviceRecoveryCloseoutResult {
        val emergencyCloseout = closeMechanicalStateAfterEmergencyReset(
            administratorConfirmed = emergencyResetAdministratorConfirmed,
            reason = reason
        )
        if (emergencyCloseout != null) {
            return ManualDeviceRecoveryCloseoutResult(
                emergencyCloseout.ok,
                emergencyCloseout.reason
            )
        }

        val orderClaim = try {
            withFaultHeatTransitionLock { orderDeliveryClaimStore.load() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return ManualDeviceRecoveryCloseoutResult(false, "订单 claim 读取失败或损坏：${error.message}")
        }
        if (orderClaim != null) {
            val closeout = closeStaleOrderDeliveryClaimForMaintenance(orderClaim, reason)
            return ManualDeviceRecoveryCloseoutResult(closeout.ok, closeout.reason)
        }
        val evidence = buildActionGroupRecoveryResidueEvidence(reason)
        return withFaultHeatTransitionLock {
            confirmManualDeviceRecoveryResidueLocked(reason, hasProcessingOrder, evidence)
        }
    }

    private fun confirmManualDeviceRecoveryResidueLocked(
        reason: String,
        hasProcessingOrder: Boolean,
        sampledEvidence: ActionGroupRecoveryResidueEvidence
    ): ManualDeviceRecoveryCloseoutResult {
        check(Thread.holdsLock(faultHeatTransitionLock)) { "人工恢复必须持有统一状态锁" }
        val config = AppConfig.getAppConfig()
        if (LowerPlatformEpisodeReducer.hasOpenGuardedIsolation(config)) {
            return ManualDeviceRecoveryCloseoutResult(false, "存在补肠 guarded 残留，请使用下平台专用安全确认入口")
        }
        val prefetchRead = readSupplementPrefetchEvidence()
        val prefetchSession = (prefetchRead as? DurableEvidenceReadResult.Valid)?.value
        val evidence = sampledEvidence.copy(
            moveStatus = config.moveStatus,
            session = actionGroupSessionStore.load()
        )
        val decision = ManualDeviceRecoveryCloseoutPolicy.evaluate(
            evidence = evidence,
            hasProcessingOrder = hasProcessingOrder,
            hasPanMoveJournal = panMoveJournalStore.load() != null,
            hasDiscardJournal = discardJournalStore.load() != null,
            durableEvidenceCorrupted = prefetchRead is DurableEvidenceReadResult.Corrupted
        )
        if (!decision.canCloseout) {
            logW(LOG_ACTION_GROUP, "人工恢复本地残留被拒绝：reason=$reason，detail=${decision.reason}，evidence=${evidence.summary()}")
            return ManualDeviceRecoveryCloseoutResult(false, decision.reason)
        }

        val actionSession = actionGroupSessionStore.load()
        val pausedSession = pausedSupplementSessionStore.load()
        val pendingIntent = supplementBatchIntentRepository.loadActive()
        if (pendingIntent?.returnBindingConflictActive == true) {
            return ManualDeviceRecoveryCloseoutResult(
                false,
                "回搬目标存在重复所有者，通用人工恢复不能解除专用门禁"
            )
        }
        val expectedOwners = listOfNotNull(
            prefetchSession?.let { it.sessionId to it.resourceMask },
            actionSession?.let { it.sessionId to it.resourceMask },
            pausedSession?.let { it.sessionId to it.resourceMask }
        ).toMap()
        val unknownOwners = actionGroupLocks.ownerIds() - expectedOwners.keys
        if (unknownOwners.isNotEmpty()) {
            return ManualDeviceRecoveryCloseoutResult(false, "存在未确认的资源占用，请重新检查设备状态")
        }
        val mismatchedOwner = expectedOwners.entries.firstOrNull { (owner, expectedMask) ->
            actionGroupLocks.ownedMask(owner)?.let { it != expectedMask } == true
        }
        if (mismatchedOwner != null) {
            return ManualDeviceRecoveryCloseoutResult(false, "本地资源占用与恢复记录不一致，请重新检查设备状态")
        }
        if (prefetchSession != null && pendingIntent?.requiresAnyManualConfirmation() == true) {
            val marked = runCatching {
                supplementBatchIntentRepository.markManualConfirmStrategyPrefetchPreserveRemaining(
                    pendingIntent.batchId,
                    reason
                )
            }.getOrElse { error ->
                logW(LOG_SUPPLEMENT, "人工恢复标记双 lane 保留策略失败：${error.message}")
                return ManualDeviceRecoveryCloseoutResult(false, "本地回搬恢复状态保存失败，请重试")
            }
            if (!marked) {
                return ManualDeviceRecoveryCloseoutResult(false, "本地回搬恢复状态已变化，请重试")
            }
        }

        if (pendingIntent?.requiresAnyManualConfirmation() == true) {
            val confirmed = runCatching {
                supplementBatchIntentRepository.confirmManualSafetyPreservingRemainingTargets(reason)
            }.getOrNull()
            if (confirmed == null) {
                return ManualDeviceRecoveryCloseoutResult(false, "回搬任务状态尚未收口，请重试")
            }
        }

        if (actionSession?.type == ActionGroupSessionType.OUT_SELL) {
            actionSession.orderContext?.takeIf { it.isValid() }?.let { orderContext ->
                savePendingActionGroupOrderEvent(
                    orderContext = orderContext,
                    status = ActionGroupOrderStatus.ERROR,
                    panNo = actionSession.sourceNo,
                    discarded = false
                )
            }
        }

        val oldMoveStatus = config.moveStatus
        val persistIdle = {
            config.moveStatus = 0
            AppConfig.saveAppConfigDurable(config)
        }
        if (prefetchSession != null) {
            val completion = SupplementPipelineCompletionCoordinator.complete(
                persistIdle = persistIdle,
                publish = { publishRuntimeStateWithHttpFallback("人工恢复双 lane 本地残留") },
                clearSession = {
                    supplementPrefetchSessionRepository.clearIfRevision(
                        prefetchSession.sessionId,
                        prefetchSession.revision
                    )
                },
                releaseLock = { actionGroupLocks.release(prefetchSession.sessionId) },
                restoreBusy = {
                    config.moveStatus = if (oldMoveStatus != 0) oldMoveStatus else 1
                    AppConfig.saveAppConfigDurable(config)
                },
                publishRestoredBusy = {
                    publishRuntimeStateWithHttpFallback("人工恢复双 lane CAS 失败，恢复忙态")
                }
            )
            if (completion is SupplementPipelineCompletionResult.Failed) {
                logW(LOG_ACTION_GROUP, "人工恢复双 lane 本地残留失败：${completion.reason}")
                return ManualDeviceRecoveryCloseoutResult(false, "本地恢复状态已变化，请重试")
            }
        } else {
            runCatching(persistIdle).getOrElse { error ->
                config.moveStatus = oldMoveStatus
                logW(LOG_ACTION_GROUP, "人工恢复保存空闲状态失败：${error.message}")
                return ManualDeviceRecoveryCloseoutResult(false, "本地恢复状态保存失败，请重试")
            }
        }

        actionSession?.let {
            actionGroupSessionStore.clear()
            actionGroupLocks.release(it.sessionId)
        }
        pausedSession?.let {
            pausedSupplementSessionStore.clear()
            actionGroupLocks.release(it.sessionId)
        }
        val pendingOrderNo = listOfNotNull(
            prefetchSession?.pendingOrderNo,
            actionSession?.orderContext?.orderNo,
            actionSession?.orderId,
            pausedSession?.orderId
        ).firstOrNull { it.isNotBlank() }
        if (!pendingOrderNo.isNullOrBlank() && SupplementPauseController.currentOrderNo() == pendingOrderNo) {
            SupplementPauseController.clearOrderPause(pendingOrderNo)
        } else if (pendingOrderNo.isNullOrBlank()) {
            SupplementPauseController.currentOrderNo()?.let { currentOrderNo ->
                SupplementPauseController.clearOrderPause(currentOrderNo)
            }
        }
        flushPendingActionGroupOrderEvent("人工恢复本地残留:$reason")
        lastActionGroupRecoveryFailureReason = null
        markActionGroupRecoveryVerified("人工恢复本地残留：$reason")
        logW(LOG_ACTION_GROUP, "人工恢复本地残留完成：reason=$reason，evidence=${evidence.summary()}")
        return ManualDeviceRecoveryCloseoutResult(true, decision.reason)
    }

    suspend fun confirmGuardedSerialSupplementFailureRecovery(reason: String): ManualDeviceRecoveryCloseoutResult {
        val firstEvidence = buildActionGroupRecoveryResidueEvidence("$reason:第一次空闲复核")
        delay(500L)
        val secondEvidence = buildActionGroupRecoveryResidueEvidence("$reason:第二次空闲复核")
        return withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            val key = LowerPlatformEpisodeReducer.singleOpenGuardedIsolationKey(config)
                ?: return@withFaultHeatTransitionLock ManualDeviceRecoveryCloseoutResult(
                    false,
                    "没有唯一且有效的补肠 guarded 恢复记录"
                )
            val prefetchRead = readSupplementPrefetchEvidence()
            if (prefetchRead !is DurableEvidenceReadResult.Absent ||
                panMoveJournalStore.load() != null ||
                discardJournalStore.load() != null
            ) {
                return@withFaultHeatTransitionLock ManualDeviceRecoveryCloseoutResult(
                    false,
                    "存在并行补肠或搬盘/丢弃恢复记录，禁止混合收口"
                )
            }
            val currentSession = actionGroupSessionStore.load()
            val hasProcessingOrder = OrderStateManager.hasProcessingOrder()
            val evidenceDecision = { evidence: ActionGroupRecoveryResidueEvidence ->
                ManualDeviceRecoveryCloseoutPolicy.evaluate(
                    evidence = evidence.copy(
                        moveStatus = config.moveStatus,
                        session = currentSession
                    ),
                    hasProcessingOrder = hasProcessingOrder,
                    hasPanMoveJournal = false,
                    hasDiscardJournal = false,
                    durableEvidenceCorrupted = false
                )
            }
            val firstDecision = evidenceDecision(firstEvidence)
            val secondDecision = evidenceDecision(secondEvidence)
            if (!firstDecision.canCloseout || !secondDecision.canCloseout) {
                return@withFaultHeatTransitionLock ManualDeviceRecoveryCloseoutResult(
                    false,
                    "下位机连续空闲证据不足：${if (!firstDecision.canCloseout) firstDecision.reason else secondDecision.reason}"
                )
            }
            val coordinator = GuardedSupplementCloseoutCoordinator(
                transitionLock = faultHeatTransitionLock,
                sessionStore = actionGroupSessionStore,
                resourceLocks = actionGroupLocks,
                moveStatusProvider = mechanicalMoveStatusStore::load,
                guardedEpisodeExists = { expected ->
                    LowerPlatformEpisodeReducer.singleOpenGuardedIsolationKey(config) == expected
                },
                closeEpisode = { expected ->
                    LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
                        LowerPlatformEpisodeReducer.recoverGuardedIsolationIfKey(
                            config,
                            expected.sessionId,
                            expected.claimRevision,
                            expected.actionName
                        )
                    }.value
                }
            )
            when (val result = coordinator.close(key) {
                when (val moveStatus = mechanicalMoveStatusStore.load()) {
                    OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING ->
                        mechanicalMoveClaimCoordinator(actionGroupCloseoutClaimOwnerStore(key.actionName)).release(
                            owner = key.sessionId,
                            expectedMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING,
                            idleMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
                            expectedRevision = key.claimRevision
                        )
                    OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING ->
                        mechanicalMoveClaimCoordinator(actionGroupCloseoutClaimOwnerStore(key.actionName))
                            .releaseOwnerKeepingMoveStatus(
                                owner = key.sessionId,
                                expectedMoveStatuses = setOf(OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING),
                                expectedRevision = key.claimRevision
                            )
                    else -> MechanicalMoveReleaseResult.Rejected(
                        "当前 moveStatus=${moveStatusDesc(moveStatus)} 不允许 guarded 收口"
                    )
                }
            }) {
                GuardedSupplementCloseoutResult.Closed -> {
                    serialSupplementFailureProof = null
                    lastActionGroupRecoveryFailureReason = null
                    markActionGroupRecoveryVerified("人工精确恢复补肠 guarded 残留：$reason")
                    publishRuntimeStateWithHttpFallback("人工精确恢复补肠 guarded 残留")
                    ManualDeviceRecoveryCloseoutResult(true, "已精确释放补肠 session、资源和隔离记录")
                }
                is GuardedSupplementCloseoutResult.Rejected -> {
                    logW(LOG_ACTION_GROUP, "人工精确恢复补肠 guarded 残留被拒绝：${result.reason}")
                    ManualDeviceRecoveryCloseoutResult(false, result.reason)
                }
            }
        }
    }

    fun getLastActionGroupRecoveryFailureReason(): String? = lastActionGroupRecoveryFailureReason

    private fun handleRecoveredActionGroupSession(
        session: ActionGroupSession,
        result: ActionGroupResult,
        reason: String
    ): Boolean {
        return when (result) {
            ActionGroupResult.Success -> handleRecoveredSuccess(session, reason)
            ActionGroupResult.Discarded -> handleRecoveredDiscarded(session, reason)
            is ActionGroupResult.Failed -> {
                markActionGroupFailure("动作组恢复接管", result)
                false
            }
        }
    }

    private fun handleRecoveredSuccess(session: ActionGroupSession, reason: String): Boolean {
        return when (session.type) {
            ActionGroupSessionType.IN_SUPPLEMENT -> {
                val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == session.targetNo }
                val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == session.sourceNo }
                if (pan == null || box == null) {
                    enterActionGroupMaintenance(
                        "补肠恢复成功但本地找不到烤盘或肠箱，需人工确认：reason=$reason，session=$session"
                    )
                    return false
                }
                val reconcileDecision = RecoveredSupplementStatePolicy.decide(session, pan, box)
                val reportStock: Boolean
                val reportPan: Boolean
                when (reconcileDecision) {
                    RecoveredSupplementStatePolicy.Decision.ALREADY_APPLIED -> {
                        pan.cmdStatusMove = 0
                        clearOrdinaryBoxTakeStatus(box)
                        reportStock = false
                        reportPan = false
                    }
                    RecoveredSupplementStatePolicy.Decision.CORRECT_BOX_ONLY -> {
                        box.num = session.expectedStockAfter!!
                        pan.cmdStatusMove = 0
                        clearOrdinaryBoxTakeStatus(box)
                        reportStock = true
                        reportPan = false
                    }
                    RecoveredSupplementStatePolicy.Decision.APPLY_PAN_ONLY -> {
                        applySuccessfulSupplementPanStateWithoutBoxDeduction(
                            pan,
                            box,
                            "补肠动作组恢复成功，复用已确认扣箱检查点",
                            config = AppConfig.getAppConfig()
                        )
                        reportStock = false
                        reportPan = true
                    }
                    RecoveredSupplementStatePolicy.Decision.APPLY_PAN_AND_CORRECT_BOX -> {
                        box.num = session.expectedStockAfter!!
                        applySuccessfulSupplementPanStateWithoutBoxDeduction(
                            pan,
                            box,
                            "补肠动作组恢复成功，按持久化检查点校正箱库存",
                            config = AppConfig.getAppConfig()
                        )
                        reportStock = true
                        reportPan = true
                    }
                    RecoveredSupplementStatePolicy.Decision.APPLY_PAN_AND_DEDUCT_BOX -> {
                        if (!applySuccessfulSupplementPanStateIfStockAvailable(
                                pan,
                                box,
                                "补肠动作组恢复成功",
                                config = AppConfig.getAppConfig()
                            )
                        ) {
                            enterActionGroupMaintenance(
                                "补肠恢复成功但来源箱本地库存已为0，盘箱状态不可确认，需人工确认：" +
                                    "reason=$reason，sourceBox=${box.positionSn}，targetPan=${pan.positionSn}"
                            )
                            return false
                        }
                        reportStock = true
                        reportPan = true
                    }
                    RecoveredSupplementStatePolicy.Decision.INCONSISTENT -> {
                        enterActionGroupMaintenance(
                            "补肠恢复结果与本地盘箱状态矛盾，拒绝猜测扣箱或重置计时：" +
                                "reason=$reason，session=$session，boxStock=${box.num}，" +
                                "panHas=${pan.isHasSausage}，panStatus=${pan.status}，startTime=${pan.startTime}"
                        )
                        return false
                    }
                }
                try {
                    KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                    KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                    session.batchId?.takeIf { it.isNotBlank() }?.let { recoveredBatchId ->
                        supplementBatchIntentRepository.markCompleted(recoveredBatchId, pan.positionSn)
                    }
                } catch (e: Exception) {
                    enterActionGroupMaintenance(
                        "补肠恢复成功后的盘箱/binding耐久收口失败，保留session：reason=$reason，${e.message}"
                    )
                    return false
                }
                LowerPlatformRuntimeStateReporter.markSupplyBoxRecoveredAfterSuccessfulTake(
                    sourceBoxNo = box.positionSn,
                    reason = "补肠动作组恢复确认完整成功"
                )
                reportSupplementCompletionPhysicalDeltaIfNeeded(
                    sourceBox = box,
                    targetPan = pan,
                    reportStock = reportStock,
                    reportPan = reportPan,
                    reason = "action_recovery:in:completed:box=${box.positionSn}:pan=${pan.positionSn}",
                    source = "action_recovery_in"
                )
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
                actionGroupSessionStore.clear()
                logW(LOG_ACTION_GROUP, "补肠动作组恢复完成并已回写本地状态：reason=$reason，session=$session")
                true
            }
            ActionGroupSessionType.OUT_SELL,
            ActionGroupSessionType.OUT_MANUAL -> {
                clearRecoveredOutPan(session, reason, discarded = false)
            }
        }
    }

    private fun handleRecoveredDiscarded(session: ActionGroupSession, reason: String): Boolean {
        if (session.type != ActionGroupSessionType.OUT_SELL && session.type != ActionGroupSessionType.OUT_MANUAL) {
            enterActionGroupMaintenance("补肠恢复出现 DISCARDED 结果，协议语义异常：reason=$reason，session=$session")
            return false
        }
        return clearRecoveredOutPan(session, reason, discarded = true)
    }

    private fun clearRecoveredOutPan(
        session: ActionGroupSession,
        reason: String,
        discarded: Boolean
    ): Boolean {
        val orderContext = session.orderContext
        val isManualTake = session.type == ActionGroupSessionType.OUT_MANUAL
        if (!isManualTake && (orderContext == null || !orderContext.isValid())) {
            enterActionGroupMaintenance(
                "出餐恢复完成但本地动作组会话缺少订单上下文，无法补偿回写后端订单状态，需人工确认：" +
                    "reason=$reason，session=$session"
            )
            return false
        }
        if (!isManualTake) {
            savePendingActionGroupOrderEvent(
                orderContext = orderContext!!,
                status = ActionGroupOrderStatus.FINISHED,
                panNo = session.sourceNo,
                discarded = false
            )
        }
        val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == session.sourceNo }
        if (pan == null) {
            enterActionGroupMaintenance("出餐恢复完成但本地找不到源烤盘，需人工确认：reason=$reason，session=$session")
            return false
        }
        applySuccessfulTakePanState(pan)
        if (isManualTake) {
            LowToHighDemandGate.recordNonDemandEmpty(pan.positionSn, "manual_clear")
        }
        try {
            KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            clearReturnBindingForSourceIfAny(pan.positionSn)
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
            actionGroupSessionStore.clear()
        } catch (e: Exception) {
            enterActionGroupMaintenance(
                "出餐恢复成功后的烤盘/binding耐久收口失败，保留session：reason=$reason，${e.message}"
            )
            return false
        }
        refreshTemperatureAfterStablePanMutation("动作组恢复完成清空烤盘：pan=${pan.positionSn}")
        reportOutCompletionPhysicalDelta(
            kaoPan = pan,
            reason = "action_recovery:out:completed:pan=${pan.positionSn}:manual=$isManualTake",
            source = "action_recovery_out"
        )
        val actionText = if (isManualTake) "维护手动取肠" else "出餐"
        val resultText = if (discarded) {
            "历史丢弃结果已按 4F 履约完成收口"
        } else {
            "out4 已收口，本单已处理完成"
        }
        logW(LOG_ACTION_GROUP, "$actionText 动作组恢复完成并已清空烤盘：$resultText，reason=$reason，session=$session")
        if (!isManualTake) {
            flushPendingActionGroupOrderEvent("出餐动作组恢复完成:$reason")
        }
        return true
    }

    private fun buildSelfCleanCommand(positionSn: Int): Int {
        require(positionSn in 1..33) { "positionSn out of range: $positionSn" }
        return 500 + positionSn
    }

    private fun buildSelfCleanFailureReport(
        command: Int,
        targetLabel: String,
        result: ActionExecutionResult
    ): SelfCleanExecutionReport {
        val status201 = when (result) {
            ActionExecutionResult.SUCCESS -> 0
            ActionExecutionResult.RUNNING_TIMEOUT -> 1
            ActionExecutionResult.BUSY_TIMEOUT -> 2
            ActionExecutionResult.COMMAND_REJECTED,
            ActionExecutionResult.DISCONNECTED -> -1
        }
        return SelfCleanExecutionReport(
            success = false,
            command = command,
            targetLabel = targetLabel,
            status201 = status201,
            message = "result=${actionExecutionResultDesc(result)}"
        )
    }

    private fun markSelfCleanFailure(targetLabel: String, result: ActionExecutionResult) {
        val config = AppConfig.getAppConfig()
        config.errorStatus = 2
        config.onlineStatus = 3
        config.errorCode = FaultCode.SELF_CLEAN_FAILED.name
        config.errorCategory = FaultCode.SELF_CLEAN_FAILED.category.display
        config.errorReason = "自清洁失败：target=$targetLabel，result=${actionExecutionResultDesc(result)}"
        config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
        AppConfig.saveAppConfig(config)
        publishRuntimeStateWithHttpFallback("自清洁失败，进入维护中")
        HomeUiRefreshBridge.requestRefresh("self_clean:failed")
        logE(
            LOG_SELF_CLEAN,
            "自清洁失败：target=$targetLabel，result=${actionExecutionResultDesc(result)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}，" +
                "errorStatus（设备故障状态）=${config.errorStatus}(2=设备故障)"
        )
    }

    private suspend fun executeSelfCleanCommand(
        command: Int,
        targetLabel: String,
        source: String
    ): SelfCleanExecutionReport {
        val message = "4F 当前自清洁为 no-op，不会真实机械清洁；已阻止写入 200=$command"
        logW(
            LOG_SELF_CLEAN,
            "跳过自清洁：source=$source，target=$targetLabel，command（旧 200 自清洁指令）=$command，$message"
        )
        return SelfCleanExecutionReport(
            success = false,
            command = command,
            targetLabel = targetLabel,
            status201 = -1,
            message = FourFSelfCleanNoOpPolicy.message
        )
    }

    suspend fun cleanPanPosition(positionSn: Int, source: String): SelfCleanExecutionReport {
        return cleanPanPosition(positionSn, source, busyDuringClean = false)
    }

    suspend fun cleanPanPosition(
        positionSn: Int,
        source: String,
        busyDuringClean: Boolean
    ): SelfCleanExecutionReport {
        val command = buildSelfCleanCommand(positionSn)
        return runPanSelfCleanSequence(
            listOf(positionSn),
            includeSellPlatform = false,
            source = source,
            busyDuringClean = busyDuringClean
        )
            .reports
            .firstOrNull()
            ?: SelfCleanExecutionReport(false, command, "烤盘$positionSn", -1, "missing_report")
    }

    suspend fun cleanSellPlatform(source: String): SelfCleanExecutionReport {
        return cleanSellPlatform(source, busyDuringClean = false)
    }

    suspend fun cleanSellPlatform(
        source: String,
        busyDuringClean: Boolean
    ): SelfCleanExecutionReport {
        return runPanSelfCleanSequence(
            emptyList(),
            includeSellPlatform = true,
            source = source,
            busyDuringClean = busyDuringClean
        )
            .reports
            .firstOrNull()
            ?: SelfCleanExecutionReport(false, self_clean_sell_platform_command, "出肠台", -1, "missing_report")
    }

    data class SelfCleanSequenceResult(
        val success: Boolean,
        val reports: List<SelfCleanExecutionReport>
    )

    suspend fun runPanSelfCleanSequence(
        positionSnList: List<Int>,
        includeSellPlatform: Boolean,
        source: String,
        busyDuringClean: Boolean = false
    ): SelfCleanSequenceResult {
        val uniquePositions = positionSnList.distinct().sorted()
        if (FourFSelfCleanNoOpPolicy.shouldSkipSequenceBeforeStateMutation()) {
            val command = if (includeSellPlatform) {
                self_clean_sell_platform_command
            } else {
                uniquePositions.firstOrNull()?.let(::buildSelfCleanCommand) ?: -1
            }
            val targetLabel = if (includeSellPlatform) "出肠台" else "烤盘${uniquePositions.firstOrNull() ?: ""}"
            return SelfCleanSequenceResult(
                success = false,
                reports = listOf(
                    SelfCleanExecutionReport(
                        success = false,
                        command = command,
                        targetLabel = targetLabel,
                        status201 = -1,
                        message = FourFSelfCleanNoOpPolicy.message
                    )
                )
            )
        }
        val config = AppConfig.getAppConfig()
        if (!SelfCleanStartPolicy.canStart(
                onlineStatus = config.onlineStatus,
                moveStatus = config.moveStatus,
                orderPending = SupplementPauseController.shouldPauseForOrder(),
                errorStatus = config.errorStatus,
                inspectionMode = config.inspectionMode
            )
        ) {
            val report = SelfCleanExecutionReport(false, -1, "自清洁前置检查", -1, "business_or_order_or_busy")
            logW(
                LOG_SELF_CLEAN,
                "跳过自清洁：正常营业/订单待出餐/机械非空闲/故障或检修，source=$source，" +
                    "onlineStatus=${config.onlineStatus}，moveStatus=${config.moveStatus}，" +
                    "orderPending=${SupplementPauseController.shouldPauseForOrder()}"
            )
            return SelfCleanSequenceResult(false, listOf(report))
        }
        if (AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = config.lowerPlatformStatus,
                lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName
            )
        ) {
            val report = SelfCleanExecutionReport(
                success = false,
                command = -1,
                targetLabel = "自清洁前置检查",
                status201 = -1,
                message = "lower_platform_isolated:${config.lowerPlatformIsolationActionName}"
            )
            logW(
                LOG_SELF_CLEAN,
                "跳过自清洁：下平台隔离涉及升降台/残肠风险，source=$source，" +
                    "lowerPlatformStatus=${config.lowerPlatformStatus}，action=${config.lowerPlatformIsolationActionName}"
            )
            return SelfCleanSequenceResult(false, listOf(report))
        }
        if (config.errorStatus != 0) {
            val report = SelfCleanExecutionReport(false, -1, "自清洁前置检查", -1, "device_error")
            logW(LOG_SELF_CLEAN, "跳过自清洁：设备当前已处于故障态，source=$source")
            return SelfCleanSequenceResult(false, listOf(report))
        }

        var moveStatus = AppConfig.getAppConfig().moveStatus
        while (moveStatus != 0) {
            delay(2000L)
            moveStatus = AppConfig.getAppConfig().moveStatus
            logD(LOG_SELF_CLEAN, "等待其他机械动作结束后再执行自清洁：moveStatus=${moveStatusDesc(moveStatus)}，source=$source")
        }

        val reports = mutableListOf<SelfCleanExecutionReport>()
        val runtimeConfig = AppConfig.getAppConfig()
        val previousMoveStatus = runtimeConfig.moveStatus
        val previousOnlineStatus = runtimeConfig.onlineStatus
        val switchedToBusyOnlineStatus = busyDuringClean && previousOnlineStatus == 1
        runtimeConfig.moveStatus = 5
        if (switchedToBusyOnlineStatus) {
            runtimeConfig.onlineStatus = 4
        }
        AppConfig.saveAppConfig(runtimeConfig)
        publishRuntimeStateWithHttpFallback("自清洁开始")
        HomeUiRefreshBridge.requestRefresh("self_clean:start")

        try {
            uniquePositions.forEach { positionSn ->
                val report = executeSelfCleanCommand(
                    command = buildSelfCleanCommand(positionSn),
                    targetLabel = "烤盘$positionSn",
                    source = source
                )
                reports += report
                if (!report.success) {
                    return SelfCleanSequenceResult(false, reports)
                }
            }

            if (includeSellPlatform) {
                val report = executeSelfCleanCommand(
                    command = self_clean_sell_platform_command,
                    targetLabel = "出肠台",
                    source = source
                )
                reports += report
                if (!report.success) {
                    return SelfCleanSequenceResult(false, reports)
                }
            }
            return SelfCleanSequenceResult(true, reports)
        } finally {
            val latestConfig = AppConfig.getAppConfig()
            latestConfig.moveStatus = if (latestConfig.onlineStatus == 3) 0 else previousMoveStatus
            if (switchedToBusyOnlineStatus && latestConfig.onlineStatus == 4) {
                latestConfig.onlineStatus = previousOnlineStatus
            }
            AppConfig.saveAppConfig(latestConfig)
            if (latestConfig.onlineStatus != 3) {
                publishRuntimeStateWithHttpFallback("自清洁结束")
                HomeUiRefreshBridge.requestRefresh("self_clean:finished")
            }
        }
    }

    /**
     * 自动补肠：从烤肠箱取一根生肠，经平台/运输台搬到指定烤盘。
     *
     * 只有“烤肠箱 -> 平台”和“平台 -> 烤盘”两个动作都成功时，
     * 才允许扣减烤肠箱库存并写入目标烤盘状态。
     */
    suspend fun moveSausageToKaoPanByTaste(kaoPan: KaoPan, tasteCode: String?): Boolean {
        return moveSausageToKaoPanByTasteDetailed(kaoPan, tasteCode) == SupplementMoveResult.Success
    }

    private suspend fun moveSausageToKaoPanByTasteDetailed(
        kaoPan: KaoPan,
        tasteCode: String?,
        batchType: SupplementBatchType? = null,
        batchId: String? = null
    ): SupplementMoveResult {
        val candidates = TrafficModeHelper.findSupplyBoxesByTaste(KaoPanHelper.getKaoPanBoxList(), tasteCode)
        if (candidates.isEmpty()) {
            val reason = "补肠供给不可用跳过：targetPan=${kaoPan.positionSn}，tasteCode=${tasteCode ?: "未知"}，同口味无可用烤肠箱"
            logW(LOG_SUPPLEMENT, reason)
            return SupplementMoveResult.SupplyDegraded(reason, kaoPan.positionSn, emptyList())
        }
        val isInitializationBatch = batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true
        val irPickFailedBoxNos = mutableListOf<Int>()
        candidates.forEachIndexed { index, box ->
            LogUtils.i(
                "【补肠】按口味补肠尝试：目标烤盘=${kaoPan.positionSn}，tasteCode（口味编码）=$tasteCode，" +
                    "候选=${index + 1}/${candidates.size}，来源烤肠箱=${box.positionSn}，本地库存=${box.num}"
            )
            val moveResult = moveSausageToKaoPanDetailed(
                kaoPan,
                box,
                allowPickFailureFallback = !isInitializationBatch,
                batchType = batchType,
                batchId = batchId
            )
            if (moveResult.success) {
                return SupplementMoveResult.Success
            }
            val failedResult = moveResult.actionGroupResult as? ActionGroupResult.Failed
            if (moveResult.lowerPlatformIsolationReason != null) {
                val failedActionName = (moveResult.actionGroupResult as? ActionGroupResult.Failed)?.actionName ?: "in"
                val failureCode = atomicFailureCodeOrZero(moveResult.actionGroupResult)
                return SupplementMoveResult.LowerPlatformIsolated(
                    reason = moveResult.lowerPlatformIsolationReason,
                    targetPanNo = kaoPan.positionSn,
                    sourceBoxNo = box.positionSn,
                    actionName = failedActionName,
                    code = failureCode,
                    requiresManualConfirm = moveResult.lowerPlatformIsolationRequiresManualConfirm
                )
            }
            if (isInitializationBatch) {
                val failureCode = atomicFailureCodeOrZero(moveResult.actionGroupResult)
                if (failedResult != null &&
                    SupplementPickFailurePolicy.isSerialIn2IrPickFailure(failedResult.actionName, failedResult.result)
                ) {
                    recordIrPickFailedBoxAndReport(
                        failedBox = box,
                        allCandidates = candidates,
                        reason = "action_failure:initialization_in2_ir_pick:targetPan=${kaoPan.positionSn}",
                        source = "action_failure_initialization_in2_ir_pick"
                    )
                }
                return SupplementMoveResult.HardFailure(
                    reason = "初始化补肠失败，按整机维护处理：targetPan=${kaoPan.positionSn}，sourceBox=${box.positionSn}，" +
                        "action=${failedResult?.actionName ?: "in"}，" +
                        "result=${AtomicActionExecutor.formatCode(failureCode)}（${AtomicActionExecutor.messageOf(failureCode)}）",
                    code = failureCode
                )
            }
            if (failedResult != null &&
                SupplementPickFailurePolicy.isSerialIn2IrPickFailure(failedResult.actionName, failedResult.result)
            ) {
                irPickFailedBoxNos += box.positionSn
                val markResult = recordIrPickFailedBoxAndReport(
                    failedBox = box,
                    allCandidates = candidates,
                    reason = "action_failure:serial_in2_ir_pick:targetPan=${kaoPan.positionSn}:tasteCode=${tasteCode ?: "unknown"}",
                    source = "action_failure_serial_in2_ir_pick"
                )
                val nextBoxNo = markResult.remainingCandidateBoxNos.firstOrNull()
                logW(
                    LOG_SUPPLEMENT,
                    if (nextBoxNo != null) {
                        "烤肠箱${box.positionSn} 连续取肠失败（0x00E5），已记录失败标记并保留库存，继续尝试同口味下一箱：targetPan=${kaoPan.positionSn}，tasteCode=$tasteCode，nextBox=$nextBoxNo"
                    } else {
                        "烤肠箱${box.positionSn} 连续取肠失败（0x00E5），已记录失败标记并保留库存；当前已无同口味可用候选箱：targetPan=${kaoPan.positionSn}，tasteCode=$tasteCode"
                    }
                )
            } else {
                logE(
                    LOG_SUPPLEMENT,
                    "补肠遇到非取肠红外失败，停止切箱：targetPan=${kaoPan.positionSn}，box=${box.positionSn}，" +
                        "cmdStatusTake=${AtomicActionExecutor.formatCode(box.cmdStatusTake)}"
                )
                val failureCode = atomicFailureCodeOrZero(moveResult.actionGroupResult)
                return SupplementMoveResult.HardFailure(
                    reason = "串行补肠失败：${AtomicActionExecutor.formatCode(failureCode)}（${AtomicActionExecutor.messageOf(failureCode)}）",
                    code = failureCode
                )
            }
        }
        val degradeReason = SupplementPickFailurePolicy.buildSupplyDegradedReason(
            targetPanNo = kaoPan.positionSn,
            tasteCode = tasteCode,
            failedBoxNos = irPickFailedBoxNos,
            actionName = "in2"
        )
        logW(LOG_SUPPLEMENT, "同口味候选箱全部取肠失败，保留目标并等待人工确认：$degradeReason，boxCount=${candidates.size}")
        markSupplyDegradedRuntimeState(
            degradeReason,
            irPickFailedBoxNos,
            LowerPlatformEpisodeDetails(
                targetPanNo = kaoPan.positionSn,
                tasteCode = tasteCode,
                batchId = batchId,
                batchType = batchType?.name,
                errorCode = AtomicActionExecutor.formatCode(AtomicActionCodes.IR_PICK_FAILED),
                rawResult = degradeReason,
                requiresManualConfirm = true
            )
        )
        return SupplementMoveResult.SupplyDegraded(degradeReason, kaoPan.positionSn, irPickFailedBoxNos)
    }

    private fun atomicFailureCodeOrZero(result: ActionGroupResult?): Int {
        return if (result == null) {
            0
        } else {
            atomicFailureCode(result)
        }
    }

    private fun shouldPauseSupplementForOrder(stage: String): Boolean {
        val orderNo = SupplementPauseController.currentOrderNo()
        if (orderNo.isNullOrBlank()) {
            return false
        }
        logI(
            LOG_SUPPLEMENT,
            "$stage：检测到订单待出餐，暂停当前补肠批次后续任务，orderNo=$orderNo"
        )
        return true
    }

    private fun ensureSupplementBatchStartAllowed(stage: String): Boolean {
        return withFaultHeatTransitionLock {
            ensureSupplementBatchStartAllowedLocked(stage)
        }
    }

    private fun ensureSupplementBatchStartAllowedLocked(stage: String): Boolean {
        if (enforceReturnBindingConflictMaintenanceLocked(stage)) {
            return false
        }
        val config = AppConfig.getAppConfig()
        val decision = SupplementStartGate.evaluate(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            isEnable = config.isEnable,
            faultHeatPauseActive = config.faultHeatPauseActive
        )
        if (decision.canStart) {
            return true
        }
        logW(
            LOG_SUPPLEMENT,
            "【动作组故障锁存】$stage 硬门禁拒绝启动补肠流水线：${decision.reason}，" +
                "moveStatus（机械动作状态）=${moveStatusDesc(config.moveStatus)}，" +
                "useActionGroupV2（300/400动作组开关）=${config.useActionGroupV2}，" +
                "pendingDisableActionGroupV2（待关闭动作组标记）=${config.pendingDisableActionGroupV2}"
        )
        return false
    }

    private fun enforceReturnBindingConflictMaintenanceLocked(stage: String): Boolean {
        val conflict = supplementBatchIntentRepository.returnBindingConflictSnapshot()
        if (conflict == null) {
            lastReturnBindingConflictMaintenanceFingerprint = null
            return false
        }
        val config = AppConfig.getAppConfig()
        val stateDrifted = config.isEnable != 0 || config.inspectionMode != 1 || config.onlineStatus != 3
        val fingerprintChanged = lastReturnBindingConflictMaintenanceFingerprint != conflict.fingerprint
        if (stateDrifted || fingerprintChanged) {
            config.isEnable = 0
            config.inspectionMode = 1
            config.onlineStatus = 3
            AppConfig.saveAppConfigDurable(config)
            HomeUiRefreshBridge.requestRefresh("return_binding_conflict:maintenance")
            lastReturnBindingConflictMaintenanceFingerprint = conflict.fingerprint
            logW(
                LOG_SUPPLEMENT,
                "【补肠回搬绑定冲突】已锁定维护状态并禁止自动机械动作：stage=$stage，" +
                    "batchId=${conflict.batchId}，conflicts=${conflict.details}"
            )
            operateScope.launch {
                val runtimePublished = SendServerHelper.publishServiceUpdateStatus()
                val availablePublished = SendServerHelper.publishAvailableState("return_binding_conflict:maintenance")
                if (!runtimePublished || !availablePublished) {
                    logW(
                        LOG_DEVICE,
                        "补肠回搬绑定冲突维护状态上报不完整，回退HTTP：" +
                            "runtimePublished=$runtimePublished，availablePublished=$availablePublished"
                    )
                    DataManagementAPI.uploadDeviceInfo()
                }
            }
        }
        return true
    }

    private fun claimPreparedSupplementMove(
        stage: String,
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        allowedMoveStatuses: Set<Int> = setOf(0)
    ): Boolean = claimMechanicalMove(
        stage = stage,
        owner = owner,
        ownerStore = ownerStore,
        allowedMoveStatuses = allowedMoveStatuses,
        claimedMoveStatus = 1,
        requireSupplementGate = true
    )

    private fun admitPanMoveCommandIntent(
        owner: String,
        automatic: Boolean
    ): AutomaticMechanicalCommandAdmissionResult = AutomaticMechanicalCommandAdmissionCoordinator(
        transitionLock = faultHeatTransitionLock,
        orderPending = SupplementPauseController::shouldPauseForOrder,
        currentOwner = { panMoveJournalStore.load()?.moveId },
        currentMoveStatus = mechanicalMoveStatusStore::load,
        automaticCommandAllowed = ::isAutomaticMechanicalCommandStillAllowed,
        emergencyResetRecoveryPending = ::isEmergencyResetRecoveryPending,
        saveCommandIntent = {
            val current = panMoveJournalStore.load()
            current?.moveId == owner && panMoveJournalStore.saveDurable(
                current.copy(checkpoint = PanMoveCheckpoint.COMMAND_INTENT)
            )
        }
    ).admit(owner, expectedMoveStatus = 3, automatic = automatic)

    private fun admitDiscardCommandIntent(
        owner: String,
        automatic: Boolean,
        businessCloseout: Boolean = false
    ): AutomaticMechanicalCommandAdmissionResult = AutomaticMechanicalCommandAdmissionCoordinator(
        transitionLock = faultHeatTransitionLock,
        orderPending = SupplementPauseController::shouldPauseForOrder,
        currentOwner = { discardJournalStore.load()?.owner },
        currentMoveStatus = mechanicalMoveStatusStore::load,
        automaticCommandAllowed = { isDiscardCommandStillAllowed(owner, businessCloseout) },
        emergencyResetRecoveryPending = ::isEmergencyResetRecoveryPending,
        saveCommandIntent = {
            val current = discardJournalStore.load()
            current?.owner == owner && discardJournalStore.saveDurable(
                current.copy(checkpoint = DiscardCheckpoint.COMMAND_INTENT)
            )
        }
    ).admit(owner, expectedMoveStatus = 4, automatic = automatic)

    private fun claimMechanicalMove(
        stage: String,
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        allowedMoveStatuses: Set<Int>,
        claimedMoveStatus: Int,
        requireSupplementGate: Boolean,
        claimGate: () -> Boolean = { true }
    ): Boolean {
        val coordinator = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        )
        val result = coordinator.claim(owner, allowedMoveStatuses, claimedMoveStatus) {
            if (isEmergencyResetRecoveryPending()) {
                false
            } else if (!claimGate()) {
                false
            } else if (!requireSupplementGate) {
                true
            } else {
                val config = AppConfig.getAppConfig()
                SupplementStartGate.evaluate(
                    onlineStatus = config.onlineStatus,
                    errorStatus = config.errorStatus,
                    isEnable = config.isEnable,
                    faultHeatPauseActive = config.faultHeatPauseActive
                ).canStart && !SupplementPauseController.shouldPauseForOrder()
            }
        }
        if (result !is MechanicalMoveClaimResult.Claimed) {
            logW(LOG_ACTION_GROUP, "$stage 机械认领失败：owner=$owner，result=$result")
        }
        return result is MechanicalMoveClaimResult.Claimed
    }

    private fun releaseMechanicalMove(
        stage: String,
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        expectedMoveStatus: Int,
        idleMoveStatus: Int
    ): Boolean = releaseMechanicalMove(
        stage = stage,
        owner = owner,
        ownerStore = ownerStore,
        expectedMoveStatuses = setOf(expectedMoveStatus),
        idleMoveStatus = idleMoveStatus
    )

    private fun releaseMechanicalMove(
        stage: String,
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        expectedMoveStatuses: Set<Int>,
        idleMoveStatus: Int
    ): Boolean {
        val result = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        ).release(owner, expectedMoveStatuses, idleMoveStatus)
        if (result !is MechanicalMoveReleaseResult.Released) {
            logW(LOG_ACTION_GROUP, "$stage 机械认领释放失败：owner=$owner，result=$result")
        }
        return result is MechanicalMoveReleaseResult.Released
    }

    private fun releaseSupplementMoveClaimIfOwned(
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        resourceMask: Int,
        expectedRevision: Long? = null
    ): Boolean = withFaultHeatTransitionLock {
        val current = ownerStore.snapshot()
        if (current?.owner != owner) {
            logW(LOG_SUPPLEMENT, "补肠认领释放失败：owner 不匹配，expected=$owner，actual=${current?.owner}")
            return@withFaultHeatTransitionLock false
        }
        val revision = expectedRevision ?: current.revision
        if (actionGroupLocks.ownedMask(owner)?.let { it != resourceMask } == true) {
            logW(
                LOG_SUPPLEMENT,
                "补肠认领释放失败：资源 mask 不匹配，owner=$owner，" +
                    "expected=${ResourceLocks.describe(resourceMask)}，" +
                    "actual=${ResourceLocks.describe(requireNotNull(actionGroupLocks.ownedMask(owner)))}"
            )
            return@withFaultHeatTransitionLock false
        }
        val result = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        ).release(
            owner = owner,
            expectedMoveStatus = 1,
            idleMoveStatus = 0,
            expectedRevision = revision
        )
        if (result !is MechanicalMoveReleaseResult.Released) {
            logW(LOG_SUPPLEMENT, "补肠认领释放失败：owner=$owner，result=$result")
            return@withFaultHeatTransitionLock false
        }
        when (actionGroupLocks.releaseIfOwned(owner, resourceMask)) {
            ResourceReleaseResult.RELEASED,
            ResourceReleaseResult.ALREADY_ABSENT -> true
            ResourceReleaseResult.MASK_MISMATCH -> {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠认领已清 owner/moveStatus，但资源 mask 在收口期间变化：owner=$owner，" +
                        "expected=${ResourceLocks.describe(resourceMask)}"
                )
                false
            }
        }
    }

    private sealed class SerialSupplementFailureCloseoutResult {
        data object Released : SerialSupplementFailureCloseoutResult()
        data class Rejected(val reason: String) : SerialSupplementFailureCloseoutResult()
        data class Maintenance(val reason: String) : SerialSupplementFailureCloseoutResult()
    }

    private fun captureSerialSupplementFailureProof(
        owner: String,
        actionName: String,
        sourceNo: Int,
        targetNo: Int,
        batchId: String?,
        batchType: SupplementBatchType?,
        rawResult: AtomicActionResult,
        physicalBoxDeducted: Boolean,
        physicalPanPlaced: Boolean,
        physicalStockInconsistent: Boolean
    ): SerialSupplementFailureProof? {
        val session = actionGroupSessionStore.load()?.takeIf {
            it.sessionId == owner &&
                it.type == ActionGroupSessionType.IN_SUPPLEMENT &&
                !it.prefetch &&
                it.sourceNo == sourceNo &&
                it.targetNo == targetNo
        }
        val proof = session?.let {
            SerialSupplementFailureProof(
                owner = owner,
                sessionId = it.sessionId,
                mechanicalClaimRevision = it.mechanicalClaimRevision,
                resourceMask = it.resourceMask,
                actionName = actionName,
                sourceNo = sourceNo,
                targetNo = targetNo,
                batchId = batchId,
                batchType = batchType,
                rawResult = rawResult,
                lastConfirmedStage = it.lastConfirmedStage,
                boxDeductionApplied = it.boxDeductionApplied,
                panPlacementApplied = it.panPlacementApplied,
                physicalBoxDeducted = physicalBoxDeducted,
                physicalPanPlaced = physicalPanPlaced,
                physicalStockInconsistent = physicalStockInconsistent
            )
        }
        serialSupplementFailureProof = proof
        return proof
    }

    private fun persistGuardedSerialFailureEpisode(proof: SerialSupplementFailureProof): Boolean =
        withFaultHeatTransitionLock {
            val tx = LowerPlatformEpisodeReducer.transaction(AppConfig.getAppConfig(), AppConfig::saveAppConfigDurable) {
                LowerPlatformEpisodeReducer.openGuardedIsolation(
                    config = AppConfig.getAppConfig(),
                    actionName = proof.actionName,
                    reason = "串行补肠明确失败，等待精确资源收口",
                    details = LowerPlatformEpisodeDetails(
                        sourceBoxNo = proof.sourceNo,
                        targetPanNo = proof.targetNo,
                        batchId = proof.batchId,
                        batchType = proof.batchType?.name,
                        errorCode = (proof.rawResult as? AtomicActionResult.Failed)?.errorCode?.let(AtomicActionExecutor::formatCode),
                        rawResult = proof.rawResult.toString(),
                        requiresManualConfirm = true,
                        closeoutGuarded = true,
                        closeoutSessionId = proof.sessionId,
                        closeoutClaimRevision = proof.mechanicalClaimRevision,
                        closeoutActionName = proof.actionName
                    )
                )
                LowerPlatformRuntimeStateReporter.applyLowerPlatformIsolated(AppConfig.getAppConfig())
            }
            tx.snapshot.status == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
        }

    private fun closeSerialSupplementFailure(
        proof: SerialSupplementFailureProof,
        expectedSession: ActionGroupSession? = null,
        expectedActionName: String? = null,
        expectedErrorCode: Int? = null
    ): SerialSupplementFailureCloseoutResult = withFaultHeatTransitionLock {
        val current = actionGroupSessionStore.load()
            ?: return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：当前 session 已不存在"
            )
        val rawFailed = proof.rawResult as? AtomicActionResult.Failed
            ?: return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：原始结果不是明确 Failed"
            )
        if (expectedActionName != null && proof.actionName != expectedActionName ||
            expectedErrorCode != null && rawFailed.errorCode != expectedErrorCode
        ) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：恢复动作或错误码与本进程 proof 不匹配"
            )
        }
        if (expectedSession != null &&
            (current.sessionId != expectedSession.sessionId ||
                current.mechanicalClaimRevision != expectedSession.mechanicalClaimRevision)
        ) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：恢复期间 owner/revision 已变化"
            )
        }
        if (current.sessionId != proof.sessionId ||
            current.sessionId != proof.owner ||
            current.type != ActionGroupSessionType.IN_SUPPLEMENT ||
            current.prefetch ||
            current.sourceNo != proof.sourceNo ||
            current.targetNo != proof.targetNo ||
            current.batchId != proof.batchId ||
            current.resourceMask != proof.resourceMask ||
            current.currentAction != proof.actionName ||
            current.mechanicalClaimState != MechanicalClaimState.CLAIMED ||
            current.mechanicalClaimRevision != proof.mechanicalClaimRevision
        ) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：session/owner/revision/action/resource 不匹配"
            )
        }
        if (readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent ||
            panMoveJournalStore.load() != null ||
            discardJournalStore.load() != null
        ) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：存在并行补肠或搬盘/丢弃 journal 证据"
            )
        }
        if (!SupplementFailureCloseoutPolicy.canAutoRelease(
                SupplementFailureCloseoutEvidence(
                    batchType = proof.batchType,
                    actionName = proof.actionName,
                    rawResult = proof.rawResult,
                    prefetch = current.prefetch,
                    mechanicalClaimState = current.mechanicalClaimState,
                    lastConfirmedStage = proof.lastConfirmedStage,
                    boxDeductionApplied = current.boxDeductionApplied || proof.boxDeductionApplied,
                    panPlacementApplied = current.panPlacementApplied || proof.panPlacementApplied,
                    physicalBoxDeducted = proof.physicalBoxDeducted,
                    physicalPanPlaced = proof.physicalPanPlaced,
                    physicalStockInconsistent = proof.physicalStockInconsistent
                )
            )
        ) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：不满足串行 in1~in3 无物理提交安全条件"
            )
        }
        val ownedMask = actionGroupLocks.ownedMask(proof.owner)
        if (ownedMask == null) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口发现资源 owner 已不存在，拒绝猜测物理归属：owner=${proof.owner}"
            )
        }
        if (ownedMask != proof.resourceMask) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口发现资源 mask 不匹配：owner=${proof.owner}，" +
                    "expected=${ResourceLocks.describe(proof.resourceMask)}，actual=${ResourceLocks.describe(ownedMask)}"
            )
        }
        val moveStatus = mechanicalMoveStatusStore.load()
        val claimRelease = when (moveStatus) {
            OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING ->
                mechanicalMoveClaimCoordinator(actionGroupCloseoutClaimOwnerStore(proof.actionName)).release(
                    owner = proof.owner,
                    expectedMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING,
                    idleMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
                    expectedRevision = proof.mechanicalClaimRevision
                )
            OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING ->
                mechanicalMoveClaimCoordinator(actionGroupCloseoutClaimOwnerStore(proof.actionName)).releaseOwnerKeepingMoveStatus(
                    owner = proof.owner,
                    expectedMoveStatuses = setOf(OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING),
                    expectedRevision = proof.mechanicalClaimRevision
                )
            else -> MechanicalMoveReleaseResult.Rejected(
                "补肠失败收口拒绝：当前 moveStatus=${moveStatusDesc(moveStatus)}，不允许覆盖"
            )
        }
        if (claimRelease !is MechanicalMoveReleaseResult.Released) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Rejected(
                "补肠失败收口拒绝：owner/moveStatus 收口失败：$claimRelease"
            )
        }
        if (actionGroupSessionStore.load() != null) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口 claim/session 原子 CAS 后仍有会话残留"
            )
        }
        when (actionGroupLocks.releaseIfOwned(proof.owner, proof.resourceMask)) {
            ResourceReleaseResult.RELEASED -> Unit
            ResourceReleaseResult.ALREADY_ABSENT -> return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口 claim/session 已清，但资源 owner 在提交前消失：owner=${proof.owner}"
            )
            ResourceReleaseResult.MASK_MISMATCH -> return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口 claim/session 已清，但资源释放失败：owner=${proof.owner}，" +
                    "expected=${ResourceLocks.describe(proof.resourceMask)}"
            )
        }
        val episodeClosed = LowerPlatformEpisodeReducer.transaction(
            AppConfig.getAppConfig(), AppConfig::saveAppConfigDurable
        ) {
            LowerPlatformEpisodeReducer.recoverGuardedIsolationIfKey(
                AppConfig.getAppConfig(), proof.sessionId, proof.mechanicalClaimRevision, proof.actionName
            )
        }.value
        if (!episodeClosed) {
            return@withFaultHeatTransitionLock SerialSupplementFailureCloseoutResult.Maintenance(
                "补肠失败收口 guarded episode 清理失败，保持维护门禁"
            )
        }
        serialSupplementFailureProof = null
        SerialSupplementFailureCloseoutResult.Released
    }

    private fun closeKnownIsolatedSupplementFailure(
        session: ActionGroupSession,
        actionName: String,
        errorCode: Int
    ): Boolean {
        val proof = serialSupplementFailureProof ?: return false
        if (!matchesOpenLowerPlatformIsolationEpisode(proof, actionName, errorCode)) {
            logW(
                LOG_ACTION_GROUP,
                "补肠恢复收口拒绝：当前没有匹配的下平台隔离 episode，" +
                    "action=$actionName，code=${AtomicActionExecutor.formatCode(errorCode)}"
            )
            return false
        }
        return when (val result = closeSerialSupplementFailure(proof, session, actionName, errorCode)) {
            SerialSupplementFailureCloseoutResult.Released -> true
            is SerialSupplementFailureCloseoutResult.Maintenance -> {
                enterActionGroupMaintenance(result.reason, preserveMoveStatus = true)
                false
            }
            is SerialSupplementFailureCloseoutResult.Rejected -> {
                logW(LOG_ACTION_GROUP, result.reason)
                false
            }
        }
    }

    private fun matchesOpenLowerPlatformIsolationEpisode(
        proof: SerialSupplementFailureProof,
        actionName: String,
        errorCode: Int
    ): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED ||
            proof.actionName != actionName ||
            (proof.rawResult as? AtomicActionResult.Failed)?.errorCode != errorCode
        ) {
            return false
        }
        val formattedCode = AtomicActionExecutor.formatCode(errorCode)
        return config.lowerPlatformEpisodes.any { episode ->
            val batchMatches =
                (episode.batchId.isNullOrBlank() && proof.batchId.isNullOrBlank()) ||
                    (!episode.batchId.isNullOrBlank() && episode.batchId == proof.batchId)
            episode.lifecycle == "OPEN" &&
                episode.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED &&
                episode.actionName == actionName &&
                episode.errorCode.equals(formattedCode, ignoreCase = true) &&
                episode.sourceBoxNo == proof.sourceNo &&
                episode.targetPanNo == proof.targetNo &&
                batchMatches
        }
    }

    /** 既有串行 in2+IR_PICK_FAILED 换箱 fallback 的 owner/revision 收口。 */
    private fun releaseSerialSupplementPickFallback(owner: String): Boolean = withFaultHeatTransitionLock {
        val session = actionGroupSessionStore.load()
            ?: return@withFaultHeatTransitionLock false
        if (session.sessionId != owner ||
            session.type != ActionGroupSessionType.IN_SUPPLEMENT ||
            session.prefetch ||
            session.mechanicalClaimState != MechanicalClaimState.CLAIMED
        ) {
            return@withFaultHeatTransitionLock false
        }
        if (actionGroupLocks.ownedMask(owner)?.let { it != session.resourceMask } == true) {
            return@withFaultHeatTransitionLock false
        }
        val release = mechanicalMoveClaimCoordinator(actionGroupClaimOwnerStore()).release(
            owner = owner,
            expectedMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING,
            idleMoveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
            expectedRevision = session.mechanicalClaimRevision
        )
        if (release !is MechanicalMoveReleaseResult.Released) {
            return@withFaultHeatTransitionLock false
        }
        when (actionGroupLocks.releaseIfOwned(owner, session.resourceMask)) {
            ResourceReleaseResult.RELEASED,
            ResourceReleaseResult.ALREADY_ABSENT -> true
            ResourceReleaseResult.MASK_MISMATCH -> false
        }
    }

    private fun releaseCompletedSerialSupplementRoot(
        owner: String,
        closeout: SupplementPipelineOrderCloseout
    ): Boolean {
        val expectedSession = actionGroupSessionStore.load()
            ?.takeIf { it.sessionId == owner }
            ?: return false
        return NonParallelSupplementOrderHandoffCoordinator(
            transitionLock = faultHeatTransitionLock,
            ownerStore = actionGroupClaimOwnerStore(),
            moveStore = mechanicalMoveStatusStore,
            resourceLocks = actionGroupLocks,
            expectedResourceMask = expectedSession.resourceMask
        ).releaseCompletedRoot(owner, closeout)
    }

    private fun discardPreparedSupplementOwnerIfIdle(
        owner: String,
        currentOwner: () -> String?,
        clearOwner: () -> Boolean
    ): Boolean = withFaultHeatTransitionLock {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus != 0 || currentOwner() != owner) {
            return@withFaultHeatTransitionLock false
        }
        clearOwner()
    }

    private fun hasCompletedParallelDeliveryCloseoutEvidence(
        session: SupplementPrefetchPipelineSession
    ): Boolean {
        val delivery = session.deliveryLane ?: return false
        return session.prefetchLane == null &&
            delivery.currentAction == "in7" &&
            delivery.lastConfirmedStage == "in7" &&
            delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
            delivery.boxDeductionApplied &&
            delivery.panPlacementApplied
    }

    enum class PendingSupplementBatchHandling {
        NONE,
        WAITING_MANUAL_CONFIRMATION,
        BLOCKED,
        RESUMED;

        val completedMechanicalWorkThisRound: Boolean
            get() = this == RESUMED

        val blocksNewSupplement: Boolean
            get() = this != NONE
    }

    internal fun prepareLowInitializationMaturityCloseout(
        config: AppConfigBean,
        pans: List<KaoPan>,
        nowMs: Long,
        recoveryBlocked: Boolean
    ): LowInitializationMaturityCloseoutPolicy.Closeout? {
        val intent = supplementBatchIntentRepository.loadActive()
            ?: return null
        if (intent.type == SupplementBatchType.HIGH_INITIALIZATION_1_21) {
            return null
        }
        if (intent.type != SupplementBatchType.LOW_INITIALIZATION_1_9) {
            return null
        }
        val unfinishedSupplementPlacementEvidence =
            actionGroupSessionStore.load()?.type == ActionGroupSessionType.IN_SUPPLEMENT ||
                pausedSupplementSessionStore.load() != null ||
                panMoveJournalStore.load() != null ||
                readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent
        val closeout = LowInitializationMaturityCloseoutPolicy.evaluate(
            intent = intent,
            pans = pans,
            config = config,
            nowMs = nowMs,
            recoveryBlocked = recoveryBlocked,
            unfinishedSupplementPlacementEvidence = unfinishedSupplementPlacementEvidence
        ) ?: return null

        check(
            supplementBatchIntentRepository.prepareLowInitializationMaturityCloseout(
                closeout.batchId,
                closeout.maturedPositions,
                closeout.remainingPositions
            )
        ) { "低并发残缺初始化成熟收口阶段落盘失败" }
        return closeout
    }

    internal fun completeLowInitializationMaturityCloseout(closeout: LowInitializationMaturityCloseoutPolicy.Closeout) {
        check(
            supplementBatchIntentRepository.clearPreparedLowInitializationMaturityCloseout(
                closeout.batchId,
                closeout.maturedPositions,
                closeout.remainingPositions,
                "恢复门禁阻塞且已落盘初始化烤肠全部成熟，关闭剩余直补目标"
            )
        ) { "低并发残缺初始化成熟收口 intent 已变化，拒绝清理" }
        logW(
            LOG_SUPPLEMENT,
            "低并发残缺初始化按成熟批次收口，不再向一区直补生肠：" +
                "batchId=${closeout.batchId}，matured=${closeout.maturedPositions}，" +
                "cancelled=${closeout.remainingPositions}"
        )
    }

    fun pendingSupplementBatchRequiresManualConfirm(): SupplementBatchIntent? {
        val intent = supplementBatchIntentRepository.loadActive() ?: return null
        return if (intent.requiresAnyManualConfirmation()) intent else null
    }

    fun returnBindingConflictSnapshot(): ReturnBindingConflictSnapshot? =
        supplementBatchIntentRepository.returnBindingConflictSnapshot()

    suspend fun confirmPendingSupplementBatchSafety(reason: String): ManualSupplementConfirmResult {
        val pending = pendingSupplementBatchRequiresManualConfirm()
            ?: return ManualSupplementConfirmResult(false, "当前没有待确认批次")
        if (pending.returnBindingConflictActive) {
            return ManualSupplementConfirmResult(
                false,
                "回搬目标存在重复所有者，普通确认不能解除：${pending.returnBindingConflictDetails}"
            )
        }
        when (val evidence = readSupplementPrefetchEvidence()) {
            DurableEvidenceReadResult.Absent -> Unit
            is DurableEvidenceReadResult.Corrupted -> {
                val rejectReason = "双 lane 会话损坏，禁止人工确认：fingerprint=${evidence.fingerprint}，reason=${evidence.reason}"
                logW(LOG_SUPPLEMENT, rejectReason)
                return ManualSupplementConfirmResult(false, rejectReason)
            }
            is DurableEvidenceReadResult.Valid -> {
                return confirmPendingSupplementBatchSafetyWithPrefetchSession(
                    pending = pending,
                    session = evidence.value,
                    reason = reason
                )
            }
        }
        if (panMoveJournalStore.load() != null) {
            val rejectReason = "存在搬盘journal，拒绝确认补肠现场安全：batchId=${pending.batchId}"
            logW(LOG_SUPPLEMENT, rejectReason)
            return ManualSupplementConfirmResult(false, rejectReason)
        }
        val sessions = listOfNotNull(
            actionGroupSessionStore.load(),
            pausedSupplementSessionStore.load()
        )
        val conflictingSession = sessions.firstOrNull { session ->
            session.type != ActionGroupSessionType.IN_SUPPLEMENT || session.batchId != pending.batchId
        }
        if (conflictingSession != null || (sessions.isEmpty() && !actionGroupLocks.isIdle())) {
            val rejectReason = "补肠现场安全确认与当前动作会话冲突：pendingBatchId=${pending.batchId}"
            logW(
                LOG_SUPPLEMENT,
                "$rejectReason，拒绝清理恢复证据：" +
                    "pendingBatchId=${pending.batchId}，session=$conflictingSession，locks=${actionGroupLocks.snapshot()}"
            )
            return ManualSupplementConfirmResult(false, rejectReason)
        }
        val confirmed = supplementBatchIntentRepository.confirmManualSafety(reason)
            ?: return ManualSupplementConfirmResult(false, "当前没有待确认批次")
        clearFaultTimeoutCancelledReturnReservations(confirmed, reason)
        val config = AppConfig.getAppConfig()
        if (config.moveStatus != 0) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfigDurable(config)
            logW(
                LOG_SUPPLEMENT,
                "人工确认补肠批次现场安全后清理本地机械状态：moveStatus=$oldMoveStatus->0，batchId=${confirmed.batchId}"
            )
        }
        if (actionGroupSessionStore.load() != null) {
            actionGroupSessionStore.clear()
        }
        if (pausedSupplementSessionStore.load() != null) {
            pausedSupplementSessionStore.clear()
        }
        sessions.forEach { actionGroupLocks.release(it.sessionId) }
        return ManualSupplementConfirmResult(true, "已确认续补安全")
    }

    private suspend fun confirmPendingSupplementBatchSafetyWithPrefetchSession(
        pending: SupplementBatchIntent,
        session: SupplementPrefetchPipelineSession,
        reason: String
    ): ManualSupplementConfirmResult {
        val firstSnapshot = buildActionGroupRecoveryResidueEvidence("人工确认续补安全：第一次空闲确认")
        delay(500L)
        val secondSnapshot = buildActionGroupRecoveryResidueEvidence("人工确认续补安全：第二次空闲确认")
        val snapshots = listOf(firstSnapshot, secondSnapshot)
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = pending.batchId,
            failedTargetPanNo = pending.failedTargetPanNo,
            failedActionName = pending.failedActionName,
            session = session,
            hasPausedSupplementSession = pausedSupplementSessionStore.load() != null,
            hasPanMoveJournal = panMoveJournalStore.load() != null,
            snapshots = snapshots,
            algorithmEnabled = AppConfig.getAppConfig().isEnable == 1
        )
        if (!decision.canRecover) {
            logW(
                LOG_SUPPLEMENT,
                "拒绝人工确认双 lane 补肠收口：batchId=${pending.batchId}，reason=${decision.reason}，" +
                    "session=${session.sessionId}，snapshots=${snapshots.map { it.summary() }}"
            )
            return ManualSupplementConfirmResult(false, decision.reason)
        }
        runCatching {
            supplementBatchIntentRepository.markManualConfirmStrategyPrefetchPreserveRemaining(
                batchId = pending.batchId,
                reason = reason
            )
        }.getOrElse { error ->
            val rejectReason = "双 lane 人工确认策略落盘失败：${error.message}"
            logW(LOG_SUPPLEMENT, "$rejectReason，保留 session/锁/moveStatus：batchId=${pending.batchId}")
            return ManualSupplementConfirmResult(false, rejectReason)
        }.takeIf { it } ?: return ManualSupplementConfirmResult(false, "双 lane 人工确认策略落盘失败：当前批次不匹配")

        val oldMoveStatus = AppConfig.getAppConfig().moveStatus
        val completion = SupplementPipelineCompletionCoordinator.complete(
            persistIdle = {
                val config = AppConfig.getAppConfig()
                config.moveStatus = 0
                AppConfig.saveAppConfigDurable(config)
            },
            publish = { publishRuntimeStateWithHttpFallback("人工确认双 lane 补肠收口") },
            clearSession = {
                supplementPrefetchSessionRepository.clearIfRevision(session.sessionId, session.revision)
            },
            releaseLock = { actionGroupLocks.release(session.sessionId) },
            restoreBusy = {
                val config = AppConfig.getAppConfig()
                config.moveStatus = if (oldMoveStatus != 0) oldMoveStatus else 1
                AppConfig.saveAppConfigDurable(config)
            },
            publishRestoredBusy = {
                publishRuntimeStateWithHttpFallback("人工确认双 lane 收口失败，恢复忙态")
            }
        )
        if (completion is SupplementPipelineCompletionResult.Failed) {
            val rejectReason = "双 lane 会话收口失败：${completion.reason}"
            logW(
                LOG_SUPPLEMENT,
                "$rejectReason，保留待确认批次：batchId=${pending.batchId}，" +
                    "restoreError=${completion.restoreFailure?.message}"
            )
            return ManualSupplementConfirmResult(false, rejectReason)
        }

        val confirmed = supplementBatchIntentRepository.confirmManualSafetyPreservingRemainingTargets(reason)
            ?: return ManualSupplementConfirmResult(false, "双 lane 已收口，但待确认批次写入失败，请刷新维护页后重试")
        lastActionGroupRecoveryFailureReason = null
        markActionGroupRecoveryVerified("人工确认双 lane 补肠现场安全：$reason")
        logW(
            LOG_SUPPLEMENT,
            "人工确认双 lane 补肠现场安全并完成收口：batchId=${confirmed.batchId}，" +
                "session=${session.sessionId}，failedTarget=${pending.failedTargetPanNo}，" +
                "failedAction=${pending.failedActionName}，remaining=${confirmed.remainingTargets().map { it.positionSn }}"
        )
        return ManualSupplementConfirmResult(true, decision.reason)
    }

    private fun clearFaultTimeoutCancelledReturnReservations(intent: SupplementBatchIntent, reason: String) {
        val cancelledReturnPositions = intent.targets
            .filter { it.cancelledByFaultTimeout }
            .mapNotNull { it.returnPositionSn }
            .filter { it in 1..21 }
            .distinct()
        if (cancelledReturnPositions.isEmpty()) {
            return
        }
        val pans = KaoPanHelper.getKaoPanList()
        val changedPans = mutableListOf<KaoPan>()
        cancelledReturnPositions.forEach { returnPosition ->
            val pan = pans.firstOrNull { it.positionSn == returnPosition } ?: return@forEach
            if (!pan.isHasSausage && pan.isHasGrilling) {
                pan.isHasGrilling = false
                pan.taste = null
                changedPans += pan
            }
        }
        if (changedPans.isEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "【补肠批次恢复】故障超时未找到需要清理的前区预留：batchId=${intent.batchId}，" +
                    "returnPositions=$cancelledReturnPositions，reason=$reason"
            )
            return
        }
            KaoPanHelper.saveKaoPanListDurable(pans)
        PanPhysicalStateDeltaReporter.reportPansChanged(
            changedPans = changedPans,
            reason = "supplement_fault_timeout_cancel:batch=${intent.batchId}:returnPositions=${changedPans.joinToString(",") { it.positionSn.toString() }}",
            source = "supplement_fault_timeout_cancel"
        )
        logW(
            LOG_SUPPLEMENT,
            "【补肠批次恢复】故障超过10分钟，已清除未完成目标对应前区预留：" +
                "batchId=${intent.batchId}，returnPositions=${changedPans.map { it.positionSn }}，reason=$reason"
        )
    }

    fun clearPendingSupplementBatchIntent(reason: String) {
        val conflict = supplementBatchIntentRepository.returnBindingConflictSnapshot()
        if (conflict != null) {
            logW(
                LOG_SUPPLEMENT,
                "【补肠回搬绑定冲突】拒绝普通清除intent：reason=$reason，conflicts=${conflict.details}"
            )
            return
        }
        supplementBatchIntentRepository.clear(reason)
    }

    fun clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(reason: String): Boolean {
        val intent = supplementBatchIntentRepository.loadActive()
        if (intent?.returnBindingConflictActive == true) {
            logW(
                LOG_SUPPLEMENT,
                "【补肠回搬绑定冲突】拒绝绕过专用收口清除intent：reason=$reason，" +
                    "conflicts=${intent.returnBindingConflictDetails}"
            )
            return false
        }
        if (intent != null && intent.remainingTargets().isEmpty() && intent.retainedCompletedHeatReturnTargets().isNotEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "【补肠批次恢复】当前仅剩已完成补热回搬绑定，不清除：reason=$reason，" +
                    "batchId=${intent.batchId}，retainedSources=${intent.retainedCompletedHeatReturnTargets().map { it.positionSn }}"
            )
            return false
        }
        if (intent?.type == SupplementBatchType.HIGH_HEAT_25_33 &&
            intent.remainingTargets().isNotEmpty() &&
            (intent.requiresManualConfirm || intent.failureAtMs > 0L)
        ) {
            logW(
                LOG_SUPPLEMENT,
                "【补肠批次恢复】保留25~33补热故障批次意图：reason=$reason，" +
                    "requiresManualConfirm=${intent.requiresManualConfirm}，" +
                    "batchId=${intent.batchId}，failureAtMs=${intent.failureAtMs}，" +
                    "remaining=${intent.remainingTargets().map { it.positionSn }}"
            )
            return false
        }
        return supplementBatchIntentRepository.clearPendingTargetsRetainingCompletedHeatReturnBindings(reason)
    }

    fun resolveReturnBindingConflictAfterAllSourcesCleared(
        expected: ReturnBindingConflictSnapshot,
        reason: String
    ): ManualSupplementConfirmResult = withFaultHeatTransitionLock {
        val current = supplementBatchIntentRepository.returnBindingConflictSnapshot()
            ?: return@withFaultHeatTransitionLock ManualSupplementConfirmResult(false, "当前没有回搬绑定冲突")
        if (current.batchId != expected.batchId ||
            current.updatedAtMs != expected.updatedAtMs ||
            current.fingerprint != expected.fingerprint
        ) {
            return@withFaultHeatTransitionLock ManualSupplementConfirmResult(false, "冲突意图已变化，请刷新后重新核对")
        }
        val config = AppConfig.getAppConfig()
        val orderClaimPresent = runCatching { orderDeliveryClaimStore.load() != null }.getOrElse {
            return@withFaultHeatTransitionLock ManualSupplementConfirmResult(false, "订单claim证据读取失败，禁止收口绑定")
        }
        val closeoutRejection = returnBindingConflictCloseoutRejection(
            ReturnBindingConflictCloseoutEvidence(
                moveStatus = config.moveStatus,
                hasProcessingOrder = OrderStateManager.hasProcessingOrder(),
                hasOrderPause = SupplementPauseController.shouldPauseForOrder(),
                hasOrderDeliveryClaim = orderClaimPresent,
                actionSessionAbsent = actionGroupSessionStore.readEvidence() is DurableEvidenceReadResult.Absent,
                pausedSessionAbsent = pausedSupplementSessionStore.readEvidence() is DurableEvidenceReadResult.Absent,
                prefetchEvidenceAbsent = readSupplementPrefetchEvidence() is DurableEvidenceReadResult.Absent,
                hasPanMoveJournal = panMoveJournalStore.load() != null,
                hasDiscardJournal = discardJournalStore.load() != null,
                resourceLocksIdle = actionGroupLocks.isIdle()
            )
        )
        if (closeoutRejection != null) {
            return@withFaultHeatTransitionLock ManualSupplementConfirmResult(false, "$closeoutRejection，禁止收口绑定")
        }
        val heatPans = KaoPanHelper.getKaoPanList().filter { it.positionSn in 13..18 || it.positionSn in 25..33 }
        val occupied = heatPans.filter { it.isHasSausage }.map { it.positionSn }
        if (occupied.isNotEmpty()) {
            return@withFaultHeatTransitionLock ManualSupplementConfirmResult(
                false,
                "补热源盘仍有烤肠，必须现场清空后再收口：$occupied"
            )
        }
        val resolved = supplementBatchIntentRepository.resolveReturnBindingConflictAfterSourcesCleared(
            expectedBatchId = expected.batchId,
            expectedUpdatedAtMs = expected.updatedAtMs,
            expectedFingerprint = expected.fingerprint,
            confirmedEmptySourcePositions = heatPans.map { it.positionSn }.toSet()
        )
        if (!resolved) {
            return@withFaultHeatTransitionLock ManualSupplementConfirmResult(false, "冲突绑定未能按精确来源收口")
        }
        lastReturnBindingConflictMaintenanceFingerprint = null
        logW(LOG_SUPPLEMENT, "【补肠回搬绑定冲突】全部补热源盘已确认清空，完成无机械绑定收口：reason=$reason")
        ManualSupplementConfirmResult(true, "冲突绑定已收口，设备保持维护状态，需另行确认恢复")
    }

    fun activeSupplementBatchTargetPositions(types: Set<SupplementBatchType>): Set<Int> {
        val intent = supplementBatchIntentRepository.loadActive() ?: return emptySet()
        return intent.remainingTargets()
            .filter { target ->
                target.originBatchType?.let { it in types } ?: types.any { type ->
                    intent.type == type || SupplementBatchIntentRepository.positionMatchesType(target.positionSn, type)
                }
            }
            .map { it.positionSn }
            .filter { it in 1..33 }
            .toSet()
    }

    fun activeSupplementBatchIntentForUi(): SupplementBatchIntent? {
        val intent = supplementBatchIntentRepository.loadActive() ?: return null
        withFaultHeatTransitionLock {
            enforceReturnBindingConflictMaintenanceLocked("算法轮询活动意图查询")
        }
        if (isActionGroupRecoveryBlocked()) {
            return null
        }
        return intent
    }

    fun maintenanceKaoPanIntentForUi(): SupplementBatchIntent? {
        return supplementBatchIntentRepository.loadActive()
    }

    internal fun previewOrphanSupplementReturns(): OrphanSupplementReturnCoordinator.OperationResult {
        val pans = KaoPanHelper.getKaoPanList()
        val sources = orphanSupplementSourcePans(pans)
        return orphanSupplementReturnCoordinator.preview(
            sources = sources,
            frontPans = pans.filter { it.positionSn in 1..21 },
            reservedFrontPositions = activeReturnPositions()
        )
    }

    internal suspend fun rebindOrphanSupplementReturnsToCurrentEmptyFront(
        expectedBindings: List<OrphanSupplementReturnPlanner.Binding>,
        reason: String
    ): OrphanSupplementReturnCoordinator.OperationResult {
        val pans = KaoPanHelper.getKaoPanList()
        val sources = orphanSupplementSourcePans(pans)
        return orphanSupplementReturnCoordinator.commit(
            expectedBindings = expectedBindings,
            sources = sources,
            frontPans = pans,
            reservedFrontPositions = activeReturnPositions(),
            reason = reason
        )
    }

    fun confirmOrphanSupplementSourceTaste(sourcePositionSn: Int, tasteCode: String): Boolean {
        val normalizedTaste = tasteCode.trim()
        if (normalizedTaste.isBlank()) {
            return false
        }
        val availableTastes = KaoPanHelper.getKaoPanBoxList()
            .mapNotNull { it.tasteCode?.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalizedTaste !in availableTastes) {
            return false
        }
        val pans = KaoPanHelper.getKaoPanList()
        val activeBoundSources = supplementBatchIntentRepository.loadActive()
            ?.targets
            ?.filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade && it.returnPositionSn != null }
            ?.map { it.positionSn }
            ?.toSet()
            .orEmpty()
        val source = pans.firstOrNull {
            it.positionSn == sourcePositionSn &&
                OrphanSupplementReturnPlanner.isRecognizedSource(it) &&
                it.positionSn !in activeBoundSources
        } ?: return false
        val previousTaste = source.taste
        source.taste = Taste(normalizedTaste)
        return try {
            KaoPanHelper.saveKaoPanListDurable(pans)
            true
        } catch (_: Exception) {
            source.taste = previousTaste
            false
        }
    }

    fun completedHeatReturnIntentForUi(): SupplementBatchIntent? {
        val active = supplementBatchIntentRepository.loadActive() ?: return null
        val retained = active.retainedCompletedHeatReturnTargets()
        if (retained.isEmpty()) {
            return null
        }
        return active.copy(targets = retained.map { it.copy() }.toMutableList())
    }

    private fun activeReturnPositions(): Set<Int> = supplementBatchIntentRepository.loadActive()
        ?.targets
        ?.filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade }
        ?.mapNotNull { it.returnPositionSn }
        ?.filter { it in 1..21 }
        ?.toSet()
        .orEmpty()

    private fun orphanSupplementSourcePans(pans: List<KaoPan>): List<KaoPan> {
        val activeBoundSources = supplementBatchIntentRepository.loadActive()
            ?.targets
            ?.filter { !it.cancelledByFaultTimeout && !it.skippedBySupplyDegrade && it.returnPositionSn != null }
            ?.map { it.positionSn }
            ?.toSet()
            .orEmpty()
        return pans.filter {
            OrphanSupplementReturnPlanner.isRecognizedSource(it) &&
                it.positionSn !in activeBoundSources
        }
    }

    fun clearCompletedHeatReturnBindingIfSourcesCleared(
        types: Set<SupplementBatchType>,
        sourcePositionsWithSausage: Set<Int>,
        sourcePanStatuses: Map<Int, Int> = emptyMap(),
        reason: String
    ): Boolean {
        return supplementBatchIntentRepository.clearCompletedHeatReturnIfSourceCleared(
            types = types,
            sourcePositionsWithSausage = sourcePositionsWithSausage,
            sourcePanStatuses = sourcePanStatuses,
            reason = reason
        )
    }

    private fun clearReturnBindingForSourceIfAny(sourcePositionSn: Int) {
        val target = supplementBatchIntentRepository.loadActive()
            ?.retainedCompletedHeatReturnTargets()
            ?.firstOrNull { it.positionSn == sourcePositionSn }
            ?: return
        val originBatchId = target.originBatchId ?: return
        val originBatchType = target.originBatchType ?: return
        check(
            supplementBatchIntentRepository.markSourceCleared(
                originBatchId = originBatchId,
                originBatchType = originBatchType,
                sourcePositionSn = sourcePositionSn
            )
        ) { "源盘已清空但精确回搬 binding 收口失败：source=$sourcePositionSn" }
    }

    fun shouldDelayHeatReturnForBatchCompleteness(types: Set<SupplementBatchType>): Boolean {
        return supplementBatchIntentRepository.shouldDelayHeatReturnForBatchCompleteness(types) { intent ->
            clearFaultTimeoutCancelledReturnReservations(intent, "补热批次故障超过10分钟，释放回搬预留")
        }
    }

    fun reconcilePendingSupplementTargetsAlreadyOnPans(
        allowedTypes: Set<SupplementBatchType>,
        expectedBatchId: String?,
        reason: String,
        pans: List<KaoPan> = KaoPanHelper.getKaoPanList()
    ): Int {
        val intent = supplementBatchIntentRepository.loadActive() ?: return 0
        if (expectedBatchId.isNullOrBlank() || intent.batchId != expectedBatchId || intent.type !in allowedTypes) return 0

        val physicalSausagePositions = pans.asSequence()
            .filter { it.isHasSausage == true }
            .map { it.positionSn }
            .toSet()
        val completedTargetPositions = intent.remainingTargets()
            .map { it.positionSn }
            .filter { it in physicalSausagePositions }
            .distinct()
        completedTargetPositions.forEach { targetPosition ->
            supplementBatchIntentRepository.markCompletedFromPhysicalReconciliation(intent.batchId, targetPosition)
        }
        if (completedTargetPositions.isNotEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "补肠目标已实际上盘，补记批次完成状态：reason=$reason，batchId=${intent.batchId}，" +
                    "type=${SupplementBatchIntentRepository.typeText(intent.type)}，targets=$completedTargetPositions"
            )
        }
        return completedTargetPositions.size
    }

    suspend fun handlePendingSupplementBatchIfAny(
        allowedTypes: Set<SupplementBatchType>,
        reason: String,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean,
        isBeforeCloseWindow: Boolean
    ): PendingSupplementBatchHandling {
        val intent = supplementBatchIntentRepository.loadActive() ?: return PendingSupplementBatchHandling.NONE
        if (withFaultHeatTransitionLock { enforceReturnBindingConflictMaintenanceLocked(reason) }) {
            return PendingSupplementBatchHandling.WAITING_MANUAL_CONFIRMATION
        }
        if (intent.remainingTargets().isEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "补热批次已全部上盘，保留回搬绑定等待源盘搬回前区：reason=$reason，" +
                    "batchId=${intent.batchId}，type=${SupplementBatchIntentRepository.typeText(intent.type)}"
            )
            return PendingSupplementBatchHandling.NONE
        }
        if (intent.type !in allowedTypes) {
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次，但当前调度阶段不匹配，暂不启动新补肠：reason=$reason，" +
                    "batchType=${SupplementBatchIntentRepository.typeText(intent.type)}，allowed=${allowedTypes.map { SupplementBatchIntentRepository.typeText(it) }}"
            )
            return PendingSupplementBatchHandling.BLOCKED
        }
        val pans = KaoPanHelper.getKaoPanList()
        reconcilePendingSupplementTargetsAlreadyOnPans(
            allowedTypes = allowedTypes,
            expectedBatchId = intent.batchId,
            reason = "$reason pending续补前对账",
            pans = pans
        )
        val refreshedIntent = supplementBatchIntentRepository.loadActive() ?: return PendingSupplementBatchHandling.NONE
        if (refreshedIntent.batchId != intent.batchId || refreshedIntent.type !in allowedTypes) {
            logW(
                LOG_SUPPLEMENT,
                "物理对账期间活动补肠批次已切换，阻断本轮自动续补：reason=$reason，" +
                    "expectedBatchId=${intent.batchId}，actualBatchId=${refreshedIntent.batchId}，" +
                    "actualType=${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}"
            )
            return PendingSupplementBatchHandling.BLOCKED
        }
        if (refreshedIntent.remainingTargets().isEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "补热批次已全部上盘，保留回搬绑定等待源盘搬回前区：reason=$reason，" +
                    "batchId=${refreshedIntent.batchId}，type=${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}"
            )
            return PendingSupplementBatchHandling.NONE
        }
        if (refreshedIntent.requiresManualConfirm) {
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次仍需人工确认，暂不自动续补：reason=$reason，" +
                    "batchId=${refreshedIntent.batchId}，targetPan=${refreshedIntent.failedTargetPanNo}，action=${refreshedIntent.failedActionName}，" +
                    "failure=${refreshedIntent.failureMessage}"
            )
            return PendingSupplementBatchHandling.WAITING_MANUAL_CONFIRMATION
        }
        if (!schedulingEnabled || !boxSupplyAvailable || AppConfig.getAppConfig().errorStatus != 0 || isBeforeCloseWindow) {
            if (isInitializationBatch(refreshedIntent.type) && !boxSupplyAvailable) {
                supplementBatchIntentRepository.clear("${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}部分开盘收口：烤肠箱无可用库存:$reason")
                logW(
                    LOG_SUPPLEMENT,
                    "${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}存在未完成补肠批次，但烤肠箱无可用库存，按部分开盘收口，不再阻塞已上盘烤肠成熟检查：" +
                        "reason=$reason，batchId=${refreshedIntent.batchId}，remaining=${refreshedIntent.remainingTargets().map { it.positionSn }}"
                )
                return PendingSupplementBatchHandling.NONE
            }
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次但当前不允许续补：reason=$reason，batchId=${refreshedIntent.batchId}，" +
                    "schedulingEnabled=$schedulingEnabled，boxSupplyAvailable=$boxSupplyAvailable，" +
                    "errorStatus=${AppConfig.getAppConfig().errorStatus}，isBeforeCloseWindow=$isBeforeCloseWindow"
            )
            return PendingSupplementBatchHandling.BLOCKED
        }
        if (supplementBatchIntentRepository.cancelRemainingHeatTargetsAfterFaultTimeoutIfExpired(
                types = setOf(refreshedIntent.type),
                reason = "打开算法后检测到长故障，取消旧批次未开始补肠目标:$reason"
            ) { cancelledIntent ->
                clearFaultTimeoutCancelledReturnReservations(cancelledIntent, "补热批次长故障取消未开始目标，释放回搬预留")
            }
        ) {
            logW(
                LOG_SUPPLEMENT,
                "补热批次故障已超过10分钟，取消未开始目标并等待已上盘烤肠收口后重新调度：" +
                    "reason=$reason，batchId=${refreshedIntent.batchId}，type=${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}"
            )
            return PendingSupplementBatchHandling.NONE
        }

        val targets = refreshedIntent.remainingTargets().mapNotNull { target ->
            val pan = pans.firstOrNull { it.positionSn == target.positionSn }
            if (pan == null) {
                logW(LOG_SUPPLEMENT, "未完成补肠批次找不到目标烤盘，跳过：batchId=${refreshedIntent.batchId}，targetPan=${target.positionSn}")
                null
            } else if (pan.isHasSausage) {
                supplementBatchIntentRepository.markCompleted(refreshedIntent.batchId, target.positionSn)
                null
            } else {
                pan.applySupplementIntentTaste(target.tasteCode)
                SupplementTarget(
                    pan,
                    target.tasteCode ?: pan.taste?.tasteCode,
                    returnPositionSn = target.returnPositionSn
                )
            }
        }
        if (targets.isEmpty()) {
            supplementBatchIntentRepository.clearPendingTargetsRetainingCompletedHeatReturnBindings(
                "未完成批次已无剩余目标:$reason"
            )
            return PendingSupplementBatchHandling.NONE
        }
        val availability = planExecutableSupplementTargets(
            targets = targets,
            listBox = KaoPanHelper.getKaoPanBoxList()
        )
        val executableTargets = availability.executableTargets
        if (executableTargets.isEmpty() && isInitializationBatch(intent.type)) {
            supplementBatchIntentRepository.clearPendingTargetsRetainingCompletedHeatReturnBindings(
                "${SupplementBatchIntentRepository.typeText(intent.type)}部分开盘收口：剩余目标无同口味可用库存:$reason"
            )
            logW(
                LOG_SUPPLEMENT,
                "${SupplementBatchIntentRepository.typeText(intent.type)}未完成批次剩余目标无同口味可用库存，按部分开盘收口：" +
                    "reason=$reason，batchId=${intent.batchId}，remaining=${targets.map { it.kaoPan.positionSn }}，" +
                    "skippedDetails=${availability.describeSkippedTargets()}"
            )
            return PendingSupplementBatchHandling.NONE
        }
        if (executableTargets.isEmpty()) {
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次但剩余目标当前无同口味可用库存，暂不续补：" +
                    "reason=$reason，batchId=${intent.batchId}，remaining=${targets.map { it.kaoPan.positionSn }}，" +
                    "skippedDetails=${availability.describeSkippedTargets()}"
            )
            return PendingSupplementBatchHandling.BLOCKED
        }

        applyHeatingForSupplementBatch(refreshedIntent.type)
        LogUtils.i(
                "【补肠批次恢复】检测到未完成补肠批次，人工确认后续补：reason=$reason，" +
                "batchId=${refreshedIntent.batchId}，type=${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}，" +
                "目标烤盘=${executableTargets.joinToString(",") { it.kaoPan.positionSn.toString() }}，" +
                "跳过不可执行目标=${availability.skippedTargets.map { it.targetPositionSn }}，" +
                "skippedDetails=${availability.describeSkippedTargets()}"
        )
        val movedCount = moveSausagesToKaoPansByTasteInternal(
            targets = executableTargets,
            existingBatchId = refreshedIntent.batchId,
            createIntent = false,
            stageForSerial = "补肠批次恢复"
        )
        LogUtils.i(
            "【补肠批次恢复】未完成补肠批次续补结束：batchId=${refreshedIntent.batchId}，目标数=${targets.size}，完成数=$movedCount"
        )
        return PendingSupplementBatchHandling.RESUMED
    }

    private fun isInitializationBatch(type: SupplementBatchType): Boolean =
        type == SupplementBatchType.LOW_INITIALIZATION_1_9 ||
            type == SupplementBatchType.HIGH_INITIALIZATION_1_21

    private fun applyHeatingForSupplementBatch(type: SupplementBatchType) {
        when (type) {
            SupplementBatchType.LOW_INITIALIZATION_1_9 -> heating(1)
            SupplementBatchType.LOW_HEAT_13_15,
            SupplementBatchType.LOW_HEAT_16_18 -> heating(2)
            SupplementBatchType.HIGH_INITIALIZATION_1_21 -> {
                heating(1)
                heating(2)
            }
            SupplementBatchType.HIGH_HEAT_25_33 -> heating(3)
            SupplementBatchType.UNKNOWN -> Unit
        }
    }

    private suspend fun moveSausagesToKaoPansByTasteSerial(
        validTargets: List<SupplementTarget>,
        stage: String,
        batchId: String?,
        batchType: SupplementBatchType?
    ): Int {
        var movedCount = 0
        for (target in validTargets) {
            if (shouldPauseSupplementForOrder("$stage-单根开始前")) {
                break
            }
            if (!ensureSupplementBatchStartAllowed("$stage-单根下发前")) {
                break
            }
            when (val result = moveSausageToKaoPanByTasteDetailed(
                kaoPan = target.kaoPan,
                tasteCode = target.tasteCode,
                batchType = batchType,
                batchId = batchId
            )) {
                SupplementMoveResult.Success -> {
                    movedCount += 1
                    supplementBatchIntentRepository.markCompleted(batchId, target.kaoPan.positionSn)
                }
                is SupplementMoveResult.SupplyDegraded -> {
                    recordNonDemandFrontForSupplementTarget(batchId, target.kaoPan.positionSn, "supply_degrade")
                    if (result.failedBoxNos.isNotEmpty()) {
                        supplementBatchIntentRepository.markFailure(
                            batchId = batchId,
                            targetPanNo = target.kaoPan.positionSn,
                            actionName = "in2",
                            failureMessage = result.reason,
                            requiresManualConfirm = true
                        )
                    }
                    logW(
                        LOG_SUPPLEMENT,
                        "$stage 供给暂不可用，保留当前目标并结束本轮旧计划：targetPan=${target.kaoPan.positionSn}，" +
                            "failedBoxes=${result.failedBoxNos}，reason=${result.reason}"
                    )
                    break
                }
                is SupplementMoveResult.LowerPlatformIsolated -> {
                    recordNonDemandFrontForSupplementTarget(batchId, target.kaoPan.positionSn, "lower_platform_isolated")
                    if (result.requiresManualConfirm) {
                        supplementBatchIntentRepository.markFailure(
                            batchId = batchId,
                            targetPanNo = target.kaoPan.positionSn,
                            actionName = result.actionName,
                            failureMessage = result.reason,
                            requiresManualConfirm = true
                        )
                    } else {
                        supplementBatchIntentRepository.markSkippedBySupplyDegrade(
                            batchId = batchId,
                            targetPanNo = target.kaoPan.positionSn,
                            reason = result.reason
                        )
                    }
                    logW(
                        LOG_SUPPLEMENT,
                        "$stage 下平台隔离并结束本轮旧计划：targetPan=${target.kaoPan.positionSn}，" +
                            "sourceBox=${result.sourceBoxNo ?: "未知"}，action=${result.actionName}，" +
                            "error=${AtomicActionExecutor.formatCode(result.code)}，" +
                            "requiresManualConfirm=${result.requiresManualConfirm}，reason=${result.reason}"
                    )
                    break
                }
                is SupplementMoveResult.HardFailure -> {
                    recordNonDemandFrontForSupplementTarget(batchId, target.kaoPan.positionSn, "action_failure")
                    supplementBatchIntentRepository.markFailure(
                        batchId = batchId,
                        targetPanNo = target.kaoPan.positionSn,
                        actionName = "in",
                        failureMessage = result.reason,
                        requiresManualConfirm = true
                    )
                    break
                }
            }
            if (shouldPauseSupplementForOrder("$stage-单根完成后")) {
                break
            }
        }
        return movedCount
    }

    internal fun shouldCreateSupplementIntent(
        targets: List<SupplementTarget>,
        batchType: SupplementBatchType?
    ): Boolean =
        targets.size > 1 ||
            targets.any { it.returnPositionSn != null } ||
            batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true

    internal fun initializationGapPositions(
        requestedTargets: List<SupplementTarget>,
        batchType: SupplementBatchType?
    ): Set<Int> =
        if (batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true) {
            requestedTargets.map { it.kaoPan.positionSn }.filter { it in 1..21 }.toSet()
        } else {
            emptySet()
        }

    private fun recordNonDemandFrontForSupplementTarget(
        batchId: String?,
        targetPanNo: Int,
        reason: String
    ) {
        val intent = supplementBatchIntentRepository.loadActive()
            ?.takeIf { it.batchId == batchId }
            ?: return
        val target = intent.targets.firstOrNull { it.positionSn == targetPanNo } ?: return
        val frontPosition = if (SupplementBatchIntentRepository.isInitializationBatch(intent.type)) {
            target.positionSn
        } else {
            target.returnPositionSn
        }
        if (frontPosition != null && frontPosition in 1..21) {
            LowToHighDemandGate.recordNonDemandEmpty(frontPosition, reason)
        }
    }

    suspend fun moveSausagesToKaoPansByTaste(
        targets: List<SupplementTarget>,
        batchTypeOverride: SupplementBatchType? = null
    ): Int {
        return moveSausagesToKaoPansByTasteInternal(
            targets = targets,
            existingBatchId = null,
            createIntent = shouldCreateSupplementIntent(targets, batchTypeOverride),
            stageForSerial = "补肠串行动作",
            batchTypeOverride = batchTypeOverride
        )
    }

    internal suspend fun moveSausagesToKaoPansForResume(
        targets: List<SupplementTarget>,
        batchTypeOverride: SupplementBatchType,
        onAdmitted: () -> Unit
    ): SupplementBatchAttemptResult {
        var admittedBatchId: String? = null
        val movedCount = moveSausagesToKaoPansByTasteInternal(
            targets = targets,
            existingBatchId = null,
            createIntent = true,
            stageForSerial = "补热残缺组恢复",
            batchTypeOverride = batchTypeOverride,
            admissionObserver = { batchId ->
                admittedBatchId = batchId
                onAdmitted()
            }
        )
        return SupplementBatchAttemptResult(
            admitted = admittedBatchId != null,
            movedCount = movedCount,
            batchId = admittedBatchId
        )
    }

    private suspend fun moveSausagesToKaoPansByTasteInternal(
        targets: List<SupplementTarget>,
        existingBatchId: String?,
        createIntent: Boolean,
        stageForSerial: String,
        batchTypeOverride: SupplementBatchType? = null,
        admissionObserver: (String) -> Unit = {}
    ): Int {
        val validTargets = targets.filter { it.kaoPan.positionSn in 1..33 }
        if (validTargets.isEmpty()) {
            return 0
        }
        initializationGapPositions(validTargets, batchTypeOverride).forEach { positionSn ->
            LowToHighDemandGate.recordNonDemandEmpty(positionSn, "initialization_gap")
        }
        val availability = planExecutableSupplementTargets(
            targets = validTargets,
            listBox = KaoPanHelper.getKaoPanBoxList()
        )
        val executableTargets = availability.executableTargets
        if (executableTargets.isEmpty()) {
            logW(
                LOG_SUPPLEMENT,
                "补肠批次无可执行目标：requestedTargets=${validTargets.map { it.kaoPan.positionSn }}，" +
                    "原因=同口味烤肠箱库存不足或候选箱不可用，" +
                    "requestedTastes=${validTargets.map { it.kaoPan.positionSn to (it.tasteCode ?: it.kaoPan.taste?.tasteCode) }}，" +
                    "skippedDetails=${availability.describeSkippedTargets()}"
            )
            return 0
        }
        if (executableTargets.size < validTargets.size) {
            logW(
                LOG_SUPPLEMENT,
                "补肠批次按当前库存部分开盘：requestedTargets=${validTargets.map { it.kaoPan.positionSn }}，" +
                    "executableTargets=${executableTargets.map { it.kaoPan.positionSn }}，" +
                    "requestedTastes=${validTargets.map { it.kaoPan.positionSn to (it.tasteCode ?: it.kaoPan.taste?.tasteCode) }}，" +
                    "skippedTargets=${availability.skippedTargets.map { it.targetPositionSn }}，" +
                    "skippedDetails=${availability.describeSkippedTargets()}"
            )
        }
        if (!ensureSupplementBatchStartAllowed("补肠批次入口")) {
            return 0
        }
        val activeIntent = supplementBatchIntentRepository.loadActive()
        if (createIntent && activeIntent != null && activeIntent.remainingTargets().isNotEmpty()) {
            logW(
                LOG_SUPPLEMENT,
                "存在未完成补肠批次，拒绝启动新补肠批次：activeBatchId=${activeIntent.batchId}，" +
                    "activeType=${SupplementBatchIntentRepository.typeText(activeIntent.type)}，" +
                    "newTargets=${executableTargets.map { it.kaoPan.positionSn }}"
            )
            return 0
        }
        if (AppConfig.getAppConfig().enableActionGroupPrefetch != 1 || executableTargets.size <= 1) {
            val batchId = existingBatchId ?: if (createIntent) {
                supplementBatchIntentRepository.begin(executableTargets, batchTypeOverride)?.batchId ?: return 0
            } else {
                null
            }
            val batchType = batchTypeOverride ?: batchId?.let {
                supplementBatchIntentRepository.loadActive()?.takeIf { intent -> intent.batchId == batchId }?.type
            }
            if (existingBatchId == null && batchId != null) {
                admissionObserver(batchId)
            }
            return moveSausagesToKaoPansByTasteSerial(executableTargets, stageForSerial, batchId, batchType)
        }
        if (AppConfig.getAppConfig().errorStatus > 0) {
            logD(LOG_SUPPLEMENT, "程序处于报错状态，不进行补肠预取流水线")
            return 0
        }
        if (!ensureModbusReady("补肠预取流水线恢复检查")) {
            enterActionGroupMaintenance(
                "补肠预取流水线入口前 Modbus 重连 3 次仍失败，设备进入维护：stage=补肠预取流水线",
                FaultCode.MODBUS_COMM_DROP
            )
            return 0
        }
        if (!recoverActionGroupStateAfterModbusReady(
                "补肠预取流水线入口前恢复检查",
                stopAfterParallelSessionHandled = true
            )
        ) {
            return 0
        }

        val returnPositionsByTarget = executableTargets.associate {
            it.kaoPan.positionSn to it.returnPositionSn
        }
        val plannedMoves = buildPipelineSupplementPlan(executableTargets)
        if (plannedMoves.size <= 1) {
            logD(
                LOG_SUPPLEMENT,
                "补肠预取流水线降级串行：有效计划数=${plannedMoves.size}，请求数=${executableTargets.size}"
            )
            val batchId = existingBatchId ?: if (createIntent && plannedMoves.isNotEmpty()) {
                supplementBatchIntentRepository.begin(
                    plannedMoves.map {
                        SupplementTarget(
                            it.kaoPan,
                            it.tasteCode,
                            returnPositionSn = returnPositionsByTarget[it.kaoPan.positionSn]
                        )
                    },
                    batchTypeOverride
                )?.batchId
                    ?: return 0
            } else {
                existingBatchId
            }
            if (existingBatchId == null && batchId != null) {
                admissionObserver(batchId)
            }
            return moveSausagesToKaoPansByTasteSerial(
                plannedMoves.map {
                    SupplementTarget(
                        it.kaoPan,
                        it.tasteCode,
                        returnPositionSn = returnPositionsByTarget[it.kaoPan.positionSn]
                    )
                },
                "补肠预取流水线降级串行",
                batchId,
                batchTypeOverride ?: batchId?.let {
                    supplementBatchIntentRepository.loadActive()?.takeIf { intent -> intent.batchId == batchId }?.type
                }
            )
        }
        val batchId = existingBatchId ?: if (createIntent) {
            supplementBatchIntentRepository.begin(
                plannedMoves.map {
                    SupplementTarget(
                        it.kaoPan,
                        it.tasteCode,
                        returnPositionSn = returnPositionsByTarget[it.kaoPan.positionSn]
                    )
                },
                batchTypeOverride
            )?.batchId
                ?: return 0
        } else {
            existingBatchId
        }
        if (existingBatchId == null && batchId != null) {
            admissionObserver(batchId)
        }

        if (shouldPauseSupplementForOrder("补肠预取流水线启动前")) {
            return 0
        }
        var appState = AppConfig.getAppConfig().moveStatus
        while (appState != 0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_SUPPLEMENT, "补肠预取流水线前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
        }
        waitForPostSellSupplementGuardIfNeeded()
        if (shouldPauseSupplementForOrder("补肠预取流水线占用机械臂前")) {
            return 0
        }

        val parallelEnabled = isParallelSupplementPrefetchEnabled()
        val sessionId = "in_pipeline:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        val firstMove = plannedMoves.first()
        val firstBox = KaoPanHelper.getKaoPanBoxList()
            .firstOrNull { it.positionSn == firstMove.task.sourceBoxCandidates.first() }
        val preparedOwnerStore = if (parallelEnabled) {
            if (readSupplementPrefetchEvidence() !is DurableEvidenceReadResult.Absent) {
                logE(LOG_SUPPLEMENT, "已有或损坏的双 lane 补肠证据，禁止覆盖启动新流水线")
                return 0
            }
            supplementPipelineClaimOwnerStore(
                SupplementPrefetchPipelineSession(
                        sessionId = sessionId,
                        batchId = batchId,
                        resourceMask = lockMask,
                        deliveryLane = SupplementPrefetchLaneCheckpoint(
                            sourceBoxNo = firstMove.task.sourceBoxCandidates.first(),
                            sourceBoxCandidates = firstMove.task.sourceBoxCandidates,
                            targetPanNo = firstMove.task.targetPanNo,
                            taste = firstMove.tasteCode,
                            returnPosition = firstMove.returnPosition,
                            stockBefore = firstBox?.num,
                            expectedStockAfter = firstBox?.num?.let { (it - 1).coerceAtLeast(0) }
                        )
                    )
            )
        } else {
            actionGroupClaimOwnerStore(
                ActionGroupSession(
                        sessionId = sessionId,
                        type = ActionGroupSessionType.IN_SUPPLEMENT,
                        sourceNo = firstMove.task.sourceBoxCandidates.first(),
                        targetNo = firstMove.task.targetPanNo,
                        currentAction = null,
                        resourceMask = lockMask,
                        batchId = batchId,
                        taste = firstMove.tasteCode,
                        returnPosition = firstMove.returnPosition,
                        prefetch = true,
                        checkpointVersion = 0L,
                        stockBefore = firstBox?.num,
                        expectedStockAfter = firstBox?.num?.let { (it - 1).coerceAtLeast(0) }
                    )
            )
        }
        if (!claimPreparedSupplementMove(
                stage = "补肠预取流水线下发前",
                owner = sessionId,
                ownerStore = preparedOwnerStore
            )
        ) {
            return 0
        }
        val pipelineClaimRevision = preparedOwnerStore.snapshot()
            ?.takeIf { it.owner == sessionId }
            ?.revision
            ?: return 0
        publishRuntimeStateWithHttpFallback("补肠预取流水线开始")

        if (!isActionGroupEnabledOrEnterMaintenance("补肠预取流水线")) {
            releaseSupplementMoveClaimIfOwned(
                owner = sessionId,
                ownerStore = preparedOwnerStore,
                resourceMask = lockMask,
                expectedRevision = pipelineClaimRevision
            )
            publishRuntimeStateWithHttpFallback("补肠预取流水线启动前终止")
            return 0
        }

        if (!actionGroupLocks.tryAcquire(lockMask, sessionId)) {
            releaseSupplementMoveClaimIfOwned(
                owner = sessionId,
                ownerStore = preparedOwnerStore,
                resourceMask = lockMask,
                expectedRevision = pipelineClaimRevision
            )
            publishRuntimeStateWithHttpFallback("补肠预取流水线资源忙终止")
            logW(
                LOG_ACTION_GROUP,
                "补肠预取流水线资源忙，拒绝下发：need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return 0
        }

        val taskByPanNo = plannedMoves.associateBy { it.task.targetPanNo }
        var completedCount = 0
        var durableCompletionFailed = false
        val supplementWriteRecords = mutableListOf<SupplementWriteRecord>()
        val appliedCompletedPanNos = mutableSetOf<Int>()
        val physicallyDeductedTargetPanNos = mutableSetOf<Int>()
        val physicallyPlacedTargetPanNos = mutableSetOf<Int>()
        val stockInconsistentTargetPanNos = mutableSetOf<Int>()
        fun applyCompletedPipelineTask(completedTask: CompletedInActionGroupPipelineTask) {
            if (completedTask.targetPanNo in appliedCompletedPanNos) {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠预取流水线跳过重复回写：sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
                )
                return
            }
            if (completedTask.targetPanNo in stockInconsistentTargetPanNos) {
                appliedCompletedPanNos += completedTask.targetPanNo
                enterActionGroupMaintenance(
                    "补肠预取流水线目标已落盘但来源箱扣减不可信，需人工确认盘箱状态：" +
                        "sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
                )
                return
            }
            val move = taskByPanNo[completedTask.targetPanNo]
            if (move == null) {
                enterActionGroupMaintenance(
                    "补肠预取流水线单根完成后找不到目标计划，需人工确认：sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
                )
                return
            }
            val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == completedTask.sourceBoxNo }
            if (box == null) {
                enterActionGroupMaintenance(
                    "补肠预取流水线单根完成后找不到实际来源烤肠箱，需人工确认：sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
                )
                return
            }

            val appliedMove = applySuccessfulSupplementMove(
                kaoPan = move.kaoPan,
                kaoPanBox = box,
                boxAlreadyDeducted = completedTask.targetPanNo in physicallyDeductedTargetPanNos,
                panAlreadyPlaced = completedTask.targetPanNo in physicallyPlacedTargetPanNos
            )
            if (appliedMove == null) {
                enterActionGroupMaintenance(
                    "补肠预取流水线单根完成后找不到当前烤盘缓存对象，需人工确认：sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
                )
                return
            }
            supplementWriteRecords += appliedMove.writeRecord
            appliedCompletedPanNos += completedTask.targetPanNo
            completedCount += 1
            try {
                KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                supplementBatchIntentRepository.markCompleted(batchId, completedTask.targetPanNo)
            } catch (e: Exception) {
                durableCompletionFailed = true
                enterActionGroupMaintenance(
                    "补肠预取目标完成后的盘箱/binding耐久收口失败，保留session：" +
                        "targetPan=${completedTask.targetPanNo}，${e.message}"
                )
                return
            }
            LowerPlatformRuntimeStateReporter.markSupplyBoxRecoveredAfterSuccessfulTake(
                sourceBoxNo = completedTask.sourceBoxNo,
                reason = "补肠预取流水线完整落盘成功"
            )
            reportSupplementCompletionPhysicalDeltaIfNeeded(
                sourceBox = appliedMove.sourceBox,
                targetPan = appliedMove.targetPan,
                reportStock = appliedMove.reportStockDelta,
                reportPan = appliedMove.reportPanDelta,
                reason = "action_completion:pipeline:box=${appliedMove.sourceBox.positionSn}:pan=${appliedMove.targetPan.positionSn}",
                source = "action_completion_pipeline"
            )
            logD(
                LOG_SUPPLEMENT,
                "补肠预取流水线单根状态已回写：sourceBox=${completedTask.sourceBoxNo}，" +
                    "targetPan=${completedTask.targetPanNo}，successCount=$completedCount"
            )
        }
        var actionGroupCompleted = false
        var pipelineResultForCleanup: InActionGroupPipelineResult? = null
        var replanExpectedRevision: Long? = null
        var pausedCleanupSucceeded = false
        var latestCurrentAction: InActionGroupPipelineActionContext? = null
        var latestRiskAction: InActionGroupPipelineActionContext? = null
        var safeParallelSupplyDegrade: Boolean
        val result = try {
            logD(
                LOG_ACTION_GROUP,
                "补肠预取流水线开始：protocol=300_400，targetCount=${plannedMoves.size}，" +
                    "resourceLocks=${actionGroupLocks.snapshot()}"
            )
            val tasteSummary = plannedMoves
                .map { it.tasteCode?.takeIf { tasteCode -> tasteCode.isNotBlank() } ?: "未知" }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(",") { (tasteCode, count) -> "$tasteCode=$count" }
                .ifBlank { "空" }
            LogUtils.i(
                "【补肠】开始批量补肠：目标烤盘=${plannedMoves.joinToString(",") { it.task.targetPanNo.toString() }}，" +
                    "口味=${plannedMoves.mapNotNull { it.tasteCode }.distinct().joinToString(",").ifBlank { "未知" }}，" +
                "口味数量=$tasteSummary"
            )
            val physicalCoordinator = if (parallelEnabled) {
                SupplementPrefetchPhysicalStateCoordinator(
                    repository = supplementPrefetchSessionRepository,
                    persistPhysicalState = { _, _ ->
                        KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                        KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                    },
                    publishPhysicalDelta = { role, actionName ->
                        val session = requireNotNull(supplementPrefetchSessionRepository.load())
                        val lane = if (role == SupplementLaneRole.DELIVERY) {
                            session.deliveryLane
                        } else {
                            session.prefetchLane
                        } ?: error("$role lane 不存在")
                        if (actionName == "in2") {
                            val changedBox = KaoPanHelper.getKaoPanBoxList()
                                .firstOrNull { it.positionSn == lane.sourceBoxNo }
                                ?: error("in2 来源肠箱不存在：${lane.sourceBoxNo}")
                            PanPhysicalStateDeltaReporter.reportStockChanged(
                                changedStock = changedBox,
                                reason = "action_physical:parallel_in2:box=${lane.sourceBoxNo}:pan=${lane.targetPanNo}",
                                source = "parallel_action_physical_in2"
                            )
                        } else if (actionName == "in7") {
                            val changedPan = KaoPanHelper.getKaoPanList()
                                .firstOrNull { it.positionSn == lane.targetPanNo }
                                ?: error("in7 目标烤盘不存在：${lane.targetPanNo}")
                            PanPhysicalStateDeltaReporter.reportPanChanged(
                                changedPan = changedPan,
                                reason = "action_physical:parallel_in7:box=${lane.sourceBoxNo}:pan=${lane.targetPanNo}",
                                source = "parallel_action_physical_in7"
                            )
                        }
                    }
                )
            } else {
                null
            }
            InActionGroupPrefetchPipelineRunner(
                client = createAtomicActionClient(),
                actionClientProvider = { context ->
                    createAtomicActionClient(
                        ActionGroupTelemetryContext(
                            sessionId = sessionId,
                            boxNo = context.sourceBoxNo,
                            panNo = context.targetPanNo,
                            resourceLocks = actionGroupLocks.snapshot()
                        )
                    ) { telemetry ->
                        val move = taskByPanNo[context.targetPanNo]
                        val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == context.sourceBoxNo }
                        if (move == null || box == null) {
                            logE(
                                LOG_SUPPLEMENT,
                                "补肠预取流水线动作级物理状态回写缺少计划或肠箱：targetPan=${context.targetPanNo}，" +
                                    "sourceBox=${context.sourceBoxNo}，action=${telemetry.actionName}"
                            )
                            return@createAtomicActionClient
                        }
                        val applied = if (parallelEnabled &&
                            telemetry.resultCode == AtomicActionCodes.COMPLETED &&
                            (ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(telemetry) ||
                                ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(telemetry))
                        ) {
                            when (val checkpoint = requireNotNull(physicalCoordinator)
                                .applySuccessfulPhysicalCheckpoint(context.laneRole, telemetry.actionName) { lane ->
                                    val alreadyApplied = when (telemetry.actionName) {
                                        "in2" -> lane.expectedStockAfter?.let { expected ->
                                            KaoPanHelper.getKaoPanBoxList()
                                                .firstOrNull { it.positionSn == lane.sourceBoxNo }
                                                ?.num == expected
                                        } == true
                                        "in7" -> KaoPanHelper.getKaoPanList()
                                            .firstOrNull { it.positionSn == lane.targetPanNo }
                                            ?.isHasSausage == true
                                        else -> false
                                    }
                                    if (alreadyApplied) {
                                        return@applySuccessfulPhysicalCheckpoint SupplementPhysicalMutation.ALREADY_APPLIED
                                    }
                                    val physical = applySupplementPhysicalTelemetry(
                                        telemetry = telemetry,
                                        plannedPan = move.kaoPan,
                                        plannedBox = box,
                                        source = "补肠双 lane 流水线",
                                        boxAlreadyDeducted = lane.boxDeductionApplied,
                                        panAlreadyPlaced = lane.panPlacementApplied,
                                        persistAndPublish = false
                                    )
                                    when {
                                        physical.stockInconsistent -> SupplementPhysicalMutation.FAILED
                                        physical.boxDeducted || physical.panPlaced -> SupplementPhysicalMutation.APPLIED
                                        else -> SupplementPhysicalMutation.FAILED
                                    }
                                }) {
                                is SupplementPhysicalCheckpointResult.Failed ->
                                    throw IllegalStateException(checkpoint.reason, checkpoint.cause)
                                SupplementPhysicalCheckpointResult.Applied,
                                SupplementPhysicalCheckpointResult.AlreadyApplied ->
                                    AppliedSupplementPhysicalTelemetry(
                                        boxDeducted = telemetry.actionName == "in2",
                                        panPlaced = telemetry.actionName == "in7"
                                    )
                            }
                        } else {
                            applySupplementPhysicalTelemetry(
                                telemetry = telemetry,
                                plannedPan = move.kaoPan,
                                plannedBox = box,
                                source = "补肠预取流水线",
                                boxAlreadyDeducted = context.targetPanNo in physicallyDeductedTargetPanNos,
                                panAlreadyPlaced = context.targetPanNo in physicallyPlacedTargetPanNos
                            )
                        }
                        if (applied.boxDeducted) {
                            physicallyDeductedTargetPanNos += context.targetPanNo
                        }
                        if (applied.panPlaced) {
                            physicallyPlacedTargetPanNos += context.targetPanNo
                        }
                        if (applied.stockInconsistent) {
                            stockInconsistentTargetPanNos += context.targetPanNo
                        } else {
                            recordConfirmedSupplementSessionStage(
                                sessionId = sessionId,
                                actionName = telemetry.actionName,
                                sourceBoxNo = context.sourceBoxNo,
                                targetPanNo = context.targetPanNo,
                                resultCode = telemetry.resultCode
                            )
                        }
                    }
                },
                logger = { message -> logD(LOG_ACTION_GROUP, message) },
                operationLogger = { message -> LogUtils.i(message) },
                failureLogger = { message -> LogUtils.e(message) },
                onTaskCompleted = { completedTask ->
                    applyCompletedPipelineTask(completedTask)
                    if (latestRiskAction?.targetPanNo == completedTask.targetPanNo) {
                        latestRiskAction = null
                    }
                },
                onPickFailureSkipped = { sourceBoxNo ->
                    recordIrPickFailureAndSave(
                        sourceBoxNo = sourceBoxNo,
                        reason = "补肠预取流水线连续取肠失败后跳过候选箱"
                    )
                },
                onTerminalPrefetchPickFailure = { context ->
                    if (parallelEnabled) {
                        recordIrPickFailureAndSave(
                            sourceBoxNo = context.sourceBoxNo,
                            reason = "补肠双 lane prefetch 最终取肠失败"
                        )
                    }
                },
                onPickSucceeded = { sourceBoxNo ->
                    LowerPlatformRuntimeStateReporter.markSupplyBoxRecoveredAfterSuccessfulTake(
                        sourceBoxNo = sourceBoxNo,
                        reason = "补肠预取流水线 in2 真实成功"
                    )
                },
                pauseReasonAfterFallbackSource = { task, actualSourceBoxNo ->
                    "预取实际来源箱偏离原批量库存预留，停止本轮旧计划并重新规划剩余补肠：" +
                        "targetPan=${task.targetPanNo}，plannedPrimaryBox=${task.sourceBoxCandidates.first()}，" +
                        "actualSourceBox=$actualSourceBoxNo"
                },
                onCurrentActionChanged = { context ->
                    withFaultHeatTransitionLock {
                        val currentOwner = if (parallelEnabled) {
                            supplementPrefetchSessionRepository.load()?.sessionId
                        } else {
                            actionGroupSessionStore.load()?.sessionId
                        }
                        check(currentOwner == sessionId) {
                            "迟到补肠流水线回调无权更新新会话：expected=$sessionId，current=$currentOwner"
                        }
                        latestCurrentAction = context
                        if (isSupplementPipelineDropRiskAction(context.actionName)) {
                            latestRiskAction = context
                        }
                        val checkpointMove = taskByPanNo[context.targetPanNo]
                        val previousSession = actionGroupSessionStore.load()
                        val sameMove = previousSession?.sourceNo == context.sourceBoxNo &&
                            previousSession.targetNo == context.targetPanNo
                        val currentBoxStock = KaoPanHelper.getKaoPanBoxList()
                            .firstOrNull { it.positionSn == context.sourceBoxNo }
                            ?.num
                        if (parallelEnabled) {
                            val parallelSession = requireNotNull(supplementPrefetchSessionRepository.load())
                        val existingLane = if (context.laneRole == SupplementLaneRole.DELIVERY) {
                            parallelSession.deliveryLane
                        } else {
                            parallelSession.prefetchLane
                        }
                        val switchingFallbackSource = existingLane?.currentAction == "in2" &&
                            context.actionName == "in1" &&
                            existingLane.sourceBoxNo != context.sourceBoxNo
                        if (switchingFallbackSource) {
                            supplementPrefetchSessionRepository.restartLaneAfterPickFailure(
                                role = context.laneRole,
                                failedSourceBoxNo = requireNotNull(existingLane?.sourceBoxNo),
                                fallbackSourceBoxNo = context.sourceBoxNo,
                                targetPanNo = context.targetPanNo,
                                stockBefore = currentBoxStock
                            )
                        } else if (existingLane?.currentAction != context.actionName) {
                            val lane = (existingLane ?: SupplementPrefetchLaneCheckpoint(
                                sourceBoxNo = context.sourceBoxNo,
                                sourceBoxCandidates = context.task.sourceBoxCandidates,
                                targetPanNo = context.targetPanNo,
                                taste = checkpointMove?.tasteCode,
                                returnPosition = checkpointMove?.returnPosition,
                                stockBefore = currentBoxStock,
                                expectedStockAfter = currentBoxStock?.let { (it - 1).coerceAtLeast(0) }
                            )).copy(
                                sourceBoxNo = context.sourceBoxNo,
                                targetPanNo = context.targetPanNo
                            )
                            supplementPrefetchSessionRepository.prepareAction(
                                context.laneRole,
                                lane,
                                context.actionName
                            )
                        }
                        } else {
                            actionGroupSessionStore.save(
                            ActionGroupSession(
                                sessionId = sessionId,
                                type = ActionGroupSessionType.IN_SUPPLEMENT,
                                sourceNo = context.sourceBoxNo,
                                targetNo = context.targetPanNo,
                                currentAction = context.actionName,
                                resourceMask = lockMask,
                                startedAtMs = previousSession?.startedAtMs ?: System.currentTimeMillis(),
                                batchId = batchId,
                                taste = checkpointMove?.tasteCode,
                                returnPosition = checkpointMove?.returnPosition,
                                prefetch = true,
                                checkpointVersion = (previousSession?.checkpointVersion ?: 0L) + 1L,
                                lastConfirmedStage = previousSession?.lastConfirmedStage.takeIf { sameMove },
                                stockBefore = if (sameMove) previousSession?.stockBefore else currentBoxStock,
                                expectedStockAfter = if (sameMove) {
                                    previousSession?.expectedStockAfter
                                } else {
                                    currentBoxStock?.let { (it - 1).coerceAtLeast(0) }
                                },
                                boxDeductionApplied = sameMove && previousSession?.boxDeductionApplied == true,
                                panPlacementApplied = sameMove && previousSession?.panPlacementApplied == true,
                                mechanicalClaimState = previousSession?.mechanicalClaimState,
                                mechanicalClaimRevision = previousSession?.mechanicalClaimRevision ?: 0L
                            )
                            )
                        }
                        logD(
                            LOG_ACTION_GROUP,
                            "补肠预取流水线当前动作：targetPan=${context.targetPanNo}，" +
                                "sourceBox=${context.sourceBoxNo}，action=${context.actionName}"
                        )
                    }
                },
                onActionCommandWritten = { context ->
                    if (parallelEnabled) {
                        val session = requireNotNull(supplementPrefetchSessionRepository.load())
                        val lane = if (context.laneRole == SupplementLaneRole.DELIVERY) {
                            session.deliveryLane
                        } else {
                            session.prefetchLane
                        }
                        if (lane?.lifecycle == SupplementActionLifecycle.PREPARED) {
                            supplementPrefetchSessionRepository.markCommandWritten(
                                context.laneRole,
                                context.actionName
                            )
                        }
                    }
                },
                onActionRunningConfirmed = { context ->
                    if (parallelEnabled) {
                        val session = requireNotNull(supplementPrefetchSessionRepository.load())
                        val lane = if (context.laneRole == SupplementLaneRole.DELIVERY) {
                            session.deliveryLane
                        } else {
                            session.prefetchLane
                        }
                        if (lane?.lifecycle == SupplementActionLifecycle.COMMAND_WRITTEN) {
                            supplementPrefetchSessionRepository.markRunningConfirmed(
                                context.laneRole,
                                context.actionName
                            )
                        }
                    }
                },
                onActionTerminalConfirmed = { context, _ ->
                    if (parallelEnabled) {
                        val session = requireNotNull(supplementPrefetchSessionRepository.load())
                        val lane = if (context.laneRole == SupplementLaneRole.DELIVERY) {
                            session.deliveryLane
                        } else {
                            session.prefetchLane
                        }
                        if (lane?.lifecycle == SupplementActionLifecycle.COMMAND_WRITTEN ||
                            lane?.lifecycle == SupplementActionLifecycle.RUNNING_CONFIRMED
                        ) {
                            supplementPrefetchSessionRepository.markTerminalConfirmed(
                                context.laneRole,
                                context.actionName,
                                allowWithoutRunningEvidence = true
                            )
                        }
                    }
                },
                onPrefetchPromoted = {
                    if (parallelEnabled) {
                        supplementPrefetchSessionRepository.promotePrefetchAfterDelivery()
                    }
                },
                onPausedPrefetchPromoted = {
                    if (parallelEnabled) {
                        supplementPrefetchSessionRepository.promotePrefetchAfterDelivery(
                            allowPausedCheckpoint = true
                        )
                    }
                },
                beforeDeliveryCommit = { context ->
                    SupplementDeliveryCommitCoordinator(
                        transitionLock = faultHeatTransitionLock,
                        orderPending = SupplementPauseController::shouldPauseForOrder
                    ) {
                        if (parallelEnabled) {
                            supplementPrefetchSessionRepository.tryCommitDelivery(
                                context.sourceBoxNo,
                                context.targetPanNo
                            )
                        } else {
                            true
                        }
                    }.tryCommit()
                },
                parallelEnabled = parallelEnabled,
                pauseReasonAfterCurrentTask = {
                    when {
                        SupplementPauseController.shouldPauseForOrder() -> "检测到订单待出餐"
                        !ensureSupplementBatchStartAllowed("补肠预取流水线启动下一根预取前") ->
                            "动作组故障锁存门禁拒绝启动下一根预取"
                        else -> null
                    }
                },
                pauseReasonBeforePick = {
                    if (SupplementPauseController.shouldPauseForOrder()) "检测到订单待出餐" else null
                },
                pauseReasonAfterPrefetchAction = { actionName ->
                    if (actionName in setOf("in2", "in3") && SupplementPauseController.shouldPauseForOrder()) {
                        "检测到订单待出餐，$actionName 已完成，保留滚筒状态"
                    } else {
                        null
                    }
                }
            )
                .run(plannedMoves.map { it.task })
                .also {
                    pipelineResultForCleanup = it
                    actionGroupCompleted = true
                    if (parallelEnabled && it is InActionGroupPipelineResult.ReplanRequired) {
                        replanExpectedRevision = activeSupplementPrefetchSessionOrNull("备用箱重规划收口快照")
                            ?.takeIf { session -> session.sessionId == sessionId }
                            ?.revision
                    }
                    if (!parallelEnabled &&
                        it is InActionGroupPipelineResult.Paused &&
                        it.pausedAfterAction in setOf("in1", "in2", "in3")
                    ) {
                        actionGroupSessionStore.load()?.let { session ->
                            val pausedSession = session.copy(
                                lastConfirmedStage = it.pausedAfterAction,
                                pausedForOrder = true,
                                resumeAfterAction = it.pausedAfterAction,
                                checkpointVersion = session.checkpointVersion + 1L
                            )
                            pausedSupplementSessionStore.save(pausedSession)
                            actionGroupSessionStore.clear()
                        }
                    }
                }
        } catch (e: CancellationException) {
            markSupplementPipelineCancellationRisk(
                batchId = batchId,
                currentAction = latestCurrentAction,
                riskAction = latestRiskAction,
                throwable = e
            )
            throw e
        } catch (e: Exception) {
            enterActionGroupMaintenance(
                "补肠预取流水线 durable session/runner 接线异常，保留 session/锁/moveStatus：" +
                    "${e::class.java.simpleName}:${e.message}",
                preserveMoveStatus = true
            )
            throw e
        } finally {
            safeParallelSupplyDegrade = parallelEnabled &&
                (pipelineResultForCleanup as? InActionGroupPipelineResult.Failed)?.let { failure ->
                    ParallelSupplementE5ClosurePolicy.canRelease(
                        failure,
                        activeSupplementPrefetchSessionOrNull("双 lane E5 安全收口判定"),
                        sessionId
                    )
                } == true
            val pausedSupplementContinuation = pipelineResultForCleanup is InActionGroupPipelineResult.Paused &&
                hasPausedSupplementSession()
            val safePausedSession = activeSafePausedSupplementSession()
            val safeParallelSuccess = parallelEnabled &&
                pipelineResultForCleanup is InActionGroupPipelineResult.Success &&
                activeSupplementPrefetchSessionOrNull("双 lane 成功收口判定")?.sessionId == sessionId
            val safeReleaseConfirmed =
                (pipelineResultForCleanup is InActionGroupPipelineResult.Paused &&
                    safePausedSession?.sessionId == sessionId) ||
                    safeParallelSupplyDegrade ||
                    safeParallelSuccess
            val retainParallelEvidence = SupplementPipelineFailureRetentionPolicy.shouldRetain(
                evidenceActive = hasBlockingSupplementPrefetchEvidence(),
                outcome = if (safeParallelSupplyDegrade) {
                    SupplementPipelineOutcome.SUPPLY_DEGRADED
                } else {
                    supplementPipelineOutcome(pipelineResultForCleanup)
                },
                safeReleaseConfirmed = safeReleaseConfirmed
            )
            val retainFailureEvidence = !actionGroupCompleted ||
                (pipelineResultForCleanup is InActionGroupPipelineResult.Failed && !safeParallelSupplyDegrade) ||
                pausedSupplementContinuation ||
                durableCompletionFailed ||
                retainParallelEvidence
            val deferReplanRetentionLog =
                pipelineResultForCleanup is InActionGroupPipelineResult.ReplanRequired &&
                    actionGroupCompleted &&
                    !durableCompletionFailed
            if (retainFailureEvidence && !deferReplanRetentionLog) {
                logW(LOG_ACTION_GROUP, "补肠预取流水线保留session/锁/moveStatus，等待恢复或人工确认")
            }
            if (retainParallelEvidence && !deferReplanRetentionLog) {
                logW(LOG_ACTION_GROUP, "补肠双 lane 失败，保留 session、资源锁和 moveStatus")
            }
            runCatching {
                KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            }.onFailure { error ->
                durableCompletionFailed = true
                enterActionGroupMaintenance(
                    "补肠预取流水线收口盘箱耐久保存失败，保留恢复证据：${error.message}"
                )
            }
            logD(LOG_SUPPLEMENT, "补肠预取流水线收口状态保存完成")
        }

        val completed = when (result) {
            is InActionGroupPipelineResult.Success -> result.completed
            is InActionGroupPipelineResult.Paused -> result.completed
            is InActionGroupPipelineResult.ReplanRequired -> result.completed
            is InActionGroupPipelineResult.Failed -> result.completed
        }
        completed.forEach { completedTask ->
            applyCompletedPipelineTask(completedTask)
        }

        if (safeParallelSupplyDegrade && result is InActionGroupPipelineResult.Failed) {
            runCatching {
                handleParallelE5SupplyDegrade(result, taskByPanNo, batchId)
            }.onFailure { error ->
                safeParallelSupplyDegrade = false
                durableCompletionFailed = true
                enterActionGroupMaintenance(
                    "补肠双 lane E5 安全降级落盘失败，保留 session/锁/moveStatus：${error.message}"
                )
            }
        }

        runCatching {
            val session = supplementPrefetchSessionRepository.load()
            val delivery = session?.deliveryLane
            if (session?.barrierState == SupplementBarrierState.DELIVERY_COMMITTED &&
                !session.pendingOrderNo.isNullOrBlank() &&
                session.prefetchLane == null &&
                delivery?.currentAction == "in7" &&
                delivery.lifecycle == SupplementActionLifecycle.PHYSICAL_APPLIED &&
                delivery.boxDeductionApplied &&
                delivery.panPlacementApplied
            ) {
                supplementPrefetchSessionRepository.pauseCommittedDeliveryForPendingOrder()
            }
        }.onFailure { error ->
            durableCompletionFailed = true
            enterActionGroupMaintenance(
                "补肠预取已提交 delivery 转订单安全暂停失败，保留 session/锁/moveStatus：${error.message}"
            )
        }

        val safePausedSession = activeSafePausedSupplementSession()
        val effectiveOutcome = when {
            safeParallelSupplyDegrade -> SupplementPipelineOutcome.SUPPLY_DEGRADED
            safePausedSession != null -> SupplementPipelineOutcome.PAUSED_FOR_ORDER
            else -> supplementPipelineOutcome(result)
        }
        val parallelSessionForCleanup = if (parallelEnabled) {
            activeSupplementPrefetchSessionOrNull("双 lane 最终收口")
        } else {
            null
        }
        val orderCloseout = SupplementPipelineOrderCloseoutPolicy.classify(
            parallelEnabled = parallelEnabled,
            pausedAfterAction = (result as? InActionGroupPipelineResult.Paused)?.pausedAfterAction,
            actionGroupCompleted = actionGroupCompleted,
            durableStateSaved = !durableCompletionFailed
        )
        val safeSerialCompletedRoot = orderCloseout == SupplementPipelineOrderCloseout.COMPLETED_ROOT
        val safeReplanReleaseConfirmed = if (result is InActionGroupPipelineResult.ReplanRequired) {
            if (parallelEnabled) {
                replanExpectedRevision?.let { expectedRevision ->
                    SupplementPipelineReplanReleasePolicy.canRelease(
                        session = parallelSessionForCleanup,
                        expectedSessionId = sessionId,
                        expectedRevision = expectedRevision,
                        durableStateSaved = !durableCompletionFailed
                    )
                } == true
            } else {
                actionGroupCompleted && !durableCompletionFailed
            }
        } else {
            false
        }
        val safeReleaseConfirmed = safePausedSession?.sessionId == sessionId ||
            safeSerialCompletedRoot ||
            safeReplanReleaseConfirmed ||
            (parallelSessionForCleanup?.sessionId == sessionId &&
                (result is InActionGroupPipelineResult.Success || safeParallelSupplyDegrade))
        val retainParallelEvidence = SupplementPipelineFailureRetentionPolicy.shouldRetain(
            evidenceActive = hasBlockingSupplementPrefetchEvidence(),
            outcome = effectiveOutcome,
            safeReleaseConfirmed = safeReleaseConfirmed
        )
        if ((result is InActionGroupPipelineResult.Success ||
                safeSerialCompletedRoot ||
                (result is InActionGroupPipelineResult.ReplanRequired && safeReplanReleaseConfirmed) ||
                safeParallelSupplyDegrade) &&
            !durableCompletionFailed &&
            !retainParallelEvidence
        ) {
            val completionFailure = if (parallelEnabled) {
                runCatching {
                    check(
                        releaseSupplementMoveClaimIfOwned(
                            owner = sessionId,
                            ownerStore = supplementPipelineClaimOwnerStore(),
                            resourceMask = lockMask,
                            expectedRevision = parallelSessionForCleanup
                                ?.takeIf { it.sessionId == sessionId }
                                ?.mechanicalClaimRevision
                        )
                    ) { "补肠双 lane 流水线 owner 条件释放失败" }
                    publishRuntimeStateWithHttpFallback("补肠双 lane 流水线收口")
                }.exceptionOrNull()
            } else {
                runCatching {
                    val released = if (safeSerialCompletedRoot) {
                        releaseCompletedSerialSupplementRoot(
                            owner = sessionId,
                            closeout = orderCloseout
                        )
                    } else {
                        releaseSupplementMoveClaimIfOwned(
                            owner = sessionId,
                            ownerStore = actionGroupClaimOwnerStore(),
                            resourceMask = lockMask,
                            expectedRevision = actionGroupSessionStore.load()
                                ?.takeIf { it.sessionId == sessionId }
                                ?.mechanicalClaimRevision
                        )
                    }
                    check(released) { "补肠预取流水线owner条件释放失败" }
                    publishRuntimeStateWithHttpFallback("补肠预取流水线收口")
                }.exceptionOrNull()
            }
            if (completionFailure != null) {
                durableCompletionFailed = true
                enterActionGroupMaintenance(
                    "补肠预取流水线完成后session/moveStatus耐久收口失败，保留恢复证据：" +
                        "${completionFailure.message}",
                    preserveMoveStatus = true
                )
            } else {
                applyPendingActionGroupDisableIfIdle("补肠预取流水线会话收口")
            }
        } else if (
            (result is InActionGroupPipelineResult.Paused || safePausedSession != null) &&
            (hasPausedSupplementSession() || safePausedSession != null) &&
            !durableCompletionFailed &&
            !retainParallelEvidence
        ) {
            runCatching {
                withFaultHeatTransitionLock {
                    val settledConfig = AppConfig.getAppConfig()
                    settledConfig.moveStatus = 0
                    AppConfig.saveAppConfigDurable(settledConfig)
                    val pausedOwner = safePausedSession?.sessionId ?: sessionId
                    val pausedResourceMask = safePausedSession?.resourceMask ?: lockMask
                    when (actionGroupLocks.releaseIfOwned(pausedOwner, pausedResourceMask)) {
                        ResourceReleaseResult.RELEASED,
                        ResourceReleaseResult.ALREADY_ABSENT -> Unit
                        ResourceReleaseResult.MASK_MISMATCH -> error(
                            "补肠暂停资源 owner/mask 不匹配：owner=$pausedOwner，" +
                                "expected=${ResourceLocks.describe(pausedResourceMask)}，" +
                                "actual=${actionGroupLocks.ownedMask(pausedOwner)?.let(ResourceLocks::describe)}"
                        )
                    }
                }
                publishRuntimeStateWithHttpFallback("补肠暂停等待订单完成")
                logI(
                    LOG_SUPPLEMENT,
                    "补肠已暂停并保留续作会话：session=${pausedSupplementSessionStore.load()?.sessionId}，" +
                        "action=${pausedSupplementSessionStore.load()?.resumeAfterAction}"
                )
                pausedCleanupSucceeded = true
            }.onFailure { error ->
                enterActionGroupMaintenance(
                    "补肠预取暂停完成目标收口后释放机械状态失败，保留续作证据：${error.message}"
                )
            }
        }

        when (result) {
            is InActionGroupPipelineResult.Success -> {
                logD(LOG_SUPPLEMENT, "补肠预取流水线结束：successCount=$completedCount，targetCount=${plannedMoves.size}")
            }
            is InActionGroupPipelineResult.ReplanRequired -> {
                if (safePausedSession != null && pausedCleanupSucceeded) {
                    logI(
                        LOG_SUPPLEMENT,
                        "补肠预取备用箱完成后订单优先，保留暂停session，moveStatus/锁已释放：" +
                            "successCount=$completedCount，targetCount=${plannedMoves.size}，reason=${result.reason}"
                    )
                } else if (safeReplanReleaseConfirmed && !durableCompletionFailed) {
                    logI(
                        LOG_SUPPLEMENT,
                        "补肠预取备用箱完成后已安全收口，剩余目标等待按最新库存重规划：" +
                            "successCount=$completedCount，targetCount=${plannedMoves.size}，reason=${result.reason}"
                    )
                } else {
                    logW(
                        LOG_ACTION_GROUP,
                        "补肠预取备用箱完成后未通过安全收口，保留session/锁/moveStatus：" +
                            "expectedRevision=$replanExpectedRevision，actualRevision=${parallelSessionForCleanup?.revision}，" +
                            "phase=${parallelSessionForCleanup?.phase}，barrier=${parallelSessionForCleanup?.barrierState}，" +
                            "pendingOrder=${parallelSessionForCleanup?.pendingOrderNo}，" +
                            "hasPrefetch=${parallelSessionForCleanup?.prefetchLane != null}，" +
                            "durableFailed=$durableCompletionFailed，reason=${result.reason}"
                    )
                }
            }
            is InActionGroupPipelineResult.Paused -> {
                OrderProcessingStageBridge.publish(OrderProcessingStage.SUPPLEMENT_PAUSED)
                if (safeSerialCompletedRoot && !durableCompletionFailed) {
                    logI(
                        LOG_SUPPLEMENT,
                        "非并行补肠当前根已完成 in7 并安全释放，订单优先，剩余目标等待重新规划：" +
                            "successCount=$completedCount，targetCount=${plannedMoves.size}，" +
                            "orderNo=${SupplementPauseController.currentOrderNo() ?: "未知"}"
                    )
                } else {
                    logI(
                        LOG_SUPPLEMENT,
                        "补肠预取流水线暂停，剩余目标将重新规划：successCount=$completedCount，" +
                            "targetCount=${plannedMoves.size}，orderNo=${SupplementPauseController.currentOrderNo() ?: "未知"}"
                    )
                }
            }
            is InActionGroupPipelineResult.Failed -> {
                if (safeParallelSupplyDegrade && !durableCompletionFailed) {
                    logW(
                        LOG_SUPPLEMENT,
                        "补肠双 lane 普通 E5 已按供给降级安全收口：" +
                            "lane=${result.laneRole}，targetPan=${result.task?.targetPanNo}，" +
                            "failedBoxes=${result.failedSourceBoxNos}"
                    )
                    return completedCount
                }
                val timeoutResolution = if (result.result is AtomicActionResult.Timeout) {
                    val timeoutBatchType = batchId?.let {
                        supplementBatchIntentRepository.loadActive()
                            ?.takeIf { intent -> intent.batchId == batchId }
                            ?.type
                    }
                    verifyTimeoutResolution(
                        actionName = result.actionName,
                        isHeatSupplementBatch = timeoutBatchType?.let {
                            SupplementBatchIntentRepository.isHeatSupplementBatch(it)
                        } == true
                    )
                } else {
                    null
                }
                when (timeoutResolution?.resolution) {
                    SupplementTimeoutResolutionPolicy.Resolution.KEEP_RUNNING -> {
                        logW(LOG_ACTION_GROUP, "补肠Timeout仍为RUNNING，保留session、资源锁和moveStatus：action=${result.actionName}")
                        return completedCount
                    }
                    SupplementTimeoutResolutionPolicy.Resolution.RECONCILE_COMPLETED -> {
                        recoverActionGroupStateAfterModbusReady("补肠Timeout复核为COMPLETED")
                        return completedCount
                    }
                    else -> Unit
                }
                handlePipelineSupplementFailure(
                    result,
                    taskByPanNo,
                    batchId,
                    timeoutResolution,
                    parallelSessionActive = parallelEnabled,
                    parallelSessionId = sessionId,
                    durableStateSaved = !durableCompletionFailed
                )
                return completedCount
            }
        }
        verifySupplementConsistencyOrEnterMaintenance(
            source = "补肠预取流水线",
            expectedSuccessCount = completedCount,
            records = supplementWriteRecords
        )
        KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
        KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())

        return completedCount
    }

    private data class AppliedSupplementMove(
        val targetPan: KaoPan,
        val sourceBox: KaoPanBox,
        val writeRecord: SupplementWriteRecord,
        val reportStockDelta: Boolean,
        val reportPanDelta: Boolean
    )

    private data class AppliedSupplementPhysicalTelemetry(
        val boxDeducted: Boolean = false,
        val panPlaced: Boolean = false,
        val stockInconsistent: Boolean = false
    )

    private data class SupplementPanPlacementCommit(
        val applied: Boolean,
        val targetPan: KaoPan? = null,
        val pendingDiscardCleared: Boolean = false
    )

    private fun commitSupplementPlacedOnPan(
        plannedPan: KaoPan,
        plannedBox: KaoPanBox
    ): SupplementPanPlacementCommit = withFaultHeatTransitionLock {
        val currentPans = KaoPanHelper.getKaoPanList()
        val currentBoxes = KaoPanHelper.getKaoPanBoxList()
        val applied = SupplementPanStateWriter.applySupplementPlacedOnPan(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = currentPans,
            currentBoxes = currentBoxes,
            nowMs = System.currentTimeMillis(),
            clearPendingDiscard = false,
            config = AppConfig.getAppConfig(),
            tasteBuilder = KaoPanHelper::buildTasteFromBox
        )
        if (!applied) {
            return@withFaultHeatTransitionLock SupplementPanPlacementCommit(false)
        }
        val targetPan = SupplementPanStateWriter.resolveCurrentTargetPan(plannedPan, currentPans)
            ?: return@withFaultHeatTransitionLock SupplementPanPlacementCommit(false)
        val pendingDiscardCleared =
            FaultHeatPauseManager.clearDiscardPositionStateAndAlertIfFinished(targetPan.positionSn)
        KaoPanHelper.saveKaoPanBoxListDurable(currentBoxes)
        KaoPanHelper.saveKaoPanListDurable(currentPans)
        SupplementPanPlacementCommit(
            applied = true,
            targetPan = targetPan,
            pendingDiscardCleared = pendingDiscardCleared
        )
    }

    private fun applySupplementPhysicalTelemetry(
        telemetry: ActionGroupTelemetry,
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        source: String,
        boxAlreadyDeducted: Boolean,
        panAlreadyPlaced: Boolean,
        persistAndPublish: Boolean = true
    ): AppliedSupplementPhysicalTelemetry {
        if (ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(telemetry)) {
            if (!ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                    telemetry = telemetry,
                    plannedBoxNo = plannedBox.positionSn,
                    plannedPanNo = plannedPan.positionSn
                )
            ) {
                enterActionGroupMaintenance(
                    "$source in2 物理检查点坐标与计划不一致，已阻止本地扣箱，需人工确认：" +
                        "plannedBox=${plannedBox.positionSn}，plannedPan=${plannedPan.positionSn}，" +
                        "telemetryBox=${telemetry.boxNo}，telemetryPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            if (boxAlreadyDeducted) {
                logW(
                    LOG_SUPPLEMENT,
                    "$source 跳过重复 in2 动作级扣箱：sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            val boxStockBefore = plannedBox.num
            val applied = SupplementPanStateWriter.applySupplementPickedFromBox(
                plannedPan = plannedPan,
                plannedBox = plannedBox,
                currentPans = KaoPanHelper.getKaoPanList(),
                currentBoxes = KaoPanHelper.getKaoPanBoxList()
            )
            if (!applied) {
                enterActionGroupMaintenance(
                    "$source in2 已成功但本地找不到当前盘箱缓存或来源箱库存已为0，库存状态不可确认，需人工确认：" +
                        "sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry(stockInconsistent = true)
            }
            if (persistAndPublish) {
                KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
            }
            logD(
                LOG_SUPPLEMENT,
                "$source 动作级物理状态已回写：in2 已从肠箱取肠并扣库存，sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
            )
            LogUtils.i(
                "【补肠】烤盘${telemetry.panNo} in2 完成：已从烤肠箱${telemetry.boxNo}取肠并扣减库存，" +
                    "箱库存=$boxStockBefore->${plannedBox.num}"
            )
            if (persistAndPublish) {
                PanPhysicalStateDeltaReporter.reportStockChanged(
                    changedStock = plannedBox,
                    reason = "action_physical:in2:picked_from_box:box=${telemetry.boxNo}:pan=${telemetry.panNo}",
                    source = "action_physical_in2"
                )
            }
            return AppliedSupplementPhysicalTelemetry(boxDeducted = true)
        }

        if (ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(telemetry)) {
            if (!ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                    telemetry = telemetry,
                    plannedBoxNo = plannedBox.positionSn,
                    plannedPanNo = plannedPan.positionSn
                )
            ) {
                enterActionGroupMaintenance(
                    "$source in7 物理检查点坐标与计划不一致，已阻止本地落盘，需人工确认：" +
                        "plannedBox=${plannedBox.positionSn}，plannedPan=${plannedPan.positionSn}，" +
                        "telemetryBox=${telemetry.boxNo}，telemetryPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            if (panAlreadyPlaced) {
                logW(
                    LOG_SUPPLEMENT,
                    "$source 跳过重复 in7 动作级落盘：sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            val commit = commitSupplementPlacedOnPan(plannedPan, plannedBox)
            if (!commit.applied) {
                enterActionGroupMaintenance(
                    "$source in7 已成功但本地找不到当前盘箱缓存，烤盘状态不可确认，需人工确认：" +
                        "sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            if (commit.pendingDiscardCleared) {
                FaultHeatPauseManager.publishDiscardPositionCleared(plannedPan.positionSn)
            }
            logD(
                LOG_SUPPLEMENT,
                "$source 动作级物理状态已回写：in7 已夹肠落盘并开始烤制，sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
            )
            LogUtils.i("【补肠】烤盘${telemetry.panNo} in7 完成：烤肠已落入烤盘并开始烤制计时")
            if (persistAndPublish) {
                PanPhysicalStateDeltaReporter.reportPanChanged(
                    changedPan = requireNotNull(commit.targetPan),
                    reason = "action_physical:in7:placed_on_pan:box=${telemetry.boxNo}:pan=${telemetry.panNo}",
                    source = "action_physical_in7"
                )
            }
            return AppliedSupplementPhysicalTelemetry(panPlaced = true)
        }

        return AppliedSupplementPhysicalTelemetry()
    }

    private fun applySuccessfulSupplementMove(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        boxAlreadyDeducted: Boolean = false,
        panAlreadyPlaced: Boolean = false
    ): AppliedSupplementMove? {
        val sausageName = getBoxTasteLabel(kaoPanBox)
        val currentState = SupplementPanStateWriter.resolveCurrentSupplementState(
            plannedPan = kaoPan,
            plannedBox = kaoPanBox,
            currentPans = KaoPanHelper.getKaoPanList(),
            currentBoxes = KaoPanHelper.getKaoPanBoxList()
        )
        if (currentState == null) {
            logE(
                LOG_SUPPLEMENT,
                "补肠预取流水线完成后无法回写当前烤盘缓存：targetPan=${kaoPan.positionSn}，sourceBox=${kaoPanBox.positionSn}"
            )
            return null
        }
        val targetPan = currentState.targetPan
        val sourceBox = currentState.sourceBox
        val boxStockBefore = sourceBox.num
        if (!panAlreadyPlaced) {
            if (boxAlreadyDeducted) {
                applySuccessfulSupplementPanStateWithoutBoxDeduction(
                    targetPan,
                    sourceBox,
                    "补肠预取流水线完成",
                    config = AppConfig.getAppConfig()
                )
            } else {
                if (!applySuccessfulSupplementPanStateIfStockAvailable(
                        targetPan,
                        sourceBox,
                        "补肠预取流水线完成",
                        config = AppConfig.getAppConfig()
                    )
                ) {
                    logE(
                        LOG_SUPPLEMENT,
                        "补肠预取流水线完成收口时来源箱库存已为0，拒绝静默扣箱落盘：sourceBox=${sourceBox.positionSn}，" +
                            "targetPan=${targetPan.positionSn}"
                    )
                    return null
                }
            }
        } else {
            targetPan.cmdStatusMove = 0
            clearOrdinaryBoxTakeStatus(sourceBox)
        }
        val stockLog = if (boxAlreadyDeducted) {
            "库存已在in2阶段扣减，当前库存=${sourceBox.num}"
        } else {
            "箱库存=$boxStockBefore->${sourceBox.num}"
        }
        logD(
            LOG_SUPPLEMENT,
            "补肠完成：来源烤肠箱=${sourceBox.positionSn}，目标烤盘=${targetPan.positionSn}，口味=$sausageName，" +
                "$stockLog，panStatus=${targetPan.status}，startTime=${targetPan.startTime}，" +
                "boxAlreadyDeducted=$boxAlreadyDeducted，panAlreadyPlaced=$panAlreadyPlaced"
        )
        LogUtils.i(
            "【补肠】烤盘${targetPan.positionSn} 补肠完成：来源烤肠箱=${sourceBox.positionSn}，" +
                "口味=$sausageName，烤盘开始烤制计时，$stockLog"
        )
        val panApplied = targetPan.isHasSausage && targetPan.status == 1
        val boxDeducted = boxAlreadyDeducted || (boxStockBefore > 0 && sourceBox.num == boxStockBefore - 1)
        return AppliedSupplementMove(
            targetPan = targetPan,
            sourceBox = sourceBox,
            writeRecord = SupplementWriteRecord(
                targetPanNo = targetPan.positionSn,
                sourceBoxNo = sourceBox.positionSn,
                panApplied = panApplied,
                boxDeducted = boxDeducted
            ),
            reportStockDelta = !boxAlreadyDeducted && boxDeducted,
            reportPanDelta = !panAlreadyPlaced && panApplied
        )
    }

    private fun reportSupplementCompletionPhysicalDeltaIfNeeded(
        sourceBox: KaoPanBox,
        targetPan: KaoPan,
        reportStock: Boolean,
        reportPan: Boolean,
        reason: String,
        source: String
    ) {
        when {
            reportStock && reportPan -> PanPhysicalStateDeltaReporter.reportPanAndStockChanged(
                changedPan = targetPan,
                changedStock = sourceBox,
                reason = reason,
                source = source
            )
            reportStock -> PanPhysicalStateDeltaReporter.reportStockChanged(
                changedStock = sourceBox,
                reason = reason,
                source = source
            )
            reportPan -> PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = targetPan,
                reason = reason,
                source = source
            )
        }
    }

    private fun reportOutCompletionPhysicalDelta(kaoPan: KaoPan, reason: String, source: String) {
        PanPhysicalStateDeltaReporter.reportPanChanged(
            changedPan = kaoPan,
            reason = reason,
            source = source
        )
    }

    private fun recordIrPickFailedBoxAndReport(
        failedBox: KaoPanBox,
        allCandidates: List<KaoPanBox>,
        reason: String,
        source: String
    ): SupplementPickFailurePolicy.IrPickFailureRecordResult {
        return try {
            DataManagementAPI.updateTakeStatusPrepared(
                positionSn = failedBox.positionSn,
                prepare = {
                    var preparedBox: KaoPanBox? = null
                    val result = KaoPanHelper.updateKaoPanBoxListDurable { currentBoxes ->
                        val currentFailedBox = currentBoxes.firstOrNull {
                            it.positionSn == failedBox.positionSn
                        } ?: error("取肠失败箱位已不在当前箱表：box=${failedBox.positionSn}")
                        preparedBox = currentFailedBox
                        SupplementPickFailurePolicy.recordIrPickFailedBox(
                            currentFailedBox,
                            currentBoxes.filter { candidate ->
                                allCandidates.any { it.positionSn == candidate.positionSn }
                            }
                        )
                    }
                    result to requireNotNull(preparedBox)
                }
            ) { confirmed, authoritative ->
                if (!confirmed) {
                    logW(
                        LOG_SUPPLEMENT,
                        "取肠失败状态CAS上报未确认：box=${failedBox.positionSn}，reason=$reason，source=$source，" +
                            "authoritativeRevision=${authoritative?.takeStatusRevision}"
                    )
                }
            }
        } catch (error: Exception) {
            enterActionGroupMaintenance(
                "取肠失败状态写入或落盘失败，保留补肠恢复证据：box=${failedBox.positionSn}，" +
                    "reason=$reason，source=$source，${error.message}",
                preserveMoveStatus = true
            )
            throw error
        }
    }

    private data class PlannedPipelineSupplementMove(
        val kaoPan: KaoPan,
        val tasteCode: String?,
        val returnPosition: Int?,
        val task: InActionGroupPipelineTask
    )

    private fun verifySupplementConsistencyOrEnterMaintenance(
        source: String,
        expectedSuccessCount: Int,
        records: List<SupplementWriteRecord>
    ) {
        val result = SupplementConsistencyChecker.evaluate(
            expectedSuccessCount = expectedSuccessCount,
            records = records
        )
        if (result.requiresMaintenance) {
            enterActionGroupMaintenance("$source 盘箱状态自检失败，需人工确认：${result.message}")
        } else {
            logD(LOG_SUPPLEMENT, "$source 盘箱状态自检通过：${result.message}")
        }
    }

    private fun applyOutPhysicalTelemetry(
        telemetry: ActionGroupTelemetry,
        plannedPan: KaoPan,
        source: String
    ): Boolean {
        if (!ActionPhysicalStateReducerPolicy.shouldApplyOutRemovedFromPan(telemetry)) {
            return false
        }
        if (telemetry.panNo != plannedPan.positionSn) {
            enterActionGroupMaintenance(
                "$source out1 物理检查点坐标与计划不一致，已阻止本地清盘，需人工确认：" +
                    "plannedPan=${plannedPan.positionSn}，telemetryPan=${telemetry.panNo}"
            )
            return false
        }
        val applied = SupplementPanStateWriter.applyOutRemovedFromPan(
            plannedPan = plannedPan,
            currentPans = KaoPanHelper.getKaoPanList()
        )
        if (!applied) {
            enterActionGroupMaintenance(
                "$source out1 已成功但本地找不到当前烤盘缓存，烤盘状态不可确认，需人工确认：" +
                    "sourcePan=${telemetry.panNo}"
            )
            return false
        }
        KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
        logD(LOG_ACTION_GROUP, "$source 动作级物理状态已回写：out1 已从烤盘夹走烤肠，sourcePan=${telemetry.panNo}")
        LogUtils.i("【出餐】烤盘${telemetry.panNo} out1 完成：烤肠已离开烤盘，本地烤盘已清空")
        PanPhysicalStateDeltaReporter.reportPanChanged(
            changedPan = plannedPan,
            reason = "action_physical:out1:removed_from_pan:pan=${telemetry.panNo}",
            source = "action_physical_out1"
        )
        return true
    }

    private fun buildPipelineSupplementPlan(targets: List<SupplementTarget>): List<PlannedPipelineSupplementMove> {
        val reservedStock = KaoPanHelper.getKaoPanBoxList().associate { it.positionSn to it.num }.toMutableMap()
        return targets.mapNotNull { target ->
            val candidates = TrafficModeHelper.findSupplyBoxesByTaste(KaoPanHelper.getKaoPanBoxList(), target.tasteCode)
            val candidateBoxNos = PrefetchSupplementPlanner.selectCandidateBoxNos(
                candidateBoxNos = candidates.map { it.positionSn },
                reservedStockByBoxNo = reservedStock
            )
            if (candidateBoxNos.isEmpty()) {
                logE(
                    LOG_SUPPLEMENT,
                    "补肠预取流水线跳过目标：找不到同口味可用烤肠箱，targetPan=${target.kaoPan.positionSn}，tasteCode=${target.tasteCode}"
                )
                null
            } else {
                PlannedPipelineSupplementMove(
                    kaoPan = target.kaoPan,
                    tasteCode = target.tasteCode,
                    returnPosition = target.returnPositionSn,
                    task = InActionGroupPipelineTask(
                        sourceBoxCandidates = candidateBoxNos,
                        targetPanNo = target.kaoPan.positionSn
                    )
                )
            }
        }
    }

    private fun recordIrPickFailureAndSave(sourceBoxNo: Int, reason: String) {
        val boxList = KaoPanHelper.getKaoPanBoxList()
        val failedBox = boxList.firstOrNull { it.positionSn == sourceBoxNo } ?: return
        val markResult = recordIrPickFailedBoxAndReport(
            failedBox = failedBox,
            allCandidates = boxList,
            reason = "action_failure:pipeline_in2_ir_pick:$reason",
            source = "action_failure_pipeline_in2_ir_pick"
        )
        logW(
            LOG_SUPPLEMENT,
            "$reason：烤肠箱=${markResult.boxNo}，保留库存=${markResult.previousNum}，remainingCandidates=${markResult.remainingCandidateBoxNos}"
        )
    }

    private fun isSupplementPipelineDropRiskAction(actionName: String): Boolean =
        actionName == "in6" || actionName == "in7"

    private fun markSupplementPipelineCancellationRisk(
        batchId: String?,
        currentAction: InActionGroupPipelineActionContext?,
        riskAction: InActionGroupPipelineActionContext?,
        throwable: CancellationException
    ) {
        val marked = markSupplementPipelineCancellationRiskIfNeeded(
            batchId = batchId,
            currentAction = currentAction,
            riskAction = riskAction,
            failureMessage = { action ->
                "补肠预取流水线在 ${action.actionName} 阶段被取消，目标烤盘可能已落肠，需人工确认后再续补：" +
                    "targetPan=${action.targetPanNo}，sourceBox=${action.sourceBoxNo}，cancel=${describeThrowableBrief(throwable)}"
            },
            markFailure = { targetPanNo, actionName, failureMessage ->
                logE(LOG_SUPPLEMENT, failureMessage)
                supplementBatchIntentRepository.markFailure(
                    batchId = batchId,
                    targetPanNo = targetPanNo,
                    actionName = actionName,
                    failureMessage = failureMessage,
                    requiresManualConfirm = true
                )
            }
        )
        if (!marked) {
            logW(
                LOG_SUPPLEMENT,
                "补肠预取流水线取消：当前动作=${currentAction?.actionName ?: "未知"}，未进入 in6/in7 落盘风险段，仅做同步收口"
            )
        }
    }

    internal fun selectSupplementPipelineCancellationRiskAction(
        currentAction: InActionGroupPipelineActionContext?,
        riskAction: InActionGroupPipelineActionContext?
    ): InActionGroupPipelineActionContext? =
        riskAction ?: currentAction?.takeIf { isSupplementPipelineDropRiskAction(it.actionName) }

    internal fun markSupplementPipelineCancellationRiskIfNeeded(
        batchId: String?,
        currentAction: InActionGroupPipelineActionContext?,
        riskAction: InActionGroupPipelineActionContext?,
        failureMessage: (InActionGroupPipelineActionContext) -> String,
        markFailure: (targetPanNo: Int, actionName: String, failureMessage: String) -> Unit
    ): Boolean {
        if (batchId.isNullOrBlank()) {
            return false
        }
        val action = selectSupplementPipelineCancellationRiskAction(currentAction, riskAction) ?: return false
        markFailure(action.targetPanNo, action.actionName, failureMessage(action))
        return true
    }

    private fun handleParallelE5SupplyDegrade(
        result: InActionGroupPipelineResult.Failed,
        taskByPanNo: Map<Int, PlannedPipelineSupplementMove>,
        batchId: String?
    ) {
        val targetPanNo = requireNotNull(result.task?.targetPanNo)
        val plannedMove = taskByPanNo[targetPanNo]
        val failedBoxNos = (result.failedSourceBoxNos + result.sourceBoxNo)
            .filterNotNull()
            .distinct()
        if (result.laneRole == SupplementLaneRole.DELIVERY) {
            result.sourceBoxNo?.let { finalFailedBox ->
                recordIrPickFailureAndSave(
                    sourceBoxNo = finalFailedBox,
                    reason = "补肠双 lane 普通 E5 候选耗尽"
                )
            }
        }
        val reason = SupplementPickFailurePolicy.buildSupplyDegradedReason(
            targetPanNo = targetPanNo,
            tasteCode = plannedMove?.tasteCode,
            failedBoxNos = failedBoxNos,
            actionName = result.actionName
        )
        recordNonDemandFrontForSupplementTarget(batchId, targetPanNo, "parallel_e5_supply_degrade")
        supplementBatchIntentRepository.markSkippedBySupplyDegrade(
            batchId = batchId,
            targetPanNo = targetPanNo,
            reason = reason
        )
        markSupplyDegradedRuntimeState(
            reason = reason,
            failedBoxNos = failedBoxNos,
            details = LowerPlatformEpisodeDetails(
                sourceBoxNo = result.sourceBoxNo,
                targetPanNo = targetPanNo,
                tasteCode = plannedMove?.tasteCode,
                batchId = batchId,
                errorCode = AtomicActionExecutor.formatCode(AtomicActionCodes.IR_PICK_FAILED),
                rawResult = describeAtomicActionResult(result.result),
                requiresManualConfirm = false
            )
        )
    }

    private fun closeParallelSupplementSemiFaultIfSafe(
        result: InActionGroupPipelineResult.Failed,
        resolvedAtomicResult: AtomicActionResult,
        expectedSessionId: String,
        durableStateSaved: Boolean
    ): SupplementMechanicalCloseoutResult {
        val session = activeSupplementPrefetchSessionOrNull("双 lane 下平台半故障原子收口")
            ?: return SupplementMechanicalCloseoutResult.Rejected("双 lane session 不存在或损坏")
        if (!ParallelSupplementSemiFaultClosurePolicy.canClose(
                actionName = result.actionName,
                sourceBoxNo = result.sourceBoxNo,
                targetPanNo = result.task?.targetPanNo,
                result = resolvedAtomicResult,
                session = session,
                expectedSessionId = expectedSessionId,
                laneRole = result.laneRole,
                durableStateSaved = durableStateSaved
            )
        ) {
            return SupplementMechanicalCloseoutResult.Rejected("in4 前物理证据未通过精准收口策略")
        }
        val closeout = SupplementMechanicalCloseoutCoordinator(
            transitionLock = faultHeatTransitionLock,
            supplementOwnerStore = supplementPipelineClaimOwnerStore(),
            orderOwnerStore = orderDeliveryClaimStore,
            moveStore = mechanicalMoveStatusStore,
            resourceLocks = actionGroupLocks
        ).close(
            SupplementMechanicalCloseoutRequest(
                owner = expectedSessionId,
                revision = session.mechanicalClaimRevision,
                resourceMask = session.resourceMask
            )
        )
        return closeout
    }

    private suspend fun handlePipelineSupplementFailure(
        result: InActionGroupPipelineResult.Failed,
        taskByPanNo: Map<Int, PlannedPipelineSupplementMove>,
        batchId: String?,
        timeoutProbe: TimeoutProbe? = null,
        parallelSessionActive: Boolean,
        parallelSessionId: String? = null,
        durableStateSaved: Boolean
    ) {
        val batchType = batchId?.let {
            supplementBatchIntentRepository.loadActive()?.takeIf { intent -> intent.batchId == batchId }?.type
        }
        val resolvedAtomicResult = if (
            timeoutProbe?.resolution == SupplementTimeoutResolutionPolicy.Resolution.ISOLATE_TERMINAL_ERROR &&
            timeoutProbe.resultCode != null
        ) {
            AtomicActionResult.Failed(
                timeoutProbe.resultCode,
                AtomicActionExecutor.messageOf(timeoutProbe.resultCode)
            )
        } else {
            result.result
        }
        val targetPanNo = result.task?.targetPanNo
        val plannedMove = targetPanNo?.let { taskByPanNo[it] }
        val failureCode = when (val atomicResult = resolvedAtomicResult) {
            is AtomicActionResult.Failed -> atomicResult.errorCode
            is AtomicActionResult.ModbusError,
            is AtomicActionResult.Timeout -> 2
            AtomicActionResult.Success -> 0
        }
        plannedMove?.kaoPan?.cmdStatusMove = failureCode
        val sourceBoxNo = result.sourceBoxNo

        logE(
            LOG_SUPPLEMENT,
            "补肠预取流水线失败：targetPan=$targetPanNo，sourceBox=$sourceBoxNo，" +
                "action=${result.actionName}，result=${describeAtomicActionResult(resolvedAtomicResult)}，" +
                "failedSourceBoxes=${result.failedSourceBoxNos}，hasPrefetchedNext=${result.hasPrefetchedNext}，" +
                "batchType=${batchType?.let { SupplementBatchIntentRepository.typeText(it) } ?: "未知"}，" +
                "timeoutResolution=${timeoutProbe?.resolution ?: "N/A"}"
        )
        val lowerPlatformIsolationDecision = LowerPlatformIsolationPolicy.classifyForSupplementBatch(
            batchType = batchType,
            actionName = result.actionName,
            result = resolvedAtomicResult,
            hasPrefetchedNext = result.hasPrefetchedNext,
            parallelSessionActive = parallelSessionActive
        )
        if (lowerPlatformIsolationDecision != LowerPlatformIsolationPolicy.Decision.NotIsolatable) {
            val isolationReason = LowerPlatformIsolationPolicy.buildIsolationReason(
                actionName = result.actionName,
                result = resolvedAtomicResult,
                targetPanNo = targetPanNo,
                sourceBoxNo = sourceBoxNo,
                hasPrefetchedNext = result.hasPrefetchedNext
            )
            val requiresManualConfirm =
                lowerPlatformIsolationDecision == LowerPlatformIsolationPolicy.Decision.IsolatedRequiresManualConfirm
            val episodeDetails = LowerPlatformEpisodeDetails(
                sourceBoxNo = sourceBoxNo,
                targetPanNo = targetPanNo,
                tasteCode = plannedMove?.tasteCode,
                batchId = batchId,
                batchType = batchType?.name,
                errorCode = AtomicActionExecutor.formatCode(failureCode),
                rawResult = describeAtomicActionResult(resolvedAtomicResult),
                requiresManualConfirm = requiresManualConfirm
            )
            targetPanNo?.let {
                recordNonDemandFrontForSupplementTarget(batchId, it, "lower_platform_isolated")
            }
            targetPanNo?.let { panNo ->
                if (requiresManualConfirm) {
                    supplementBatchIntentRepository.markFailure(
                        batchId = batchId,
                        targetPanNo = panNo,
                        actionName = result.actionName,
                        failureMessage = isolationReason,
                        requiresManualConfirm = true
                    )
                } else {
                    supplementBatchIntentRepository.markSkippedBySupplyDegrade(
                        batchId = batchId,
                        targetPanNo = panNo,
                        reason = isolationReason
                    )
                }
            }
            val persistedIsolation = if (parallelSessionActive && parallelSessionId != null) {
                try {
                    persistLowerPlatformIsolatedRuntimeState(isolationReason, result.actionName, episodeDetails)
                } catch (error: Throwable) {
                    enterActionGroupMaintenance(
                        "双 lane 下平台隔离状态耐久保存失败，保留 session/owner/资源，禁止订单进入：${error.message}",
                        preserveMoveStatus = true
                    )
                    return
                }
            } else {
                markLowerPlatformIsolatedRuntimeState(isolationReason, result.actionName, episodeDetails)
                null
            }
            val closeout = if (parallelSessionActive && parallelSessionId != null) {
                closeParallelSupplementSemiFaultIfSafe(
                    result = result,
                    resolvedAtomicResult = resolvedAtomicResult,
                    expectedSessionId = parallelSessionId,
                    durableStateSaved = durableStateSaved
                )
            } else {
                SupplementMechanicalCloseoutResult.Rejected("非双 lane 会话")
            }
            when (closeout) {
                SupplementMechanicalCloseoutResult.ClosedIdle,
                SupplementMechanicalCloseoutResult.ClosedKeepingOrder -> {
                    checkNotNull(persistedIsolation) { "双 lane 下平台隔离快照缺失" }
                    publishPersistedLowerPlatformIsolation(persistedIsolation)
                    applyPendingActionGroupDisableIfIdle("双 lane 下平台半故障原子收口")
                }
                is SupplementMechanicalCloseoutResult.Inconsistent -> {
                    enterActionGroupMaintenance(
                        "双 lane 下平台半故障原子收口发生状态不一致，保留 owner/资源等待人工处置：${closeout.reason}",
                        preserveMoveStatus = true
                    )
                    return
                }
                is SupplementMechanicalCloseoutResult.Failed,
                is SupplementMechanicalCloseoutResult.Rejected -> {
                    if (parallelSessionActive) {
                        enterActionGroupMaintenance(
                            "双 lane 下平台故障无法安全释放，保留 session/owner/资源，禁止订单进入：closeout=$closeout",
                            preserveMoveStatus = true
                        )
                        return
                    }
                    logW(
                        LOG_ACTION_GROUP,
                        "非双 lane 下平台半故障沿用既有隔离收口：closeout=$closeout"
                    )
                }
            }
            logW(
                LOG_SUPPLEMENT,
                "补肠预取流水线下平台失败已隔离，不进入维护：" +
                    "targetPan=$targetPanNo，sourceBox=$sourceBoxNo，action=${result.actionName}，" +
                    "result=${describeAtomicActionResult(resolvedAtomicResult)}，batchType=${batchType?.let { SupplementBatchIntentRepository.typeText(it) } ?: "未知"}，" +
                    "requiresManualConfirm=$requiresManualConfirm，closeout=$closeout，reason=$isolationReason"
            )
            return
        }
        if (batchType != null &&
            SupplementBatchIntentRepository.isInitializationBatch(batchType) &&
            result.actionName.startsWith("in")
        ) {
            if (failureCode == AtomicActionCodes.IR_PICK_FAILED) {
                val failedBoxNos = (result.failedSourceBoxNos + sourceBoxNo).filterNotNull().distinct()
                val boxList = KaoPanHelper.getKaoPanBoxList()
                val failedBoxes = failedBoxNos.mapNotNull { boxNo ->
                    boxList.firstOrNull { it.positionSn == boxNo }
                }
                failedBoxes.forEach { failedBox ->
                    recordIrPickFailedBoxAndReport(
                        failedBox = failedBox,
                        allCandidates = boxList,
                        reason = "action_failure:pipeline_initialization_ir_pick:targetPan=$targetPanNo",
                        source = "action_failure_pipeline_initialization_ir_pick"
                    )
                }
            }
            targetPanNo?.let {
                recordNonDemandFrontForSupplementTarget(batchId, it, "initialization_action_failure")
            }
            supplementBatchIntentRepository.markFailure(
                batchId = batchId,
                targetPanNo = targetPanNo,
                actionName = result.actionName,
                failureMessage = describeAtomicActionResult(resolvedAtomicResult),
                requiresManualConfirm = true
            )
            enterActionGroupMaintenance(
                "${SupplementBatchIntentRepository.typeText(batchType)}补肠失败，初始化阶段不降级，设备进入维护停售：" +
                    "targetPan=$targetPanNo，sourceBox=$sourceBoxNo，action=${result.actionName}，" +
                    "result=${describeAtomicActionResult(resolvedAtomicResult)}"
            )
            return
        }
        if (SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(
                actionName = result.actionName,
                result = resolvedAtomicResult,
                hasPrefetchedNext = result.hasPrefetchedNext
            )
        ) {
            val failedBoxNos = (result.failedSourceBoxNos + sourceBoxNo).filterNotNull().distinct()
            if (failedBoxNos.isNotEmpty()) {
                val boxList = KaoPanHelper.getKaoPanBoxList()
                val failedBoxes = failedBoxNos.mapNotNull { boxNo ->
                    boxList.firstOrNull { it.positionSn == boxNo }
                }
                failedBoxes.forEach { failedBox ->
                    recordIrPickFailedBoxAndReport(
                        failedBox = failedBox,
                        allCandidates = boxList,
                        reason = "action_failure:pipeline_supply_degrade:targetPan=$targetPanNo:action=${result.actionName}",
                        source = "action_failure_pipeline_supply_degrade"
                    )
                }
            }
            val degradeReason = SupplementPickFailurePolicy.buildSupplyDegradedReason(
                targetPanNo = targetPanNo,
                tasteCode = plannedMove?.tasteCode,
                failedBoxNos = failedBoxNos,
                actionName = result.actionName
            )
            targetPanNo?.let {
                recordNonDemandFrontForSupplementTarget(batchId, it, "pipeline_supply_degrade")
            }
            targetPanNo?.let {
                supplementBatchIntentRepository.markFailure(
                    batchId = batchId,
                    targetPanNo = it,
                    actionName = result.actionName,
                    failureMessage = degradeReason,
                    requiresManualConfirm = true
                )
            }
            markSupplyDegradedRuntimeState(
                degradeReason,
                failedBoxNos,
                LowerPlatformEpisodeDetails(
                    sourceBoxNo = sourceBoxNo,
                    targetPanNo = targetPanNo,
                    tasteCode = plannedMove?.tasteCode,
                    batchId = batchId,
                    batchType = batchType?.name,
                    errorCode = AtomicActionExecutor.formatCode(failureCode),
                    rawResult = describeAtomicActionResult(resolvedAtomicResult),
                    requiresManualConfirm = true
                )
            )
            logW(
                LOG_SUPPLEMENT,
                 "补肠预取流水线取肠结果不确定，保留session并进入人工确认，不自动跳过目标：" +
                    "targetPan=$targetPanNo，failedBoxes=$failedBoxNos，reason=$degradeReason"
            )
            return
        }
        targetPanNo?.let {
            recordNonDemandFrontForSupplementTarget(batchId, it, "pipeline_action_failure")
        }
        supplementBatchIntentRepository.markFailure(
            batchId = batchId,
            targetPanNo = targetPanNo,
            actionName = result.actionName,
            failureMessage = describeAtomicActionResult(resolvedAtomicResult),
            requiresManualConfirm = true
        )
        if (result.hasPrefetchedNext) {
            enterActionGroupMaintenance(
                "补肠预取流水线投放失败且下一根已预取，设备进入维护，等待人工确认现场：" +
                    "targetPan=$targetPanNo，action=${result.actionName}，result=${describeAtomicActionResult(result.result)}"
            )
            return
        }
        if (failureCode == AtomicActionCodes.IR_PICK_FAILED) {
            val failedBoxNos = (result.failedSourceBoxNos + sourceBoxNo).filterNotNull().distinct()
            if (failedBoxNos.isNotEmpty()) {
                val boxList = KaoPanHelper.getKaoPanBoxList()
                val failedBoxes = failedBoxNos.mapNotNull { boxNo ->
                    boxList.firstOrNull { it.positionSn == boxNo }
                }
                failedBoxes.forEach { failedBox ->
                    recordIrPickFailedBoxAndReport(
                        failedBox = failedBox,
                        allCandidates = boxList,
                        reason = "action_failure:pipeline_ir_pick_maintenance:targetPan=$targetPanNo:action=${result.actionName}",
                        source = "action_failure_pipeline_ir_pick_maintenance"
                    )
                }
            }
            enterActionGroupMaintenance(
                "补肠预取流水线同口味候选箱取肠均失败（0x00E5），设备进入维护等待人工确认：" +
                    "targetPan=$targetPanNo，sourceBox=$sourceBoxNo，failedSourceBoxes=${result.failedSourceBoxNos}，" +
                    "action=${result.actionName}，result=${describeAtomicActionResult(result.result)}"
            )
            return
        }
        if (failureCode != AtomicActionCodes.IR_PICK_FAILED) {
            markActionGroupFailure(
                "补肠预取流水线",
                ActionGroupResult.Failed(result.actionName, result.result, result.message)
            )
        }
    }

    private fun describeAtomicActionResult(result: AtomicActionResult): String {
        return when (result) {
            AtomicActionResult.Success -> "SUCCESS（成功）"
            is AtomicActionResult.Failed -> "${AtomicActionExecutor.formatCode(result.errorCode)}（${result.message}）"
            is AtomicActionResult.ModbusError -> "MODBUS_ERROR（${result.message}）"
            is AtomicActionResult.Timeout -> "TIMEOUT（${result.message}）"
        }
    }

    private data class TimeoutProbe(
        val resolution: SupplementTimeoutResolutionPolicy.Resolution,
        val resultCode: Int? = null
    )

    private suspend fun verifyTimeoutResolution(
        actionName: String,
        isHeatSupplementBatch: Boolean
    ): TimeoutProbe {
        if (!VMModbusHelper.connectStatus()) {
            return TimeoutProbe(SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE)
        }
        val spec = ActionGroupRecoveryBootstrap.allSpecs.firstOrNull { it.name == actionName }
            ?: return TimeoutProbe(SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE)
        repeat(2) { cycle ->
            val legacyStatus = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status)
            val reader = createAtomicActionClient() as? AtomicActionStateReader
                ?: return TimeoutProbe(SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE)
            val actionState = reader.readResult(spec)
            if (legacyStatus < 0 || actionState !is AtomicReadResult.Success) {
                logE(
                    LOG_ACTION_GROUP,
                    "动作超时后状态读取失败，按整机故障处理：action=$actionName，cycle=${cycle + 1}/2，" +
                        "legacy201=$legacyStatus，actionState=$actionState"
                )
                return TimeoutProbe(SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE)
            }
            val resolution = SupplementTimeoutResolutionPolicy.resolve(
                resultCode = actionState.value,
                readsSucceeded = true,
                actionName = actionName,
                isHeatSupplementBatch = isHeatSupplementBatch,
                isFinalCycle = cycle == 1
            )
            logW(
                LOG_ACTION_GROUP,
                "动作超时后通信健康复核：action=$actionName，cycle=${cycle + 1}/2，" +
                    "legacy201=$legacyStatus，result=${actionState.value}，resolution=$resolution"
            )
            if (resolution != SupplementTimeoutResolutionPolicy.Resolution.KEEP_RUNNING) {
                return TimeoutProbe(resolution, actionState.value)
            }
            if (cycle == 0) {
                delay(spec.pollDelayMs)
            }
        }
        logE(LOG_ACTION_GROUP, "动作超时后两周期仍未收敛，按整机故障处理：action=$actionName")
        return TimeoutProbe(SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE)
    }

    private fun describeThrowableBrief(throwable: Throwable): String {
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message == null) {
            throwable.javaClass.simpleName
        } else {
            "${throwable.javaClass.simpleName}: $message"
        }
    }

    suspend fun moveSausageToKaoPan(kaoPan: KaoPan, kaoPanBox: KaoPanBox): Boolean {
        return moveSausageToKaoPanDetailed(kaoPan, kaoPanBox, allowPickFailureFallback = false).success
    }

    private suspend fun moveSausageToKaoPanDetailed(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        allowPickFailureFallback: Boolean,
        batchType: SupplementBatchType? = null,
        batchId: String? = null
    ): SingleSupplementMoveResult {
        if (AppConfig.getAppConfig().errorStatus>0){
            logD(LOG_SUPPLEMENT, "程序处于报错状态，不进行补肠操作")
            return SingleSupplementMoveResult(false)
        }
        if (!SourceBoxPositionPolicy.isValidSourceBox(kaoPanBox.positionSn)) {
            val reason = "补肠取消：烤肠箱${kaoPanBox.positionSn}为 4F 保留/非法源肠箱，合法源肠箱=${SourceBoxPositionPolicy.validRangeText()}"
            logE(
                LOG_SUPPLEMENT,
                reason
            )
            SendServerHelper.publishIllegalSourceBoxBlocked(kaoPanBox.positionSn, kaoPan.positionSn, reason)
            return SingleSupplementMoveResult(false)
        }
        if (kaoPanBox.num <= 0) {
            logE(LOG_SUPPLEMENT, "补肠取消：烤肠箱${kaoPanBox.positionSn}库存不足，当前库存=${kaoPanBox.num}")
            return SingleSupplementMoveResult(false)
        }
        if (!ensureModbusReady("补肠动作组恢复检查")) {
            enterActionGroupMaintenance(
                "补肠入口前 Modbus 重连 3 次仍失败，设备进入维护：stage=普通补肠",
                FaultCode.MODBUS_COMM_DROP
            )
            return SingleSupplementMoveResult(false)
        }
        if (!recoverActionGroupStateAfterModbusReady("补肠入口前恢复检查")) {
            return SingleSupplementMoveResult(false)
        }

        var appState = AppConfig.getAppConfig().moveStatus
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_SUPPLEMENT, "补肠前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
        }
        waitForPostSellSupplementGuardIfNeeded()
        val sausageName = getBoxTasteLabel(kaoPanBox)
        val boxStockBefore = kaoPanBox.num
        val owner = "in:${kaoPanBox.positionSn}->${kaoPan.positionSn}:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        val sessionIntentTarget = supplementBatchIntentRepository.loadActive()
            ?.takeIf { it.batchId == batchId }
            ?.targets
            ?.firstOrNull { it.positionSn == kaoPan.positionSn }
        val preparedSession = ActionGroupSession(
                sessionId = owner,
                type = ActionGroupSessionType.IN_SUPPLEMENT,
                sourceNo = kaoPanBox.positionSn,
                targetNo = kaoPan.positionSn,
                currentAction = null,
                resourceMask = lockMask,
                batchId = batchId,
                taste = kaoPanBox.tasteCode,
                returnPosition = sessionIntentTarget?.returnPositionSn,
                prefetch = false,
                checkpointVersion = 0L,
                stockBefore = boxStockBefore,
                expectedStockAfter = (boxStockBefore - 1).coerceAtLeast(0)
            )
        if (!claimPreparedSupplementMove(
                stage = "补肠单根动作组下发前",
                owner = owner,
                ownerStore = actionGroupClaimOwnerStore(preparedSession)
            )
        ) {
            return SingleSupplementMoveResult(false)
        }
        val claimedRevision = actionGroupSessionStore.load()
            ?.takeIf { it.sessionId == owner }
            ?.mechanicalClaimRevision
            ?: return SingleSupplementMoveResult(false)

        logD(
            LOG_SUPPLEMENT,
            "开始补肠：目标烤盘=${kaoPan.positionSn}，来源烤肠箱=${kaoPanBox.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore，targetPanHasSausage=${kaoPan.isHasSausage}"
        )
        if (!isActionGroupEnabledOrEnterMaintenance("补肠")) {
            releaseSupplementMoveClaimIfOwned(
                owner = owner,
                ownerStore = actionGroupClaimOwnerStore(),
                resourceMask = lockMask,
                expectedRevision = claimedRevision
            )
            return SingleSupplementMoveResult(false)
        }

        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            releaseSupplementMoveClaimIfOwned(
                owner = owner,
                ownerStore = actionGroupClaimOwnerStore(),
                resourceMask = lockMask,
                expectedRevision = claimedRevision
            )
            logW(
                LOG_ACTION_GROUP,
                "补肠动作组资源忙，拒绝下发：sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}，" +
                    "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return SingleSupplementMoveResult(false)
        }

        var physicalBoxDeducted = false
        var physicalPanPlaced = false
        var physicalStockInconsistent = false
        var actionGroupResult = run {
            logD(
                LOG_ACTION_GROUP,
                "补肠动作组开始：protocol=300_400，sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}，" +
                    "resourceLocks=${actionGroupLocks.snapshot()}"
            )
            LogUtils.i(
                "【补肠】开始单根补肠：目标烤盘=${kaoPan.positionSn}，来源烤肠箱=${kaoPanBox.positionSn}，口味=${getBoxTasteLabel(kaoPanBox)}"
            )
            val result = InActionGroupRunner(
                client = createAtomicActionClient(
                    ActionGroupTelemetryContext(
                        sessionId = owner,
                        boxNo = kaoPanBox.positionSn,
                        panNo = kaoPan.positionSn,
                        resourceLocks = actionGroupLocks.snapshot()
                    )
                ) { telemetry ->
                    val applied = applySupplementPhysicalTelemetry(
                        telemetry = telemetry,
                        plannedPan = kaoPan,
                        plannedBox = kaoPanBox,
                        source = "补肠动作组",
                        boxAlreadyDeducted = physicalBoxDeducted,
                        panAlreadyPlaced = physicalPanPlaced
                    )
                    if (applied.boxDeducted) {
                        physicalBoxDeducted = true
                    }
                    if (applied.panPlaced) {
                        physicalPanPlaced = true
                    }
                    if (applied.stockInconsistent) {
                        physicalStockInconsistent = true
                    } else {
                        recordConfirmedSupplementSessionStage(
                            sessionId = owner,
                            actionName = telemetry.actionName,
                            sourceBoxNo = kaoPanBox.positionSn,
                            targetPanNo = kaoPan.positionSn,
                            resultCode = telemetry.resultCode
                        )
                    }
                },
                logger = { message -> logD(LOG_ACTION_GROUP, message) },
                operationLogContext = ActionGroupOperationLogContext(
                    category = "补肠",
                    subject = "烤盘${kaoPan.positionSn}"
                ),
                operationLogger = { message -> LogUtils.i("$message：来源烤肠箱=${kaoPanBox.positionSn}") },
                failureLogger = { message -> LogUtils.e("$message：来源烤肠箱=${kaoPanBox.positionSn}") },
                onCurrentActionChanged = { actionName ->
                    withFaultHeatTransitionLock {
                        val previousSession = actionGroupSessionStore.load()
                        check(previousSession?.sessionId == owner) {
                            "迟到单根补肠回调无权更新新会话：expected=$owner，current=${previousSession?.sessionId}"
                        }
                        actionGroupSessionStore.save(
                        ActionGroupSession(
                            sessionId = owner,
                            type = ActionGroupSessionType.IN_SUPPLEMENT,
                            sourceNo = kaoPanBox.positionSn,
                            targetNo = kaoPan.positionSn,
                            currentAction = actionName,
                            resourceMask = lockMask,
                            startedAtMs = previousSession?.startedAtMs ?: System.currentTimeMillis(),
                            batchId = batchId,
                            taste = kaoPanBox.tasteCode,
                            returnPosition = sessionIntentTarget?.returnPositionSn,
                            prefetch = false,
                            checkpointVersion = (previousSession?.checkpointVersion ?: 0L) + 1L,
                            lastConfirmedStage = previousSession?.lastConfirmedStage,
                            stockBefore = previousSession?.stockBefore ?: boxStockBefore,
                            expectedStockAfter = previousSession?.expectedStockAfter
                                ?: (boxStockBefore - 1).coerceAtLeast(0),
                            boxDeductionApplied = previousSession?.boxDeductionApplied == true,
                            panPlacementApplied = previousSession?.panPlacementApplied == true,
                            mechanicalClaimState = previousSession?.mechanicalClaimState,
                            mechanicalClaimRevision = previousSession?.mechanicalClaimRevision ?: 0L
                        )
                        )
                    }
                }
            )
                .run(kaoPanBox.positionSn, kaoPan.positionSn)
            result
        }

        val rawActionGroupResult = actionGroupResult
        val timeoutProbe = if (
            actionGroupResult is ActionGroupResult.Failed &&
            actionGroupResult.result is AtomicActionResult.Timeout
        ) {
            verifyTimeoutResolution(
                actionName = actionGroupResult.actionName,
                isHeatSupplementBatch = batchType?.let {
                    SupplementBatchIntentRepository.isHeatSupplementBatch(it)
                } == true
            )
        } else {
            null
        }
        when (timeoutProbe?.resolution) {
            SupplementTimeoutResolutionPolicy.Resolution.KEEP_RUNNING -> {
                logW(LOG_ACTION_GROUP, "单根补肠Timeout仍为RUNNING，保留session、资源锁和moveStatus")
                return SingleSupplementMoveResult(false, actionGroupResult = actionGroupResult)
            }
            SupplementTimeoutResolutionPolicy.Resolution.RECONCILE_COMPLETED -> {
                val recovered = recoverActionGroupStateAfterModbusReady("单根补肠Timeout复核为COMPLETED")
                return SingleSupplementMoveResult(recovered, actionGroupResult = actionGroupResult)
            }
            SupplementTimeoutResolutionPolicy.Resolution.ISOLATE_TERMINAL_ERROR -> {
                val resultCode = timeoutProbe.resultCode
                    ?: return SingleSupplementMoveResult(false, actionGroupResult = actionGroupResult)
                actionGroupResult = ActionGroupResult.Failed(
                    actionName = (actionGroupResult as ActionGroupResult.Failed).actionName,
                    result = AtomicActionResult.Failed(
                        resultCode,
                        AtomicActionExecutor.messageOf(resultCode)
                    ),
                    message = "Timeout复核读到下位机错误终态：${AtomicActionExecutor.formatCode(resultCode)}（${AtomicActionExecutor.messageOf(resultCode)}）"
                )
            }
            else -> Unit
        }
        val moveSuccess = actionGroupResult == ActionGroupResult.Success
        val failureCode = atomicFailureCode(actionGroupResult)
        val failureActionName = (actionGroupResult as? ActionGroupResult.Failed)?.actionName.orEmpty()
        val serialFailureProof = if (actionGroupResult is ActionGroupResult.Failed) {
            withFaultHeatTransitionLock {
                val proof = captureSerialSupplementFailureProof(
                    owner = owner,
                    actionName = failureActionName,
                    sourceNo = kaoPanBox.positionSn,
                    targetNo = kaoPan.positionSn,
                    batchId = batchId,
                    batchType = batchType,
                    rawResult = (rawActionGroupResult as? ActionGroupResult.Failed)?.result
                        ?: actionGroupResult.result,
                    physicalBoxDeducted = physicalBoxDeducted,
                    physicalPanPlaced = physicalPanPlaced,
                    physicalStockInconsistent = physicalStockInconsistent
                )
                if (proof != null && SupplementFailureCloseoutPolicy.canAutoRelease(
                        SupplementFailureCloseoutEvidence(
                            batchType = proof.batchType,
                            actionName = proof.actionName,
                            rawResult = proof.rawResult,
                            prefetch = false,
                            mechanicalClaimState = MechanicalClaimState.CLAIMED,
                            lastConfirmedStage = proof.lastConfirmedStage,
                            boxDeductionApplied = proof.boxDeductionApplied,
                            panPlacementApplied = proof.panPlacementApplied,
                            physicalBoxDeducted = proof.physicalBoxDeducted,
                            physicalPanPlaced = proof.physicalPanPlaced,
                            physicalStockInconsistent = proof.physicalStockInconsistent
                        )
                    )) {
                    check(persistGuardedSerialFailureEpisode(proof)) { "串行补肠 guarded episode 落盘失败" }
                }
                proof
            }
        } else {
            serialSupplementFailureProof = null
            null
        }
        val lowerPlatformIsolationDecision = if (!moveSuccess && actionGroupResult is ActionGroupResult.Failed) {
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                batchType = batchType,
                actionName = actionGroupResult.actionName,
                result = actionGroupResult.result,
                hasPrefetchedNext = false
            )
        } else {
            LowerPlatformIsolationPolicy.Decision.NotIsolatable
        }
        val lowerPlatformIsolationReason = if (actionGroupResult is ActionGroupResult.Failed &&
            lowerPlatformIsolationDecision != LowerPlatformIsolationPolicy.Decision.NotIsolatable
        ) {
            LowerPlatformIsolationPolicy.buildIsolationReason(
                actionName = actionGroupResult.actionName,
                result = actionGroupResult.result,
                targetPanNo = kaoPan.positionSn,
                sourceBoxNo = kaoPanBox.positionSn,
                hasPrefetchedNext = false
            )
        } else {
            null
        }
        val rawIrPickFailure = (rawActionGroupResult as? ActionGroupResult.Failed)?.result
            ?.let { result ->
                result is AtomicActionResult.Failed &&
                    result.errorCode == AtomicActionCodes.IR_PICK_FAILED
            } == true
        val guardedEpisodeOpened = serialFailureProof?.let { proof ->
            SupplementFailureCloseoutPolicy.canAutoRelease(
                SupplementFailureCloseoutEvidence(
                    batchType = proof.batchType,
                    actionName = proof.actionName,
                    rawResult = proof.rawResult,
                    prefetch = false,
                    mechanicalClaimState = MechanicalClaimState.CLAIMED,
                    lastConfirmedStage = proof.lastConfirmedStage,
                    boxDeductionApplied = proof.boxDeductionApplied,
                    panPlacementApplied = proof.panPlacementApplied,
                    physicalBoxDeducted = proof.physicalBoxDeducted,
                    physicalPanPlaced = proof.physicalPanPlaced,
                    physicalStockInconsistent = proof.physicalStockInconsistent
                )
            )
        } == true
        val shouldRecordIrPickFailure = !moveSuccess &&
            failureCode == AtomicActionCodes.IR_PICK_FAILED &&
            rawIrPickFailure
        kaoPanBox.cmdStatusTake = when {
            moveSuccess -> if (kaoPanBox.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED) {
                AtomicActionCodes.IR_PICK_FAILED
            } else {
                0
            }
            shouldRecordIrPickFailure -> kaoPanBox.cmdStatusTake
            kaoPanBox.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED -> AtomicActionCodes.IR_PICK_FAILED
            else -> failureCode
        }
        if (shouldRecordIrPickFailure) {
            recordIrPickFailedBoxAndReport(
                failedBox = kaoPanBox,
                allCandidates = KaoPanHelper.getKaoPanBoxList(),
                reason = "action_failure:single_in2_ir_pick:targetPan=${kaoPan.positionSn}",
                source = "action_failure_single_in2_ir_pick"
            )
        }
        kaoPan.cmdStatusMove = if (moveSuccess) 0 else failureCode
        logD(
            LOG_SUPPLEMENT,
            "补肠动作组结束：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，" +
                "result=${actionGroupResultDesc(actionGroupResult)}"
        )

        val supplementWriteRecords = mutableListOf<SupplementWriteRecord>()
        var supplementStateApplied = false
        var completionTargetPan: KaoPan? = null
        var completionSourceBox: KaoPanBox? = null
        var completionReportStock = false
        var completionReportPan = false
        if (moveSuccess){
            val currentState = SupplementPanStateWriter.resolveCurrentSupplementState(
                plannedPan = kaoPan,
                plannedBox = kaoPanBox,
                currentPans = KaoPanHelper.getKaoPanList(),
                currentBoxes = KaoPanHelper.getKaoPanBoxList()
            )
            if (currentState == null) {
                enterActionGroupMaintenance(
                    "补肠动作组完成后找不到当前盘箱缓存对象，需人工确认：sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}"
                )
            } else {
                val currentPan = currentState.targetPan
                val currentBox = currentState.sourceBox
                val currentBoxStockBefore = currentBox.num
                var stateAppliedForThisMove = !physicalStockInconsistent
                val cfg = if (!physicalPanPlaced) {
                    if (physicalBoxDeducted) {
                        applySuccessfulSupplementPanStateWithoutBoxDeduction(
                            currentPan,
                            currentBox,
                            "补肠动作组完成",
                            config = AppConfig.getAppConfig()
                        )
                    } else {
                        if (!applySuccessfulSupplementPanStateIfStockAvailable(
                                currentPan,
                                currentBox,
                                "补肠动作组完成",
                                config = AppConfig.getAppConfig()
                            )
                        ) {
                            enterActionGroupMaintenance(
                                "补肠动作组完成但来源箱本地库存已为0，盘箱状态不可确认，需人工确认：" +
                                    "sourceBox=${currentBox.positionSn}，targetPan=${currentPan.positionSn}"
                            )
                            stateAppliedForThisMove = false
                        }
                        AppConfig.getAppConfig()
                    }
                } else {
                    currentPan.cmdStatusMove = 0
                    clearOrdinaryBoxTakeStatus(currentBox)
                    if (physicalStockInconsistent) {
                        enterActionGroupMaintenance(
                            "补肠动作组已落盘但来源箱扣减不可信，需人工确认盘箱状态：" +
                                "sourceBox=${currentBox.positionSn}，targetPan=${currentPan.positionSn}"
                        )
                    }
                    AppConfig.getAppConfig()
                }
                supplementWriteRecords += SupplementWriteRecord(
                    targetPanNo = currentPan.positionSn,
                    sourceBoxNo = currentBox.positionSn,
                    panApplied = currentPan.isHasSausage && currentPan.status == 1,
                    boxDeducted = physicalBoxDeducted ||
                        (currentBoxStockBefore > 0 && currentBox.num == currentBoxStockBefore - 1)
                )
                completionTargetPan = currentPan
                completionSourceBox = currentBox
                completionReportStock = !physicalBoxDeducted &&
                    (currentBoxStockBefore > 0 && currentBox.num == currentBoxStockBefore - 1)
                completionReportPan = !physicalPanPlaced && currentPan.isHasSausage && currentPan.status == 1
                supplementStateApplied = stateAppliedForThisMove
                logD(LOG_SUPPLEMENT, "补肠后刷新烤制参数：bakingTime=${cfg.bakingTime}min, discardTime=${cfg.discardTime}h → panId=${currentPan.id}")
                logD(
                    LOG_SUPPLEMENT,
                    "补肠完成：来源烤肠箱=${currentBox.positionSn}，目标烤盘=${currentPan.positionSn}，口味=$sausageName，" +
                        "箱库存=$currentBoxStockBefore->${currentBox.num}，panStatus=${currentPan.status}，startTime=${currentPan.startTime}，" +
                        "physicalBoxDeducted=$physicalBoxDeducted，physicalPanPlaced=$physicalPanPlaced"
                )
            }
        } else {
            logE(
                LOG_SUPPLEMENT,
                "补肠失败，未更新烤盘状态和库存：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}, " +
                    "result=${actionGroupResultDesc(actionGroupResult)}"
            )
            if (lowerPlatformIsolationReason != null && !guardedEpisodeOpened) {
                markLowerPlatformIsolatedRuntimeState(
                    lowerPlatformIsolationReason,
                    failureActionName,
                    LowerPlatformEpisodeDetails(
                        sourceBoxNo = kaoPanBox.positionSn,
                        targetPanNo = kaoPan.positionSn,
                        tasteCode = kaoPanBox.tasteCode,
                        batchId = batchId,
                        batchType = batchType?.name,
                        errorCode = AtomicActionExecutor.formatCode(failureCode),
                        rawResult = actionGroupResultDesc(actionGroupResult),
                        requiresManualConfirm =
                            lowerPlatformIsolationDecision == LowerPlatformIsolationPolicy.Decision.IsolatedRequiresManualConfirm,
                        closeoutGuarded = serialFailureProof != null && failureActionName in setOf("in1", "in2", "in3"),
                        closeoutSessionId = serialFailureProof?.sessionId,
                        closeoutClaimRevision = serialFailureProof?.mechanicalClaimRevision,
                        closeoutActionName = serialFailureProof?.actionName
                    )
                )
                logW(
                    LOG_SUPPLEMENT,
                    "补肠下平台动作失败，已隔离下平台并停止自动补肠，不切整机维护：" +
                        "targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}, reason=$lowerPlatformIsolationReason"
                )
            } else if (allowPickFailureFallback &&
                atomicFailureCode(actionGroupResult) == AtomicActionCodes.IR_PICK_FAILED &&
                rawIrPickFailure
            ) {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠取肠失败由同口味切箱逻辑接管，暂不切维护：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}"
                )
            } else {
                markActionGroupFailure("补肠", actionGroupResult)
            }
        }

        val canClearFailedSession = false
        var failureCloseoutCompleted = false
        if (actionGroupResult is ActionGroupResult.Failed && !canClearFailedSession) {
            when (val closeout = serialFailureProof?.let { closeSerialSupplementFailure(it) }) {
                SerialSupplementFailureCloseoutResult.Released -> {
                    failureCloseoutCompleted = true
                    logW(
                        LOG_ACTION_GROUP,
                        "补肠明确失败且无物理提交，已安全释放旧补肠占有：" +
                            "action=$failureActionName，code=${AtomicActionExecutor.formatCode(failureCode)}"
                    )
                }
                is SerialSupplementFailureCloseoutResult.Maintenance -> {
                    enterActionGroupMaintenance(closeout.reason, preserveMoveStatus = true)
                    return SingleSupplementMoveResult(
                        success = false,
                        actionGroupResult = actionGroupResult,
                        lowerPlatformIsolationReason = lowerPlatformIsolationReason,
                        lowerPlatformIsolationRequiresManualConfirm = true
                    )
                }
                is SerialSupplementFailureCloseoutResult.Rejected,
                null -> {
                    logW(
                        LOG_ACTION_GROUP,
                        "补肠失败物理状态未完全确定，保留session、资源锁和moveStatus等待恢复：" +
                            "action=$failureActionName，code=${AtomicActionExecutor.formatCode(failureCode)}，" +
                            "reason=${(closeout as? SerialSupplementFailureCloseoutResult.Rejected)?.reason ?: "缺少进程 proof"}"
                    )
                    return SingleSupplementMoveResult(
                        success = false,
                        actionGroupResult = actionGroupResult,
                        lowerPlatformIsolationReason = lowerPlatformIsolationReason,
                        lowerPlatformIsolationRequiresManualConfirm = true
                    )
                }
            }
        }
        if (moveSuccess && supplementStateApplied) {
            verifySupplementConsistencyOrEnterMaintenance(
                source = "补肠动作组",
                expectedSuccessCount = 1,
                records = supplementWriteRecords
            )
        }

        if (moveSuccess && supplementStateApplied) {
            try {
                KaoPanHelper.saveKaoPanBoxListDurable(KaoPanHelper.getKaoPanBoxList())
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                batchId?.let { supplementBatchIntentRepository.markCompleted(it, kaoPan.positionSn) }
                check(
                    releaseSupplementMoveClaimIfOwned(
                        owner = owner,
                        ownerStore = actionGroupClaimOwnerStore(),
                        resourceMask = lockMask,
                        expectedRevision = actionGroupSessionStore.load()
                            ?.takeIf { it.sessionId == owner }
                            ?.mechanicalClaimRevision
                    )
                ) { "补肠成功后owner条件释放失败" }
                applyPendingActionGroupDisableIfIdle("补肠动作组会话收口")
            } catch (e: Exception) {
                enterActionGroupMaintenance(
                    "补肠动作物理成功后的盘箱/binding耐久收口失败，保留恢复证据：" +
                        "sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}，${e.message}"
                )
                return SingleSupplementMoveResult(false, actionGroupResult = actionGroupResult)
            }
        } else if (actionGroupResult !is ActionGroupResult.Failed) {
            enterActionGroupMaintenance(
                "补肠动作完成但本地盘箱状态未确认，保留session：sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}"
            )
            return SingleSupplementMoveResult(false, actionGroupResult = actionGroupResult)
        } else {
            val fallbackReleased = if (canClearFailedSession) {
                releaseSerialSupplementPickFallback(owner)
            } else {
                failureCloseoutCompleted
            }
            if (!fallbackReleased) {
                enterActionGroupMaintenance(
                    "补肠失败收口 owner/resource 条件未满足，保留现场维护证据：" +
                        "action=$failureActionName，code=${AtomicActionExecutor.formatCode(failureCode)}",
                    preserveMoveStatus = true
                )
                return SingleSupplementMoveResult(false, actionGroupResult = actionGroupResult)
            }
            applyPendingActionGroupDisableIfIdle("补肠动作组失败收口")
        }
        if (moveSuccess && supplementStateApplied && completionTargetPan != null && completionSourceBox != null) {
            LowerPlatformRuntimeStateReporter.markSupplyBoxRecoveredAfterSuccessfulTake(
                sourceBoxNo = completionSourceBox.positionSn,
                reason = "单根补肠完整落盘成功"
            )
            reportSupplementCompletionPhysicalDeltaIfNeeded(
                sourceBox = completionSourceBox,
                targetPan = completionTargetPan,
                reportStock = completionReportStock,
                reportPan = completionReportPan,
                reason = "action_completion:in:box=${completionSourceBox.positionSn}:pan=${completionTargetPan.positionSn}",
                source = "action_completion_in"
            )
        }
        logD(LOG_SUPPLEMENT, "补肠后烤盘状态更新完成")
        logD(LOG_SUPPLEMENT, "补肠流程结束：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，success=$moveSuccess")

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_SUPPLEMENT, "等待当前售卖结束后再补下一根烤肠")
        }
        return SingleSupplementMoveResult(
            success = moveSuccess && supplementStateApplied,
            actionGroupResult = actionGroupResult,
            lowerPlatformIsolationReason = lowerPlatformIsolationReason,
            lowerPlatformIsolationRequiresManualConfirm =
                lowerPlatformIsolationDecision == LowerPlatformIsolationPolicy.Decision.IsolatedRequiresManualConfirm
        )
    }

    /**
     * 烤盘间搬移。
     *
     * 这条链路走 202/203 寄存器，不复用 200/201 的忙态语义：
     * - 202：下发搬移动作，值为“起始烤盘 << 8 | 目标烤盘”
     * - 203：搬移结果，0=空闲/等待接收，1=执行中，2=完成，0x00F8~0x00FB=错误，-1=上位机读失败
     *
     * 因此这里除了写指令预检外，还要单独处理：
     * - 写 202 后必须先观察到 203=1（执行中），再观察到 203=2（完成）
     * - 203 长时间停在 0（空闲/等待接收）或 1（执行中）的超时
     * - 203 读失败
     * - 203 返回非 0/1 的明确错误
     */
    suspend fun moveKaoPanToKaoPan(
        kaoPan: KaoPan,
        newKaoPan: KaoPan,
        targetHoldingTime: Long = kaoPan.holdingTime
    ): Boolean {
        return moveKaoPanToKaoPanInternal(
            kaoPan = kaoPan,
            newKaoPan = newKaoPan,
            targetHoldingTime = targetHoldingTime,
            requireAutoDispatchGate = true,
            operationReason = "自动调度",
            ownerPrefix = "legacy_move"
        )
    }

    suspend fun moveKaoPanToKaoPanManual(
        kaoPan: KaoPan,
        newKaoPan: KaoPan,
        targetHoldingTime: Long = kaoPan.holdingTime
    ): Boolean {
        val activeBinding = supplementBatchIntentRepository.loadActive()
            ?.retainedCompletedHeatReturnTargets()
            ?.firstOrNull { it.positionSn == kaoPan.positionSn }
        if (activeBinding != null && activeBinding.returnPositionSn != newKaoPan.positionSn) {
            logW(
                LOG_PAN_MOVE,
                "拒绝维护页手动搬盘破坏回搬绑定：source=${kaoPan.positionSn}，" +
                    "requestedTarget=${newKaoPan.positionSn}，boundTarget=${activeBinding.returnPositionSn}，" +
                    "originBatchId=${activeBinding.originBatchId}，originBatchType=${activeBinding.originBatchType}"
            )
            return false
        }
        return moveKaoPanToKaoPanInternal(
            kaoPan = kaoPan,
            newKaoPan = newKaoPan,
            targetHoldingTime = targetHoldingTime,
            requireAutoDispatchGate = false,
            operationReason = "维护页手动搬盘",
            ownerPrefix = "manual_move"
        )
    }

    private suspend fun moveKaoPanToKaoPanInternal(
        kaoPan: KaoPan,
        newKaoPan: KaoPan,
        targetHoldingTime: Long,
        requireAutoDispatchGate: Boolean,
        operationReason: String,
        ownerPrefix: String
    ): Boolean {
        if (withFaultHeatTransitionLock { enforceReturnBindingConflictMaintenanceLocked("搬盘入口") }) {
            logW(LOG_PAN_MOVE, "回搬目标存在重复所有者，拒绝启动搬盘")
            return false
        }
        val sourcePosition = kaoPan.positionSn
        val targetPosition = newKaoPan.positionSn
        if (sourcePosition==targetPosition){
            return true
        }
        if (hasBlockingSupplementPrefetchEvidence()) {
            logW(
                LOG_PAN_MOVE,
                "双 lane 补肠会话存在，拒绝启动搬盘：source=$sourcePosition，target=$targetPosition"
            )
            return false
        }
        if (requireAutoDispatchGate) {
            val config = AppConfig.getAppConfig()
            val dispatchDecision = AutoMechanicalDispatchPolicy.evaluate(
                onlineStatus = config.onlineStatus,
                errorStatus = config.errorStatus,
                inspectionMode = config.inspectionMode,
                moveStatus = config.moveStatus,
                lowerPlatformStatus = config.lowerPlatformStatus,
                lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName,
                orderPending = SupplementPauseController.shouldPauseForOrder()
            )
            if (!dispatchDecision.canStart) {
                logD(
                    LOG_PAN_MOVE,
                    "跳过本轮自动搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，reason=${dispatchDecision.reason}"
                )
                return false
            }
        }

        val owner = "$ownerPrefix:$sourcePosition->$targetPosition:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.lift or ResourceMask.above
        val returnBinding = supplementBatchIntentRepository.loadActive()
                ?.retainedCompletedHeatReturnTargets()
                ?.firstOrNull { target ->
                    target.positionSn == sourcePosition && target.returnPositionSn == targetPosition
                }
        var journal = PanMoveJournal(
            moveId = owner,
            sourcePositionSn = sourcePosition,
            targetPositionSn = targetPosition,
            originBatchId = returnBinding?.originBatchId,
            originBatchType = returnBinding?.originBatchType,
            targetHoldingTime = targetHoldingTime,
            checkpoint = PanMoveCheckpoint.PREPARED,
            createdAtMs = System.currentTimeMillis()
        )
        val claimResult = synchronized(faultHeatTransitionLock) {
            if (panMoveJournalStore.load() != null) {
                return@synchronized MechanicalMoveClaimResult.Rejected(
                    "存在未收口搬盘 journal，拒绝启动新搬盘：source=$sourcePosition，target=$targetPosition"
                )
            }
            if (!panMoveJournalStore.saveDurable(journal)) {
                return@synchronized MechanicalMoveClaimResult.Failed(
                    "搬盘 PREPARED journal 落盘失败"
                )
            }
            mechanicalMoveClaimCoordinator(
                panMoveClaimOwnerStore(journal)
            ).claim(
                owner = owner,
                allowedMoveStatuses = setOf(0),
                claimedMoveStatus = 3
            ) {
                val config = AppConfig.getAppConfig()
                if (requireAutoDispatchGate) {
                    AutoMechanicalDispatchPolicy.evaluate(
                        onlineStatus = config.onlineStatus,
                        errorStatus = config.errorStatus,
                        inspectionMode = config.inspectionMode,
                        moveStatus = config.moveStatus,
                        lowerPlatformStatus = config.lowerPlatformStatus,
                        lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName,
                        orderPending = SupplementPauseController.shouldPauseForOrder()
                    ).canStart
                } else {
                    config.onlineStatus != 4
                }
            }
        }
        if (claimResult !is MechanicalMoveClaimResult.Claimed) {
            if (claimResult is MechanicalMoveClaimResult.Rejected) {
                val cleared = withFaultHeatTransitionLock {
                    if (panMoveJournalStore.load()?.moveId == owner) {
                        panMoveJournalStore.clearDurable()
                    } else {
                        true
                    }
                }
                if (!cleared) {
                    enterActionGroupMaintenance(
                        "搬盘认领被拒且 PREPARED journal 无法清理：$sourcePosition->$targetPosition，" +
                            "保留 journal 与 moveStatus=3",
                        preserveMoveStatus = true
                    )
                }
            } else {
                enterActionGroupMaintenance(
                    "搬盘机械认领落盘失败：$sourcePosition->$targetPosition，保留 journal 与 moveStatus=3",
                    preserveMoveStatus = true
                )
            }
            logW(LOG_PAN_MOVE, "搬盘机械认领失败：source=$sourcePosition，target=$targetPosition，result=$claimResult")
            return false
        }
        journal = panMoveJournalStore.load() ?: journal

        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            val released = releaseMechanicalMove(
                "搬盘资源忙",
                owner,
                panMoveClaimOwnerStore(),
                expectedMoveStatus = 3,
                idleMoveStatus = 0
            )
            if (!released) {
                enterActionGroupMaintenance(
                    "搬盘资源忙且 owner/moveStatus 回滚失败：$sourcePosition->$targetPosition",
                    preserveMoveStatus = true
                )
            }
            logW(
                LOG_PAN_MOVE,
                "烤盘搬移资源忙，跳过本轮搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，" +
                    "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return false
        }

        var commandAdmitted = false
        try {
            LogUtils.i(
                "【搬盘】开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=$operationReason"
            )
            logD(
                LOG_PAN_MOVE,
                "开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，口味=${getPanTasteLabel(kaoPan)}，" +
                    "targetHoldingTime=$targetHoldingTime"
            )

            if (!ensureModbusReady("烤盘${sourcePosition}到烤盘${targetPosition}搬移")) {
                releaseMechanicalMove(
                    "搬盘下位机通信断开",
                    owner,
                    panMoveClaimOwnerStore(),
                    expectedMoveStatus = 3,
                    idleMoveStatus = 0
                )
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=下位机通信断开")
                markDeviceErrorAndUpload(FaultCode.MODBUS_COMM_DROP,
                    "烤盘${sourcePosition}到烤盘${targetPosition}搬移前下位机通信断开，设备进入维护中")
                return false
            }

            val value = (sourcePosition shl 8) or targetPosition
            when (val admission = admitPanMoveCommandIntent(owner, requireAutoDispatchGate)) {
                AutomaticMechanicalCommandAdmissionResult.Admitted -> {
                    commandAdmitted = true
                    journal = panMoveJournalStore.load() ?: journal
                }
                is AutomaticMechanicalCommandAdmissionResult.Rejected -> {
                    val released = releaseMechanicalMove(
                        "搬盘命令准入被拒",
                        owner,
                        panMoveClaimOwnerStore(),
                        expectedMoveStatus = 3,
                        idleMoveStatus = 0
                    )
                    if (!released) {
                        enterActionGroupMaintenance(
                            "搬盘命令准入被拒且 owner/moveStatus 释放失败：$sourcePosition->$targetPosition，" +
                                admission.reason,
                            preserveMoveStatus = true
                        )
                    }
                    return false
                }
                is AutomaticMechanicalCommandAdmissionResult.Failed -> {
                    enterActionGroupMaintenance(
                        "搬盘命令准入失败，保留 owner/journal 与 moveStatus=3：" +
                            "$sourcePosition->$targetPosition，${admission.reason}",
                        preserveMoveStatus = true
                    )
                    return false
                }
            }
            val writeMoveResult = writeSingleRegister2(action_address_move, value)
            if (!writeMoveResult.isSuccess) {
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=202 指令下发失败，status=${writeMoveResult.status}")
                markDeviceErrorAndUpload(FaultCode.KAOPAN_MOVE_FAILED,
                    "烤盘${sourcePosition}到烤盘${targetPosition}搬移指令下发失败：${writeMoveResult.status}")
                return false
            }
            journal = journal.copy(checkpoint = PanMoveCheckpoint.COMMAND_ISSUED)
            if (!panMoveJournalStore.saveDurable(journal)) {
                enterActionGroupMaintenance(
                    "搬盘 202 已下发但 COMMAND_ISSUED journal 落盘失败：$sourcePosition->$targetPosition",
                    preserveMoveStatus = true
                )
                return false
            }
            val moveStart = System.currentTimeMillis()
            val moveTimeoutMs = 60_000L
            var hasObservedRunning = false
            while (true) {
                delay(PanMove203Policy.pollDelayMs)
                val result = VMModbusHelper.readHoldingRegisters(modbus_address, action_address_move_result)
                when (PanMove203Policy.evaluate(result, hasObservedRunning)) {
                    PanMove203Decision.Running -> {
                        if (!hasObservedRunning) {
                            journal = journal.copy(checkpoint = PanMoveCheckpoint.OBSERVED_RUNNING)
                            if (!panMoveJournalStore.saveDurable(journal)) {
                                enterActionGroupMaintenance("搬盘已观察 203=1 但 journal 落盘失败：$sourcePosition->$targetPosition")
                                return false
                            }
                            hasObservedRunning = true
                        }
                        logD(LOG_PAN_MOVE, "搬移执行中：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，203=1（执行中）")
                    }
                    PanMove203Decision.Success -> break
                    PanMove203Decision.WaitingForStart -> {
                        logD(
                            LOG_PAN_MOVE,
                            "等待搬移启动确认：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，203=$result（空闲/残留完成，不算本轮成功）"
                        )
                    }
                    PanMove203Decision.ReadFailed -> {
                        logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=读取 203 失败")
                        markDeviceErrorAndUpload(FaultCode.MODBUS_COMM_DROP,
                            "烤盘${sourcePosition}到烤盘${targetPosition}搬移时读取203失败，设备进入维护中")
                        return false
                    }
                    PanMove203Decision.Failed -> {
                        logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，203=$result")
                        markDeviceErrorAndUpload(FaultCode.KAOPAN_MOVE_FAILED,
                            "烤盘${sourcePosition}到烤盘${targetPosition}搬移失败，203=$result，设备进入维护中")
                        return false
                    }
                }
                if (System.currentTimeMillis() - moveStart > moveTimeoutMs) {
                    val reason = if (hasObservedRunning) {
                        "203 长时间为 1（执行中）"
                    } else {
                        "写 202 后未观察到 203=1（执行中）"
                    }
                    logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=$reason")
                    markDeviceErrorAndUpload(FaultCode.KAOPAN_MOVE_TIMEOUT,
                        "烤盘${sourcePosition}到烤盘${targetPosition}搬移超时：$reason，设备进入维护中")
                    return false
                }
            }
            val beforeMoveSellable =
                KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            kaoPan.cmdStatusMove = 0
            newKaoPan.isHasSausage = true
            LowToHighDemandGate.clearPosition(newKaoPan.positionSn, "烤盘搬移目标位写入")
            newKaoPan.isHasGrilling = false
            newKaoPan.taste = KaoPanHelper.copyResolvedTaste(kaoPan.taste) ?: kaoPan.taste
            newKaoPan.holdingTime = targetHoldingTime
            newKaoPan.status = KaoPan.STATUS_SELLABLE_KEEP_WARM
            kaoPan.isHasSausage = false
            kaoPan.startTime = 0
            kaoPan.status = KaoPan.STATUS_EMPTY
            try {
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                if (returnBinding?.originBatchId != null && returnBinding.originBatchType != null) {
                    check(
                        supplementBatchIntentRepository.markReturned(
                            originBatchId = returnBinding.originBatchId!!,
                            originBatchType = returnBinding.originBatchType!!,
                            sourcePositionSn = sourcePosition,
                            returnPositionSn = targetPosition
                        )
                    ) { "搬盘完成但精确回搬 binding 未找到" }
                }
                check(
                    releaseMechanicalMove(
                        "搬盘完成",
                        owner,
                        panMoveClaimOwnerStore(),
                        expectedMoveStatus = 3,
                        idleMoveStatus = 0
                    )
                ) { "搬盘 journal/moveStatus 收口失败" }
            } catch (e: Exception) {
                enterActionGroupMaintenance(
                    "搬盘物理完成后的耐久收口失败：$sourcePosition->$targetPosition，${e.message}"
                )
                return false
            }
            LogUtils.i(
                "【搬盘】搬移完成：来源烤盘=$sourcePosition 已清空，目标烤盘=$targetPosition 进入保温可售"
            )
            logD(
                LOG_PAN_MOVE,
                "搬移完成：来源烤盘=$sourcePosition 已清空，目标烤盘=$targetPosition 已写入，" +
                    "targetHoldingTime=${newKaoPan.holdingTime}，targetStatus=${newKaoPan.status}"
            )
            PanPhysicalStateDeltaReporter.reportPansChanged(
                changedPans = listOf(kaoPan, newKaoPan),
                reason = "pan_move:$sourcePosition:$targetPosition",
                source = "pan_move_success"
            )
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeMoveSellable,
                KaoPanHelper.getKaoPanList(),
                "单根搬盘完成：$sourcePosition->$targetPosition"
            )
            return true
        } catch (cancelled: CancellationException) {
            if (commandAdmitted) {
                enterActionGroupMaintenance(
                    "搬盘 COMMAND_INTENT 已准入但流程被取消，保留 owner/journal 与 moveStatus=3：" +
                        "$sourcePosition->$targetPosition",
                    preserveMoveStatus = true
                )
            }
            throw cancelled
        } catch (error: Exception) {
            if (commandAdmitted) {
                enterActionGroupMaintenance(
                    "搬盘 COMMAND_INTENT 已准入但流程异常，保留 owner/journal 与 moveStatus=3：" +
                        "$sourcePosition->$targetPosition，error=${error.message ?: error.javaClass.simpleName}",
                    preserveMoveStatus = true
                )
            }
            throw error
        } finally {
            actionGroupLocks.release(owner)
        }
    }   

    /**
     * 维护页手动取肠入口。
     *
     * 当前手动取肠也走 300/400 新动作组 out1~out5，由 out4 负责售卖口出肠检测和收口；
     * out5 只负责关门回零，不再调用售卖口拍照或视觉识别。
     */
    fun startManualTakeSausage(kaoPan: KaoPan): Deferred<Boolean>? {
        if (AppConfig.getAppConfig().inspectionMode != AppConfigBean.INSPECTION_MODE_ACTIVE) {
            logW(LOG_ACTION, "维护手动取肠启动被拒绝：检修模式未开启，pan=${kaoPan.positionSn}")
            return null
        }
        return manualTakeTaskCoordinator.start { takeSausage(kaoPan) }
    }

    suspend fun takeSausage(kaoPan: KaoPan) :Boolean {
        if (AppConfig.getAppConfig().inspectionMode != AppConfigBean.INSPECTION_MODE_ACTIVE) {
            logW(LOG_ACTION, "维护手动取肠动作层被拒绝：检修模式未开启，pan=${kaoPan.positionSn}")
            return false
        }
        if (!ensureModbusReady("维护手动取肠动作组恢复检查")) {
            enterActionGroupMaintenance(
                "维护手动取肠入口前 Modbus 重连 3 次仍失败，设备进入维护：stage=维护手动取肠",
                FaultCode.MODBUS_COMM_DROP
            )
            return false
        }
        if (!recoverActionGroupStateAfterModbusReady("维护手动取肠入口前恢复检查")) {
            return false
        }

        if (!waitUntilMoveStatusIdleOrTimeout("维护手动取肠")) {
            return false
        }

        val sausageName = getPanTasteLabel(kaoPan)
        logD(
            LOG_ACTION,
            "维护手动取肠走 300/400 新动作组：烤盘=${kaoPan.positionSn}，口味=$sausageName，" +
                "检测方式=out4 收口结果，不调用视觉识别/拍照"
        )
        val owner = "out_manual:${kaoPan.positionSn}:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
        if (!claimMechanicalMove(
                stage = "维护手动取肠下发前",
                owner = owner,
                ownerStore = actionGroupClaimOwnerStore(
                    ActionGroupSession(
                        sessionId = owner,
                        type = ActionGroupSessionType.OUT_MANUAL,
                        sourceNo = kaoPan.positionSn,
                        currentAction = null,
                        resourceMask = lockMask
                    )
                ),
                allowedMoveStatuses = setOf(0),
                claimedMoveStatus = 2,
                requireSupplementGate = false,
                claimGate = {
                    AppConfig.getAppConfig().inspectionMode == AppConfigBean.INSPECTION_MODE_ACTIVE &&
                        !OrderStateManager.hasProcessingOrder() &&
                        !SupplementPauseController.shouldPauseForOrder()
                }
            )
        ) {
            return false
        }
        try {
            if (!isActionGroupEnabledOrEnterMaintenance("维护手动取肠")) {
                releaseMechanicalMove(
                    "维护手动取肠动作组关闭",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = 0
                )
                return false
            }

            if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
                releaseMechanicalMove(
                    "维护手动取肠资源忙",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = 0
                )
                logW(
                    LOG_ACTION_GROUP,
                    "维护手动取肠动作组资源忙，拒绝下发：sourcePan=${kaoPan.positionSn}，" +
                        "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
                )
                return false
            }

            var outPanClearedByTelemetry = false
            val actionGroupResult = run {
                val claimed = requireNotNull(actionGroupSessionStore.load())
                check(claimed.sessionId == owner) { "维护手动取肠 owner 已变化" }
                actionGroupSessionStore.save(
                    claimed.copy(currentAction = "out1", checkpointVersion = claimed.checkpointVersion + 1L)
                )
                logD(
                    LOG_ACTION_GROUP,
                    "维护手动取肠动作组开始：protocol=300_400，sourcePan=${kaoPan.positionSn}，" +
                        "resourceLocks=${actionGroupLocks.snapshot()}"
                )
                OutActionGroupRunner(
                    createAtomicActionClient(
                        ActionGroupTelemetryContext(
                            sessionId = owner,
                            panNo = kaoPan.positionSn,
                            resourceLocks = actionGroupLocks.snapshot()
                        )
                    ) { telemetry ->
                        if (applyOutPhysicalTelemetry(
                            telemetry = telemetry,
                            plannedPan = kaoPan,
                            source = "维护手动取肠"
                        )) {
                            outPanClearedByTelemetry = true
                        }
                    }
                ) { message -> logD(LOG_ACTION_GROUP, message) }
                    .run(
                        sourcePanNo = kaoPan.positionSn,
                        enableVoiceReminder = false
                    )
            }

            logD(
                LOG_ACTION_GROUP,
                "维护手动取肠动作组结束：sourcePan=${kaoPan.positionSn}，result=${actionGroupResultDesc(actionGroupResult)}"
            )

            when (actionGroupResult) {
                ActionGroupResult.Success,
                ActionGroupResult.Discarded -> {
                    applySuccessfulTakePanState(kaoPan)
                    LowToHighDemandGate.recordNonDemandEmpty(kaoPan.positionSn, "manual_clear")
                    KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                    clearReturnBindingForSourceIfAny(kaoPan.positionSn)
                    refreshTemperatureAfterStablePanMutation("维护手动取肠完成：pan=${kaoPan.positionSn}")
                    if (!outPanClearedByTelemetry) {
                        reportOutCompletionPhysicalDelta(
                            kaoPan = kaoPan,
                            reason = "maintenance_manual_out:completed:pan=${kaoPan.positionSn}",
                            source = "maintenance_manual_out_completion"
                        )
                    }
                    if (!releaseMechanicalMove(
                            "维护手动取肠完成",
                            owner,
                            actionGroupClaimOwnerStore(),
                            expectedMoveStatus = 2,
                            idleMoveStatus = 0
                        )
                    ) {
                        enterActionGroupMaintenance("维护手动取肠完成但 owner/moveStatus 收口失败")
                        return false
                    }
                    actionGroupLocks.release(owner)
                    if (actionGroupResult == ActionGroupResult.Discarded) {
                        logW(LOG_ACTION_GROUP, "维护手动取肠历史丢弃结果已按 4F 收口完成处理：panNo=${kaoPan.positionSn}")
                    } else {
                        logD(LOG_ACTION_GROUP, "维护手动取肠完成，out4 已收口且 out5 已关门回零：panNo=${kaoPan.positionSn}")
                    }
                    return true
                }
                is ActionGroupResult.Failed -> {
                    kaoPan.cmdStatusTake = atomicFailureCode(actionGroupResult)
                    markActionGroupFailure("维护手动取肠", actionGroupResult)
                    KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                    return false
                }
            }
        } finally {
            val hasUnsettledSession = actionGroupSessionStore.load()?.sessionId == owner
            if (!hasUnsettledSession) {
                actionGroupLocks.release(owner)
            }
            if (!hasUnsettledSession && AppConfig.getAppConfig().moveStatus != 0) {
                enterActionGroupMaintenance("维护手动取肠 owner 已清理但 moveStatus 仍忙，禁止无 owner 自动清零")
            }
        }
    }

    /**
     * 正式订单出餐入口。
     *
     * 当前 4F 业务语义：
     * - out4 返回 2（out4 收口成功）：返回 TAKEN_BY_USER，按 status=2（单次履约完成）推进
     * - 4F 当前无法区分“顾客正常取走”和“15 秒未取后下位机回收”
     * - 历史兼容的 Discarded 结果也按 TAKEN_BY_USER/status=2 收口；当前 300/400 主流程不应再主动产生该结果
     * - 机械、识别或通信异常：返回 ERROR，并由上层上报 status=5
     */
    private fun closeConfirmedFailedOutSession(owner: String): Boolean = withFaultHeatTransitionLock {
        val failedSession = actionGroupSessionStore.load()?.takeIf {
            it.sessionId == owner &&
                it.type == ActionGroupSessionType.OUT_SELL &&
                it.mechanicalClaimState == MechanicalClaimState.CLAIMED &&
                it.out1DispatchEvidence == Out1DispatchEvidence.DISPATCHED
        } ?: return@withFaultHeatTransitionLock false
        if (actionGroupLocks.ownedMask(owner) != failedSession.resourceMask) {
            logW(LOG_ORDER, "已确认出餐失败但资源 owner/mask 不匹配，保留恢复证据：owner=$owner")
            return@withFaultHeatTransitionLock false
        }
        val released = mechanicalMoveClaimCoordinator(actionGroupClaimOwnerStore()).release(
            owner = owner,
            expectedMoveStatuses = setOf(2),
            idleMoveStatus = 2,
            expectedRevision = failedSession.mechanicalClaimRevision
        ) is MechanicalMoveReleaseResult.Released
        if (!released) {
            logW(LOG_ORDER, "已确认出餐失败但 OUT 会话未能精确收口：owner=$owner")
            return@withFaultHeatTransitionLock false
        }
        actionGroupLocks.releaseIfOwned(owner, failedSession.resourceMask) != ResourceReleaseResult.MASK_MISMATCH
    }

    suspend fun takeSausageResult(
        kaoPan: KaoPan,
        orderContext: ActionGroupOrderContext? = null,
        deliveryIndex: Int? = null,
        deliveryTotal: Int? = null
    ): TakeSausageResult {
        // 说明：
        // - 订单流程会在整单开始时把 moveStatus 置为 2，表示“订单出餐进行中”。
        // - 这里不能再要求 moveStatus==0，否则会和整单锁冲突，导致出餐卡住。
        // - 只需要等待与出餐冲突的动作（补肠/搬盘/丢弃/自清洁）结束：1/3/4/5。
        // - MQTT 订单入口已对 moveStatus=5（自清洁中）做新单拒绝；这里是动作层的二次竞态兜底。
        // - 如果当前是 0（例如非整单场景直接调用），这里临时占用为 2，结束后再恢复。
        if (!ensureModbusReady("出餐动作组恢复检查")) {
            enterActionGroupMaintenance(
                "正式出餐入口前 Modbus 重连 3 次仍失败，设备进入维护：stage=正式出餐",
                FaultCode.MODBUS_COMM_DROP
            )
            return TakeSausageResult.NO_DISPATCH
        }
        if (!ensureActionGroupReadyForOrderDispatch("正式出餐入口前恢复检查")) {
            return TakeSausageResult.NO_DISPATCH
        }
        var appState = AppConfig.getAppConfig().moveStatus
        var waitedMs = 0L
        var maxWaitMs = sellPriorityWaitTimeoutMs(appState)
        while (appState == 1 || appState == 3 || appState == 4 || appState == 5) {
            if (appState == 5 || maxWaitMs <= 0L || waitedMs >= maxWaitMs) {
                val reason = "正式出餐入口等待当前机械动作安全收口超时或不可抢占：" +
                    "moveStatus=$appState，waitedMs=$waitedMs/$maxWaitMs"
                enterActionGroupMaintenance(reason, FaultCode.ACTION_BUSY_TIMEOUT)
                return TakeSausageResult.NO_DISPATCH
            }
            delay(2000)
            waitedMs += 2000
            appState = AppConfig.getAppConfig().moveStatus
            maxWaitMs = maxOf(maxWaitMs, sellPriorityWaitTimeoutMs(appState))
            logD(LOG_ACTION, "正式出餐前等待机械动作收尾：moveStatus=$appState，waitedMs=$waitedMs/$maxWaitMs")
        }
        val acquiredByThisCall = appState == 0

        val sausageName = getPanTasteLabel(kaoPan)
        val deliverySubject = if (deliveryIndex != null && deliveryTotal != null) {
            "第$deliveryIndex/${deliveryTotal}根"
        } else {
            null
        }
        val deliveryPrefix = deliverySubject?.let { "$it " } ?: ""
        logD(LOG_ACTION, "300/400 动作组将烤肠($sausageName)从烤盘${kaoPan.positionSn}夹取、插签、搬运到售卖口")
        val owner = "out:${kaoPan.positionSn}:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
        if (!claimMechanicalMove(
                stage = "正式出餐下发前",
                owner = owner,
                ownerStore = actionGroupClaimOwnerStore(
                    ActionGroupSession(
                        sessionId = owner,
                        type = ActionGroupSessionType.OUT_SELL,
                        sourceNo = kaoPan.positionSn,
                        orderId = orderContext?.orderNo,
                        orderContext = orderContext,
                        currentAction = null,
                        resourceMask = lockMask,
                        out1DispatchEvidence = Out1DispatchEvidence.PREPARED
                    )
                ),
                allowedMoveStatuses = setOf(appState),
                claimedMoveStatus = 2,
                requireSupplementGate = false
            )
        ) {
            return TakeSausageResult.NO_DISPATCH
        }
        try {
            if (!isActionGroupEnabledOrEnterMaintenance("正式出餐")) {
                releaseMechanicalMove(
                    "正式出餐动作组关闭",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = if (acquiredByThisCall) 0 else 2
                )
                return TakeSausageResult.NO_DISPATCH
            }

            if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
                releaseMechanicalMove(
                    "正式出餐资源忙",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = if (acquiredByThisCall) 0 else 2
                )
                logW(
                    LOG_ACTION_GROUP,
                    "出餐动作组资源忙，拒绝下发：sourcePan=${kaoPan.positionSn}，" +
                        "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
                )
                return TakeSausageResult.NO_DISPATCH
            }

            val latestPan = KaoPanHelper.getKaoPanList()
                .firstOrNull { it.positionSn == kaoPan.positionSn }
            val latestConfig = AppConfig.getAppConfig()
            val purchaseSellEnd = TrafficModeHelper.purchaseSellEnd(
                latestConfig.modeType,
                latestConfig.transitionMode,
                latestConfig.highConcurrencyResidualDrain
            )
            val stillSellable = latestPan != null &&
                latestPan.positionSn <= purchaseSellEnd &&
                latestPan.taste?.tasteCode == orderContext?.tasteCode &&
                latestPan.taste?.productId == orderContext?.productId &&
                KaoPanHelper.isSellable(
                    latestPan,
                    KaoPanHelper.pendingDiscardPositionsOrEmpty()
                )
            if (!stillSellable) {
                val released = releaseMechanicalMove(
                    "正式出餐 out1 前候选失效",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = 2
                )
                actionGroupLocks.release(owner)
                if (!released) {
                    enterActionGroupMaintenance(
                        "正式出餐 out1 前候选失效，但本次 OUT owner 未能精确释放：owner=$owner",
                        preserveMoveStatus = true
                    )
                    return TakeSausageResult.EVIDENCE_UNKNOWN
                }
                logW(
                    LOG_ORDER,
                    "正式出餐 out1 前最终复核失败，未下发机械命令：" +
                        "orderNo=${orderContext?.orderNo}，pan=${kaoPan.positionSn}，sellEnd=$purchaseSellEnd"
                )
                return TakeSausageResult.NO_SELLABLE_STOCK
            }

            var outPanClearedByTelemetry = false
            val actionGroupResult = run {
                val claimed = requireNotNull(actionGroupSessionStore.load())
                check(claimed.sessionId == owner) { "正式出餐 owner 已变化" }
                actionGroupSessionStore.save(
                    claimed.copy(currentAction = "out1", checkpointVersion = claimed.checkpointVersion + 1L)
                )
                logD(
                    LOG_ACTION_GROUP,
                    "出餐动作组开始：protocol=300_400，sourcePan=${kaoPan.positionSn}，resourceLocks=${actionGroupLocks.snapshot()}"
                )
                LogUtils.i("【出餐】${deliveryPrefix}开始：来源烤盘=${kaoPan.positionSn}，订单号=${orderContext?.orderNo ?: "无订单上下文"}")
                var voiceReminderPending = TakeSausageVoiceReminderPolicy.shouldPlay(orderContext, deliveryIndex)
                val result = OutActionGroupRunner(
                    client = createAtomicActionClient(
                        ActionGroupTelemetryContext(
                            sessionId = owner,
                            panNo = kaoPan.positionSn,
                            orderId = orderContext?.orderNo,
                            resourceLocks = actionGroupLocks.snapshot()
                        )
                    ) { telemetry ->
                        if (applyOutPhysicalTelemetry(
                            telemetry = telemetry,
                            plannedPan = kaoPan,
                            source = "正式出餐"
                        )) {
                            outPanClearedByTelemetry = true
                        }
                        persistOutTerminalEventFromTelemetry(telemetry, orderContext, kaoPan.positionSn)
                    },
                    logger = { message -> logD(LOG_ACTION_GROUP, message) },
                    operationLogContext = ActionGroupOperationLogContext(
                        category = "动作组",
                        subject = deliverySubject
                    ),
                    operationLogger = { message -> LogUtils.i(message) },
                    failureLogger = { message -> LogUtils.e(message) }
                )
                    .run(
                        sourcePanNo = kaoPan.positionSn,
                        enableVoiceReminder = true,
                        onActionCommandWritten = { actionName ->
                            val current = requireNotNull(actionGroupSessionStore.load()) {
                                "out1 已写命令但动作会话不存在"
                            }
                            check(current.sessionId == owner && current.currentAction == actionName) {
                                "out1 已写命令但 owner/action 已变化"
                            }
                            actionGroupSessionStore.save(
                                current.copy(
                                    out1DispatchEvidence = Out1DispatchEvidence.DISPATCHED,
                                    checkpointVersion = current.checkpointVersion + 1L
                                )
                            )
                            orderContext?.let {
                                reportActionGroupOrderStartedEvent(it, kaoPan.positionSn)
                            }
                        },
                        onActionCommandNotDispatched = { actionName ->
                            val current = requireNotNull(actionGroupSessionStore.load()) {
                                "out1 写失败但动作会话不存在"
                            }
                            check(current.sessionId == owner && current.currentAction == actionName) {
                                "out1 写失败但 owner/action 已变化"
                            }
                            check(current.out1DispatchEvidence == Out1DispatchEvidence.PREPARED) {
                                "out1 写失败时 dispatch evidence 已变化"
                            }
                            actionGroupSessionStore.save(
                                current.copy(
                                    out1DispatchEvidence = Out1DispatchEvidence.NOT_DISPATCHED_CONFIRMED,
                                    checkpointVersion = current.checkpointVersion + 1L
                                )
                            )
                        },
                        onWindowOpened = {
                            OrderProcessingStageBridge.publish(OrderProcessingStage.READY_TO_TAKE)
                            if (voiceReminderPending) {
                                voiceReminderPending = false
                                LogUtils.i(
                                    "【取肠语音】正式订单开窗后触发取肠提醒：" +
                                        "orderNo=${orderContext?.orderNo}，deliveryIndex=${deliveryIndex ?: -1}，" +
                                        "deliveryTotal=${deliveryTotal ?: -1}"
                                )
                                TakeSausageVoiceReminder.play()
                            }
                        }
                    )
                result
            }

            logD(
                LOG_ACTION_GROUP,
                "出餐动作组结束：sourcePan=${kaoPan.positionSn}，result=${actionGroupResultDesc(actionGroupResult)}"
            )
            LogUtils.i(
                "【动作组】出餐动作组结束：来源烤盘=${kaoPan.positionSn}，结果=${actionGroupResultDesc(actionGroupResult)}"
            )

            val takeResult = when (actionGroupResult) {
                ActionGroupResult.Success -> TakeSausageResult.TAKEN_BY_USER
                ActionGroupResult.Discarded -> TakeSausageResult.TAKEN_BY_USER
                is ActionGroupResult.Failed -> {
                    kaoPan.cmdStatusTake = atomicFailureCode(actionGroupResult)
                    val evidence = actionGroupSessionStore.load()
                        ?.takeIf { it.sessionId == owner }
                        ?.out1DispatchEvidence
                    when {
                        actionGroupResult.result is AtomicActionResult.Failed &&
                            actionGroupResult.result.freshExecutionObserved &&
                            evidence == Out1DispatchEvidence.DISPATCHED -> {
                            markActionGroupFailure("正式出餐", actionGroupResult)
                            if (closeConfirmedFailedOutSession(owner)) {
                                TakeSausageResult.ERROR
                            } else {
                                enterActionGroupMaintenance(
                                    "正式出餐已确认失败，但 OUT 会话或资源未能精确释放：owner=$owner",
                                    preserveMoveStatus = true
                                )
                                TakeSausageResult.EVIDENCE_UNKNOWN
                            }
                        }
                        evidence == Out1DispatchEvidence.NOT_DISPATCHED_CONFIRMED ->
                            TakeSausageResult.NO_DISPATCH
                        else -> TakeSausageResult.EVIDENCE_UNKNOWN
                    }
                }
            }

            if (takeResult == TakeSausageResult.NO_DISPATCH) {
                val released = releaseMechanicalMove(
                    "正式出餐确认未下发",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = 2
                )
                if (released) {
                    actionGroupLocks.release(owner)
                } else {
                    enterActionGroupMaintenance(
                        "正式出餐确认未下发，但本次 OUT owner 未能精确释放：owner=$owner",
                        preserveMoveStatus = true
                    )
                    return TakeSausageResult.EVIDENCE_UNKNOWN
                }
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                return takeResult
            }

            if (takeResult == TakeSausageResult.ERROR ||
                takeResult == TakeSausageResult.EVIDENCE_UNKNOWN
            ) {
                KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                return takeResult
            }

            when (takeResult) {
                TakeSausageResult.TAKEN_BY_USER -> LogUtils.i(
                    "【出餐】${deliveryPrefix}完成：out4 已收口，本单已处理完成，来源烤盘=${kaoPan.positionSn}，订单号=${orderContext?.orderNo ?: "无订单上下文"}"
                )
                TakeSausageResult.DISCARDED -> LogUtils.w(
                    "【出餐】${deliveryPrefix}完成：历史丢弃结果已按 4F 履约完成收口，来源烤盘=${kaoPan.positionSn}，订单号=${orderContext?.orderNo ?: "无订单上下文"}"
                )
                TakeSausageResult.NO_SELLABLE_STOCK,
                TakeSausageResult.NO_DISPATCH,
                TakeSausageResult.EVIDENCE_UNKNOWN,
                TakeSausageResult.ERROR -> Unit
            }

            val terminalStatus = ActionGroupOrderStatus.statusFor(takeResult)
            if (orderContext != null && orderContext.isValid()) {
                savePendingActionGroupOrderEvent(
                    orderContext = orderContext,
                    status = terminalStatus,
                    panNo = kaoPan.positionSn,
                    discarded = false
                )
            } else {
                logW(
                    LOG_ACTION_GROUP,
                    "正式出餐完成但缺少订单上下文，无法建立订单终态补偿事件：panNo=${kaoPan.positionSn}，" +
                        "takeResult=$takeResult，orderContext=$orderContext"
                )
            }
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            applySuccessfulTakePanState(kaoPan)
            armPostSellSupplementGuard("out5 已完成关门回零/出餐收尾")
            KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            clearReturnBindingForSourceIfAny(kaoPan.positionSn)
            refreshTemperatureAfterStablePanMutation("正式出餐完成清空烤盘：pan=${kaoPan.positionSn}")
            if (!outPanClearedByTelemetry) {
                reportOutCompletionPhysicalDelta(
                    kaoPan = kaoPan,
                    reason = "action_completion:out:pan=${kaoPan.positionSn}",
                    source = "action_completion_out"
                )
            }
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "单根出餐完成后清空可售烤盘"
            )
            if (!releaseMechanicalMove(
                    "正式出餐完成",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = if (acquiredByThisCall) 0 else 2
                )
            ) {
                enterActionGroupMaintenance("正式出餐完成但 owner/moveStatus 收口失败")
                return TakeSausageResult.ERROR
            }
            actionGroupLocks.release(owner)

            return takeResult
        } finally {
            val hasUnsettledSession = actionGroupSessionStore.load()?.sessionId == owner
            if (!hasUnsettledSession) {
                actionGroupLocks.release(owner)
            }
            if (acquiredByThisCall && !hasUnsettledSession && AppConfig.getAppConfig().moveStatus != 0) {
                enterActionGroupMaintenance("正式出餐 owner 已清理但 moveStatus 仍忙，禁止无 owner 自动清零")
            }
        }
    }

    internal fun applySuccessfulTakePanState(kaoPan: KaoPan) {
        kaoPan.cmdStatusTake = 0
        kaoPan.isHasSausage = false
        kaoPan.isHasGrilling = false
        kaoPan.holdingTime = 0L
        kaoPan.startTime = 0
        kaoPan.status = 0
    }

    internal fun applySuccessfulSupplementPanState(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        demandGateReason: String,
        nowMs: Long = System.currentTimeMillis(),
        clearPendingDiscard: Boolean = true,
        config: AppConfigBean,
        tasteBuilder: (KaoPanBox, Taste?) -> Taste = KaoPanHelper::buildTasteFromBox
    ): AppConfigBean {
        kaoPanBox.num = maxOf(0, kaoPanBox.num - 1)
        return applySuccessfulSupplementPanStateWithoutBoxDeduction(
            kaoPan = kaoPan,
            kaoPanBox = kaoPanBox,
            demandGateReason = demandGateReason,
            nowMs = nowMs,
            clearPendingDiscard = clearPendingDiscard,
            config = config,
            tasteBuilder = tasteBuilder
        )
    }

    internal fun applySuccessfulSupplementPanStateIfStockAvailable(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        demandGateReason: String,
        nowMs: Long = System.currentTimeMillis(),
        clearPendingDiscard: Boolean = true,
        config: AppConfigBean,
        tasteBuilder: (KaoPanBox, Taste?) -> Taste = KaoPanHelper::buildTasteFromBox
    ): Boolean {
        if (kaoPanBox.num <= 0) {
            return false
        }
        applySuccessfulSupplementPanState(
            kaoPan = kaoPan,
            kaoPanBox = kaoPanBox,
            demandGateReason = demandGateReason,
            nowMs = nowMs,
            clearPendingDiscard = clearPendingDiscard,
            config = config,
            tasteBuilder = tasteBuilder
        )
        return true
    }

    internal fun applySuccessfulSupplementPanStateWithoutBoxDeduction(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        demandGateReason: String,
        nowMs: Long = System.currentTimeMillis(),
        clearPendingDiscard: Boolean = true,
        config: AppConfigBean,
        tasteBuilder: (KaoPanBox, Taste?) -> Taste = KaoPanHelper::buildTasteFromBox
    ): AppConfigBean {
        val cfg = config
        kaoPan.isHasSausage = true
        kaoPan.isHasGrilling = false
        LowToHighDemandGate.clearPosition(kaoPan.positionSn, demandGateReason)
        kaoPan.taste = tasteBuilder(kaoPanBox, kaoPan.taste)
        kaoPan.status = 1
        kaoPan.startTime = nowMs
        kaoPan.holdingTime = 0L
        kaoPan.bakingTime = cfg.bakingTime * 60L * 1000L
        kaoPan.closeTime = cfg.discardTime * 60L * 60L * 1000L
        kaoPan.cmdStatusMove = 0
        clearOrdinaryBoxTakeStatus(kaoPanBox)
        if (clearPendingDiscard) {
            FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(kaoPan.positionSn)
        }
        return cfg
    }

    private fun clearOrdinaryBoxTakeStatus(box: KaoPanBox) {
        if (box.cmdStatusTake != AtomicActionCodes.IR_PICK_FAILED) {
            box.cmdStatusTake = 0
        }
    }

    /**
     * 过保丢弃：把烤盘中超过保温时长的烤肠丢弃。
     * 这条链路也按 201=忙时重试、通信失败/超时才判故障。
     */
    suspend fun discardSausage(
        kaoPan: KaoPan,
        source: String = "过保到期",
        detail: String? = null,
        automatic: Boolean = true,
        businessCloseout: Boolean = false
    ) :Boolean {
        val owner = "discard:${kaoPan.positionSn}:${System.currentTimeMillis()}"
        val tasteLabel = getPanTasteLabel(kaoPan)
        val wasteConsumableEvent = ConsumableEventPayloadBuilder.nextEvent(
            consumableType = "WASTE",
            eventType = "WASTE_DISCARD_EXPIRED",
            deltaValue = 1L,
            source = "discardSausage_success",
            detail = "source=$source, pan=${kaoPan.positionSn}, taste=$tasteLabel"
        )
        val discardJournal = DiscardJournal(
            owner = owner,
            positionSn = kaoPan.positionSn,
            checkpoint = DiscardCheckpoint.PREPARED,
            createdAtMs = System.currentTimeMillis(),
            source = source,
            tasteLabel = tasteLabel,
            wasteConsumableEvent = wasteConsumableEvent
        )
        if (!claimMechanicalMove(
                stage = "过保丢弃下发前",
                owner = owner,
                ownerStore = discardClaimOwnerStore(discardJournal),
                allowedMoveStatuses = setOf(0),
                claimedMoveStatus = 4,
                requireSupplementGate = automatic && !businessCloseout,
                claimGate = { !businessCloseout || isBusinessCloseoutDiscardStillAllowed(owner) }
            )
        ) {
            return false
        }
        val requiresLockedAdmission = automatic || businessCloseout
        if (automatic && !isAutomaticDiscardStillAllowed()) {
            val released = releaseMechanicalMove(
                "过保丢弃认领后订单门禁失败",
                owner,
                discardClaimOwnerStore(),
                expectedMoveStatus = 4,
                idleMoveStatus = 0
            )
            if (!released) {
                enterActionGroupMaintenance(
                    "过保丢弃认领后订单门禁失败且释放失败：pan=${kaoPan.positionSn}",
                    preserveMoveStatus = true
                )
            }
            return false
        }

        var commandAttempted = false
        val sourceDetail = detail?.takeIf { it.isNotBlank() } ?: "未补充详细原因"
        LogUtils.i(
            "【丢弃流程】开始丢弃：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail"
        )

        val result = try {
            executeActionWithRetry(
                actionName = "烤盘${kaoPan.positionSn}${source}丢弃",
                maxBusyRetry = 0,
                runningPollDelayMs = 2000L,
                runningTimeoutMs = 120_000L,
                requireRunningBeforeIdleSuccess = true
            ) {
                when (val admission = admitDiscardCommandIntent(
                    owner = owner,
                    automatic = requiresLockedAdmission,
                    businessCloseout = businessCloseout
                )) {
                    AutomaticMechanicalCommandAdmissionResult.Admitted -> commandAttempted = true
                    is AutomaticMechanicalCommandAdmissionResult.Rejected -> {
                        throw AutomaticMechanicalDispatchPreemptedException()
                    }
                    is AutomaticMechanicalCommandAdmissionResult.Failed -> {
                        commandAttempted = true
                        enterActionGroupMaintenance(
                            "丢弃命令准入失败，保留 owner/journal 与 moveStatus=4：" +
                                "pan=${kaoPan.positionSn}，${admission.reason}",
                            preserveMoveStatus = true
                        )
                        return@executeActionWithRetry VMModbusHelper.ModbusOperationResult(
                            status = VMModbusHelper.ModbusOperationStatus.PROTOCOL_ERROR,
                            message = admission.reason
                        )
                    }
                }
                delay(150L)
                val writeResult = writeSingleRegister(action_address, kaoPan.cmdValueDiscard)
                if (writeResult.isSuccess) {
                    val currentJournal = requireNotNull(discardJournalStore.load()) { "丢弃 journal 不存在" }
                    if (!discardJournalStore.saveDurable(
                            currentJournal.copy(checkpoint = DiscardCheckpoint.COMMAND_ISSUED)
                        )
                    ) {
                        return@executeActionWithRetry VMModbusHelper.ModbusOperationResult(
                            status = VMModbusHelper.ModbusOperationStatus.PROTOCOL_ERROR,
                            message = "丢弃命令已下发但 COMMAND_ISSUED journal 落盘失败"
                        )
                    }
                }
                writeResult
            }
        } catch (_: AutomaticMechanicalDispatchPreemptedException) {
            val released = releaseMechanicalMove(
                "过保丢弃命令下发前检测到订单",
                owner,
                discardClaimOwnerStore(),
                expectedMoveStatus = 4,
                idleMoveStatus = 0
            )
            if (!released) {
                enterActionGroupMaintenance(
                    "过保丢弃命令下发前检测到订单且 owner/moveStatus 回滚失败：pan=${kaoPan.positionSn}",
                    preserveMoveStatus = true
                )
            } else {
                logI(LOG_ACTION, "过保丢弃命令下发前检测到订单，已释放机械资源：pan=${kaoPan.positionSn}")
            }
            return false
        } catch (cancelled: CancellationException) {
            if (commandAttempted) {
                enterActionGroupMaintenance(
                    "过保丢弃命令已尝试但流程被取消，保留 owner/journal 与 moveStatus=4：pan=${kaoPan.positionSn}",
                    preserveMoveStatus = true
                )
            } else if (!commandAttempted) {
                val released = releaseMechanicalMove(
                    "过保丢弃下发前取消",
                    owner,
                    discardClaimOwnerStore(),
                    expectedMoveStatus = 4,
                    idleMoveStatus = 0
                )
                if (!released) {
                    enterActionGroupMaintenance(
                        "过保丢弃下发前取消且 owner/moveStatus 回滚失败：pan=${kaoPan.positionSn}",
                        preserveMoveStatus = true
                    )
                }
            }
            throw cancelled
        } catch (error: Exception) {
            if (commandAttempted) {
                enterActionGroupMaintenance(
                    "过保丢弃 COMMAND_INTENT 已准入但流程异常，保留 owner/journal 与 moveStatus=4：" +
                        "pan=${kaoPan.positionSn}，error=${error.message ?: error.javaClass.simpleName}",
                    preserveMoveStatus = true
                )
            } else {
                val released = releaseMechanicalMove(
                    "过保丢弃下发前异常",
                    owner,
                    discardClaimOwnerStore(),
                    expectedMoveStatus = 4,
                    idleMoveStatus = 0
                )
                if (!released) {
                    enterActionGroupMaintenance(
                        "过保丢弃下发前异常且 owner/moveStatus 回滚失败：pan=${kaoPan.positionSn}",
                        preserveMoveStatus = true
                    )
                }
            }
            throw error
        }
        if(result != ActionExecutionResult.SUCCESS){
            LogUtils.e(
                "【丢弃流程】丢弃失败：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，结果=$result，详情=$sourceDetail"
            )
            markActionFailure("烤盘${kaoPan.positionSn}${source}丢弃", result)
        }
        kaoPan.cmdStatusDiscard=if (result == ActionExecutionResult.SUCCESS) 0 else 2
        val beforeSellable = if (result == ActionExecutionResult.SUCCESS) {
            KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        } else {
            null
        }
        if (result==ActionExecutionResult.SUCCESS){
            applySuccessfulTakePanState(kaoPan)
            SendServerHelper.publishConsumableEvent(wasteConsumableEvent)
            handleSuccessfulDiscardDemandGate(kaoPan.positionSn, source)
            FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(kaoPan.positionSn)
            LogUtils.i(
                "【丢弃流程】丢弃完成：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail，" +
                    "panHasSausage=${kaoPan.isHasSausage}，panStatus=${kaoPan.status}"
            )
            KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            clearReturnBindingForSourceIfAny(kaoPan.positionSn)
            if (shouldRefreshTemperatureAfterDiscard(businessCloseout)) {
                refreshTemperatureAfterStablePanMutation("过保丢弃完成清空烤盘：pan=${kaoPan.positionSn}")
            }
            PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = kaoPan,
                reason = "discardSausage_success:pan=${kaoPan.positionSn}:source=$source",
                source = "discardSausage_success"
            )
        }
        if (result == ActionExecutionResult.SUCCESS || !commandAttempted) {
            if (!releaseMechanicalMove(
                    "过保丢弃完成",
                    owner,
                    discardClaimOwnerStore(),
                    expectedMoveStatus = 4,
                    idleMoveStatus = 0
                )
            ) {
                enterActionGroupMaintenance(
                    "过保丢弃完成但 owner/moveStatus 收口失败",
                    preserveMoveStatus = true
                )
                return false
            }
        } else {
            enterActionGroupMaintenance(
                "过保丢弃结果未知，保留 owner/journal 与 moveStatus=4：pan=${kaoPan.positionSn}，result=$result",
                preserveMoveStatus = true
            )
            return false
        }
        if (result != ActionExecutionResult.SUCCESS) {
            operateScope.launch {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }
        }
        if (beforeSellable != null) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "过保丢弃完成后清空可售烤盘"
            )
        }
        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_ACTION, "等待当前售卖结束后再丢弃下一根烤肠")
        }
        return result==ActionExecutionResult.SUCCESS
    }

    private fun isAutomaticMechanicalCommandStillAllowed(): Boolean {
        val config = AppConfig.getAppConfig()
        return AutoMechanicalDispatchPolicy.canIssueAutomaticCommandAfterClaim(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            isEnable = config.isEnable,
            faultHeatPauseActive = config.faultHeatPauseActive,
            orderPending = SupplementPauseController.shouldPauseForOrder(),
            lowerPlatformStatus = config.lowerPlatformStatus,
            lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName
        )
    }

    private fun isAutomaticDiscardStillAllowed(): Boolean =
        isAutomaticMechanicalCommandStillAllowed()

    private fun isDiscardCommandStillAllowed(owner: String, businessCloseout: Boolean): Boolean =
        if (businessCloseout) {
            isBusinessCloseoutDiscardStillAllowed(owner)
        } else {
            isAutomaticDiscardStillAllowed()
        }

    private fun isBusinessCloseoutDiscardStillAllowed(owner: String): Boolean {
        val config = AppConfig.getAppConfig()
        val recoveryBlocked = !actionGroupRecoveryVerified ||
            actionGroupSessionStore.load() != null ||
            panMoveJournalStore.load() != null ||
            discardJournalStore.load()?.owner?.let { it != owner } == true ||
            hasBlockingSupplementPrefetchEvidence()
        return canIssueBusinessCloseoutDiscardCommand(
            onlineStatus = config.onlineStatus,
            automaticRestSource = AppConfig.normalizeRestStatusSource(config.restStatusSource) ==
                AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            faultHeatPauseActive = config.faultHeatPauseActive,
            orderPending = SupplementPauseController.shouldPauseForOrder(),
            recoveryBlocked = recoveryBlocked,
            lowerPlatformBlocked = AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                config.lowerPlatformStatus,
                config.lowerPlatformIsolationActionName
            ),
            modbusMasterAvailable = VMModbusHelper.currentDiagnosisSnapshot().connected
        )
    }
    /**
     * 读取烤盘温度
     * area 1，2，3区
     */
    fun readKaoPanTemperature(area: Int):Int{
        when (area) {   
            1 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_1_temperature)
            }
            2 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_2_temperature)
            }
            3 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_3_temperature)
            }
        }
        return 0
    }
    /**
     *  1区500 2区501 3区502 读取状态
     *  503 504 505 写入温度
     * area 加热1，2，3区
     * 加热温度 160
     */
    fun heating(area: Int) {
        withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            if (isNormalPositiveTemperatureWriteBlocked(config)) {
                logW(LOG_HEAT, "拒绝算法加热写入：area=$area，设备处于整机故障停热或维护状态")
                return@withFaultHeatTransitionLock
            }
            val heatingTemperature = targetHeatingTemperature(
                area = area,
                heatingTemperature = config.heatingTemperature,
                zone1HeatingOffset = config.zone1HeatingOffset,
                zone2HeatingOffset = config.zone2HeatingOffset,
                zone3HeatingOffset = config.zone3HeatingOffset
            )
            val offset = when (area) {
                1 -> config.zone1HeatingOffset
                2 -> config.zone2HeatingOffset
                3 -> config.zone3HeatingOffset
                else -> 0
            }
            logD(
                LOG_HEAT,
                "开始加热：area=$area，baseTemperature=${config.heatingTemperature}，" +
                    "heatingOffset=$offset，targetTemperature=$heatingTemperature"
            )
            setAreaTargetTemperature(area, heatingTemperature, "heating")
        }
    }

    internal fun targetHeatingTemperature(
        area: Int,
        heatingTemperature: Int,
        zone1HeatingOffset: Int,
        zone2HeatingOffset: Int,
        zone3HeatingOffset: Int
    ): Int {
        val offset = when (area) {
            1 -> zone1HeatingOffset
            2 -> zone2HeatingOffset
            3 -> zone3HeatingOffset
            else -> 0
        }
        return FourFTemperaturePolicy.clampTargetTemperature(heatingTemperature + offset)
    }
    /**
     *
     * area 1，2，3区
     * 保温 60
     */
    fun keepWarm(area: Int) {
        withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            if (isNormalPositiveTemperatureWriteBlocked(config)) {
                logW(LOG_HEAT, "拒绝算法保温写入：area=$area，设备处于整机故障停热或维护状态")
                return@withFaultHeatTransitionLock
            }
            logD(LOG_HEAT, "开始保温：temperature=${config.keepWarmTemperature}")
            logD(LOG_HEAT, "当前加热温度配置：temperature=${config.heatingTemperature}")
            setAreaTargetTemperature(area, config.keepWarmTemperature, "keepWarm")
        }
    }

    internal fun keepWarmForMaturityCloseout(area: Int): VMModbusHelper.ModbusOperationResult {
        return withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            if (isNormalPositiveTemperatureWriteBlocked(config)) {
                return@withFaultHeatTransitionLock VMModbusHelper.ModbusOperationResult(
                    status = VMModbusHelper.ModbusOperationStatus.DEVICE_FAILURE,
                    message = "设备处于整机故障停热或维护状态，拒绝成熟收口保温写入"
                )
            }
            setAreaTargetTemperature(area, config.keepWarmTemperature, "maturityCloseout")
        }
    }

    /**
     *
     * area 加热1，2，3区
     * 停止加热 0
     */
    fun stopHeating(area: Int) {
        withFaultHeatTransitionLock {
            writeAreaTargetTemperatureRaw(area, 0, "stopHeating")
        }
    }

    /**
     * 全盘无肠时关闭 1/2/3 区目标温度。
     *
     * 除烤盘实际是否有肠外，还会保护活动补肠批次的有效待补温区；不依赖 isEnable
     * （烤肠算法开关：0关闭，1开启），避免把“是否自动调度”和“是否需要控温”混在一起。
     *
     * @return true 表示已确认全盘无肠并写入 503/504/505=0；false 表示仍有烤肠，未清零。
     */
    fun stopAllHeatingIfNoSausage(reason: String, force: Boolean = false): Boolean {
        return withFaultHeatTransitionLock {
            val list = KaoPanHelper.getKaoPanList()
            val batchDemand = TemperatureControlPolicy.projectBatchIntent(
                supplementBatchIntentRepository.loadActive()
            )
            val decision = TemperatureControlPolicy.decideEmptyPanStop(
                hasAnySausage = TemperatureControlPolicy.hasAnySausageInPhysicalPans(list),
                hasPendingSupplementTarget = batchDemand.pendingSupplementPositions.isNotEmpty(),
                previousHasAnySausage = lastPhysicalPanHasAnySausage,
                force = force
            )
            lastPhysicalPanHasAnySausage = decision.nextHasAnySausage
            if (!decision.shouldStopAllHeating) {
                if (!decision.nextHasAnySausage) {
                    logTemperatureApplyOnce(
                        signature = "skipStopAll:$reason",
                        message = "全盘持续无肠，本轮不重复写入 503/504/505=0，保留测试页或人工临时温度：reason=$reason"
                    )
                }
                return@withFaultHeatTransitionLock false
            }

            writeAreaTargetTemperatureRaw(1, 0, "stopAllHeatingIfNoSausage")
            writeAreaTargetTemperatureRaw(2, 0, "stopAllHeatingIfNoSausage")
            writeAreaTargetTemperatureRaw(3, 0, "stopAllHeatingIfNoSausage")
            logTemperatureApplyOnce(
                signature = "stopAll:$reason:$force:0,0,0",
                message = "全盘无肠，已关闭一/二/三区目标温度：reason=$reason，force（是否强制清零）=$force，寄存器503=0，寄存器504=0，寄存器505=0"
            )
            true
        }
    }

    private fun isNormalPositiveTemperatureWriteBlocked(config: AppConfigBean): Boolean {
        return FaultHeatPauseManager.shouldSkipBakingCompletion(config) ||
            config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
            config.onlineStatus == 3
    }

    /**
     * 按当前烤盘状态和活动补肠/回补批次刷新 1/2/3 区目标温度。
     *
     * 物理区域口径：
     * - 一区：1~9
     * - 二区：13~21
     * - 三区：25~33
     *
     * 规则：
     * - 区域内存在有效待补目标：写 heatingTemperature（加热温度）
     * - 区域内存在 status=1（烤制中）的烤肠：写 heatingTemperature（加热温度）
     * - 否则区域内存在待回补绑定、status=2（保温可售）或 holdingTime>0 的烤肠：写 keepWarmTemperature（保温温度）
     * - 否则：写 0，关闭该区加热和保温
     */
    fun <T> withFaultHeatTransitionLock(block: () -> T): T =
        synchronized(faultHeatTransitionLock) { block() }

    private fun refreshTemperatureAfterStablePanMutation(reason: String) {
        if (!applyConfiguredTemperatureByPanState(reason)) {
            logW(LOG_HEAT, "盘位稳定变化后的温控重算未完成，保留盘位/订单终态：reason=$reason")
        }
    }

    fun applyConfiguredTemperatureByPanState(reason: String): Boolean =
        withFaultHeatTransitionLock {
            if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
                logW(LOG_HEAT, "故障停热暂停中，拒绝普通温度刷新：reason=$reason")
                false
            } else {
                applyConfiguredTemperatureByPanStateInternal(
                    reason = reason,
                    finishFaultPause = false,
                    stopHeatingOnFailure = false
                )
            }
        }

    private fun applyConfiguredTemperatureByPanStateInternal(
        reason: String,
        finishFaultPause: Boolean,
        stopHeatingOnFailure: Boolean,
        onFaultPauseEnded: ((FaultHeatPauseManager.EndPauseResult) -> Unit)? = null
    ): Boolean {
        val config = AppConfig.getAppConfig()
        val pausePreview = if (finishFaultPause) {
            FaultHeatPauseManager.previewFaultHeatPauseEnd(reason)
        } else {
            FaultHeatPauseManager.EndPauseResult.NotActive
        }
        val journalPreparation = if (finishFaultPause) {
            FaultHeatPauseManager.prepareRecoveryJournalIfNeeded(reason)
        } else {
            FaultHeatPauseManager.RecoveryJournalPrepareResult(
                FaultHeatPauseManager.RecoveryJournalPrepareStatus.NO_PAUSE
            )
        }
        if (
            finishFaultPause &&
            FaultHeatPauseManager.shouldBlockRecoveryTemperatureWrite(pausePreview, journalPreparation)
        ) {
            logW(
                LOG_HEAT,
                "故障停热恢复预判或journal准备失败，拒绝写入正温：" +
                    "reason=$reason，previewAlert=${pausePreview.alertCode}，" +
                    "prepareStatus=${journalPreparation.status}，prepareError=${journalPreparation.error}"
            )
            return false
        }
        val pendingDiscardForPolicy =
            (config.pendingDiscardPositions + pausePreview.pendingDiscardPositions).distinct().sorted()
        val batchDemand = TemperatureControlPolicy.projectBatchIntent(
            supplementBatchIntentRepository.loadActive()
        )
        val decisions = TemperatureControlPolicy.decideZoneTemperatures(
            pans = KaoPanHelper.getKaoPanList(),
            keepWarmTemperature = config.keepWarmTemperature,
            heatingTemperature = config.heatingTemperature,
            heatingOffsetsByArea = mapOf(
                1 to config.zone1HeatingOffset,
                2 to config.zone2HeatingOffset,
                3 to config.zone3HeatingOffset
            ),
            pendingDiscardPositions = pendingDiscardForPolicy,
            pendingSupplementPositions = batchDemand.pendingSupplementPositions,
            pendingReturnPositions = batchDemand.pendingReturnPositions
        )
        val writeResults = decisions.map { decision ->
            setAreaTargetTemperature(decision.area, decision.targetTemperature, reason)
        }
        val allWritesSucceeded = writeResults.all { it.isSuccess }
        if (!allWritesSucceeded) {
            val failures = decisions.zip(writeResults)
                .filterNot { (_, result) -> result.isSuccess }
                .joinToString("，") { (decision, result) ->
                    "${decision.area}区=${result.status}/${result.message ?: "无详情"}"
                }
            logW(
                LOG_HEAT,
                "按当前烤盘状态恢复目标温度未全部成功，保持故障停热暂停：reason=$reason，failures=$failures"
            )
            if (stopHeatingOnFailure) {
                stopHeatingForFault("$reason：温控恢复未全部成功，重新停热")
            }
        } else if (finishFaultPause) {
            val pauseResult = try {
                FaultHeatPauseManager.endFaultHeatPauseAndAdjustIfNeeded(reason).also { result ->
                    onFaultPauseEnded?.invoke(result)
                }
            } catch (throwable: Throwable) {
                logW(LOG_HEAT, "故障停热恢复提交异常，立即重新停热：reason=$reason，error=${throwable.message}")
                stopHeatingForFault("$reason：故障恢复提交异常，重新停热")
                return false
            }
            if (
                FaultHeatPauseManager.shouldCompensateFailedRecoveryFinalize(
                    pauseResult,
                    journalPreparation
                )
            ) {
                logW(
                    LOG_HEAT,
                    "故障停热恢复提交被安全阻断，立即重新停热：" +
                        "reason=$reason，alertCode=${pauseResult.alertCode}"
                )
                stopHeatingForFault("$reason：故障恢复提交失败，重新停热")
                return false
            }
            val unplannedPending = pauseResult.pendingDiscardPositions - pausePreview.pendingDiscardPositions.toSet()
            if (unplannedPending.isNotEmpty()) {
                return applyConfiguredTemperatureByPanStateInternal(
                    reason = "$reason：故障恢复提交时新增待丢弃盘后重刷温控",
                    finishFaultPause = false,
                    stopHeatingOnFailure = true
                )
            }
        }
        val detail = decisions.joinToString("，") { decision ->
            val offset = when (decision.area) {
                1 -> config.zone1HeatingOffset
                2 -> config.zone2HeatingOffset
                3 -> config.zone3HeatingOffset
                else -> 0
            }
            val targetDetail = if (decision.mode == TemperatureControlPolicy.ZoneMode.HEATING) {
                "${config.heatingTemperature}${formatHeatingOffset(offset)}=>${decision.targetTemperature}℃"
            } else {
                "${decision.targetTemperature}℃"
            }
            "${decision.area}区(${decision.positions.first}~${decision.positions.last})=$targetDetail/${temperatureModeText(decision.mode)}"
        }
        logTemperatureApplyOnce(
            signature = "apply:$reason:$detail:success=$allWritesSucceeded",
            message = "按当前烤盘状态刷新目标温度：reason=$reason，$detail，" +
                "keepWarmTemperature（保温温度）=${config.keepWarmTemperature}℃，" +
                "heatingTemperature（基础烤制温度）=${config.heatingTemperature}℃，" +
                "zone1HeatingOffset（一区烤制补偿）=${config.zone1HeatingOffset}℃，" +
                "zone2HeatingOffset（二区烤制补偿）=${config.zone2HeatingOffset}℃，" +
                "zone3HeatingOffset（三区烤制补偿）=${config.zone3HeatingOffset}℃，" +
                "allWritesSucceeded（全部温区写入成功）=$allWritesSucceeded"
        )
        return allWritesSucceeded
    }

    /**
     * 按当前烤盘实际状态重建 1/2/3 区温度。
     */
    fun recoverFaultHeatingAndRuntimeState(
        targetOnlineStatus: Int,
        reason: String
    ): ManualFaultRecoveryResult {
        var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
        val stateStore = object : ManualFaultRecoveryStateStore {
            override fun snapshot(): ManualFaultRecoveryState {
                val config = AppConfig.getAppConfig()
                return ManualFaultRecoveryState(
                    onlineStatus = config.onlineStatus,
                    errorStatus = config.errorStatus,
                    errorCode = config.errorCode,
                    errorCategory = config.errorCategory,
                    errorReason = config.errorReason,
                    isEnable = config.isEnable
                )
            }

            override fun saveRecovered(targetOnlineStatus: Int) {
                val config = AppConfig.getAppConfig()
                config.errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
                config.errorCode = null
                config.errorCategory = null
                config.errorReason = null
                config.onlineStatus = targetOnlineStatus
                config.isEnable = 0
                AppConfig.saveAppConfigDurable(config)
            }

            override fun restoreFault(snapshot: ManualFaultRecoveryState) {
                val config = AppConfig.getAppConfig()
                config.onlineStatus = snapshot.onlineStatus
                config.errorStatus = snapshot.errorStatus
                config.errorCode = snapshot.errorCode
                config.errorCategory = snapshot.errorCategory
                config.errorReason = snapshot.errorReason
                config.isEnable = 0
                AppConfig.saveAppConfigDurable(config)
            }
        }
        val recoverySafetyGuard = {
            val config = AppConfig.getAppConfig()
            targetOnlineStatus in 0..2 &&
                VMModbusHelper.connectStatus() &&
                config.moveStatus == 0 &&
                (!isActionGroupRecoveryBlocked() || isParallelSupplementPausedForRuntimeRecovery())
        }
        val result = ManualFaultRecoveryCoordinator(faultHeatTransitionLock, stateStore).recover(
            targetOnlineStatus = targetOnlineStatus,
            initialCondition = {
                val config = AppConfig.getAppConfig()
                recoverySafetyGuard() &&
                    (
                        config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
                            config.onlineStatus == 3 ||
                            FaultHeatPauseManager.shouldSkipBakingCompletion(config)
                        )
            },
            commitGuard = recoverySafetyGuard,
            forceAlgorithmDisabled = {
                KaoChangAlgorithm.isEnabled = false
                AppConfig.getAppConfig().isEnable = 0
            },
            restoreHeating = {
                restoreHeatingStateFromCurrentPansLocked(reason).also { outcome ->
                    pauseEndResult = outcome.pauseEndResult
                }.succeeded
            },
            stopHeating = {
                stopHeatingForFault("$reason：恢复未完成，重新停热并保持维护")
            }
        )
        syncRecoveredFaultHeatPhysicalState(pauseEndResult, reason)
        return result
    }

    internal fun restoreHeatingStateFromCurrentPansLocked(reason: String): FaultHeatingRestoreOutcome {
        check(Thread.holdsLock(faultHeatTransitionLock)) {
            "故障温控恢复必须由最外层调用方持有 transition lock"
        }
        var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
        val succeeded = applyConfiguredTemperatureByPanStateInternal(
            reason = reason,
            finishFaultPause = true,
            stopHeatingOnFailure = true,
            onFaultPauseEnded = { pauseEndResult = it }
        )
        return FaultHeatingRestoreOutcome(succeeded, pauseEndResult)
    }

    fun restoreHeatingStateFromCurrentPansSafely(
        reason: String,
        canRestore: () -> Boolean
    ): Boolean {
        var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
        val succeeded = withFaultHeatTransitionLock {
            if (!canRestore()) {
                false
            } else {
                restoreHeatingStateFromCurrentPansLocked(reason).also { outcome ->
                    pauseEndResult = outcome.pauseEndResult
                }.succeeded
            }
        }
        syncRecoveredFaultHeatPhysicalState(pauseEndResult, reason)
        return succeeded
    }

    internal fun syncRecoveredFaultHeatPhysicalState(
        pauseEndResult: FaultHeatPauseManager.EndPauseResult?,
        reason: String
    ) {
        val result = pauseEndResult ?: return
        runCatching {
            FaultHeatPauseManager.syncRecoveredPhysicalState(result, reason)
        }.onFailure { error ->
            logW(
                LOG_HEAT,
                "故障停热恢复物理增量同步失败，等待后续快照补偿：reason=$reason，error=${error.message}"
            )
        }
    }

    fun invalidateFaultHeatStopConfirmation(reason: String): Boolean {
        return withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            if (
                FaultHeatPauseManager.shouldSkipBakingCompletion(config) &&
                config.faultHeatStopConfirmed == 1
            ) {
                config.faultHeatStopConfirmed = 0
                AppConfig.saveAppConfig(config)
                logW(LOG_HEAT, "故障停热确认已因连接周期变化失效，等待重新写0确认：reason=$reason")
                true
            } else {
                false
            }
        }
    }

    /**
     * 设备故障态停热入口。
     *
     * 寄存器 503/504/505 分别是一/二/三区目标温度，写 0 表示停止烤制和保温加热。
     * 这里只停热和关闭自动调度；计时补偿由恢复非 0 温度前统一执行。
     */
    fun stopHeatingForFault(reason: String): Boolean {
        return withFaultHeatTransitionLock {
            FaultHeatPauseManager.beginFaultHeatPauseIfNeeded(reason, source = "stopHeatingForFault")
            val writeResults = (1..3).map { area ->
                area to writeAreaTargetTemperatureRaw(area, 0, "stopHeatingForFault")
            }
            val allWritesSucceeded = writeResults.all { (_, result) -> result.isSuccess }
            val config = AppConfig.getAppConfig()
            config.faultHeatStopConfirmed = if (allWritesSucceeded) 1 else 0
            AppConfig.saveAppConfig(config)
            lastTemperatureApplyLogSignature = null
            if (allWritesSucceeded) {
                logW(
                    LOG_HEAT,
                    "设备故障停热：reason=$reason，寄存器503/504/505目标温度写0成功，" +
                        "已记录故障停热暂停；后续恢复非0温度前会先补偿烤制计时"
                )
            } else {
                val failures = writeResults.filterNot { (_, result) -> result.isSuccess }
                    .joinToString("，") { (area, result) ->
                        "${area}区=${result.status}/${result.message ?: "无详情"}"
                    }
                logW(
                    LOG_HEAT,
                    "设备故障停热写0未全部确认，保持计时暂停并等待重试：reason=$reason，failures=$failures"
                )
            }
            allWritesSucceeded
        }
    }

    private fun setAreaTargetTemperature(
        area: Int,
        temperature: Int,
        reason: String
    ): VMModbusHelper.ModbusOperationResult =
        writeAreaTargetTemperatureRaw(area, temperature, "setAreaTargetTemperature:$reason")

    // 仅供写 0 停热/主动暂停/故障停热路径，以及 setAreaTargetTemperature 内部最终落寄存器使用。
    private fun writeAreaTargetTemperatureRaw(
        area: Int,
        temperature: Int,
        source: String
    ): VMModbusHelper.ModbusOperationResult {
        val targetTemperature = FourFTemperaturePolicy.clampTargetTemperature(temperature)
        if (targetTemperature != temperature) {
            logW(
                LOG_HEAT,
                "温区目标温度已按 4F 限制归一化：source=$source，area=$area，" +
                    "rawTemperature=$temperature，targetTemperature=$targetTemperature（>=1 按配置加热，<=0 写0）"
            )
        }
        logD(LOG_HEAT, "写入温区目标温度：source=$source，area=$area，temperature=$targetTemperature")
        return when (area) {
            1 -> writeSingleRegister(action_address_1_temperature_write, targetTemperature)
            2 -> writeSingleRegister(action_address_2_temperature_write, targetTemperature)
            3 -> writeSingleRegister(action_address_3_temperature_write, targetTemperature)
            else -> VMModbusHelper.ModbusOperationResult(
                status = VMModbusHelper.ModbusOperationStatus.PARAM_ERROR,
                message = "非法温区：$area"
            )
        }
    }

    private fun temperatureModeText(mode: TemperatureControlPolicy.ZoneMode): String {
        return when (mode) {
            TemperatureControlPolicy.ZoneMode.HEATING -> "烤制中"
            TemperatureControlPolicy.ZoneMode.KEEP_WARM -> "保温可售"
            TemperatureControlPolicy.ZoneMode.STOP -> "无肠停止"
        }
    }

    private fun handleSuccessfulDiscardDemandGate(positionSn: Int, source: String) {
        if (source != "过保到期") {
            return
        }
        val config = AppConfig.getAppConfig()
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn)
        if (config.transitionMode == 2) {
            clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation("过保丢弃进入高转低/残留消化，停止25~33未完成补肠意图")
        }
        AppConfig.saveAppConfigDurable(config)
        if (!result.changedModeState && !result.blockedFrontPositionsChanged) {
            return
        }
        LogUtils.w(
            "【并发切换诊断】过保丢弃触发残留消化/低并发收口：source=KaoChangOperate.handleSuccessfulDiscardDemandGate，烤盘=$positionSn，" +
                "modeType（并发模式）=${modeTypeDesc(oldModeType)}->${modeTypeDesc(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionModeDesc(oldTransitionMode)}->${transitionModeDesc(config.transitionMode)}，" +
                "blockedFrontPositions（过保丢弃空位，跟踪1~21）=${result.blockedFrontPositions}，" +
                "highConcurrencyResidualDrain（高并发残留消化态）不在此处写入，交由下一轮现有求值器计算"
        )
        SendServerHelper.publishServiceUpdateStatus("runtime_state:expired_discard_low_mode_closeout")
    }

    private fun formatHeatingOffset(offset: Int): String {
        return if (offset >= 0) {
            "+$offset"
        } else {
            offset.toString()
        }
    }

    private fun logTemperatureApplyOnce(signature: String, message: String) {
        if (lastTemperatureApplyLogSignature == signature) {
            return
        }
        lastTemperatureApplyLogSignature = signature
        logD(LOG_HEAT, message)
    }
    /**
     * 暂停营业时间
     * 停止加热和保温
     */
    fun pauseBusiness(): Boolean {
        val area1 = writeAreaTargetTemperatureRaw(1, 0, "pauseBusiness")
        val area2 = writeAreaTargetTemperatureRaw(2, 0, "pauseBusiness")
        val area3 = writeAreaTargetTemperatureRaw(3, 0, "pauseBusiness")
        return area1.isSuccess && area2.isSuccess && area3.isSuccess
    }

    /**
     * 营业时间开始。
     * 若当前是休息中（2）且无故障，则自动恢复到运营中（1），并恢复算法与加热状态。
     * 若存在故障，则切到维护中（3），避免带病恢复营业。
     */
    fun startBusinessHours(config: AppConfigBean, source: String = "营业时间自动恢复") {
        if (config.errorStatus != 0) {
            val previousOnlineStatus = config.onlineStatus
            config.onlineStatus = 3
            config.isEnable = 0
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            KaoChangAlgorithm.isEnabled = false
            HomeUiRefreshBridge.requestRefresh("business_start:fault")
            logW(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=检测到故障，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                    "isEnable=${config.isEnable}(0=关闭)，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}，" +
                    "errorStatus=${config.errorStatus}(0=正常, 非0=故障)"
            )
            SendServerHelper.publishServiceUpdateStatus()
            return
        }

        if (config.onlineStatus != 2) {
            logD(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=当前状态无需自动恢复，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}，" +
                    "isEnable=${config.isEnable}，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
            )
            return
        }

        if (AppConfig.normalizeRestStatusSource(config.restStatusSource) != AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS) {
            logD(
                LOG_DEVICE,
                "跳过营业开始自动恢复：原因=休息中来源不允许自动恢复，source=$source，" +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}，" +
                    "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
            )
            return
        }

        val previousOnlineStatus = config.onlineStatus
        val previousIsEnable = config.isEnable
        val previousRestStatusSource = config.restStatusSource
        var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
        val restored = withFaultHeatTransitionLock {
            val latestConfig = AppConfig.getAppConfig()
            if (
                latestConfig.errorStatus != 0 ||
                latestConfig.onlineStatus != 2 ||
                AppConfig.normalizeRestStatusSource(latestConfig.restStatusSource) !=
                AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
            ) {
                false
            } else {
                val restoreOutcome = restoreHeatingStateFromCurrentPansLocked("营业时间开始自动恢复")
                pauseEndResult = restoreOutcome.pauseEndResult
                if (!restoreOutcome.succeeded) {
                    false
                } else {
                    latestConfig.onlineStatus = 1
                    latestConfig.isEnable = 1
                    latestConfig.restStatusSource = AppConfigBean.REST_SOURCE_NONE
                    AppConfig.saveAppConfig(latestConfig)
                    KaoChangAlgorithm.isEnabled = true
                    true
                }
            }
        }
        syncRecoveredFaultHeatPhysicalState(pauseEndResult, "营业时间开始自动恢复")
        if (!restored) {
            logW(
                LOG_DEVICE,
                "营业开始自动恢复未完成：温控恢复失败或运行态已变化，保持算法关闭，source=$source"
            )
            return
        }
        HomeUiRefreshBridge.requestRefresh("business_start:rest_to_operating")
        logI(
            LOG_DEVICE,
            "营业开始自动恢复：source=$source，" +
                "onlineStatus=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                "isEnable=$previousIsEnable(0=关闭, 1=开启)->${config.isEnable}(1=开启)，" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(previousRestStatusSource)}->${restStatusSourceDesc(config.restStatusSource)}"
        )
        SendServerHelper.publishServiceUpdateStatus()
    }

    /**
     * 营业时间结束。
     * 只有当设备当前是"运营中（1）"时才切换为"休息中（2）"；
     * 若后台已手动设为维护中/待投运，不覆盖。
     */
    suspend fun endBusinessHours(config: AppConfigBean){
        KaoChangAlgorithm.requestBusinessCloseout(
            "兼容入口 endBusinessHours：onlineStatus=${config.onlineStatus}，errorStatus=${config.errorStatus}"
        )
    }

    /**
     * 读取寄存器的值
     */
    fun readHoldingRegisters(address:Int,quantity:Int):IntArray?{
        return VMModbusHelper.readHoldingRegisters(modbus_address,address,quantity)
    }



    /**
     * 写入寄存器的值
     */
    fun writeSingleRegister(address: Int,value:Int): VMModbusHelper.ModbusOperationResult{
        logD(LOG_DEVICE, "写寄存器前错误状态检查：errorStatus=${AppConfig.getAppConfig().errorStatus}")
        return VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

    /**
     * 由于下位机处理频率过低需要延迟150毫秒，读取都需要加延迟150发送，每个读取方法已经加了delay
     */
    suspend fun writeSingleRegister2(address: Int,value:Int): VMModbusHelper.ModbusOperationResult{
        delay(150)
        logD(LOG_DEVICE, "延迟写寄存器前错误状态检查：errorStatus=${AppConfig.getAppConfig().errorStatus}")
        return VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

    fun actionDebug() {
        writeSingleRegister(action_debug,5555)

    }

}
