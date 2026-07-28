package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
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
import cn.niuyannet.kaochang.android.services.actiongroup.CompletedInActionGroupPipelineTask
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
import cn.niuyannet.kaochang.android.services.actiongroup.OutActionGroupRunner
import cn.niuyannet.kaochang.android.services.actiongroup.PrefetchSupplementPlanner
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceLocks
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.PendingActionGroupOrderEvent
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupOrderEventStore
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupSessionStore
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceSupplementPrefetchSessionStore
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementBarrierState
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementLaneRole
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPhysicalCheckpointResult
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPhysicalMutation
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPhysicalStateCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchOrderGatePolicy
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchOrderPauseSafety
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchLaneCheckpoint
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchRecoveryCoordinator
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchRecoveryResult
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePlanner
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePreparation
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePlan
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchSessionRepository
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementResumeSingleFlight
import cn.niuyannet.kaochang.android.services.actiongroup.TakeSausageVoiceReminderPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.OrderProcessingStage
import cn.niuyannet.kaochang.android.utils.OrderProcessingStageBridge
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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

    internal data class FaultHeatingRestoreOutcome(
        val succeeded: Boolean,
        val pauseEndResult: FaultHeatPauseManager.EndPauseResult?
    )

    private const val MANUAL_TAKE_IDLE_TIMEOUT_MS = 15_000L
    /**
     * 机械动作对象内部的主线程作用域。
     *
     * 这里只承接“保存配置、同步烤盘、上报设备状态”这类不属于页面生命周期、
     * 但又需要切回主线程执行的任务，避免继续使用 GlobalScope 造成悬挂协程。
     */
    private val operateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionGroupLocks = ResourceLocks()
    private val faultHeatTransitionLock = Any()
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
    private val panMoveJournalStore = SharedPreferencePanMoveJournalStore()
    private val supplementBatchIntentRepository =
        SupplementBatchIntentRepository(SharedPreferenceSupplementBatchIntentStore())
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
    }

    private fun mechanicalMoveClaimCoordinator(
        ownerStore: MechanicalClaimOwnerStore
    ) = MechanicalMoveClaimCoordinator(
        transitionLock = faultHeatTransitionLock,
        ownerStore = ownerStore,
        moveStore = mechanicalMoveStatusStore
    )

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

    fun isActionGroupIdleForMoveStatusRecovery(): Boolean {
        return actionGroupSessionStore.load() == null &&
            panMoveJournalStore.load() == null &&
            !hasBlockingSupplementPrefetchEvidence() &&
            actionGroupLocks.isIdle()
    }

    fun abandonSupplementPrefetchForManualPanClear(
        statusReadings: List<Int>,
        reason: String
    ): Boolean {
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
            hasBlockingSupplementPrefetchEvidence()

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

    private fun supplementPipelineOutcome(
        result: InActionGroupPipelineResult?
    ): SupplementPipelineOutcome = when (result) {
        is InActionGroupPipelineResult.Success -> SupplementPipelineOutcome.SUCCESS
        is InActionGroupPipelineResult.Paused -> SupplementPipelineOutcome.PAUSED_FOR_ORDER
        is InActionGroupPipelineResult.ReplanRequired -> SupplementPipelineOutcome.REPLAN_REQUIRED
        is InActionGroupPipelineResult.Failed -> SupplementPipelineOutcome.FAILED
        null -> SupplementPipelineOutcome.CANCELLED
    }

    fun requestSupplementPauseForOrder(orderNo: String) {
        if (orderNo.isBlank()) return
        SupplementPauseController.requestPauseForOrder(orderNo)
        val session = activeSupplementPrefetchSessionOrNull("订单暂停请求") ?: return
        runCatching { supplementPrefetchSessionRepository.requestOrderBarrier(orderNo) }
            .onFailure { error ->
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
            if (!actionGroupLocks.tryAcquire(parallelSession.resourceMask, parallelSession.sessionId)) {
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
            return handleParallelSupplementResumePreparation(preparation, reason, lockAlreadyOwned = true)
        }
        SupplementPauseController.clearOrderPause(orderNo)
        return resumePausedSupplementAfterOrder(reason)
    }

    fun clearOrderMoveStatusIfRecoverySafe(reason: String): Boolean {
        if (hasBlockingSupplementPrefetchEvidence()) {
            logW(LOG_ORDER, "订单收尾保留双 lane 恢复证据和 moveStatus：reason=$reason")
            return false
        }
        val config = AppConfig.getAppConfig()
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
        return true
    }

    private fun restoreParallelSupplementBusyMoveStatus() {
        val config = AppConfig.getAppConfig()
        config.moveStatus = 1
        AppConfig.saveAppConfigDurable(config)
    }

    private suspend fun handleParallelSupplementResumePreparation(
        preparation: SupplementPrefetchResumePreparation,
        reason: String,
        lockAlreadyOwned: Boolean
    ): Boolean = when (preparation) {
        SupplementPrefetchResumePreparation.NoSession -> true
        is SupplementPrefetchResumePreparation.Unsafe -> {
            enterActionGroupMaintenance("双 lane 续作检查失败：reason=$reason，${preparation.reason}")
            false
        }
        is SupplementPrefetchResumePreparation.ReplanRemaining -> {
            runCatching {
                supplementBatchIntentRepository.markCompleted(
                    preparation.batchId,
                    preparation.targetPanNo
                )
                check(
                    releaseMechanicalMove(
                        "双 lane 当前根完成",
                        preparation.sessionId,
                        supplementPipelineClaimOwnerStore(),
                        expectedMoveStatus = 1,
                        idleMoveStatus = 0
                    )
                ) { "双 lane 当前根 owner 条件释放失败" }
                actionGroupLocks.release(preparation.sessionId)
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
        is SupplementPrefetchResumePreparation.Ready -> resumePreparedParallelSupplement(
            preparation.plan,
            reason,
            lockAlreadyOwned
        )
    }

    private suspend fun resumePreparedParallelSupplement(
        plan: SupplementPrefetchResumePlan,
        reason: String,
        lockAlreadyOwned: Boolean
    ): Boolean {
        if (!supplementResumeSingleFlight.tryEnter()) {
            logW(LOG_SUPPLEMENT, "双 lane 续作已有执行中的尝试，拒绝重复下发：sessionId=${plan.sessionId}，reason=$reason")
            return false
        }
        return try {
            resumePreparedParallelSupplementOnce(plan, reason, lockAlreadyOwned)
        } finally {
            supplementResumeSingleFlight.exit()
        }
    }

    private suspend fun resumePreparedParallelSupplementOnce(
        plan: SupplementPrefetchResumePlan,
        reason: String,
        lockAlreadyOwned: Boolean
    ): Boolean {
        val session = activeSupplementPrefetchSessionOrNull("双 lane 续作执行前") ?: return false
        val lane = session.deliveryLane
        if (session.sessionId != plan.sessionId || lane?.sourceBoxNo != plan.sourceBoxNo ||
            lane.targetPanNo != plan.targetPanNo || lane.lastConfirmedStage != plan.resumeAfterAction
        ) {
            enterActionGroupMaintenance("双 lane 续作计划与持久化会话不一致：plan=$plan")
            return false
        }
        val runtime = AppConfig.getAppConfig()
        if (runtime.onlineStatus != 1 || runtime.errorStatus != 0 || runtime.inspectionMode != 0 ||
            runtime.supplyStatus != 0 || runtime.isEnable != 1 || !VMModbusHelper.connectStatus()
        ) {
            logW(LOG_SUPPLEMENT, "双 lane 续作运行态门禁拒绝下发，保留会话和锁：reason=$reason")
            return false
        }
        if (!lockAlreadyOwned && !actionGroupLocks.tryAcquire(plan.resourceMask, plan.sessionId)) {
            return false
        }
        val pan = KaoPanHelper.getKaoPanList().firstOrNull { it.positionSn == plan.targetPanNo }
        val box = KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == plan.sourceBoxNo }
        if (pan == null || box == null) {
            enterActionGroupMaintenance("双 lane 续作找不到目标盘或来源箱：plan=$plan")
            return false
        }
        if (!claimMechanicalMove(
                stage = "双 lane 续作下发前",
                owner = plan.sessionId,
                ownerStore = supplementPipelineClaimOwnerStore(),
                allowedMoveStatuses = setOf(0, 1),
                claimedMoveStatus = 1,
                requireSupplementGate = true
            )
        ) {
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
        return finishParallelSupplementResume(plan, pan, box, reason)
    }

    private suspend fun finishParallelSupplementResume(
        plan: SupplementPrefetchResumePlan,
        pan: KaoPan,
        box: KaoPanBox,
        reason: String
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
                    lockAlreadyOwned = true
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
        if (!actionGroupLocks.tryAcquire(session.resourceMask, session.sessionId)) {
            releaseSupplementMoveClaimIfOwned(
                session.sessionId,
                currentOwner = { actionGroupSessionStore.load()?.sessionId },
                clearOwner = {
                    if (actionGroupSessionStore.load()?.sessionId != session.sessionId) false else {
                        actionGroupSessionStore.clear()
                        true
                    }
                }
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
        return "session=${session?.sessionId ?: "无"}，" +
            "pausedSupplement=${pausedSupplement?.sessionId ?: "无"}/${pausedSupplement?.resumeAfterAction ?: "无"}，" +
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
        val actionGroupIdle = isActionGroupIdleForMoveStatusRecovery()
        if (!MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(statusReadings, actionGroupIdle)) {
            logW(
                LOG_ACTION,
                "跳过本地机械状态自动收口：reason=$reason，" +
                    "moveStatus（机械动作状态）=${moveStatusDesc(config.moveStatus)}，" +
                    "status201连续读数=$statusReadings，actionGroupIdle=$actionGroupIdle，" +
                    "actionGroup=${actionGroupRuntimeSnapshotForLog()}"
            )
            return false
        }
        val oldMoveStatus = config.moveStatus
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
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
        discarded: Boolean
    ) {
        actionGroupOrderEventStore.save(
            PendingActionGroupOrderEvent(
                orderContext = orderContext,
                status = status,
                panNo = panNo,
                discarded = discarded
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
        source: String
    ): Boolean {
        if (!orderContext.isValid()) {
            logE(LOG_ORDER, "动作组订单终态上报失败：订单上下文不完整，source=$source，orderContext=$orderContext")
            return false
        }
        val ok = SendServerHelper.publishServiceUpdateOrder(
            ActionGroupOrderEventPayloadBuilder.build(orderContext, status)
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

    fun savePendingOrderErrorEventAndTryFlush(
        orderContext: ActionGroupOrderContext,
        panNo: Int,
        source: String
    ): Boolean {
        if (!orderContext.isValid()) {
            logE(LOG_ORDER, "出餐失败订单终态补偿保存失败：订单上下文不完整，source=$source，orderContext=$orderContext")
            return false
        }
        savePendingActionGroupOrderEvent(
            orderContext = orderContext,
            status = ActionGroupOrderStatus.ERROR,
            panNo = panNo,
            discarded = false
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
            source = "动作组恢复补偿:$reason"
        )
    }

    private fun persistOutTerminalEventFromTelemetry(
        telemetry: ActionGroupTelemetry,
        orderContext: ActionGroupOrderContext?,
        panNo: Int
    ) {
        if (telemetry.actionName != "out4" || orderContext == null || !orderContext.isValid()) {
            return
        }
        val status = when (telemetry.errorCode) {
            null -> ActionGroupOrderStatus.FINISHED
            AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED -> ActionGroupOrderStatus.ERROR
            else -> return
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
        val hasPendingSession = actionGroupSessionStore.load() != null || panMoveJournalStore.load() != null
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
        val panMoveJournal = runCatching { panMoveJournalStore.load() }
            .getOrElse { error ->
                enterActionGroupMaintenance("搬盘 journal 读取失败，禁止恢复：${error.message}")
                return false
        }
        if (doubleLaneSession != null) {
            val lockFailure = runCatching {
                actionGroupLocks.forceAcquire(doubleLaneSession.resourceMask, doubleLaneSession.sessionId)
            }.exceptionOrNull()
            if (lockFailure != null) {
                enterActionGroupMaintenance(
                    "双 lane 恢复资源锁冲突，保留恢复证据：session=${doubleLaneSession.sessionId}，" +
                        "error=${lockFailure.message}",
                    preserveMoveStatus = true
                )
                return false
            }
        }
        if (doubleLaneSession != null && panMoveJournal != null) {
            enterActionGroupMaintenance(
                "双 lane 会话与搬盘 journal 同时存在，无法安全判定机械归属，已保留二者：" +
                    "session=${doubleLaneSession.sessionId}，moveId=${panMoveJournal.moveId}"
            )
            return false
        }
        if (doubleLaneSession != null) {
            val dedicated = try {
                SupplementPrefetchRecoveryCoordinator(
                    client = createAtomicActionClient(),
                    repository = supplementPrefetchSessionRepository,
                    betweenSnapshots = { delay(200L) },
                    logger = { message -> logD(LOG_ACTION_GROUP, message) }
                ).recover()
            } catch (cancellation: CancellationException) {
                enterActionGroupMaintenance(
                    "双 lane 专用恢复被取消，保留 session、资源锁和 moveStatus：" +
                        "session=${doubleLaneSession.sessionId}",
                    preserveMoveStatus = true
                )
                throw cancellation
            }
            logI(
                LOG_ACTION_GROUP,
                "双 lane 会话存在，禁止进入通用动作组恢复：session=${doubleLaneSession.sessionId}，result=$dedicated"
            )
            val recovered = when (dedicated) {
                SupplementPrefetchRecoveryResult.Idle -> {
                    enterActionGroupMaintenance("双 lane 会话在专用恢复期间消失，拒绝进入通用恢复")
                    false
                }
                is SupplementPrefetchRecoveryResult.PausedForOrder -> {
                    val config = AppConfig.getAppConfig()
                    config.moveStatus = 0
                    AppConfig.saveAppConfigDurable(config)
                    actionGroupLocks.release(dedicated.session.sessionId)
                    markActionGroupRecoveryVerified("双 lane 订单暂停检查点已验证：$reason")
                    true
                }
                is SupplementPrefetchRecoveryResult.PreparedClaimIdle -> {
                    val abandoned = mechanicalMoveClaimCoordinator(supplementPipelineClaimOwnerStore())
                        .abandonPrepared(dedicated.session.sessionId, setOf(0, 1), 0)
                    if (abandoned is MechanicalMoveReleaseResult.Released) {
                        actionGroupLocks.release(dedicated.session.sessionId)
                        markActionGroupRecoveryVerified("双 lane PREPARED 会话已安全清理：$reason")
                        true
                    } else {
                        enterActionGroupMaintenance(
                            "双 lane PREPARED 会话 owner 清理失败，保持忙态：$abandoned"
                        )
                        false
                    }
                }
                is SupplementPrefetchRecoveryResult.ReadyToResume -> {
                    val preparation = SupplementPrefetchResumePlanner.prepareRecovered(
                        supplementPrefetchSessionRepository
                    )
                    handleParallelSupplementResumePreparation(
                        preparation,
                        "双 lane 启动恢复：$reason",
                        lockAlreadyOwned = true
                    )
                }
                is SupplementPrefetchRecoveryResult.MaintenanceRequired -> {
                    enterActionGroupMaintenance(dedicated.reason)
                    false
                }
            }
            if (stopAfterParallelSessionHandled) {
                logI(
                    LOG_ACTION_GROUP,
                    "本次调用已处理既有双 lane 会话，禁止继续使用恢复前目标快照：" +
                        "session=${doubleLaneSession.sessionId}，recovered=$recovered，reason=$reason"
                )
                return false
            }
            return recovered
        }
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
            allowKnownIsolatedTerminalFailure = ::isKnownIsolatedSupplementTerminalFailure,
            abandonPreparedClaim = { session ->
                mechanicalMoveClaimCoordinator(actionGroupClaimOwnerStore())
                    .abandonPrepared(session.sessionId, setOf(0, 1, 2), 0) is
                    MechanicalMoveReleaseResult.Released
            },
            moveStatusProvider = mechanicalMoveStatusStore::load
        ).recover()
        return when (result) {
            ActionGroupRecoveryResult.Idle -> {
                lastActionGroupRecoveryFailureReason = null
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
                flushPendingActionGroupOrderEvent(reason)
                markActionGroupRecoveryVerified(reason)
                true
            }
            is ActionGroupRecoveryResult.StaleSessionCleared -> {
                lastActionGroupRecoveryFailureReason = null
                val session = result.session
                val config = AppConfig.getAppConfig()
                config.moveStatus = 0
                AppConfig.saveAppConfig(config)
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
                val config = AppConfig.getAppConfig()
                config.moveStatus = 0
                AppConfig.saveAppConfigDurable(config)
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

    private fun isKnownIsolatedSupplementTerminalFailure(
        session: ActionGroupSession,
        actionName: String,
        errorCode: Int
    ): Boolean {
        if (session.type != ActionGroupSessionType.IN_SUPPLEMENT || session.currentAction != actionName) {
            return false
        }
        if (actionName !in setOf("in1", "in2", "in3", "in4", "in5") ||
            LowerPlatformIsolationPolicy.isUnsafeLiftTerminalError(errorCode)
        ) {
            return false
        }
        val config = AppConfig.getAppConfig()
        if (config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            return false
        }
        val formattedCode = AtomicActionExecutor.formatCode(errorCode)
        return config.lowerPlatformEpisodes.any { episode ->
            episode.lifecycle == "OPEN" &&
                episode.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED &&
                episode.actionName == actionName &&
                episode.errorCode.equals(formattedCode, ignoreCase = true) &&
                episode.sourceBoxNo == session.sourceNo &&
                episode.targetPanNo == session.targetNo &&
                (episode.batchId.isNullOrBlank() && session.batchId.isNullOrBlank()) ||
                    (!episode.batchId.isNullOrBlank() && episode.batchId == session.batchId)
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

    suspend fun confirmClearActionGroupRecoveryResidue(reason: String): Boolean {
        if (hasBlockingSupplementPrefetchEvidence()) {
            logW(LOG_ACTION_GROUP, "双 lane 会话存在，拒绝人工清理通用动作组残留：reason=$reason")
            return false
        }
        val evidence = buildActionGroupRecoveryResidueEvidence(reason)
        val decision = ActionGroupManualResidueClearPolicy.evaluate(evidence)
        if (!decision.canShowManualClear) {
            logW(LOG_ACTION_GROUP, "拒绝人工确认清理动作组残留：reason=$reason，拒绝原因=${decision.reason}，evidence=${evidence.summary()}")
            return false
        }
        actionGroupSessionStore.clear()
        pausedSupplementSessionStore.clear()
        actionGroupLocks.clear()
        lastActionGroupRecoveryFailureReason = null
        actionGroupRecoveryVerified = false
        val config = AppConfig.getAppConfig()
        if (config.moveStatus != 0) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfig(config)
            logW(LOG_ACTION_GROUP, "人工确认清理动作组残留后规整机械动作状态：moveStatus（机械动作状态）=$oldMoveStatus->0（空闲），reason=$reason")
        }
        logW(LOG_ACTION_GROUP, "人工确认清理动作组本地残留完成：reason=$reason，evidence=${evidence.summary()}")
        return true
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
        config.zone3Dirty = 0
        config.zone3CleanPending = 0
        config.zone3CleanSuppressedFor4F = 0
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
        val config = AppConfig.getAppConfig()
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

    private fun claimMechanicalMove(
        stage: String,
        owner: String,
        ownerStore: MechanicalClaimOwnerStore,
        allowedMoveStatuses: Set<Int>,
        claimedMoveStatus: Int,
        requireSupplementGate: Boolean
    ): Boolean {
        val coordinator = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        )
        val result = coordinator.claim(owner, allowedMoveStatuses, claimedMoveStatus) {
            if (!requireSupplementGate) {
                true
            } else {
                val config = AppConfig.getAppConfig()
                SupplementStartGate.evaluate(
                    onlineStatus = config.onlineStatus,
                    errorStatus = config.errorStatus,
                    isEnable = config.isEnable,
                    faultHeatPauseActive = config.faultHeatPauseActive
                ).canStart
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
    ): Boolean {
        val result = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        ).release(owner, expectedMoveStatus, idleMoveStatus)
        if (result !is MechanicalMoveReleaseResult.Released) {
            logW(LOG_ACTION_GROUP, "$stage 机械认领释放失败：owner=$owner，result=$result")
        }
        return result is MechanicalMoveReleaseResult.Released
    }

    private fun releaseSupplementMoveClaimIfOwned(
        owner: String,
        currentOwner: () -> String?,
        clearOwner: () -> Boolean
    ): Boolean {
        val ownerStore = object : MechanicalClaimOwnerStore {
            override fun snapshot(): MechanicalClaimOwnerSnapshot? = currentOwner()?.let {
                MechanicalClaimOwnerSnapshot(it, MechanicalClaimState.CLAIMED, 0L)
            }
            override fun savePrepared(owner: String) = error("释放路径禁止 prepare")
            override fun saveClaimed(owner: String) = error("释放路径禁止 claim")
            override fun clearIfOwner(owner: String): Boolean = clearOwner()
        }
        val result = MechanicalMoveClaimCoordinator(
            faultHeatTransitionLock,
            ownerStore,
            mechanicalMoveStatusStore
        ).release(owner, expectedMoveStatus = 1, idleMoveStatus = 0)
        if (result !is MechanicalMoveReleaseResult.Released) {
            logW(LOG_SUPPLEMENT, "补肠认领释放失败：owner=$owner，result=$result")
        }
        return result is MechanicalMoveReleaseResult.Released
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

    enum class PendingSupplementBatchHandling {
        NONE,
        WAITING_MANUAL_CONFIRMATION,
        BLOCKED,
        RESUMED
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
        return if (intent.requiresManualConfirm) intent else null
    }

    suspend fun confirmPendingSupplementBatchSafety(reason: String): ManualSupplementConfirmResult {
        val pending = pendingSupplementBatchRequiresManualConfirm()
            ?: return ManualSupplementConfirmResult(false, "当前没有待确认批次")
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
        supplementBatchIntentRepository.clear(reason)
    }

    fun clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(reason: String): Boolean {
        val intent = supplementBatchIntentRepository.loadActive()
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
        if (isActionGroupRecoveryBlocked()) {
            return null
        }
        return supplementBatchIntentRepository.loadActive()
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

    suspend fun handlePendingSupplementBatchIfAny(
        allowedTypes: Set<SupplementBatchType>,
        reason: String,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean,
        isBeforeCloseWindow: Boolean
    ): PendingSupplementBatchHandling {
        val intent = supplementBatchIntentRepository.loadActive() ?: return PendingSupplementBatchHandling.NONE
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
        if (intent.requiresManualConfirm) {
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次仍需人工确认，暂不自动续补：reason=$reason，" +
                    "batchId=${intent.batchId}，targetPan=${intent.failedTargetPanNo}，action=${intent.failedActionName}，" +
                    "failure=${intent.failureMessage}"
            )
            return PendingSupplementBatchHandling.WAITING_MANUAL_CONFIRMATION
        }
        if (!schedulingEnabled || !boxSupplyAvailable || AppConfig.getAppConfig().errorStatus != 0 || isBeforeCloseWindow) {
            if (isInitializationBatch(intent.type) && !boxSupplyAvailable) {
                supplementBatchIntentRepository.clear("${SupplementBatchIntentRepository.typeText(intent.type)}部分开盘收口：烤肠箱无可用库存:$reason")
                logW(
                    LOG_SUPPLEMENT,
                    "${SupplementBatchIntentRepository.typeText(intent.type)}存在未完成补肠批次，但烤肠箱无可用库存，按部分开盘收口，不再阻塞已上盘烤肠成熟检查：" +
                        "reason=$reason，batchId=${intent.batchId}，remaining=${intent.remainingTargets().map { it.positionSn }}"
                )
                return PendingSupplementBatchHandling.NONE
            }
            logW(
                LOG_SUPPLEMENT,
                "检测到未完成补肠批次但当前不允许续补：reason=$reason，batchId=${intent.batchId}，" +
                    "schedulingEnabled=$schedulingEnabled，boxSupplyAvailable=$boxSupplyAvailable，" +
                    "errorStatus=${AppConfig.getAppConfig().errorStatus}，isBeforeCloseWindow=$isBeforeCloseWindow"
            )
            return PendingSupplementBatchHandling.BLOCKED
        }
        val pans = KaoPanHelper.getKaoPanList()
        intent.remainingTargets().forEach { target ->
            if (pans.firstOrNull { it.positionSn == target.positionSn }?.isHasSausage == true) {
                supplementBatchIntentRepository.markCompleted(intent.batchId, target.positionSn)
            }
        }
        val refreshedIntent = supplementBatchIntentRepository.loadActive() ?: return PendingSupplementBatchHandling.NONE
        if (refreshedIntent.remainingTargets().isEmpty()) {
            logD(
                LOG_SUPPLEMENT,
                "补热批次已全部上盘，保留回搬绑定等待源盘搬回前区：reason=$reason，" +
                    "batchId=${refreshedIntent.batchId}，type=${SupplementBatchIntentRepository.typeText(refreshedIntent.type)}"
            )
            return PendingSupplementBatchHandling.NONE
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

    private suspend fun moveSausagesToKaoPansByTasteInternal(
        targets: List<SupplementTarget>,
        existingBatchId: String?,
        createIntent: Boolean,
        stageForSerial: String,
        batchTypeOverride: SupplementBatchType? = null
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
        val currentPipelineOwner = {
            if (parallelEnabled) supplementPrefetchSessionRepository.load()?.sessionId
            else actionGroupSessionStore.load()?.sessionId
        }
        val clearPipelineOwner = {
            if (parallelEnabled) {
                val current = supplementPrefetchSessionRepository.load()
                current?.takeIf { it.sessionId == sessionId }?.let {
                    supplementPrefetchSessionRepository.clearIfRevision(it.sessionId, it.revision)
                } == true
            } else if (actionGroupSessionStore.load()?.sessionId == sessionId) {
                actionGroupSessionStore.clear()
                true
            } else {
                false
            }
        }
        if (!claimPreparedSupplementMove(
                stage = "补肠预取流水线下发前",
                owner = sessionId,
                ownerStore = preparedOwnerStore
            )
        ) {
            return 0
        }
        publishRuntimeStateWithHttpFallback("补肠预取流水线开始")

        if (!isActionGroupEnabledOrEnterMaintenance("补肠预取流水线")) {
            releaseSupplementMoveClaimIfOwned(sessionId, currentPipelineOwner, clearPipelineOwner)
            publishRuntimeStateWithHttpFallback("补肠预取流水线启动前终止")
            return 0
        }

        if (!actionGroupLocks.tryAcquire(lockMask, sessionId)) {
            releaseSupplementMoveClaimIfOwned(sessionId, currentPipelineOwner, clearPipelineOwner)
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
                    !parallelEnabled || supplementPrefetchSessionRepository.tryCommitDelivery(
                        context.sourceBoxNo,
                        context.targetPanNo
                    )
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
            safeReplanReleaseConfirmed ||
            (parallelSessionForCleanup?.sessionId == sessionId &&
                (result is InActionGroupPipelineResult.Success || safeParallelSupplyDegrade))
        val retainParallelEvidence = SupplementPipelineFailureRetentionPolicy.shouldRetain(
            evidenceActive = hasBlockingSupplementPrefetchEvidence(),
            outcome = effectiveOutcome,
            safeReleaseConfirmed = safeReleaseConfirmed
        )
        if ((result is InActionGroupPipelineResult.Success ||
                (result is InActionGroupPipelineResult.ReplanRequired && safeReplanReleaseConfirmed) ||
                safeParallelSupplyDegrade) &&
            !durableCompletionFailed &&
            !retainParallelEvidence
        ) {
            val completionFailure = if (parallelEnabled) {
                runCatching {
                    check(
                        releaseMechanicalMove(
                            "补肠双 lane 流水线收口",
                            sessionId,
                            supplementPipelineClaimOwnerStore(),
                            expectedMoveStatus = 1,
                            idleMoveStatus = 0
                        )
                    ) { "补肠双 lane 流水线 owner 条件释放失败" }
                    actionGroupLocks.release(sessionId)
                    publishRuntimeStateWithHttpFallback("补肠双 lane 流水线收口")
                }.exceptionOrNull()
            } else {
                runCatching {
                    check(
                        releaseSupplementMoveClaimIfOwned(
                            owner = sessionId,
                            currentOwner = { actionGroupSessionStore.load()?.sessionId },
                            clearOwner = {
                                if (actionGroupSessionStore.load()?.sessionId != sessionId) false else {
                                    actionGroupSessionStore.clear()
                                    true
                                }
                            }
                        )
                    ) { "补肠预取流水线owner条件释放失败" }
                    actionGroupLocks.release(sessionId)
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
                val settledConfig = AppConfig.getAppConfig()
                settledConfig.moveStatus = 0
                AppConfig.saveAppConfigDurable(settledConfig)
                if (safePausedSession != null) {
                    actionGroupLocks.release(requireNotNull(safePausedSession).sessionId)
                } else {
                    actionGroupLocks.release(sessionId)
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
                logI(
                    LOG_SUPPLEMENT,
                    "补肠预取流水线暂停，剩余目标将重新规划：successCount=$completedCount，" +
                        "targetCount=${plannedMoves.size}，orderNo=${SupplementPauseController.currentOrderNo() ?: "未知"}"
                )
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
                    parallelSessionActive = parallelEnabled
                )
                logW(
                    LOG_ACTION_GROUP,
                    "补肠预取失败后保留session、资源锁和moveStatus，等待维护/启动恢复：" +
                        "action=${result.actionName}，targetPan=${result.task?.targetPanNo}"
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
        logD(
            LOG_SUPPLEMENT,
            "补肠完成：来源烤肠箱=${sourceBox.positionSn}，目标烤盘=${targetPan.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore->${sourceBox.num}，panStatus=${targetPan.status}，startTime=${targetPan.startTime}，" +
                "boxAlreadyDeducted=$boxAlreadyDeducted，panAlreadyPlaced=$panAlreadyPlaced"
        )
        LogUtils.i(
            "【补肠】烤盘${targetPan.positionSn} 补肠完成：来源烤肠箱=${sourceBox.positionSn}，" +
                "口味=$sausageName，烤盘开始烤制计时，箱库存=$boxStockBefore->${sourceBox.num}"
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

    private suspend fun handlePipelineSupplementFailure(
        result: InActionGroupPipelineResult.Failed,
        taskByPanNo: Map<Int, PlannedPipelineSupplementMove>,
        batchId: String?,
        timeoutProbe: TimeoutProbe? = null,
        parallelSessionActive: Boolean
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
            markLowerPlatformIsolatedRuntimeState(isolationReason, result.actionName, episodeDetails)
            logW(
                LOG_SUPPLEMENT,
                "补肠预取流水线下平台失败已隔离，不进入维护：" +
                    "targetPan=$targetPanNo，sourceBox=$sourceBoxNo，action=${result.actionName}，" +
                    "result=${describeAtomicActionResult(resolvedAtomicResult)}，batchType=${batchType?.let { SupplementBatchIntentRepository.typeText(it) } ?: "未知"}，" +
                    "requiresManualConfirm=$requiresManualConfirm，reason=$isolationReason"
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

        logD(
            LOG_SUPPLEMENT,
            "开始补肠：目标烤盘=${kaoPan.positionSn}，来源烤肠箱=${kaoPanBox.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore，targetPanHasSausage=${kaoPan.isHasSausage}"
        )
        if (!isActionGroupEnabledOrEnterMaintenance("补肠")) {
            releaseSupplementMoveClaimIfOwned(
                owner = owner,
                currentOwner = { actionGroupSessionStore.load()?.sessionId },
                clearOwner = {
                    if (actionGroupSessionStore.load()?.sessionId != owner) false else {
                        actionGroupSessionStore.clear()
                        true
                    }
                }
            )
            return SingleSupplementMoveResult(false)
        }

        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            releaseSupplementMoveClaimIfOwned(
                owner = owner,
                currentOwner = { actionGroupSessionStore.load()?.sessionId },
                clearOwner = {
                    if (actionGroupSessionStore.load()?.sessionId != owner) false else {
                        actionGroupSessionStore.clear()
                        true
                    }
                }
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
        val shouldRecordIrPickFailure = !moveSuccess && failureCode == AtomicActionCodes.IR_PICK_FAILED
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
            if (lowerPlatformIsolationReason != null) {
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
                            lowerPlatformIsolationDecision == LowerPlatformIsolationPolicy.Decision.IsolatedRequiresManualConfirm
                    )
                )
                logW(
                    LOG_SUPPLEMENT,
                    "补肠下平台动作失败，已隔离下平台并停止自动补肠，不切整机维护：" +
                        "targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}, reason=$lowerPlatformIsolationReason"
                )
            } else if (allowPickFailureFallback && atomicFailureCode(actionGroupResult) == AtomicActionCodes.IR_PICK_FAILED) {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠取肠失败由同口味切箱逻辑接管，暂不切维护：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}"
                )
            } else {
                markActionGroupFailure("补肠", actionGroupResult)
            }
        }

        val canClearFailedSession = actionGroupResult is ActionGroupResult.Failed &&
            failureActionName == "in2" &&
            failureCode == AtomicActionCodes.IR_PICK_FAILED &&
            allowPickFailureFallback
        if (actionGroupResult is ActionGroupResult.Failed && !canClearFailedSession) {
            logW(
                LOG_ACTION_GROUP,
                "补肠失败物理状态未完全确定，保留session、资源锁和moveStatus等待恢复：" +
                    "action=$failureActionName，code=${AtomicActionExecutor.formatCode(failureCode)}"
            )
            return SingleSupplementMoveResult(
                success = false,
                actionGroupResult = actionGroupResult,
                lowerPlatformIsolationReason = lowerPlatformIsolationReason,
                lowerPlatformIsolationRequiresManualConfirm = true
            )
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
                        currentOwner = { actionGroupSessionStore.load()?.sessionId },
                        clearOwner = {
                            if (actionGroupSessionStore.load()?.sessionId != owner) false else {
                                actionGroupSessionStore.clear()
                                true
                            }
                        }
                    )
                ) { "补肠成功后owner条件释放失败" }
                actionGroupLocks.release(owner)
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
            if (releaseSupplementMoveClaimIfOwned(
                    owner = owner,
                    currentOwner = { actionGroupSessionStore.load()?.sessionId },
                    clearOwner = {
                        if (actionGroupSessionStore.load()?.sessionId != owner) false else {
                            actionGroupSessionStore.clear()
                            true
                        }
                    }
                )
            ) {
                actionGroupLocks.release(owner)
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
        if (panMoveJournalStore.load() != null) {
            enterActionGroupMaintenance(
                "存在未收口搬盘 journal，拒绝启动新搬盘：source=$sourcePosition，target=$targetPosition"
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
                lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName
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
        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            logW(
                LOG_PAN_MOVE,
                "烤盘搬移资源忙，跳过本轮搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，" +
                    "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return false
        }

        try {
            var appState = AppConfig.getAppConfig().moveStatus
            while (appState != 0) {
                delay(PanMove203Policy.waitOtherActionDelayMs)
                appState = AppConfig.getAppConfig().moveStatus
                logD(LOG_PAN_MOVE, "烤盘搬移前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
            }
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
            if (!panMoveJournalStore.saveDurable(journal)) {
                enterActionGroupMaintenance("搬盘 PREPARED journal 落盘失败：$sourcePosition->$targetPosition")
                return false
            }
            AppConfig.getAppConfig().moveStatus = 3
            try {
                AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
            } catch (e: Exception) {
                enterActionGroupMaintenance("搬盘 moveStatus=3 落盘失败：$sourcePosition->$targetPosition，${e.message}")
                return false
            }
            LogUtils.i(
                "【搬盘】开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=$operationReason"
            )
            logD(
                LOG_PAN_MOVE,
                "开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，口味=${getPanTasteLabel(kaoPan)}，" +
                    "targetHoldingTime=$targetHoldingTime"
            )

            if (!ensureModbusReady("烤盘${sourcePosition}到烤盘${targetPosition}搬移")) {
                if (panMoveJournalStore.clearDurable()) {
                    AppConfig.getAppConfig().moveStatus = 0
                    AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
                }
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=下位机通信断开")
                markDeviceErrorAndUpload(FaultCode.MODBUS_COMM_DROP,
                    "烤盘${sourcePosition}到烤盘${targetPosition}搬移前下位机通信断开，设备进入维护中")
                return false
            }

            val value = (sourcePosition shl 8) or targetPosition
            journal = journal.copy(checkpoint = PanMoveCheckpoint.COMMAND_INTENT)
            if (!panMoveJournalStore.saveDurable(journal)) {
                enterActionGroupMaintenance("搬盘 COMMAND_INTENT journal 落盘失败：$sourcePosition->$targetPosition")
                return false
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
                enterActionGroupMaintenance("搬盘 202 已下发但 COMMAND_ISSUED journal 落盘失败：$sourcePosition->$targetPosition")
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
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfigDurable(AppConfig.getAppConfig())
                check(panMoveJournalStore.clearDurable()) { "搬盘 journal 清理落盘失败" }
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
    suspend fun takeSausage(kaoPan: KaoPan) :Boolean {
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

        var appState = AppConfig.getAppConfig().moveStatus
        while (appState == 1 || appState == 3 || appState == 4 || appState == 5) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "维护手动取肠前等待机械动作收尾：moveStatus=$appState")
        }
        if (appState != 0 && appState != 2) {
            logW(
                LOG_ACTION,
                "维护手动取肠被非空闲机械状态拦截：moveStatus（机械动作状态）=${moveStatusDesc(appState)}"
            )
            return false
        }

        val acquiredByThisCall = appState == 0

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
                allowedMoveStatuses = setOf(appState),
                claimedMoveStatus = 2,
                requireSupplementGate = false
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
                    idleMoveStatus = if (acquiredByThisCall) 0 else 2
                )
                return false
            }

            if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
                releaseMechanicalMove(
                    "维护手动取肠资源忙",
                    owner,
                    actionGroupClaimOwnerStore(),
                    expectedMoveStatus = 2,
                    idleMoveStatus = if (acquiredByThisCall) 0 else 2
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
                            idleMoveStatus = if (acquiredByThisCall) 0 else 2
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
            if (acquiredByThisCall && !hasUnsettledSession && AppConfig.getAppConfig().moveStatus != 0) {
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
            return TakeSausageResult.ERROR
        }
        if (!ensureActionGroupReadyForOrderDispatch("正式出餐入口前恢复检查")) {
            return TakeSausageResult.ERROR
        }
        var appState = AppConfig.getAppConfig().moveStatus
        var waitedMs = 0L
        var maxWaitMs = sellPriorityWaitTimeoutMs(appState)
        while (appState == 1 || appState == 3 || appState == 4 || appState == 5) {
            if (appState == 5 || maxWaitMs <= 0L || waitedMs >= maxWaitMs) {
                val reason = "正式出餐入口等待当前机械动作安全收口超时或不可抢占：" +
                    "moveStatus=$appState，waitedMs=$waitedMs/$maxWaitMs"
                enterActionGroupMaintenance(reason, FaultCode.ACTION_BUSY_TIMEOUT)
                return TakeSausageResult.ERROR
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
                        resourceMask = lockMask
                    )
                ),
                allowedMoveStatuses = setOf(appState),
                claimedMoveStatus = 2,
                requireSupplementGate = false
            )
        ) {
            return TakeSausageResult.ERROR
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
                return TakeSausageResult.ERROR
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
                return TakeSausageResult.ERROR
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
                    markActionGroupFailure("正式出餐", actionGroupResult)
                    TakeSausageResult.ERROR
                }
            }

            if (takeResult == TakeSausageResult.ERROR) {
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
            SelfCleanManager.recordUsedPanForCurrentOrder(kaoPan.positionSn)
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
        detail: String? = null
    ) :Boolean {

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "丢弃烤肠等待售卖完成：state=$appState")
        }
        // 机械臂状态改为搬运
        AppConfig.getAppConfig().moveStatus = 4
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val tasteLabel = getPanTasteLabel(kaoPan)
        val sourceDetail = detail?.takeIf { it.isNotBlank() } ?: "未补充详细原因"
        LogUtils.i(
            "【丢弃流程】开始丢弃：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail"
        )

        val result = executeActionWithRetry(
            actionName = "烤盘${kaoPan.positionSn}${source}丢弃",
            maxBusyRetry = 0,
            runningPollDelayMs = 2000L,
            runningTimeoutMs = 120_000L,
            requireRunningBeforeIdleSuccess = true
        ) {
            writeSingleRegister2(action_address,kaoPan.cmdValueDiscard)
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
            SendServerHelper.publishConsumableEvent(
                ConsumableEventPayloadBuilder.nextEvent(
                    consumableType = "WASTE",
                    eventType = "WASTE_DISCARD_EXPIRED",
                    deltaValue = 1L,
                    source = "discardSausage_success",
                    detail = "source=$source, pan=${kaoPan.positionSn}, taste=$tasteLabel"
                )
            )
            handleSuccessfulDiscardDemandGate(kaoPan.positionSn, source)
            FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(kaoPan.positionSn)
            LogUtils.i(
                "【丢弃流程】丢弃完成：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail，" +
                    "panHasSausage=${kaoPan.isHasSausage}，panStatus=${kaoPan.status}"
            )
            KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
            clearReturnBindingForSourceIfAny(kaoPan.positionSn)
            refreshTemperatureAfterStablePanMutation("过保丢弃完成清空烤盘：pan=${kaoPan.positionSn}")
            PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = kaoPan,
                reason = "discardSausage_success:pan=${kaoPan.positionSn}:source=$source",
                source = "discardSausage_success"
            )
        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
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
                !isActionGroupRecoveryBlocked()
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
        if (!result.changedModeState && !result.blockedFrontPositionsChanged) {
            return
        }
        if (config.transitionMode == 2) {
            clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation("过保丢弃进入高转低/残留消化，停止25~33未完成补肠意图")
        }
        AppConfig.saveAppConfig(config)
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
    fun pauseBusiness() {
        //1，2，3 区停止保温
        writeAreaTargetTemperatureRaw(1, 0, "pauseBusiness")
        writeAreaTargetTemperatureRaw(2, 0, "pauseBusiness")
        writeAreaTargetTemperatureRaw(3, 0, "pauseBusiness")

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
        if (config.errorStatus != 0) {
            config.onlineStatus = 3
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            HomeUiRefreshBridge.requestRefresh("business_end:fault")
            logW(
                LOG_DEVICE,
                "营业时间结束：检测到设备故障标记，保持维护中 | errorStatus=${config.errorStatus}(0=正常, 非0=故障), " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}"
            )
            SendServerHelper.publishServiceUpdateStatus()
            return
        }
        if (config.onlineStatus != 1) {
            logD(
                LOG_DEVICE,
                "营业时间结束：当前状态非运营中，跳过自动切换休息中（尊重后台设置） | " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}"
            )
            return
        }
        // 当前是运营中，自动切换为休息中
        config.apply {
            onlineStatus = 2
            restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        }
        AppConfig.saveAppConfig(config)
        HomeUiRefreshBridge.requestRefresh("business_end:operating_to_rest")
        logD(
            LOG_DEVICE,
            "营业时间结束：已自动切换为休息中（2），" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
        )
        //同步更新云端状态
        SendServerHelper.publishServiceUpdateStatus()
        //执行丢弃操作
        try {
            val businessCloseResidualCount = KaoPanHelper.getKaoPanList().count { it.isHasSausage }
            KaoChangAlgorithm.resetKaoPan()
            val selfCleanResult = runPanSelfCleanSequence(
                positionSnList = (1..33).toList(),
                includeSellPlatform = true,
                source = "营业结束全量自清洁"
            )
            if (!selfCleanResult.success) {
                val failedReport = selfCleanResult.reports.lastOrNull()
                if (selfCleanResult.reports.any { FourFSelfCleanNoOpPolicy.isNoOp(it.message) }) {
                    config.zone3CleanPending = 0
                    if (config.zone3Dirty == 1) {
                        config.zone3CleanSuppressedFor4F = 1
                    }
                    AppConfig.saveAppConfig(config)
                    logI(
                        LOG_SELF_CLEAN,
                        "营业结束全量自清洁未执行：4F 当前为 no-op，不会真实机械清洁；" +
                            "onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}，" +
                            "zone3Dirty=${config.zone3Dirty}，zone3CleanPending=${config.zone3CleanPending}，" +
                            "zone3CleanSuppressedFor4F=${config.zone3CleanSuppressedFor4F}"
                    )
                    return
                }
                logE(
                    LOG_SELF_CLEAN,
                    "营业结束全量自清洁失败：target=${failedReport?.targetLabel ?: "unknown"}，" +
                        "command=${failedReport?.command ?: -1}，message=${failedReport?.message ?: "unknown"}"
                )
                return
            }
            config.zone3Dirty = 0
            config.zone3CleanPending = 0
            config.zone3CleanSuppressedFor4F = 0
            AppConfig.saveAppConfig(config)
            if (ConsumableEventPayloadBuilder.shouldReportBusinessCloseWaste(businessCloseResidualCount, selfCleanResult.success)) {
                SendServerHelper.publishConsumableEvent(
                    ConsumableEventPayloadBuilder.nextEvent(
                        consumableType = "WASTE",
                        eventType = "WASTE_DISCARD_BUSINESS_CLOSE",
                        deltaValue = businessCloseResidualCount.toLong(),
                        source = "business_close_self_clean_success",
                        detail = "residualCount=$businessCloseResidualCount"
                    )
                )
            }
            logI(
                LOG_SELF_CLEAN,
                "营业结束全量自清洁完成：onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}"
            )
        } catch (e: Exception) {
            // 营业结束时若重置烤盘失败，不应抛未实现异常导致崩溃；
            // 这里降级为维护中并上报，等待人工排查。
            config.errorStatus = 2
            config.onlineStatus = 3
            config.errorCode = FaultCode.KAOPAN_RESET_FAILED.name
            config.errorCategory = FaultCode.KAOPAN_RESET_FAILED.category.display
            config.errorReason = "营业结束重置烤盘失败：${e.javaClass.simpleName}：${e.message}"
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
            HomeUiRefreshBridge.requestRefresh("business_end:reset_failed")
            logE(
                LOG_DEVICE,
                "营业时间结束：重置烤盘失败，已降级为维护中并上报 | " +
                    "onlineStatus=${onlineStatusDesc(config.onlineStatus)}, " +
                    "errorStatus=${config.errorStatus}(2=设备故障), " +
                    "exception=${e.javaClass.simpleName}, message=${e.message}",
                e
            )
            SendServerHelper.publishServiceUpdateStatus()
        }
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
