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
import cn.niuyannet.kaochang.android.services.actiongroup.OutActionGroupRunner
import cn.niuyannet.kaochang.android.services.actiongroup.PrefetchSupplementPlanner
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceLocks
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.PendingActionGroupOrderEvent
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupOrderEventStore
import cn.niuyannet.kaochang.android.services.actiongroup.SharedPreferenceActionGroupSessionStore
import cn.niuyannet.kaochang.android.services.actiongroup.TakeSausageVoiceReminderPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
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
    private val actionGroupOrderEventStore = SharedPreferenceActionGroupOrderEventStore()
    private val supplementBatchIntentRepository =
        SupplementBatchIntentRepository(SharedPreferenceSupplementBatchIntentStore())

    @Volatile
    private var lastActionGroupRecoveryFailureReason: String? = null

    @Volatile
    private var actionGroupRecoveryVerified: Boolean = false

    @Volatile
    private var supplementBlockedUntilMs: Long = 0L

    fun isActionGroupBusyForOrderGate(): Boolean {
        return !actionGroupLocks.isIdle()
    }

    fun isActionGroupIdleForMoveStatusRecovery(): Boolean {
        return actionGroupSessionStore.load() == null && actionGroupLocks.isIdle()
    }

    fun actionGroupRuntimeSnapshotForLog(): String {
        val session = actionGroupSessionStore.load()
        return "session=${session?.sessionId ?: "无"}，resourceLocks=${actionGroupLocks.snapshot()}"
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

    private fun enterActionGroupMaintenance(reason: String, code: FaultCode = FaultCode.ACTION_GROUP_FAILED) {
        markDeviceErrorAndUpload(code, reason)
        val config = AppConfig.getAppConfig()
        config.moveStatus = 0
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
        val hasPendingSession = actionGroupSessionStore.load() != null
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

    suspend fun recoverActionGroupStateAfterModbusReady(reason: String): Boolean {
        if (!VMModbusHelper.connectStatus()) {
            val evidence = buildActionGroupRecoveryResidueEvidence(reason)
            val detail = "动作组恢复早退：Modbus 未连接，reason=$reason，evidence=${evidence.summary()}"
            lastActionGroupRecoveryFailureReason = detail
            invalidateActionGroupRecoveryVerification(detail)
            enterActionGroupMaintenance(detail, FaultCode.MODBUS_COMM_DROP)
            return false
        }
        val client = createAtomicActionClient()
        val result = ActionGroupRecoveryBootstrap(
            client = client,
            sessionStore = actionGroupSessionStore,
            resourceLocks = actionGroupLocks,
            logger = { message -> logD(LOG_ACTION_GROUP, message) }
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
        val evidence = buildActionGroupRecoveryResidueEvidence(reason)
        val decision = ActionGroupManualResidueClearPolicy.evaluate(evidence)
        if (!decision.canShowManualClear) {
            logW(LOG_ACTION_GROUP, "拒绝人工确认清理动作组残留：reason=$reason，拒绝原因=${decision.reason}，evidence=${evidence.summary()}")
            return false
        }
        actionGroupSessionStore.clear()
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
                        box.cmdStatusTake = 0
                        reportStock = false
                        reportPan = false
                    }
                    RecoveredSupplementStatePolicy.Decision.CORRECT_BOX_ONLY -> {
                        box.num = session.expectedStockAfter!!
                        pan.cmdStatusMove = 0
                        box.cmdStatusTake = 0
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
                KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
                KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
                session.batchId?.takeIf { it.isNotBlank() }?.let { recoveredBatchId ->
                    supplementBatchIntentRepository.markCompleted(recoveredBatchId, pan.positionSn)
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
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
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
            LowToHighDemandGate.clearPosition(pan.positionSn, "维护手动取肠动作组恢复完成")
        }
        KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
        reportOutCompletionPhysicalDelta(
            kaoPan = pan,
            reason = "action_recovery:out:completed:pan=${pan.positionSn}:manual=$isManualTake",
            source = "action_recovery_out"
        )
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        val actionText = if (isManualTake) "维护手动取肠" else "出餐"
        val resultText = if (discarded) {
            "历史丢弃结果已按 4F 履约完成收口"
        } else {
            "out4 已收口，本单已处理完成"
        }
        logW(LOG_ACTION_GROUP, "$actionText 动作组恢复完成并已清空烤盘：$resultText，reason=$reason，session=$session")
        actionGroupSessionStore.clear()
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
                val markResult = SupplementPickFailurePolicy.recordIrPickFailedBox(box, candidates)
                KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
                reportIrPickFailedStocksDelta(
                    failedBoxes = listOf(box),
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
        logW(LOG_SUPPLEMENT, "同口味候选箱全部取肠失败，按供给降级跳过：$degradeReason，boxCount=${candidates.size}")
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
                requiresManualConfirm = false
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
        val config = AppConfig.getAppConfig()
        val decision = SupplementStartGate.evaluate(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            isEnable = config.isEnable
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

    enum class PendingSupplementBatchHandling {
        NONE,
        WAITING_MANUAL_CONFIRMATION,
        BLOCKED,
        RESUMED
    }

    fun pendingSupplementBatchRequiresManualConfirm(): SupplementBatchIntent? {
        val intent = supplementBatchIntentRepository.loadActive() ?: return null
        return if (intent.requiresManualConfirm) intent else null
    }

    fun confirmPendingSupplementBatchSafety(reason: String): Boolean {
        val confirmed = supplementBatchIntentRepository.confirmManualSafety(reason) ?: return false
        clearFaultTimeoutCancelledReturnReservations(confirmed, reason)
        actionGroupSessionStore.clear()
        actionGroupLocks.clear()
        val config = AppConfig.getAppConfig()
        if (config.moveStatus != 0) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfig(config)
            logW(
                LOG_SUPPLEMENT,
                "人工确认补肠批次现场安全后清理本地机械状态：moveStatus=$oldMoveStatus->0，batchId=${confirmed.batchId}"
            )
        }
        return true
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
        KaoPanHelper.saveKaoPanListSync(pans)
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
                types.any { type ->
                    intent.type == type ||
                        SupplementBatchIntentRepository.positionMatchesType(target.positionSn, type)
                }
            }
            .map { it.positionSn }
            .filter { it in 1..33 }
            .toSet()
    }

    fun activeSupplementBatchIntentForUi(): SupplementBatchIntent? {
        return supplementBatchIntentRepository.loadActive()
    }

    fun clearCompletedHeatReturnBindingIfSourcesCleared(
        types: Set<SupplementBatchType>,
        sourcePositionsWithSausage: Set<Int>,
        reason: String
    ): Boolean {
        return supplementBatchIntentRepository.clearCompletedHeatReturnIfSourceCleared(
            types = types,
            sourcePositionsWithSausage = sourcePositionsWithSausage,
            reason = reason
        )
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
        val targets = intent.remainingTargets().mapNotNull { target ->
            val pan = pans.firstOrNull { it.positionSn == target.positionSn }
            if (pan == null) {
                logW(LOG_SUPPLEMENT, "未完成补肠批次找不到目标烤盘，跳过：batchId=${intent.batchId}，targetPan=${target.positionSn}")
                null
            } else if (pan.isHasSausage) {
                supplementBatchIntentRepository.markCompleted(intent.batchId, target.positionSn)
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

        applyHeatingForSupplementBatch(intent.type)
        LogUtils.i(
                "【补肠批次恢复】检测到未完成补肠批次，人工确认后续补：reason=$reason，" +
                "batchId=${intent.batchId}，type=${SupplementBatchIntentRepository.typeText(intent.type)}，" +
                "目标烤盘=${executableTargets.joinToString(",") { it.kaoPan.positionSn.toString() }}，" +
                "跳过不可执行目标=${availability.skippedTargets.map { it.targetPositionSn }}，" +
                "skippedDetails=${availability.describeSkippedTargets()}"
        )
        val movedCount = moveSausagesToKaoPansByTasteInternal(
            targets = executableTargets,
            existingBatchId = intent.batchId,
            createIntent = false,
            stageForSerial = "补肠批次恢复"
        )
        LogUtils.i(
            "【补肠批次恢复】未完成补肠批次续补结束：batchId=${intent.batchId}，目标数=${targets.size}，完成数=$movedCount"
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
                    supplementBatchIntentRepository.markSkippedBySupplyDegrade(
                        batchId = batchId,
                        targetPanNo = target.kaoPan.positionSn,
                        reason = result.reason
                    )
                    logW(
                        LOG_SUPPLEMENT,
                        "$stage 供给降级跳过当前目标并结束本轮旧计划：targetPan=${target.kaoPan.positionSn}，" +
                            "failedBoxes=${result.failedBoxNos}，reason=${result.reason}"
                    )
                    break
                }
                is SupplementMoveResult.LowerPlatformIsolated -> {
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

    suspend fun moveSausagesToKaoPansByTaste(
        targets: List<SupplementTarget>,
        batchTypeOverride: SupplementBatchType? = null
    ): Int {
        return moveSausagesToKaoPansByTasteInternal(
            targets = targets,
            existingBatchId = null,
            createIntent = targets.size > 1,
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
        if (!recoverActionGroupStateAfterModbusReady("补肠预取流水线入口前恢复检查")) {
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
        if (!ensureSupplementBatchStartAllowed("补肠预取流水线下发前")) {
            return 0
        }
        AppConfig.getAppConfig().moveStatus = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        publishRuntimeStateWithHttpFallback("补肠预取流水线开始")

        if (!isActionGroupEnabledOrEnterMaintenance("补肠预取流水线")) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            publishRuntimeStateWithHttpFallback("补肠预取流水线启动前终止")
            return 0
        }

        val owner = "in_pipeline:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            publishRuntimeStateWithHttpFallback("补肠预取流水线资源忙终止")
            logW(
                LOG_ACTION_GROUP,
                "补肠预取流水线资源忙，拒绝下发：need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return 0
        }

        val taskByPanNo = plannedMoves.associateBy { it.task.targetPanNo }
        var completedCount = 0
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
            supplementBatchIntentRepository.markCompleted(batchId, completedTask.targetPanNo)
            KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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
        var latestCurrentAction: InActionGroupPipelineActionContext? = null
        var latestRiskAction: InActionGroupPipelineActionContext? = null
        val result = try {
            val firstMove = plannedMoves.first()
            val firstBox = KaoPanHelper.getKaoPanBoxList()
                .firstOrNull { it.positionSn == firstMove.task.sourceBoxCandidates.first() }
            actionGroupSessionStore.save(
                ActionGroupSession(
                    sessionId = owner,
                    type = ActionGroupSessionType.IN_SUPPLEMENT,
                    sourceNo = firstMove.task.sourceBoxCandidates.first(),
                    targetNo = firstMove.task.targetPanNo,
                    currentAction = "in_pipeline",
                    resourceMask = lockMask,
                    batchId = batchId,
                    taste = firstMove.tasteCode,
                    returnPosition = firstMove.returnPosition,
                    prefetch = true,
                    checkpointVersion = 1L,
                    stockBefore = firstBox?.num,
                    expectedStockAfter = firstBox?.num?.let { (it - 1).coerceAtLeast(0) }
                )
            )
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
            InActionGroupPrefetchPipelineRunner(
                client = createAtomicActionClient(),
                actionClientProvider = { context ->
                    createAtomicActionClient(
                        ActionGroupTelemetryContext(
                            sessionId = owner,
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
                        val applied = applySupplementPhysicalTelemetry(
                            telemetry = telemetry,
                            plannedPan = move.kaoPan,
                            plannedBox = box,
                            source = "补肠预取流水线",
                            boxAlreadyDeducted = context.targetPanNo in physicallyDeductedTargetPanNos,
                            panAlreadyPlaced = context.targetPanNo in physicallyPlacedTargetPanNos
                        )
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
                                sessionId = owner,
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
                    actionGroupSessionStore.save(
                        ActionGroupSession(
                            sessionId = owner,
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
                            panPlacementApplied = sameMove && previousSession?.panPlacementApplied == true
                        )
                    )
                    logD(
                        LOG_ACTION_GROUP,
                        "补肠预取流水线当前动作：targetPan=${context.targetPanNo}，" +
                            "sourceBox=${context.sourceBoxNo}，action=${context.actionName}"
                    )
                },
                pauseReasonAfterCurrentTask = {
                    when {
                        SupplementPauseController.shouldPauseForOrder() -> "检测到订单待出餐"
                        !ensureSupplementBatchStartAllowed("补肠预取流水线启动下一根预取前") ->
                            "动作组故障锁存门禁拒绝启动下一根预取"
                        else -> null
                    }
                }
            )
                .run(plannedMoves.map { it.task })
                .also {
                    pipelineResultForCleanup = it
                    actionGroupCompleted = true
                }
        } catch (e: CancellationException) {
            markSupplementPipelineCancellationRisk(
                batchId = batchId,
                currentAction = latestCurrentAction,
                riskAction = latestRiskAction,
                throwable = e
            )
            throw e
        } finally {
            val retainFailureEvidence = !actionGroupCompleted ||
                pipelineResultForCleanup is InActionGroupPipelineResult.Failed
            if (!retainFailureEvidence) {
                actionGroupSessionStore.clear()
                actionGroupLocks.release(owner)
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                publishRuntimeStateWithHttpFallback("补肠预取流水线收口")
                applyPendingActionGroupDisableIfIdle("补肠预取流水线会话收口")
            }
            KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
            logD(LOG_SUPPLEMENT, "补肠预取流水线收口状态保存完成")
        }

        val completed = when (result) {
            is InActionGroupPipelineResult.Success -> result.completed
            is InActionGroupPipelineResult.Paused -> result.completed
            is InActionGroupPipelineResult.Failed -> result.completed
        }
        completed.forEach { completedTask ->
            applyCompletedPipelineTask(completedTask)
        }

        when (result) {
            is InActionGroupPipelineResult.Success -> {
                logD(LOG_SUPPLEMENT, "补肠预取流水线结束：successCount=$completedCount，targetCount=${plannedMoves.size}")
            }
            is InActionGroupPipelineResult.Paused -> {
                logI(
                    LOG_SUPPLEMENT,
                    "补肠预取流水线暂停，剩余目标将重新规划：successCount=$completedCount，" +
                        "targetCount=${plannedMoves.size}，orderNo=${SupplementPauseController.currentOrderNo() ?: "未知"}"
                )
            }
            is InActionGroupPipelineResult.Failed -> {
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
                handlePipelineSupplementFailure(result, taskByPanNo, batchId, timeoutResolution)
                actionGroupSessionStore.clear()
                actionGroupLocks.release(owner)
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                publishRuntimeStateWithHttpFallback("补肠预取流水线失败收口")
                applyPendingActionGroupDisableIfIdle("补肠预取流水线失败收口")
            }
        }
        verifySupplementConsistencyOrEnterMaintenance(
            source = "补肠预取流水线",
            expectedSuccessCount = completedCount,
            records = supplementWriteRecords
        )
        KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
        KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())

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

    private fun applySupplementPhysicalTelemetry(
        telemetry: ActionGroupTelemetry,
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        source: String,
        boxAlreadyDeducted: Boolean,
        panAlreadyPlaced: Boolean
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
            KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
            logD(
                LOG_SUPPLEMENT,
                "$source 动作级物理状态已回写：in2 已从肠箱取肠并扣库存，sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
            )
            LogUtils.i(
                "【补肠】烤盘${telemetry.panNo} in2 完成：已从烤肠箱${telemetry.boxNo}取肠并扣减库存，" +
                    "箱库存=$boxStockBefore->${plannedBox.num}"
            )
            PanPhysicalStateDeltaReporter.reportStockChanged(
                changedStock = plannedBox,
                reason = "action_physical:in2:picked_from_box:box=${telemetry.boxNo}:pan=${telemetry.panNo}",
                source = "action_physical_in2"
            )
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
            val applied = SupplementPanStateWriter.applySupplementPlacedOnPan(
                plannedPan = plannedPan,
                plannedBox = plannedBox,
                currentPans = KaoPanHelper.getKaoPanList(),
                currentBoxes = KaoPanHelper.getKaoPanBoxList(),
                nowMs = System.currentTimeMillis(),
                clearPendingDiscard = true,
                config = AppConfig.getAppConfig(),
                tasteBuilder = KaoPanHelper::buildTasteFromBox
            )
            if (!applied) {
                enterActionGroupMaintenance(
                    "$source in7 已成功但本地找不到当前盘箱缓存，烤盘状态不可确认，需人工确认：" +
                        "sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
                )
                return AppliedSupplementPhysicalTelemetry()
            }
            KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
            logD(
                LOG_SUPPLEMENT,
                "$source 动作级物理状态已回写：in7 已夹肠落盘并开始烤制，sourceBox=${telemetry.boxNo}，targetPan=${telemetry.panNo}"
            )
            LogUtils.i("【补肠】烤盘${telemetry.panNo} in7 完成：烤肠已落入烤盘并开始烤制计时")
            PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = plannedPan,
                reason = "action_physical:in7:placed_on_pan:box=${telemetry.boxNo}:pan=${telemetry.panNo}",
                source = "action_physical_in7"
            )
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
            sourceBox.cmdStatusTake = 0
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

    private fun reportIrPickFailedStocksDelta(failedBoxes: List<KaoPanBox>, reason: String, source: String) {
        val distinctBoxes = failedBoxes.distinctBy { it.positionSn }
        if (distinctBoxes.isEmpty()) {
            return
        }
        PanPhysicalStateDeltaReporter.reportStocksChanged(
            changedStocks = distinctBoxes,
            reason = "$reason:boxes=${distinctBoxes.joinToString(",") { it.positionSn.toString() }}",
            source = source
        )
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
        KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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
        val markResult = SupplementPickFailurePolicy.recordIrPickFailedBox(failedBox, boxList)
        KaoPanHelper.saveKaoPanBoxListSync(boxList)
        reportIrPickFailedStocksDelta(
            failedBoxes = listOf(failedBox),
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

    private suspend fun handlePipelineSupplementFailure(
        result: InActionGroupPipelineResult.Failed,
        taskByPanNo: Map<Int, PlannedPipelineSupplementMove>,
        batchId: String?,
        timeoutProbe: TimeoutProbe? = null
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
            hasPrefetchedNext = result.hasPrefetchedNext
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
                    SupplementPickFailurePolicy.recordIrPickFailedBox(failedBox, boxList)
                }
                KaoPanHelper.saveKaoPanBoxListSync(boxList)
                reportIrPickFailedStocksDelta(
                    failedBoxes = failedBoxes,
                    reason = "action_failure:pipeline_supply_degrade:targetPan=$targetPanNo:action=${result.actionName}",
                    source = "action_failure_pipeline_supply_degrade"
                )
            }
            val degradeReason = SupplementPickFailurePolicy.buildSupplyDegradedReason(
                targetPanNo = targetPanNo,
                tasteCode = plannedMove?.tasteCode,
                failedBoxNos = failedBoxNos,
                actionName = result.actionName
            )
            targetPanNo?.let {
                supplementBatchIntentRepository.markSkippedBySupplyDegrade(
                    batchId = batchId,
                    targetPanNo = it,
                    reason = degradeReason
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
                    requiresManualConfirm = false
                )
            )
            logW(
                LOG_SUPPLEMENT,
                "补肠预取流水线供给降级跳过当前目标，不进入维护、不要求人工确认：" +
                    "targetPan=$targetPanNo，failedBoxes=$failedBoxNos，reason=$degradeReason"
            )
            return
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
                    SupplementPickFailurePolicy.recordIrPickFailedBox(failedBox, boxList)
                }
                KaoPanHelper.saveKaoPanBoxListSync(boxList)
                reportIrPickFailedStocksDelta(
                    failedBoxes = failedBoxes,
                    reason = "action_failure:pipeline_ir_pick_maintenance:targetPan=$targetPanNo:action=${result.actionName}",
                    source = "action_failure_pipeline_ir_pick_maintenance"
                )
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
        if (!ensureSupplementBatchStartAllowed("补肠单根动作组下发前")) {
            return SingleSupplementMoveResult(false)
        }
        AppConfig.getAppConfig().moveStatus = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val sausageName = getBoxTasteLabel(kaoPanBox)
        val boxStockBefore = kaoPanBox.num
        logD(
            LOG_SUPPLEMENT,
            "开始补肠：目标烤盘=${kaoPan.positionSn}，来源烤肠箱=${kaoPanBox.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore，targetPanHasSausage=${kaoPan.isHasSausage}"
        )
        if (!isActionGroupEnabledOrEnterMaintenance("补肠")) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            return SingleSupplementMoveResult(false)
        }

        val owner = "in:${kaoPanBox.positionSn}->${kaoPan.positionSn}:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            logW(
                LOG_ACTION_GROUP,
                "补肠动作组资源忙，拒绝下发：sourceBox=${kaoPanBox.positionSn}，targetPan=${kaoPan.positionSn}，" +
                    "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return SingleSupplementMoveResult(false)
        }

        var actionGroupCompleted = false
        var singleResultForCleanup: ActionGroupResult? = null
        var physicalBoxDeducted = false
        var physicalPanPlaced = false
        var physicalStockInconsistent = false
        val sessionIntentTarget = supplementBatchIntentRepository.loadActive()
            ?.takeIf { it.batchId == batchId }
            ?.targets
            ?.firstOrNull { it.positionSn == kaoPan.positionSn }
        var actionGroupResult = try {
            actionGroupSessionStore.save(
                ActionGroupSession(
                    sessionId = owner,
                    type = ActionGroupSessionType.IN_SUPPLEMENT,
                    sourceNo = kaoPanBox.positionSn,
                    targetNo = kaoPan.positionSn,
                    currentAction = "in1",
                    resourceMask = lockMask,
                    batchId = batchId,
                    taste = kaoPanBox.tasteCode,
                    returnPosition = sessionIntentTarget?.returnPositionSn,
                    prefetch = false,
                    checkpointVersion = 1L,
                    stockBefore = kaoPanBox.num,
                    expectedStockAfter = (kaoPanBox.num - 1).coerceAtLeast(0)
                )
            )
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
                    val previousSession = actionGroupSessionStore.load()
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
                            panPlacementApplied = previousSession?.panPlacementApplied == true
                        )
                    )
                }
            )
                .run(kaoPanBox.positionSn, kaoPan.positionSn)
            actionGroupCompleted = true
            singleResultForCleanup = result
            result
        } finally {
            val retainFailureEvidence = !actionGroupCompleted || singleResultForCleanup is ActionGroupResult.Failed
            if (!retainFailureEvidence) {
                actionGroupSessionStore.clear()
                actionGroupLocks.release(owner)
            }
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
        kaoPanBox.cmdStatusTake = when {
            moveSuccess -> 0
            failureCode == AtomicActionCodes.IR_PICK_FAILED -> failureCode
            kaoPanBox.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED -> AtomicActionCodes.IR_PICK_FAILED
            else -> failureCode
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
                    currentBox.cmdStatusTake = 0
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

        if (actionGroupResult is ActionGroupResult.Failed) {
            actionGroupSessionStore.clear()
            actionGroupLocks.release(owner)
        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        applyPendingActionGroupDisableIfIdle("补肠动作组会话收口")
        if (moveSuccess && supplementStateApplied) {
            verifySupplementConsistencyOrEnterMaintenance(
                source = "补肠动作组",
                expectedSuccessCount = 1,
                records = supplementWriteRecords
            )
        }

        //更新烤肠箱的库存和烤盘的状态
        KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())
        KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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
            AppConfig.getAppConfig().moveStatus = 3
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            LogUtils.i(
                "【搬盘】开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=$operationReason"
            )
            logD(
                LOG_PAN_MOVE,
                "开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，口味=${getPanTasteLabel(kaoPan)}，" +
                    "targetHoldingTime=$targetHoldingTime"
            )

            if (!ensureModbusReady("烤盘${sourcePosition}到烤盘${targetPosition}搬移")) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=下位机通信断开")
                markDeviceErrorAndUpload(FaultCode.MODBUS_COMM_DROP,
                    "烤盘${sourcePosition}到烤盘${targetPosition}搬移前下位机通信断开，设备进入维护中")
                return false
            }

            val value = (sourcePosition shl 8) or targetPosition
            val writeMoveResult = writeSingleRegister2(action_address_move, value)
            if (!writeMoveResult.isSuccess) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=202 指令下发失败，status=${writeMoveResult.status}")
                markDeviceErrorAndUpload(FaultCode.KAOPAN_MOVE_FAILED,
                    "烤盘${sourcePosition}到烤盘${targetPosition}搬移指令下发失败：${writeMoveResult.status}")
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
                        hasObservedRunning = true
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
                        AppConfig.getAppConfig().moveStatus = 0
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
                        logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=读取 203 失败")
                        markDeviceErrorAndUpload(FaultCode.MODBUS_COMM_DROP,
                            "烤盘${sourcePosition}到烤盘${targetPosition}搬移时读取203失败，设备进入维护中")
                        return false
                    }
                    PanMove203Decision.Failed -> {
                        AppConfig.getAppConfig().moveStatus = 0
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
                        logE(LOG_PAN_MOVE, "搬移失败：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，203=$result")
                        markDeviceErrorAndUpload(FaultCode.KAOPAN_MOVE_FAILED,
                            "烤盘${sourcePosition}到烤盘${targetPosition}搬移失败，203=$result，设备进入维护中")
                        return false
                    }
                }
                if (System.currentTimeMillis() - moveStart > moveTimeoutMs) {
                    AppConfig.getAppConfig().moveStatus = 0
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
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
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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

        var acquiredByThisCall = false
        if (appState == 0) {
            AppConfig.getAppConfig().moveStatus = 2
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            acquiredByThisCall = true
        }

        val sausageName = getPanTasteLabel(kaoPan)
        logD(
            LOG_ACTION,
            "维护手动取肠走 300/400 新动作组：烤盘=${kaoPan.positionSn}，口味=$sausageName，" +
                "检测方式=out4 收口结果，不调用视觉识别/拍照"
        )
        try {
            if (!isActionGroupEnabledOrEnterMaintenance("维护手动取肠")) {
                return false
            }

            val owner = "out_manual:${kaoPan.positionSn}:${System.currentTimeMillis()}"
            val lockMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
            if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
                logW(
                    LOG_ACTION_GROUP,
                    "维护手动取肠动作组资源忙，拒绝下发：sourcePan=${kaoPan.positionSn}，" +
                        "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
                )
                return false
            }

            var outPanClearedByTelemetry = false
            val actionGroupResult = try {
                actionGroupSessionStore.save(
                    ActionGroupSession(
                        sessionId = owner,
                        type = ActionGroupSessionType.OUT_MANUAL,
                        sourceNo = kaoPan.positionSn,
                        currentAction = "out1",
                        resourceMask = lockMask
                    )
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
            } finally {
                actionGroupLocks.release(owner)
            }

            logD(
                LOG_ACTION_GROUP,
                "维护手动取肠动作组结束：sourcePan=${kaoPan.positionSn}，result=${actionGroupResultDesc(actionGroupResult)}"
            )

            when (actionGroupResult) {
                ActionGroupResult.Success,
                ActionGroupResult.Discarded -> {
                    applySuccessfulTakePanState(kaoPan)
                    LowToHighDemandGate.clearPosition(kaoPan.positionSn, "维护手动取肠完成")
                    KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
                    if (!outPanClearedByTelemetry) {
                        reportOutCompletionPhysicalDelta(
                            kaoPan = kaoPan,
                            reason = "maintenance_manual_out:completed:pan=${kaoPan.positionSn}",
                            source = "maintenance_manual_out_completion"
                        )
                    }
                    actionGroupSessionStore.clear()
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
                    actionGroupSessionStore.clear()
                    operateScope.launch {
                        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    }
                    return false
                }
            }
        } finally {
            if (acquiredByThisCall) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                applyPendingActionGroupDisableIfIdle("维护手动取肠动作组会话收口")
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
        var acquiredByThisCall = false
        if (appState == 0) {
            AppConfig.getAppConfig().moveStatus = 2
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            acquiredByThisCall = true
        }

        val sausageName = getPanTasteLabel(kaoPan)
        val deliverySubject = if (deliveryIndex != null && deliveryTotal != null) {
            "第$deliveryIndex/${deliveryTotal}根"
        } else {
            null
        }
        val deliveryPrefix = deliverySubject?.let { "$it " } ?: ""
        logD(LOG_ACTION, "300/400 动作组将烤肠($sausageName)从烤盘${kaoPan.positionSn}夹取、插签、搬运到售卖口")
        try {
            if (!isActionGroupEnabledOrEnterMaintenance("正式出餐")) {
                return TakeSausageResult.ERROR
            }

            val owner = "out:${kaoPan.positionSn}:${System.currentTimeMillis()}"
            val lockMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
            if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
                logW(
                    LOG_ACTION_GROUP,
                    "出餐动作组资源忙，拒绝下发：sourcePan=${kaoPan.positionSn}，" +
                        "need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
                )
                return TakeSausageResult.ERROR
            }

            var outPanClearedByTelemetry = false
            val actionGroupResult = try {
                actionGroupSessionStore.save(
                    ActionGroupSession(
                        sessionId = owner,
                        type = ActionGroupSessionType.OUT_SELL,
                        sourceNo = kaoPan.positionSn,
                        orderId = orderContext?.orderNo,
                        orderContext = orderContext,
                        currentAction = "out1",
                        resourceMask = lockMask
                    )
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
            } finally {
                actionGroupLocks.release(owner)
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
                actionGroupSessionStore.clear()
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
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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
            actionGroupSessionStore.clear()

            return takeResult
        } finally {
            if (acquiredByThisCall) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                applyPendingActionGroupDisableIfIdle("出餐动作组会话收口")
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
        kaoPanBox.cmdStatusTake = 0
        if (clearPendingDiscard) {
            FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(kaoPan.positionSn)
        }
        return cfg
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
            KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())
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
        val config = AppConfig.getAppConfig()
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
        //加热管开始工作
        setAreaTargetTemperature(area, heatingTemperature, "heating")
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
        val config = AppConfig.getAppConfig()
        logD(LOG_HEAT, "开始保温：temperature=${config.keepWarmTemperature}")
        logD(LOG_HEAT, "当前加热温度配置：temperature=${config.heatingTemperature}")
        //开始保温工作
        setAreaTargetTemperature(area, config.keepWarmTemperature, "keepWarm")
    }

    /**
     *
     * area 加热1，2，3区
     * 停止加热 0
     */
    fun stopHeating(area: Int) {
        //停止加热
        writeAreaTargetTemperatureRaw(area, 0, "stopHeating")
    }

    /**
     * 全盘无肠时关闭 1/2/3 区目标温度。
     *
     * 这里只看烤盘实际是否有肠，不依赖 isEnable（烤肠算法开关：0关闭，1开启），
     * 避免把“是否自动调度”和“是否需要控温”混在一起。
     *
     * @return true 表示已确认全盘无肠并写入 503/504/505=0；false 表示仍有烤肠，未清零。
     */
    fun stopAllHeatingIfNoSausage(reason: String, force: Boolean = false): Boolean {
        val list = KaoPanHelper.getKaoPanList()
        val decision = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = TemperatureControlPolicy.hasAnySausageInPhysicalPans(list),
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
            return false
        }

        writeAreaTargetTemperatureRaw(1, 0, "stopAllHeatingIfNoSausage")
        writeAreaTargetTemperatureRaw(2, 0, "stopAllHeatingIfNoSausage")
        writeAreaTargetTemperatureRaw(3, 0, "stopAllHeatingIfNoSausage")
        logTemperatureApplyOnce(
            signature = "stopAll:$reason:$force:0,0,0",
            message = "全盘无肠，已关闭一/二/三区目标温度：reason=$reason，force（是否强制清零）=$force，寄存器503=0，寄存器504=0，寄存器505=0"
        )
        return true
    }

    /**
     * 按当前烤盘实际状态刷新 1/2/3 区目标温度。
     *
     * 物理区域口径：
     * - 一区：1~9
     * - 二区：13~21
     * - 三区：25~33
     *
     * 规则：
     * - 区域内存在 status=1（烤制中）的烤肠：写 heatingTemperature（加热温度）
     * - 否则区域内存在 status=2（保温可售）或 holdingTime>0 的烤肠：写 keepWarmTemperature（保温温度）
     * - 否则：写 0，关闭该区加热和保温
     */
    fun <T> withFaultHeatTransitionLock(block: () -> T): T =
        synchronized(faultHeatTransitionLock) { block() }

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
        stopHeatingOnFailure: Boolean
    ): Boolean {
        val config = AppConfig.getAppConfig()
        val pausePreview = if (finishFaultPause) {
            FaultHeatPauseManager.previewFaultHeatPauseEnd(reason)
        } else {
            FaultHeatPauseManager.EndPauseResult.NotActive
        }
        val pendingDiscardForPolicy =
            (config.pendingDiscardPositions + pausePreview.pendingDiscardPositions).distinct().sorted()
        val decisions = TemperatureControlPolicy.decideZoneTemperatures(
            pans = KaoPanHelper.getKaoPanList(),
            keepWarmTemperature = config.keepWarmTemperature,
            heatingTemperature = config.heatingTemperature,
            heatingOffsetsByArea = mapOf(
                1 to config.zone1HeatingOffset,
                2 to config.zone2HeatingOffset,
                3 to config.zone3HeatingOffset
            ),
            pendingDiscardPositions = pendingDiscardForPolicy
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
            val pauseResult = FaultHeatPauseManager.endFaultHeatPauseAndSyncIfNeeded(reason)
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
    fun restoreHeatingStateFromCurrentPans(reason: String): Boolean {
        return withFaultHeatTransitionLock {
            applyConfiguredTemperatureByPanStateInternal(
                reason = reason,
                finishFaultPause = true,
                stopHeatingOnFailure = true
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
        config.onlineStatus = 1
        config.isEnable = 1
        config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
        AppConfig.saveAppConfig(config)
        KaoChangAlgorithm.isEnabled = true
        HomeUiRefreshBridge.requestRefresh("business_start:rest_to_operating")
        logI(
            LOG_DEVICE,
            "营业开始自动恢复：source=$source，" +
                "onlineStatus=${onlineStatusDesc(previousOnlineStatus)}->${onlineStatusDesc(config.onlineStatus)}，" +
                "isEnable=$previousIsEnable(0=关闭, 1=开启)->${config.isEnable}(1=开启)，" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(previousRestStatusSource)}->${restStatusSourceDesc(config.restStatusSource)}"
        )
        restoreHeatingStateFromCurrentPans("营业时间开始自动恢复")
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
