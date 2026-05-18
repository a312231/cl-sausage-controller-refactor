package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupResult
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryBootstrap
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResult
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderEventPayloadBuilder
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderStatus
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderRecoveryDecision
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderRecoveryGatePolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOperationLogContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetryContext
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionClient
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.CompletedInActionGroupPipelineTask
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
        val tasteCode: String?
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
        0 -> "0(未启用)"
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
        return when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
            ActionStatus201.IDLE -> {
                val oldMoveStatus = config.moveStatus
                config.moveStatus = 0
                AppConfig.saveAppConfig(config)
                logW(
                    LOG_ACTION,
                    "检测到本地机械状态残留，已按下位机空闲状态自动收口：" +
                        "reason=$reason，moveStatus（机械动作状态）=${moveStatusDesc(oldMoveStatus)}->${moveStatusDesc(config.moveStatus)}"
                )
                true
            }
            else -> false
        }
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
            logW(LOG_DEVICE, "$reason：MQTT 主上报失败，回退 HTTP 补偿")
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
        operateScope.launch {
            AppConfig.saveAppConfig(config)
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

            while (true) {
                delay(runningPollDelayMs)
                if (!ensureModbusReady(actionName)) {
                    return ActionExecutionResult.DISCONNECTED
                }
                when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
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
            }
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
            "$actionName 动作组失败：protocol=300_400，result=${actionGroupResultDesc(result)}，" +
                "resourceLocks=${actionGroupLocks.snapshot()}"
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
                "动作组订单终态上报成功：source=$source，orderNo=${orderContext.orderNo}，" +
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
                "动作组订单终态 MQTT 上报失败，保留待补偿事件：source=$source，" +
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
            val detail = "动作组恢复早退：Modbus 未连接，reason=$reason"
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
                val detail = "动作组恢复失败，设备进入维护：reason=$reason，detail=${result.reason}"
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
                pan.isHasSausage = true
                LowToHighDemandGate.clearPosition(pan.positionSn, "补肠动作组恢复成功")
                box.num = maxOf(0, box.num - 1)
                pan.taste = KaoPanHelper.buildTasteFromBox(box, pan.taste)
                pan.status = 1
                pan.startTime = System.currentTimeMillis()
                pan.cmdStatusMove = 0
                box.cmdStatusTake = 0
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
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
        pan.cmdStatusTake = 0
        pan.isHasSausage = false
        pan.holdingTime = 0L
        pan.startTime = 0
        pan.status = 0
        if (isManualTake) {
            LowToHighDemandGate.clearPosition(pan.positionSn, "维护手动取肠动作组恢复完成")
        }
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
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
        logI(
            LOG_SELF_CLEAN,
            "开始执行自清洁：source=$source，target=$targetLabel，command（下位机自清洁指令）=$command，" +
                "moveStatus（机械动作状态）=${moveStatusDesc(AppConfig.getAppConfig().moveStatus)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusDesc(AppConfig.getAppConfig().onlineStatus)}"
        )
        val result = executeActionWithRetry(
            actionName = "$targetLabel 自清洁",
            runningPollDelayMs = 1000L,
            runningTimeoutMs = 180_000L
        ) {
            writeSingleRegister2(action_address, command)
        }
        if (result != ActionExecutionResult.SUCCESS) {
            logE(
                LOG_SELF_CLEAN,
                "自清洁失败：source=$source，target=$targetLabel，command=$command，result=${actionExecutionResultDesc(result)}"
            )
            markSelfCleanFailure(targetLabel, result)
            return buildSelfCleanFailureReport(command, targetLabel, result)
        }

        logI(
            LOG_SELF_CLEAN,
            "自清洁完成：source=$source，target=$targetLabel，command=$command，status201（动作状态寄存器）=0（空闲/完成）"
        )
        return SelfCleanExecutionReport(
            success = true,
            command = command,
            targetLabel = targetLabel,
            status201 = 0,
            message = "completed"
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
        val candidates = TrafficModeHelper.findSupplyBoxesByTaste(KaoPanHelper.getKaoPanBoxList(), tasteCode)
        if (candidates.isEmpty()) {
            logE(LOG_SUPPLEMENT, "补肠失败：找不到同口味可用烤肠箱，targetPan=${kaoPan.positionSn}，tasteCode=$tasteCode")
            return false
        }
        val irPickFailedBoxNos = mutableListOf<Int>()
        candidates.forEachIndexed { index, box ->
            LogUtils.i(
                "【补肠】按口味补肠尝试：目标烤盘=${kaoPan.positionSn}，tasteCode（口味编码）=$tasteCode，" +
                    "候选=${index + 1}/${candidates.size}，来源烤肠箱=${box.positionSn}，本地库存=${box.num}"
            )
            val moved = moveSausageToKaoPan(kaoPan, box, allowPickFailureFallback = true)
            if (moved) {
                return true
            }
            if (box.cmdStatusTake == AtomicActionCodes.IR_PICK_FAILED) {
                irPickFailedBoxNos += box.positionSn
                val markResult = SupplementPickFailurePolicy.markIrPickFailedBoxAsEmpty(box, candidates)
                KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                NetApi.updateStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
                    if (code == 0) {
                        logD(
                            LOG_SUPPLEMENT,
                            "烤肠箱物理空箱库存已同步云端：box=${markResult.boxNo}，previousNum=${markResult.previousNum}->0，cloudResponse=$content"
                        )
                    } else {
                        logW(
                            LOG_SUPPLEMENT,
                            "烤肠箱物理空箱库存同步云端失败，继续尝试同口味下一箱：box=${markResult.boxNo}，code=$code，content=$content"
                        )
                    }
                }
                val nextBoxNo = markResult.remainingCandidateBoxNos.firstOrNull()
                logW(
                    LOG_SUPPLEMENT,
                    if (nextBoxNo != null) {
                        "烤肠箱${box.positionSn} 连续取肠失败（0x00E5），已标记为空箱并尝试同口味下一箱：targetPan=${kaoPan.positionSn}，tasteCode=$tasteCode，nextBox=$nextBoxNo"
                    } else {
                        "烤肠箱${box.positionSn} 连续取肠失败（0x00E5），已标记为空箱；当前已无同口味可用候选箱：targetPan=${kaoPan.positionSn}，tasteCode=$tasteCode"
                    }
                )
            } else {
                logE(
                    LOG_SUPPLEMENT,
                    "补肠遇到非取肠红外失败，停止切箱：targetPan=${kaoPan.positionSn}，box=${box.positionSn}，" +
                        "cmdStatusTake=${AtomicActionExecutor.formatCode(box.cmdStatusTake)}"
                )
                return false
            }
        }
        val faultReason = SupplementPickFailurePolicy.buildAllCandidatesFailedFaultReason(
            targetPanNo = kaoPan.positionSn,
            tasteCode = tasteCode,
            failedBoxNos = irPickFailedBoxNos,
            lastErrorCode = AtomicActionCodes.IR_PICK_FAILED
        )
        logE(LOG_SUPPLEMENT, "同口味候选箱全部取肠失败：$faultReason，boxCount=${candidates.size}")
        markDeviceErrorAndUpload(FaultCode.ACTION_GROUP_FAILED, faultReason)
        return false
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

    fun clearPendingSupplementBatchIntent(reason: String) {
        supplementBatchIntentRepository.clear(reason)
    }

    suspend fun handlePendingSupplementBatchIfAny(
        allowedTypes: Set<SupplementBatchType>,
        reason: String,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean,
        isBeforeCloseWindow: Boolean
    ): PendingSupplementBatchHandling {
        val intent = supplementBatchIntentRepository.loadActive() ?: return PendingSupplementBatchHandling.NONE
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
                SupplementTarget(pan, target.tasteCode ?: pan.taste?.tasteCode)
            }
        }
        if (targets.isEmpty()) {
            supplementBatchIntentRepository.clear("未完成批次已无剩余目标:$reason")
            return PendingSupplementBatchHandling.NONE
        }

        applyHeatingForSupplementBatch(intent.type)
        LogUtils.i(
            "【补肠批次恢复】检测到未完成补肠批次，人工确认后续补：reason=$reason，" +
                "batchId=${intent.batchId}，type=${SupplementBatchIntentRepository.typeText(intent.type)}，" +
                "目标烤盘=${targets.joinToString(",") { it.kaoPan.positionSn.toString() }}"
        )
        val movedCount = moveSausagesToKaoPansByTasteInternal(
            targets = targets,
            existingBatchId = intent.batchId,
            createIntent = false,
            stageForSerial = "补肠批次恢复"
        )
        LogUtils.i(
            "【补肠批次恢复】未完成补肠批次续补结束：batchId=${intent.batchId}，目标数=${targets.size}，完成数=$movedCount"
        )
        return PendingSupplementBatchHandling.RESUMED
    }

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
        batchId: String?
    ): Int {
        var movedCount = 0
        for (target in validTargets) {
            if (shouldPauseSupplementForOrder("$stage-单根开始前")) {
                break
            }
            if (!ensureSupplementBatchStartAllowed("$stage-单根下发前")) {
                break
            }
            if (moveSausageToKaoPanByTaste(target.kaoPan, target.tasteCode)) {
                movedCount += 1
                supplementBatchIntentRepository.markCompleted(batchId, target.kaoPan.positionSn)
            } else {
                val failureCode = target.kaoPan.cmdStatusMove.takeIf { it != 0 } ?: target.kaoPan.cmdStatusTake
                supplementBatchIntentRepository.markFailure(
                    batchId = batchId,
                    targetPanNo = target.kaoPan.positionSn,
                    actionName = "in",
                    failureMessage = "串行补肠失败：${AtomicActionExecutor.formatCode(failureCode)}（${AtomicActionExecutor.messageOf(failureCode)}）",
                    requiresManualConfirm = true
                )
                break
            }
            if (shouldPauseSupplementForOrder("$stage-单根完成后")) {
                break
            }
        }
        return movedCount
    }

    suspend fun moveSausagesToKaoPansByTaste(targets: List<SupplementTarget>): Int {
        return moveSausagesToKaoPansByTasteInternal(
            targets = targets,
            existingBatchId = null,
            createIntent = targets.size > 1,
            stageForSerial = "补肠串行动作"
        )
    }

    private suspend fun moveSausagesToKaoPansByTasteInternal(
        targets: List<SupplementTarget>,
        existingBatchId: String?,
        createIntent: Boolean,
        stageForSerial: String
    ): Int {
        val validTargets = targets.filter { it.kaoPan.positionSn in 1..33 }
        if (validTargets.isEmpty()) {
            return 0
        }
        if (!ensureSupplementBatchStartAllowed("补肠批次入口")) {
            return 0
        }
        val activeIntent = supplementBatchIntentRepository.loadActive()
        if (createIntent && activeIntent != null) {
            logW(
                LOG_SUPPLEMENT,
                "存在未完成补肠批次，拒绝启动新补肠批次：activeBatchId=${activeIntent.batchId}，" +
                    "activeType=${SupplementBatchIntentRepository.typeText(activeIntent.type)}，" +
                    "newTargets=${validTargets.map { it.kaoPan.positionSn }}"
            )
            return 0
        }
        if (AppConfig.getAppConfig().enableActionGroupPrefetch != 1 || validTargets.size <= 1) {
            val batchId = existingBatchId ?: if (createIntent) {
                supplementBatchIntentRepository.begin(validTargets)?.batchId ?: return 0
            } else {
                null
            }
            return moveSausagesToKaoPansByTasteSerial(validTargets, stageForSerial, batchId)
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

        val plannedMoves = buildPipelineSupplementPlan(validTargets)
        if (plannedMoves.size <= 1) {
            logD(
                LOG_SUPPLEMENT,
                "补肠预取流水线降级串行：有效计划数=${plannedMoves.size}，请求数=${validTargets.size}"
            )
            val batchId = existingBatchId ?: if (createIntent && plannedMoves.isNotEmpty()) {
                supplementBatchIntentRepository.begin(plannedMoves.map { SupplementTarget(it.kaoPan, it.tasteCode) })?.batchId
                    ?: return 0
            } else {
                existingBatchId
            }
            return moveSausagesToKaoPansByTasteSerial(
                plannedMoves.map { SupplementTarget(it.kaoPan, it.tasteCode) },
                "补肠预取流水线降级串行",
                batchId
            )
        }
        val batchId = existingBatchId ?: if (createIntent) {
            supplementBatchIntentRepository.begin(plannedMoves.map { SupplementTarget(it.kaoPan, it.tasteCode) })?.batchId
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

        if (!isActionGroupEnabledOrEnterMaintenance("补肠预取流水线")) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            return 0
        }

        val owner = "in_pipeline:${System.currentTimeMillis()}"
        val lockMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        if (!actionGroupLocks.tryAcquire(lockMask, owner)) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            logW(
                LOG_ACTION_GROUP,
                "补肠预取流水线资源忙，拒绝下发：need=${ResourceLocks.describe(lockMask)}，held=${actionGroupLocks.snapshot()}"
            )
            return 0
        }

        val taskByPanNo = plannedMoves.associateBy { it.task.targetPanNo }
        var completedCount = 0
        val appliedCompletedPanNos = mutableSetOf<Int>()
        fun applyCompletedPipelineTask(completedTask: CompletedInActionGroupPipelineTask) {
            if (completedTask.targetPanNo in appliedCompletedPanNos) {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠预取流水线跳过重复回写：sourceBox=${completedTask.sourceBoxNo}，targetPan=${completedTask.targetPanNo}"
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

            applySuccessfulSupplementMove(move.kaoPan, box)
            appliedCompletedPanNos += completedTask.targetPanNo
            completedCount += 1
            supplementBatchIntentRepository.markCompleted(batchId, completedTask.targetPanNo)
            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            logD(
                LOG_SUPPLEMENT,
                "补肠预取流水线单根状态已回写：sourceBox=${completedTask.sourceBoxNo}，" +
                    "targetPan=${completedTask.targetPanNo}，successCount=$completedCount"
            )
        }
        var actionGroupCompleted = false
        val result = try {
            val firstMove = plannedMoves.first()
            actionGroupSessionStore.save(
                ActionGroupSession(
                    sessionId = owner,
                    type = ActionGroupSessionType.IN_SUPPLEMENT,
                    sourceNo = firstMove.task.sourceBoxCandidates.first(),
                    targetNo = firstMove.task.targetPanNo,
                    currentAction = "in_pipeline",
                    resourceMask = lockMask
                )
            )
            logD(
                LOG_ACTION_GROUP,
                "补肠预取流水线开始：protocol=300_400，targetCount=${plannedMoves.size}，" +
                    "resourceLocks=${actionGroupLocks.snapshot()}"
            )
            LogUtils.i(
                "【补肠】开始批量补肠：目标烤盘=${plannedMoves.joinToString(",") { it.task.targetPanNo.toString() }}，" +
                    "口味=${plannedMoves.mapNotNull { it.tasteCode }.distinct().joinToString(",").ifBlank { "未知" }}"
            )
            InActionGroupPrefetchPipelineRunner(
                client = createAtomicActionClient(
                    ActionGroupTelemetryContext(
                        sessionId = owner,
                        resourceLocks = actionGroupLocks.snapshot()
                    )
                ),
                logger = { message -> logD(LOG_ACTION_GROUP, message) },
                operationLogger = { message -> LogUtils.i(message) },
                failureLogger = { message -> LogUtils.e(message) },
                onTaskCompleted = { completedTask -> applyCompletedPipelineTask(completedTask) },
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
                .also { actionGroupCompleted = true }
        } finally {
            if (actionGroupCompleted) {
                actionGroupSessionStore.clear()
            }
            actionGroupLocks.release(owner)
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
                    "补肠预取流水线因订单优先暂停：successCount=$completedCount，" +
                        "targetCount=${plannedMoves.size}，orderNo=${SupplementPauseController.currentOrderNo() ?: "未知"}"
                )
            }
            is InActionGroupPipelineResult.Failed -> {
                handlePipelineSupplementFailure(result, taskByPanNo, batchId)
            }
        }

        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        applyPendingActionGroupDisableIfIdle("补肠预取流水线会话收口")

        operateScope.launch {
            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            logD(LOG_SUPPLEMENT, "补肠预取流水线收口状态保存完成")
        }
        return completedCount
    }

    private fun applySuccessfulSupplementMove(kaoPan: KaoPan, kaoPanBox: KaoPanBox) {
        val sausageName = getBoxTasteLabel(kaoPanBox)
        val boxStockBefore = kaoPanBox.num
        kaoPan.isHasSausage = true
        LowToHighDemandGate.clearPosition(kaoPan.positionSn, "补肠预取流水线完成")
        kaoPanBox.num = maxOf(0, kaoPanBox.num - 1)
        kaoPan.taste = KaoPanHelper.buildTasteFromBox(kaoPanBox, kaoPan.taste)
        kaoPan.status = 1
        kaoPan.startTime = System.currentTimeMillis()
        val cfg = AppConfig.getAppConfig()
        kaoPan.bakingTime = cfg.bakingTime * 60L * 1000L
        kaoPan.closeTime = cfg.discardTime * 60L * 60L * 1000L
        kaoPan.cmdStatusMove = 0
        kaoPanBox.cmdStatusTake = 0
        logD(
            LOG_SUPPLEMENT,
            "补肠完成：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，口味=$sausageName，" +
                "箱库存=$boxStockBefore->${kaoPanBox.num}，panStatus=${kaoPan.status}，startTime=${kaoPan.startTime}"
        )
        LogUtils.i(
            "【补肠】烤盘${kaoPan.positionSn} 补肠完成：来源烤肠箱=${kaoPanBox.positionSn}，" +
                "口味=$sausageName，烤盘开始烤制计时，箱库存=$boxStockBefore->${kaoPanBox.num}"
        )
    }

    private data class PlannedPipelineSupplementMove(
        val kaoPan: KaoPan,
        val tasteCode: String?,
        val task: InActionGroupPipelineTask
    )

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
                    task = InActionGroupPipelineTask(
                        sourceBoxCandidates = candidateBoxNos,
                        targetPanNo = target.kaoPan.positionSn
                    )
                )
            }
        }
    }

    private fun handlePipelineSupplementFailure(
        result: InActionGroupPipelineResult.Failed,
        taskByPanNo: Map<Int, PlannedPipelineSupplementMove>,
        batchId: String?
    ) {
        val targetPanNo = result.task?.targetPanNo
        val plannedMove = targetPanNo?.let { taskByPanNo[it] }
        val failureCode = when (val atomicResult = result.result) {
            is AtomicActionResult.Failed -> atomicResult.errorCode
            is AtomicActionResult.ModbusError,
            is AtomicActionResult.Timeout -> 2
            AtomicActionResult.Success -> 0
        }
        plannedMove?.kaoPan?.cmdStatusMove = failureCode
        val sourceBoxNo = result.sourceBoxNo
        val sourceBox = sourceBoxNo?.let { boxNo ->
            KaoPanHelper.getKaoPanBoxList().firstOrNull { it.positionSn == boxNo }
        }
        sourceBox?.cmdStatusTake = failureCode

        logE(
            LOG_SUPPLEMENT,
            "补肠预取流水线失败：targetPan=$targetPanNo，sourceBox=$sourceBoxNo，" +
                "action=${result.actionName}，result=${describeAtomicActionResult(result.result)}，" +
                "failedSourceBoxes=${result.failedSourceBoxNos}，hasPrefetchedNext=${result.hasPrefetchedNext}"
        )
        supplementBatchIntentRepository.markFailure(
            batchId = batchId,
            targetPanNo = targetPanNo,
            actionName = result.actionName,
            failureMessage = describeAtomicActionResult(result.result),
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
            if (sourceBox != null) {
                val markResult = SupplementPickFailurePolicy.markIrPickFailedBoxAsEmpty(
                    failedBox = sourceBox,
                    allCandidates = listOf(sourceBox)
                )
                KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
                NetApi.updateStock(JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())) { code, content ->
                    if (code == 0) {
                        logD(
                            LOG_SUPPLEMENT,
                            "补肠预取流水线取肠失败箱位已标记为空并同步云端：box=${markResult.boxNo}，previousNum=${markResult.previousNum}->0，cloudResponse=$content"
                        )
                    } else {
                        logW(
                            LOG_SUPPLEMENT,
                            "补肠预取流水线取肠失败箱位已标记为空，但同步云端失败：box=${markResult.boxNo}，code=$code，content=$content"
                        )
                    }
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

    suspend fun moveSausageToKaoPan(kaoPan: KaoPan, kaoPanBox: KaoPanBox): Boolean {
        return moveSausageToKaoPan(kaoPan, kaoPanBox, allowPickFailureFallback = false)
    }

    private suspend fun moveSausageToKaoPan(
        kaoPan: KaoPan,
        kaoPanBox: KaoPanBox,
        allowPickFailureFallback: Boolean
    ):Boolean {
        if (AppConfig.getAppConfig().errorStatus>0){
            logD(LOG_SUPPLEMENT, "程序处于报错状态，不进行补肠操作")
            return false
        }
        if (kaoPanBox.num <= 0) {
            logE(LOG_SUPPLEMENT, "补肠取消：烤肠箱${kaoPanBox.positionSn}库存不足，当前库存=${kaoPanBox.num}")
            return false
        }
        if (!ensureModbusReady("补肠动作组恢复检查")) {
            enterActionGroupMaintenance(
                "补肠入口前 Modbus 重连 3 次仍失败，设备进入维护：stage=普通补肠",
                FaultCode.MODBUS_COMM_DROP
            )
            return false
        }
        if (!recoverActionGroupStateAfterModbusReady("补肠入口前恢复检查")) {
            return false
        }

        var appState = AppConfig.getAppConfig().moveStatus
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_SUPPLEMENT, "补肠前等待其他机械动作结束：moveStatus=${moveStatusDesc(appState)}")
        }
        waitForPostSellSupplementGuardIfNeeded()
        if (!ensureSupplementBatchStartAllowed("补肠单根动作组下发前")) {
            return false
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
            return false
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
            return false
        }

        var actionGroupCompleted = false
        val actionGroupResult = try {
            actionGroupSessionStore.save(
                ActionGroupSession(
                    sessionId = owner,
                    type = ActionGroupSessionType.IN_SUPPLEMENT,
                    sourceNo = kaoPanBox.positionSn,
                    targetNo = kaoPan.positionSn,
                    currentAction = "in1",
                    resourceMask = lockMask
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
                ),
                logger = { message -> logD(LOG_ACTION_GROUP, message) },
                operationLogContext = ActionGroupOperationLogContext(
                    category = "补肠",
                    subject = "烤盘${kaoPan.positionSn}"
                ),
                operationLogger = { message -> LogUtils.i("$message：来源烤肠箱=${kaoPanBox.positionSn}") },
                failureLogger = { message -> LogUtils.e("$message：来源烤肠箱=${kaoPanBox.positionSn}") }
            )
                .run(kaoPanBox.positionSn, kaoPan.positionSn)
            actionGroupCompleted = true
            result
        } finally {
            if (actionGroupCompleted) {
                actionGroupSessionStore.clear()
            }
            actionGroupLocks.release(owner)
        }

        val moveSuccess = actionGroupResult == ActionGroupResult.Success
        kaoPanBox.cmdStatusTake = if (moveSuccess) 0 else atomicFailureCode(actionGroupResult)
        kaoPan.cmdStatusMove = if (moveSuccess) 0 else atomicFailureCode(actionGroupResult)
        logD(
            LOG_SUPPLEMENT,
            "补肠动作组结束：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，" +
                "result=${actionGroupResultDesc(actionGroupResult)}"
        )

        if (moveSuccess){
            kaoPan.isHasSausage=true
            LowToHighDemandGate.clearPosition(kaoPan.positionSn, "补肠动作组完成")
            // 减少烤肠箱中的数量，最低只允许到 0，防止库存被写成负数
            kaoPanBox.num = maxOf(0, kaoPanBox.num - 1)
            kaoPan.taste = KaoPanHelper.buildTasteFromBox(kaoPanBox, kaoPan.taste)
            //清空旧位置信息
            kaoPan.status=1//开始加热烤制
            // 记录开始时间
            kaoPan.startTime = System.currentTimeMillis()
            // 从当前 config 刷新烤制时长和丢弃阈值，防止初始化时 config 尚未从云端加载
            // 导致 bakingTime/closeTime 遗留为 0 而直接跳入保温状态
            val cfg = AppConfig.getAppConfig()
            kaoPan.bakingTime = cfg.bakingTime * 60L * 1000L
            kaoPan.closeTime  = cfg.discardTime * 60L * 60L * 1000L
            logD(LOG_SUPPLEMENT, "补肠后刷新烤制参数：bakingTime=${cfg.bakingTime}min, discardTime=${cfg.discardTime}h → panId=${kaoPan.id}")
            logD(
                LOG_SUPPLEMENT,
                "补肠完成：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，口味=$sausageName，" +
                    "箱库存=$boxStockBefore->${kaoPanBox.num}，panStatus=${kaoPan.status}，startTime=${kaoPan.startTime}"
            )
        } else {
            logE(
                LOG_SUPPLEMENT,
                "补肠失败，未更新烤盘状态和库存：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}, " +
                    "result=${actionGroupResultDesc(actionGroupResult)}"
            )
            if (allowPickFailureFallback && atomicFailureCode(actionGroupResult) == AtomicActionCodes.IR_PICK_FAILED) {
                logW(
                    LOG_SUPPLEMENT,
                    "补肠取肠失败由同口味切箱逻辑接管，暂不切维护：targetPan=${kaoPan.positionSn}, sourceBox=${kaoPanBox.positionSn}"
                )
            } else {
                markActionGroupFailure("补肠", actionGroupResult)
            }
        }

        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        applyPendingActionGroupDisableIfIdle("补肠动作组会话收口")

        //更新烤肠箱的库存和烤盘的状态
        operateScope.launch {
            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        logD(LOG_SUPPLEMENT, "补肠后烤盘状态更新完成")
        }
        logD(LOG_SUPPLEMENT, "补肠流程结束：来源烤肠箱=${kaoPanBox.positionSn}，目标烤盘=${kaoPan.positionSn}，success=$moveSuccess")

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            logD(LOG_SUPPLEMENT, "等待当前售卖结束后再补下一根烤肠")
        }
        return moveSuccess
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
    ):Boolean  {
        val sourcePosition = kaoPan.positionSn
        val targetPosition = newKaoPan.positionSn
        if (sourcePosition==targetPosition){
            return true
        }

        val dispatchDecision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = AppConfig.getAppConfig().onlineStatus,
            errorStatus = AppConfig.getAppConfig().errorStatus,
            inspectionMode = AppConfig.getAppConfig().inspectionMode,
            moveStatus = AppConfig.getAppConfig().moveStatus
        )
        if (!dispatchDecision.canStart) {
            logD(
                LOG_PAN_MOVE,
                "跳过本轮自动搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，reason=${dispatchDecision.reason}"
            )
            return false
        }

        val owner = "legacy_move:$sourcePosition->$targetPosition:${System.currentTimeMillis()}"
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
                "【搬盘】开始搬移：来源烤盘=$sourcePosition，目标烤盘=$targetPosition，原因=自动调度"
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
                val commandValue = if (result == AtomicActionCodes.COMPLETED && !hasObservedRunning) {
                    VMModbusHelper.readHoldingRegisters(modbus_address, action_address_move)
                } else {
                    null
                }
                when (PanMove203Policy.evaluate(result, hasObservedRunning, commandValue)) {
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
            kaoPan.cmdStatusMove = 0
            newKaoPan.isHasSausage = true
            LowToHighDemandGate.clearPosition(newKaoPan.positionSn, "烤盘搬移目标位写入")
            newKaoPan.isHasGrilling = false
            newKaoPan.taste = KaoPanHelper.copyResolvedTaste(kaoPan.taste) ?: kaoPan.taste
            newKaoPan.holdingTime = targetHoldingTime
            newKaoPan.status = 2
            kaoPan.isHasSausage = false
            kaoPan.startTime = 0
            kaoPan.status = 0
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            operateScope.launch {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }
            LogUtils.i(
                "【搬盘】搬移完成：来源烤盘=$sourcePosition 已清空，目标烤盘=$targetPosition 进入保温可售"
            )
            logD(
                LOG_PAN_MOVE,
                "搬移完成：来源烤盘=$sourcePosition 已清空，目标烤盘=$targetPosition 已写入，" +
                    "targetHoldingTime=${newKaoPan.holdingTime}，targetStatus=${newKaoPan.status}"
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
                    )
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
                    kaoPan.cmdStatusTake = 0
                    kaoPan.isHasSausage = false
                    kaoPan.holdingTime = 0L
                    kaoPan.startTime = 0
                    kaoPan.status = 0
                    LowToHighDemandGate.clearPosition(kaoPan.positionSn, "维护手动取肠完成")
                    operateScope.launch {
                        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
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
        // - 只需要等待与出餐冲突的动作（补肠/搬盘/丢弃）结束：1/3/4。
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
        while (appState == 1 || appState == 3 || appState == 4 || appState == 5) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            logD(LOG_ACTION, "正式出餐前等待机械动作收尾：moveStatus=$appState")
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
            kaoPan.cmdStatusTake = 0
            kaoPan.isHasSausage = false
            kaoPan.holdingTime = 0L
            kaoPan.startTime = 0
            kaoPan.status = 0
            armPostSellSupplementGuard("out5 已完成关门回零/出餐收尾")
            operateScope.launch {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
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
            runningPollDelayMs = 2000L,
            runningTimeoutMs = 120_000L
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
            kaoPan.isHasSausage=false
            kaoPan.holdingTime=0
            kaoPan.startTime=0
            kaoPan.status=0
            handleSuccessfulDiscardDemandGate(kaoPan.positionSn, source)
            FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(kaoPan.positionSn)
            LogUtils.i(
                "【丢弃流程】丢弃完成：来源=$source，烤盘=${kaoPan.positionSn}，口味=$tasteLabel，详情=$sourceDetail，" +
                    "panHasSausage=${kaoPan.isHasSausage}，panStatus=${kaoPan.status}"
            )
        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        operateScope.launch {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
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
        val heatingTemperature = AppConfig.getAppConfig().heatingTemperature
        logD(LOG_HEAT, "开始加热：temperature=$heatingTemperature")
        //加热管开始工作
        setAreaTargetTemperature(area, heatingTemperature, "heating")
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
    fun applyConfiguredTemperatureByPanState(reason: String) {
        FaultHeatPauseManager.endFaultHeatPauseAndAdjustIfNeeded(reason)
        val config = AppConfig.getAppConfig()
        val decisions = TemperatureControlPolicy.decideZoneTemperatures(
            pans = KaoPanHelper.getKaoPanList(),
            keepWarmTemperature = config.keepWarmTemperature,
            heatingTemperature = config.heatingTemperature,
            heatingOffsetsByArea = mapOf(
                1 to config.zone1HeatingOffset,
                2 to config.zone2HeatingOffset,
                3 to config.zone3HeatingOffset
            ),
            pendingDiscardPositions = config.pendingDiscardPositions
        )
        decisions.forEach { decision ->
            setAreaTargetTemperature(decision.area, decision.targetTemperature, reason)
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
            signature = "apply:$reason:$detail",
            message = "按当前烤盘状态刷新目标温度：reason=$reason，$detail，" +
                "keepWarmTemperature（保温温度）=${config.keepWarmTemperature}℃，" +
                "heatingTemperature（基础烤制温度）=${config.heatingTemperature}℃，" +
                "zone1HeatingOffset（一区烤制补偿）=${config.zone1HeatingOffset}℃，" +
                "zone2HeatingOffset（二区烤制补偿）=${config.zone2HeatingOffset}℃，" +
                "zone3HeatingOffset（三区烤制补偿）=${config.zone3HeatingOffset}℃"
        )
    }

    /**
     * 按当前烤盘实际状态重建 1/2/3 区温度。
     */
    fun restoreHeatingStateFromCurrentPans(reason: String) {
        applyConfiguredTemperatureByPanState(reason)
    }

    /**
     * 设备故障态停热入口。
     *
     * 寄存器 503/504/505 分别是一/二/三区目标温度，写 0 表示停止烤制和保温加热。
     * 这里只停热和关闭自动调度；计时补偿由恢复非 0 温度前统一执行。
     */
    fun stopHeatingForFault(reason: String) {
        FaultHeatPauseManager.beginFaultHeatPauseIfNeeded(reason, source = "stopHeatingForFault")
        writeAreaTargetTemperatureRaw(1, 0, "stopHeatingForFault")
        writeAreaTargetTemperatureRaw(2, 0, "stopHeatingForFault")
        writeAreaTargetTemperatureRaw(3, 0, "stopHeatingForFault")
        lastTemperatureApplyLogSignature = null
        logW(
            LOG_HEAT,
            "设备故障停热：reason=$reason，" +
                "寄存器503/504/505目标温度已写0，已记录故障停热暂停；后续恢复非0温度前会先补偿烤制计时"
        )
    }

    private fun setAreaTargetTemperature(area: Int, temperature: Int, reason: String) {
        if (temperature > 0) {
            FaultHeatPauseManager.endFaultHeatPauseAndAdjustIfNeeded(reason)
        }
        writeAreaTargetTemperatureRaw(area, temperature, "setAreaTargetTemperature:$reason")
    }

    // 仅供写 0 停热/主动暂停/故障停热路径，以及 setAreaTargetTemperature 内部最终落寄存器使用。
    private fun writeAreaTargetTemperatureRaw(area: Int, temperature: Int, source: String) {
        logD(LOG_HEAT, "写入温区目标温度：source=$source，area=$area，temperature=$temperature")
        when (area) {
            1 -> writeSingleRegister(action_address_1_temperature_write, temperature)
            2 -> writeSingleRegister(action_address_2_temperature_write, temperature)
            3 -> writeSingleRegister(action_address_3_temperature_write, temperature)
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
        AppConfig.saveAppConfig(config)
        LogUtils.w(
            "【并发切换】过保丢弃触发低并发收口：烤盘=$positionSn，" +
                "modeType（并发模式）=${modeTypeDesc(oldModeType)}->${modeTypeDesc(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionModeDesc(oldTransitionMode)}->${transitionModeDesc(config.transitionMode)}，" +
                "blockedFrontPositions（过保丢弃空位，仅1~9）=${result.blockedFrontPositions}，" +
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
     * 若后台已手动设为维护中/未启用，不覆盖。
     */
    suspend fun endBusinessHours(config: AppConfigBean){
        if (config.errorStatus != 0) {
            config.onlineStatus = 3
            config.restStatusSource = AppConfigBean.REST_SOURCE_NONE
            AppConfig.saveAppConfig(config)
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
        logD(
            LOG_DEVICE,
            "营业时间结束：已自动切换为休息中（2），" +
                "restStatusSource（休息中来源）=${restStatusSourceDesc(config.restStatusSource)}"
        )
        //同步更新云端状态
        SendServerHelper.publishServiceUpdateStatus()
        //执行丢弃操作
        try {
            KaoChangAlgorithm.resetKaoPan()
            val selfCleanResult = runPanSelfCleanSequence(
                positionSnList = (1..33).toList(),
                includeSellPlatform = true,
                source = "营业结束全量自清洁"
            )
            if (!selfCleanResult.success) {
                val failedReport = selfCleanResult.reports.lastOrNull()
                logE(
                    LOG_SELF_CLEAN,
                    "营业结束全量自清洁失败：target=${failedReport?.targetLabel ?: "unknown"}，" +
                        "command=${failedReport?.command ?: -1}，message=${failedReport?.message ?: "unknown"}"
                )
                return
            }
            config.zone3Dirty = 0
            config.zone3CleanPending = 0
            AppConfig.saveAppConfig(config)
            logI(
                LOG_SELF_CLEAN,
                "营业结束全量自清洁完成：sequence=501..533,534，onlineStatus（设备营业状态）=${onlineStatusDesc(config.onlineStatus)}"
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
