package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.delay

object AtomicActionCodes {
    const val IDLE = 0x0000
    const val RUNNING = 0x0001
    const val COMPLETED = 0x0002
    const val PARAM_ERROR = 0x00E1
    const val X_RESET_FAILED = 0x00E2
    const val Y_RESET_FAILED = 0x00E3
    const val LIFT_FAILED = 0x00E4
    const val IR_PICK_FAILED = 0x00E5
    const val IR_THROW_FAILED = 0x00E6
    const val STICK_FAILED = 0x00E7
    const val OUT_EXIT_SAUSAGE_NOT_DETECTED = 0x00E8
    const val STICK_EMPTY = 0x00E9
    const val BUSY = 0x00EA
    const val IN_SOURCE_SLOT_PARAM = 0x00EB
    const val IN7_TARGET_SLOT_PARAM = 0x00EC
    const val OUT_SLOT_PARAM = 0x00ED
    const val IN1_X_RESET_FAILED = 0x00EE
    const val IN1_Y_RESET_FAILED = 0x00EF
    const val IN3_X_RESET_FAILED = 0x00F0
    const val IN3_Y_RESET_FAILED = 0x00F1
    const val LIFT_DOWN_LOCKED_ROTOR = 0x00F2
    const val LIFT_UP_LOCKED_ROTOR = 0x00F3
    const val LIFT_NOT_BOTTOM = 0x00F4
    const val IN7_SAU_ADD_BUSY = 0x00F5
    const val OUT_UNSUPPORTED_ACTION = 0x00F6
    const val BOTTOM_UNSUPPORTED_ACTION = 0x00F7
    const val MOVE_PARAM_ERROR = 0x00F8
    const val MOVE_BUSY = 0x00F9
    const val MOVE_TIMEOUT = 0x00FA
    const val MOVE_STATE_ERROR = 0x00FB
}

data class AtomicActionSpec(
    val name: String,
    val commandRegister: Int,
    val resultRegister: Int,
    val infoRegister: Int,
    val timeoutMs: Long,
    val pollDelayMs: Long,
    val description: String
)

object AtomicActions {
    val in1 = AtomicActionSpec("in1", 310, 311, 312, 80_000L, 200L, "in1：移动到肠箱预推送位置")
    val in2 = AtomicActionSpec("in2", 320, 321, 322, 80_000L, 200L, "in2：取肠、分料、避让")
    val in3 = AtomicActionSpec("in3", 330, 331, 332, 80_000L, 200L, "in3：回到抛肠点")
    val in4 = AtomicActionSpec("in4", 340, 341, 342, 80_000L, 500L, "in4：升降台下降")
    val in5 = AtomicActionSpec("in5", 350, 351, 352, 80_000L, 300L, "in5：抛肠并检测是否抛出成功")
    val in6 = AtomicActionSpec("in6", 360, 361, 362, 80_000L, 500L, "in6：升降台上升")
    val in7 = AtomicActionSpec("in7", 370, 371, 372, 120_000L, 500L, "in7：上平台从升降台夹肠并放入烤盘")

    val out1 = AtomicActionSpec("out1", 410, 411, 412, 120_000L, 500L, "out1：从烤盘取熟肠到穿签位")
    val out2 = AtomicActionSpec("out2", 420, 421, 422, 90_000L, 500L, "out2：执行穿签")
    val out3 = AtomicActionSpec("out3", 430, 431, 432, 90_000L, 500L, "out3：从穿签位放到预推送位置")
    val out4 = AtomicActionSpec("out4", 440, 441, 442, 90_000L, 500L, "out4：开窗并推送")
    val out5 = AtomicActionSpec("out5", 450, 451, 452, 90_000L, 500L, "out5：关门回零/出餐收尾")
}

sealed class AtomicIoResult {
    data object Success : AtomicIoResult()
    data class Failure(val message: String) : AtomicIoResult()
}

sealed class AtomicReadResult {
    data class Success(val value: Int) : AtomicReadResult()
    data class Failure(val message: String) : AtomicReadResult()
}

sealed class AtomicActionResult {
    data object Success : AtomicActionResult()
    data class Failed(val errorCode: Int, val message: String) : AtomicActionResult()
    data class Timeout(val message: String) : AtomicActionResult()
    data class ModbusError(val message: String) : AtomicActionResult()
}

interface AtomicModbusGateway {
    suspend fun writeRegister(address: Int, value: Int): AtomicIoResult
    suspend fun readRegister(address: Int): AtomicReadResult
}

interface AtomicActionClient {
    suspend fun execute(
        spec: AtomicActionSpec,
        infoValue: Int = 0,
        onCommandDispatched: (suspend () -> Unit)? = null
    ): AtomicActionResult
}

interface AtomicActionStateReader {
    suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult
}

class AtomicActionExecutor(
    private val gateway: AtomicModbusGateway,
    private val logger: (String) -> Unit,
    private val telemetryContext: ActionGroupTelemetryContext = ActionGroupTelemetryContext(),
    private val telemetrySink: (ActionGroupTelemetry) -> Unit = {},
    private val startupConfirmTimeoutMs: Long = STARTUP_CONFIRM_TIMEOUT_MS,
    private val commandPendingConfirmTimeoutMs: Long = COMMAND_PENDING_CONFIRM_TIMEOUT_MS,
    private val diagnosisSnapshotProvider: () -> VMModbusHelper.ModbusDiagnosisSnapshot = {
        VMModbusHelper.ModbusDiagnosisSnapshot(
            connected = VMModbusHelper.modbusMaster != null,
            port = "未知",
            baudRate = 0
        )
    },
    private val modbusServerAddress: Int? = null
) : AtomicActionClient, AtomicActionStateReader {

    override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
        return gateway.readRegister(spec.resultRegister)
    }

    override suspend fun execute(
        spec: AtomicActionSpec,
        infoValue: Int,
        onCommandDispatched: (suspend () -> Unit)?
    ): AtomicActionResult {
        val startedAtMs = System.currentTimeMillis()
        return when (val current = readResult(spec)) {
            is AtomicReadResult.Failure -> finishWithTelemetry(
                spec = spec,
                result = AtomicActionResult.ModbusError(
                    structuredFailureMessage(
                        responsibility = "上位机与下位机通信读取异常",
                        stage = "启动前读取结果寄存器失败",
                        spec = spec,
                        extraParts = listOf("异常信息=${current.message}")
                    )
                ),
                startedAtMs = startedAtMs
            )
            is AtomicReadResult.Success -> {
                if (current.value == AtomicActionCodes.RUNNING) {
                    logger(
                        "接管原子动作轮询：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                            "resultRegister=${spec.resultRegister}，resultCode=${formatCode(current.value)}"
                    )
                    onCommandDispatched?.invoke()
                    pollUntilFinish(
                        spec = spec,
                        startedAtMs = startedAtMs,
                        requireFreshStateBeforeSuccess = false,
                        hasAlreadyObservedRunning = true,
                        allowFastCompletedWhenCommandCleared = false
                    )
                } else {
                    val clearResult = clearStaleCommandMirrorIfNeeded(spec, current.value, startedAtMs)
                    if (clearResult != null) {
                        return clearResult
                    }
                    startAndPoll(spec, infoValue, startedAtMs, onCommandDispatched)
                }
            }
        }
    }

    private suspend fun clearStaleCommandMirrorIfNeeded(
        spec: AtomicActionSpec,
        startupResultCode: Int,
        startedAtMs: Long
    ): AtomicActionResult? {
        val commandRead = gateway.readRegister(spec.commandRegister)
        if (commandRead !is AtomicReadResult.Success || commandRead.value != AtomicActionCodes.RUNNING) {
            return null
        }
        logger(
            "清理残留命令镜像：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                "commandRegister（命令寄存器）=${spec.commandRegister}，当前命令=${commandValueDesc(commandRead.value)}，" +
                "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(startupResultCode)}"
        )
        return when (val writeClear = gateway.writeRegister(spec.commandRegister, 0)) {
            AtomicIoResult.Success -> null
            is AtomicIoResult.Failure -> finishWithTelemetry(
                spec = spec,
                result = AtomicActionResult.ModbusError(
                    structuredFailureMessage(
                        responsibility = "上位机写入下位机命令寄存器失败",
                        stage = "清理残留命令寄存器失败",
                        spec = spec,
                        commandValue = AtomicActionCodes.IDLE,
                        resultValue = startupResultCode,
                        extraParts = listOf("异常信息=${writeClear.message}")
                    )
                ),
                startedAtMs = startedAtMs
            )
        }
    }

    private suspend fun startAndPoll(
        spec: AtomicActionSpec,
        infoValue: Int,
        startedAtMs: Long,
        onCommandDispatched: (suspend () -> Unit)?
    ): AtomicActionResult {
        logger(
            "准备下发原子动作：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                "description=${spec.description}，commandRegister（命令寄存器）=${spec.commandRegister}，" +
                "resultRegister（结果寄存器）=${spec.resultRegister}，infoRegister（信息寄存器）=${spec.infoRegister}，" +
                "infoValue（信息值）=$infoValue"
        )
        when (val writeInfo = gateway.writeRegister(spec.infoRegister, infoValue)) {
            is AtomicIoResult.Failure -> {
                logger(
                    "写入信息寄存器失败：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "infoRegister（信息寄存器）=${spec.infoRegister}，infoValue（信息值）=$infoValue，" +
                        "message=${writeInfo.message}"
                )
                return finishWithTelemetry(
                    spec = spec,
                    result = AtomicActionResult.ModbusError(
                        structuredFailureMessage(
                            responsibility = "上位机写入下位机信息寄存器失败",
                            stage = "写信息寄存器失败",
                            spec = spec,
                            infoValue = infoValue,
                            extraParts = listOf("异常信息=${writeInfo.message}")
                        )
                    ),
                    startedAtMs = startedAtMs
                )
            }
            AtomicIoResult.Success -> logger(
                "写入信息寄存器成功：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                    "infoRegister（信息寄存器）=${spec.infoRegister}，infoValue（信息值）=$infoValue"
            )
        }
        when (val writeCommand = gateway.writeRegister(spec.commandRegister, 1)) {
            is AtomicIoResult.Failure -> {
                logger(
                    "写入命令寄存器失败：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "commandRegister（命令寄存器）=${spec.commandRegister}，commandValue（命令值）=1，" +
                        "message=${writeCommand.message}"
                )
                return finishWithTelemetry(
                    spec = spec,
                    result = AtomicActionResult.ModbusError(
                        structuredFailureMessage(
                            responsibility = "上位机写入下位机命令寄存器失败",
                            stage = "写命令寄存器失败",
                            spec = spec,
                            commandValue = AtomicActionCodes.RUNNING,
                            extraParts = listOf("异常信息=${writeCommand.message}")
                        )
                    ),
                    startedAtMs = startedAtMs
                )
            }
            AtomicIoResult.Success -> {
                logger(
                    "写入命令寄存器成功：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "commandRegister（命令寄存器）=${spec.commandRegister}，commandValue（命令值）=1"
                )
                onCommandDispatched?.invoke()
            }
        }
        return pollUntilFinish(
            spec = spec,
            startedAtMs = startedAtMs,
            requireFreshStateBeforeSuccess = true,
            hasAlreadyObservedRunning = false,
            allowFastCompletedWhenCommandCleared = true
        )
    }

    private suspend fun pollUntilFinish(
        spec: AtomicActionSpec,
        startedAtMs: Long,
        requireFreshStateBeforeSuccess: Boolean,
        hasAlreadyObservedRunning: Boolean,
        allowFastCompletedWhenCommandCleared: Boolean
    ): AtomicActionResult {
        var lastRunningLogAtMs = 0L
        var hasLoggedRunning = hasAlreadyObservedRunning
        var hasObservedFreshState = !requireFreshStateBeforeSuccess
        var hasObservedRunning = hasAlreadyObservedRunning
        var hasLoggedStartupWait = false
        var hasLoggedCommandPendingWait = false
        val startupConfirmWindowMs = minOf(startupConfirmTimeoutMs, spec.timeoutMs)
        val commandPendingTimeoutMs = minOf(commandPendingConfirmTimeoutMs, spec.timeoutMs)
        while (System.currentTimeMillis() - startedAtMs <= spec.timeoutMs) {
            when (val read = gateway.readRegister(spec.resultRegister)) {
                is AtomicReadResult.Failure -> return AtomicActionResult.ModbusError(
                    structuredFailureMessage(
                        responsibility = "上位机与下位机通信读取异常",
                        stage = "轮询读取结果寄存器失败",
                        spec = spec,
                        elapsedMs = System.currentTimeMillis() - startedAtMs,
                        hasObservedRunning = hasObservedRunning,
                        extraParts = listOf("异常信息=${read.message}")
                    )
                ).let {
                    finishWithTelemetry(spec, it, startedAtMs)
                }
                is AtomicReadResult.Success -> {
                    val code = read.value
                    val nowMs = System.currentTimeMillis()
                    val elapsedMs = nowMs - startedAtMs
                    if (code != AtomicActionCodes.RUNNING ||
                        !hasLoggedRunning ||
                        nowMs - lastRunningLogAtMs >= RUNNING_PROGRESS_LOG_INTERVAL_MS
                    ) {
                        logger(
                            "轮询原子动作：action=${spec.name}，resultRegister=${spec.resultRegister}，" +
                                "resultCode=${formatCode(code)}，elapsedMs=$elapsedMs"
                        )
                        if (code == AtomicActionCodes.RUNNING) {
                            hasLoggedRunning = true
                            lastRunningLogAtMs = nowMs
                        }
                    }
                    when {
                        code == AtomicActionCodes.COMPLETED && hasObservedRunning -> return finishWithTelemetry(
                            spec,
                            AtomicActionResult.Success,
                            startedAtMs
                        )
                        code == AtomicActionCodes.COMPLETED && allowFastCompletedWhenCommandCleared -> {
                            /*
                             * 快速完成只允许在 startAndPoll() 已经成功写入本轮 commandRegister=1 后使用。
                             * execute() 首读到的 result=2 仍然必须重新下发本轮命令，避免把上一轮残留完成态当成本轮成功。
                             */
                            when (val commandRead = gateway.readRegister(spec.commandRegister)) {
                                is AtomicReadResult.Failure -> {
                                    if (elapsedMs >= startupConfirmWindowMs) {
                                        val message = buildStartupNotConfirmedMessage(spec, elapsedMs, code, commandRead)
                                        logStartupNotConfirmedFailure(spec, elapsedMs, code)
                                        return finishWithTelemetry(
                                            spec,
                                            AtomicActionResult.Timeout(message),
                                            startedAtMs
                                        )
                                    }
                                }
                                is AtomicReadResult.Success -> {
                                    if (commandRead.value == AtomicActionCodes.IDLE) {
                                        logger(
                                            "快速完成确认：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                                                "commandRegister（命令寄存器）=${spec.commandRegister}，当前命令=${commandValueDesc(commandRead.value)}，" +
                                                "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(code)}，elapsedMs=$elapsedMs"
                                        )
                                        return finishWithTelemetry(
                                            spec,
                                            AtomicActionResult.Success,
                                            startedAtMs
                                        )
                                    }
                                    if (commandRead.value == AtomicActionCodes.RUNNING &&
                                        elapsedMs < commandPendingTimeoutMs
                                    ) {
                                        if (!hasLoggedCommandPendingWait) {
                                            hasLoggedCommandPendingWait = true
                                            logger(
                                                "命令仍在等待下位机接收：sessionId=${telemetryContext.sessionId}，" +
                                                    "action=${spec.name}，commandRegister（命令寄存器）=${spec.commandRegister}，" +
                                                    "当前命令=${commandValueDesc(commandRead.value)}，" +
                                                    "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(code)}，" +
                                                    "elapsedMs=$elapsedMs，commandPendingTimeoutMs=$commandPendingTimeoutMs"
                                            )
                                        }
                                    } else if (elapsedMs >= startupConfirmWindowMs) {
                                        val message = buildStartupNotConfirmedMessage(spec, elapsedMs, code, commandRead)
                                        logStartupNotConfirmedFailure(spec, elapsedMs, code)
                                        return finishWithTelemetry(
                                            spec,
                                            AtomicActionResult.Timeout(message),
                                            startedAtMs
                                        )
                                    }
                                }
                            }
                            delay(spec.pollDelayMs)
                        }
                        code == AtomicActionCodes.IDLE && hasObservedRunning -> {
                            return finishWithTelemetry(
                                spec,
                                AtomicActionResult.Timeout(
                                    structuredFailureMessage(
                                        responsibility = "下位机执行状态异常回退",
                                        stage = "执行中后结果回退",
                                        spec = spec,
                                        elapsedMs = elapsedMs,
                                        resultValue = code,
                                        hasObservedRunning = true
                                    )
                                ),
                                startedAtMs
                            )
                        }
                        code == AtomicActionCodes.IDLE || code == AtomicActionCodes.COMPLETED -> {
                            if (code == AtomicActionCodes.IDLE && !hasObservedFreshState) {
                                hasObservedFreshState = true
                                logger(
                                    "动作写入确认成功：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                                        "resultRegister（结果寄存器）=${spec.resultRegister}，resultCode=${resultCodeDesc(code)}，" +
                                        "说明下位机已清空旧结果并接收本轮命令，elapsedMs=$elapsedMs"
                                )
                            }
                            if (!hasLoggedStartupWait) {
                                hasLoggedStartupWait = true
                                logger(
                                    "等待动作启动确认：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                                        "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(code)}，" +
                                        "startupConfirmTimeoutMs=$startupConfirmWindowMs"
                                )
                            }
                            if (elapsedMs >= spec.timeoutMs) {
                                return finishWithTelemetry(
                                    spec,
                                    AtomicActionResult.Timeout(
                                        structuredFailureMessage(
                                            responsibility = "下位机未启动或未回写执行中",
                                            stage = "整体执行超时",
                                            spec = spec,
                                            elapsedMs = elapsedMs,
                                            resultValue = code,
                                            hasObservedRunning = false
                                        )
                                    ),
                                    startedAtMs
                                )
                            }
                            if (elapsedMs >= startupConfirmWindowMs) {
                                when (val commandRead = gateway.readRegister(spec.commandRegister)) {
                                    is AtomicReadResult.Failure -> {
                                        val message = buildStartupNotConfirmedMessage(spec, elapsedMs, code, commandRead)
                                        logStartupNotConfirmedFailure(spec, elapsedMs, code)
                                        return finishWithTelemetry(
                                            spec,
                                            AtomicActionResult.Timeout(message),
                                            startedAtMs
                                        )
                                    }
                                    is AtomicReadResult.Success -> {
                                        if (commandRead.value == AtomicActionCodes.RUNNING &&
                                            elapsedMs < commandPendingTimeoutMs
                                        ) {
                                            if (!hasLoggedCommandPendingWait) {
                                                hasLoggedCommandPendingWait = true
                                                logger(
                                                    "命令仍在等待下位机接收：sessionId=${telemetryContext.sessionId}，" +
                                                        "action=${spec.name}，commandRegister（命令寄存器）=${spec.commandRegister}，" +
                                                        "当前命令=${commandValueDesc(commandRead.value)}，" +
                                                        "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(code)}，" +
                                                        "elapsedMs=$elapsedMs，commandPendingTimeoutMs=$commandPendingTimeoutMs"
                                                )
                                            }
                                            delay(spec.pollDelayMs)
                                            continue
                                        }
                                        val message = buildStartupNotConfirmedMessage(spec, elapsedMs, code, commandRead)
                                        logStartupNotConfirmedFailure(spec, elapsedMs, code)
                                        return finishWithTelemetry(
                                            spec,
                                            AtomicActionResult.Timeout(message),
                                            startedAtMs
                                        )
                                    }
                                }
                            }
                            delay(spec.pollDelayMs)
                        }
                        code == AtomicActionCodes.RUNNING -> {
                            hasObservedFreshState = true
                            if (!hasObservedRunning) {
                                hasObservedRunning = true
                                logger(
                                    "动作启动确认成功：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                                        "resultRegister（结果寄存器）=${spec.resultRegister}，resultCode=${formatCode(code)}，elapsedMs=$elapsedMs"
                                )
                            }
                            delay(spec.pollDelayMs)
                        }
                        code >= AtomicActionCodes.PARAM_ERROR -> return finishWithTelemetry(
                            spec,
                            AtomicActionResult.Failed(
                                code,
                                structuredFailureMessage(
                                    responsibility = "下位机明确返回动作错误码",
                                    stage = "结果寄存器返回下位机错误码",
                                    spec = spec,
                                    elapsedMs = elapsedMs,
                                    resultValue = code,
                                    hasObservedRunning = hasObservedRunning,
                                    extraParts = listOf("错误说明=${messageOf(code)}")
                                )
                            ),
                            startedAtMs
                        )
                        else -> return finishWithTelemetry(
                            spec,
                            AtomicActionResult.ModbusError(
                                structuredFailureMessage(
                                    responsibility = "上下位机协议口径不一致",
                                    stage = "结果寄存器返回未知状态码",
                                    spec = spec,
                                    elapsedMs = elapsedMs,
                                    resultValue = code,
                                    hasObservedRunning = hasObservedRunning
                                )
                            ),
                            startedAtMs
                        )
                    }
                }
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        if (requireFreshStateBeforeSuccess && !hasObservedFreshState) {
            return finishWithTelemetry(
                spec,
                AtomicActionResult.Timeout(
                    structuredFailureMessage(
                        responsibility = "下位机未启动或未回写执行中",
                        stage = "整体执行超时",
                        spec = spec,
                        elapsedMs = elapsedMs,
                        resultValue = AtomicActionCodes.IDLE,
                        hasObservedRunning = false
                    )
                ),
                startedAtMs
            )
        }
        return finishWithTelemetry(
            spec,
            AtomicActionResult.Timeout(
                structuredFailureMessage(
                    responsibility = if (hasObservedRunning) {
                        "下位机执行超时未回写完成"
                    } else {
                        "下位机未启动或未回写执行中"
                    },
                    stage = "整体执行超时",
                    spec = spec,
                    elapsedMs = elapsedMs,
                    hasObservedRunning = hasObservedRunning
                )
            ),
            startedAtMs
        )
    }

    private suspend fun buildStartupNotConfirmedMessage(
        spec: AtomicActionSpec,
        elapsedMs: Long,
        resultCode: Int
    ): String {
        return buildStartupNotConfirmedMessage(spec, elapsedMs, resultCode, gateway.readRegister(spec.commandRegister))
    }

    private fun buildStartupNotConfirmedMessage(
        spec: AtomicActionSpec,
        elapsedMs: Long,
        resultCode: Int,
        commandRead: AtomicReadResult
    ): String {
        return when (commandRead) {
            is AtomicReadResult.Success -> {
                logger(
                    "启动确认失败诊断：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "commandRegister（命令寄存器）=${spec.commandRegister}，当前命令=${commandValueDesc(commandRead.value)}，" +
                        "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(resultCode)}，elapsedMs=$elapsedMs"
                )
                when (commandRead.value) {
                    AtomicActionCodes.IDLE -> {
                        structuredFailureMessage(
                            responsibility = "疑似下位机复位或命令丢失",
                            stage = "启动确认超时",
                            spec = spec,
                            elapsedMs = elapsedMs,
                            commandValue = commandRead.value,
                            resultValue = resultCode,
                            hasObservedRunning = false
                        )
                    }
                    AtomicActionCodes.RUNNING -> {
                        structuredFailureMessage(
                            responsibility = "下位机长时间未接收命令",
                            stage = "启动确认超时",
                            spec = spec,
                            elapsedMs = elapsedMs,
                            commandValue = commandRead.value,
                            resultValue = resultCode,
                            hasObservedRunning = false
                        )
                    }
                    else -> {
                        structuredFailureMessage(
                            responsibility = "下位机未启动或未回写执行中",
                            stage = "启动确认超时",
                            spec = spec,
                            elapsedMs = elapsedMs,
                            commandValue = commandRead.value,
                            resultValue = resultCode,
                            hasObservedRunning = false
                        )
                    }
                }
            }
            is AtomicReadResult.Failure -> {
                logger(
                    "启动确认失败诊断：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "commandRegister（命令寄存器）=${spec.commandRegister}读取失败，" +
                        "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(resultCode)}，" +
                        "message=${commandRead.message}，elapsedMs=$elapsedMs"
                )
                structuredFailureMessage(
                    responsibility = "上位机与下位机通信读取异常",
                    stage = "启动确认读取命令寄存器失败",
                    spec = spec,
                    elapsedMs = elapsedMs,
                    resultValue = resultCode,
                    hasObservedRunning = false,
                    extraParts = listOf("异常信息=${commandRead.message}")
                )
            }
        }
    }

    private fun structuredFailureMessage(
        responsibility: String,
        stage: String,
        spec: AtomicActionSpec,
        elapsedMs: Long? = null,
        commandValue: Int? = null,
        resultValue: Int? = null,
        infoValue: Int? = null,
        hasObservedRunning: Boolean? = null,
        extraParts: List<String> = emptyList()
    ): String {
        val snapshot = diagnosisSnapshotProvider()
        val parts = mutableListOf(
            "初判责任=$responsibility",
            "失败阶段=$stage",
            "动作=${spec.name}",
            "动作说明=${spec.description.substringAfter('：', spec.description)}"
        )
        if (commandValue != null) {
            parts += "命令寄存器=${spec.commandRegister}"
            parts += "命令值=${commandValueDesc(commandValue)}"
        }
        parts += "结果寄存器=${spec.resultRegister}"
        if (resultValue != null) {
            parts += "结果值=${resultCodeDesc(resultValue)}"
        }
        if (infoValue != null) {
            parts += "信息寄存器=${spec.infoRegister}"
            parts += "信息值=$infoValue"
        }
        if (hasObservedRunning != null) {
            parts += "已观察到执行中=${if (hasObservedRunning) "是" else "否"}"
        }
        if (elapsedMs != null) {
            parts += "耗时=${formatElapsedSeconds(elapsedMs)}秒"
        }
        parts += "连接状态=${if (snapshot.connected) "已连接" else "未连接"}"
        parts += "串口地址=${snapshot.port.ifBlank { "未知" }}"
        parts += "波特率=${snapshot.baudRate}"
        if (modbusServerAddress != null) {
            parts += "从站地址=$modbusServerAddress"
        }
        parts += extraParts
        return truncateDiagnosis(parts.joinToString("，"))
    }

    private fun truncateDiagnosis(text: String): String {
        return if (text.length <= MAX_ACTION_FAILURE_DIAGNOSIS_LENGTH) {
            text
        } else {
            text.take(MAX_ACTION_FAILURE_DIAGNOSIS_LENGTH - 3) + "..."
        }
    }

    private fun formatElapsedSeconds(elapsedMs: Long): String {
        return String.format(java.util.Locale.US, "%.1f", elapsedMs / 1000.0)
    }

    private fun logStartupNotConfirmedFailure(spec: AtomicActionSpec, elapsedMs: Long, resultCode: Int) {
        logger(
            "动作未确认启动，未观察到0x0001（执行中）：sessionId=${telemetryContext.sessionId}，" +
                "action=${spec.name}，resultRegister（结果寄存器）=${spec.resultRegister}，" +
                "当前结果=${resultCodeDesc(resultCode)}，elapsedMs=$elapsedMs"
        )
        logger(
            "动作启动失败：${spec.name}（${spec.description}）下位机未确认执行，" +
                "结果寄存器${spec.resultRegister}未观察到0x0001（执行中）"
        )
    }

    private fun finishWithTelemetry(
        spec: AtomicActionSpec,
        result: AtomicActionResult,
        startedAtMs: Long
    ): AtomicActionResult {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        logger(
            "原子动作完成：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                "success=${result == AtomicActionResult.Success}，result=${describeResult(result)}，elapsedMs=$elapsedMs"
        )
        val (errorCode, errorMessage) = when (result) {
            AtomicActionResult.Success -> null to null
            is AtomicActionResult.Failed -> result.errorCode to result.message
            is AtomicActionResult.ModbusError -> null to result.message
            is AtomicActionResult.Timeout -> null to result.message
        }
        telemetrySink(
            ActionGroupTelemetry(
                sessionId = telemetryContext.sessionId,
                actionName = spec.name,
                registerAddress = spec.commandRegister,
                resultRegister = spec.resultRegister,
                errorCode = errorCode,
                errorMessage = errorMessage,
                boxNo = telemetryContext.boxNo,
                panNo = telemetryContext.panNo,
                orderId = telemetryContext.orderId,
                resourceLocks = telemetryContext.resourceLocks,
                elapsedMs = elapsedMs
            )
        )
        return result
    }

    private fun describeResult(result: AtomicActionResult): String {
        return when (result) {
            AtomicActionResult.Success -> "0x0002（完成）"
            is AtomicActionResult.Failed -> "${formatCode(result.errorCode)}（${result.message}）"
            is AtomicActionResult.ModbusError -> "MODBUS_ERROR（${result.message}）"
            is AtomicActionResult.Timeout -> "TIMEOUT（${result.message}）"
        }
    }

    companion object {
        private const val RUNNING_PROGRESS_LOG_INTERVAL_MS = 5_000L
        private const val STARTUP_CONFIRM_TIMEOUT_MS = 3_000L
        private const val COMMAND_PENDING_CONFIRM_TIMEOUT_MS = 40_000L
        private const val MAX_ACTION_FAILURE_DIAGNOSIS_LENGTH = 300

        fun formatCode(code: Int): String = "0x${code.toString(16).uppercase().padStart(4, '0')}"

        fun commandValueDesc(code: Int): String {
            return when (code) {
                AtomicActionCodes.IDLE -> "0x0000（空闲/已清零）"
                AtomicActionCodes.RUNNING -> "0x0001（请求执行）"
                else -> "${formatCode(code)}（当前值）"
            }
        }

        fun resultCodeDesc(code: Int): String {
            return when (code) {
                AtomicActionCodes.IDLE -> "0x0000（空闲/未启动）"
                AtomicActionCodes.RUNNING -> "0x0001（执行中）"
                AtomicActionCodes.COMPLETED -> "0x0002（完成）"
                else -> "${formatCode(code)}（当前值）"
            }
        }

        fun messageOf(code: Int): String = when (code) {
            AtomicActionCodes.PARAM_ERROR -> "参数错误"
            AtomicActionCodes.X_RESET_FAILED -> "通用 X 复位失败"
            AtomicActionCodes.Y_RESET_FAILED -> "通用 Y 复位失败"
            AtomicActionCodes.LIFT_FAILED -> "通用升降台错误"
            AtomicActionCodes.IR_PICK_FAILED -> "下平台推动取肠红外未检测到肠"
            AtomicActionCodes.IR_THROW_FAILED -> "下平台抛肠后残肠红外失败"
            AtomicActionCodes.STICK_FAILED -> "穿签机械失败，签杆推出超时"
            AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED -> "售卖口未检测到烤肠（出肠检测失败）"
            AtomicActionCodes.STICK_EMPTY -> "穿签插空，签杆推出但疑似未穿到肠"
            AtomicActionCodes.BUSY -> "忙碌错误"
            AtomicActionCodes.IN_SOURCE_SLOT_PARAM -> "in1/in2 源肠箱编号参数非法"
            AtomicActionCodes.IN7_TARGET_SLOT_PARAM -> "in7 目标烤位参数非法"
            AtomicActionCodes.OUT_SLOT_PARAM -> "out1 目标烤位参数非法"
            AtomicActionCodes.IN1_X_RESET_FAILED -> "in1 的 X 复位失败"
            AtomicActionCodes.IN1_Y_RESET_FAILED -> "in1 的 Y 复位失败"
            AtomicActionCodes.IN3_X_RESET_FAILED -> "in3 的 X 复位失败"
            AtomicActionCodes.IN3_Y_RESET_FAILED -> "in3 的 Y 复位失败"
            AtomicActionCodes.LIFT_DOWN_LOCKED_ROTOR -> "升降台下降堵转"
            AtomicActionCodes.LIFT_UP_LOCKED_ROTOR -> "升降台上升堵转"
            AtomicActionCodes.LIFT_NOT_BOTTOM -> "需要抛肠时升降台不在底位"
            AtomicActionCodes.IN7_SAU_ADD_BUSY -> "in7 调上平台放肠流程时上平台忙或状态异常"
            AtomicActionCodes.OUT_UNSUPPORTED_ACTION -> "不支持的 400 动作"
            AtomicActionCodes.BOTTOM_UNSUPPORTED_ACTION -> "不支持的下平台 300 动作"
            AtomicActionCodes.MOVE_PARAM_ERROR -> "搬盘参数非法"
            AtomicActionCodes.MOVE_BUSY -> "搬盘机构忙"
            AtomicActionCodes.MOVE_TIMEOUT -> "搬盘超时"
            AtomicActionCodes.MOVE_STATE_ERROR -> "搬盘内部状态异常"
            else -> "未知错误码 ${formatCode(code)}"
        }
    }
}
