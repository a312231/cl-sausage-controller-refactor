package cn.niuyannet.kaochang.android.services.actiongroup

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
    const val CLIENT_TAKE_TIMEOUT_DISCARDED = 0x00E8
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
    val in1 = AtomicActionSpec("in1", 310, 311, 312, 60_000L, 200L, "in1：移动到肠箱预推送位置")
    val in2 = AtomicActionSpec("in2", 320, 321, 322, 60_000L, 200L, "in2：取肠、分料、避让")
    val in3 = AtomicActionSpec("in3", 330, 331, 332, 60_000L, 200L, "in3：回到抛肠点")
    val in4 = AtomicActionSpec("in4", 340, 341, 342, 60_000L, 500L, "in4：升降台下降")
    val in5 = AtomicActionSpec("in5", 350, 351, 352, 60_000L, 300L, "in5：抛肠并检测是否抛出成功")
    val in6 = AtomicActionSpec("in6", 360, 361, 362, 60_000L, 500L, "in6：升降台上升")
    val in7 = AtomicActionSpec("in7", 370, 371, 372, 120_000L, 500L, "in7：上平台从升降台夹肠并放入烤盘")

    val out1 = AtomicActionSpec("out1", 410, 411, 412, 120_000L, 500L, "out1：从烤盘取熟肠到穿签位")
    val out2 = AtomicActionSpec("out2", 420, 421, 422, 90_000L, 500L, "out2：执行穿签")
    val out3 = AtomicActionSpec("out3", 430, 431, 432, 90_000L, 500L, "out3：从穿签位放到预推送位置")
    val out4 = AtomicActionSpec("out4", 440, 441, 442, 90_000L, 500L, "out4：开窗并推送")
    val out5 = AtomicActionSpec("out5", 450, 451, 452, 90_000L, 500L, "out5：检查顾客是否取走，超时未取由下位机自动丢弃")
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

enum class AtomicStartupConfirmPolicy {
    STRICT,
    SOFT_SUCCESS_WHEN_COMMAND_STILL_PENDING
}

object AtomicActionFailureFormatter {
    fun startupNotConfirmedText(actionText: String, resultRegister: Int, message: String): String {
        val resetHint = if (message.contains("疑似下位机复位/命令丢失")) {
            "，疑似下位机复位/命令丢失"
        } else {
            ""
        }
        val commandDiagnostic = commandDiagnosticText(message)
        return "${actionText}启动失败：下位机未确认执行，" +
            "结果寄存器${resultRegister}未观察到0x0001（执行中）" +
            resetHint +
            commandDiagnostic
    }

    private fun commandDiagnosticText(message: String): String {
        val commandRegister = Regex("commandRegister=(\\d+)").find(message)?.groupValues?.getOrNull(1)
        val currentCommand = Regex("currentCommand=(0x[0-9A-Fa-f]{4})").find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase()
        if (commandRegister == null || currentCommand == null) {
            return ""
        }
        val valueText = if (currentCommand == "0X0000") {
            "0x0000（空闲/已清零）"
        } else {
            currentCommand.lowercase()
        }
        return "，命令寄存器${commandRegister}当前=$valueText"
    }
}

interface AtomicModbusGateway {
    suspend fun writeRegister(address: Int, value: Int): AtomicIoResult
    suspend fun readRegister(address: Int): AtomicReadResult
}

interface AtomicActionClient {
    suspend fun execute(
        spec: AtomicActionSpec,
        infoValue: Int = 0,
        onCommandDispatched: (() -> Unit)? = null
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
    @Suppress("UNUSED_PARAMETER")
    startupConfirmPolicy: AtomicStartupConfirmPolicy = AtomicStartupConfirmPolicy.STRICT
) : AtomicActionClient, AtomicActionStateReader {

    override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
        return gateway.readRegister(spec.resultRegister)
    }

    override suspend fun execute(
        spec: AtomicActionSpec,
        infoValue: Int,
        onCommandDispatched: (() -> Unit)?
    ): AtomicActionResult {
        val startedAtMs = System.currentTimeMillis()
        return when (val current = readResult(spec)) {
            is AtomicReadResult.Failure -> finishWithTelemetry(
                spec = spec,
                result = AtomicActionResult.ModbusError(
                    "${spec.name} 启动前读取结果寄存器 ${spec.resultRegister} 失败：${current.message}"
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
                        hasAlreadyObservedRunning = true
                    )
                } else {
                    startAndPoll(spec, infoValue, startedAtMs, current.value, onCommandDispatched)
                }
            }
        }
    }

    private suspend fun startAndPoll(
        spec: AtomicActionSpec,
        infoValue: Int,
        startedAtMs: Long,
        startupResultCode: Int,
        onCommandDispatched: (() -> Unit)?
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
                        "${spec.name} 写信息寄存器 ${spec.infoRegister} 失败：${writeInfo.message}"
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
                        "${spec.name} 写命令寄存器 ${spec.commandRegister} 失败：${writeCommand.message}"
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
            requireFreshStateBeforeSuccess = startupResultCode == AtomicActionCodes.COMPLETED,
            hasAlreadyObservedRunning = false
        )
    }

    private suspend fun pollUntilFinish(
        spec: AtomicActionSpec,
        startedAtMs: Long,
        requireFreshStateBeforeSuccess: Boolean,
        hasAlreadyObservedRunning: Boolean
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
                    "${spec.name} 读取结果寄存器 ${spec.resultRegister} 失败：${read.message}"
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
                        code == AtomicActionCodes.COMPLETED && hasObservedFreshState -> return finishWithTelemetry(
                            spec,
                            AtomicActionResult.Success,
                            startedAtMs
                        )
                        code == AtomicActionCodes.IDLE && hasObservedRunning -> {
                            return finishWithTelemetry(
                                spec,
                                AtomicActionResult.Timeout(
                                    "${spec.name} 执行中后返回${resultCodeDesc(code)}，" +
                                        "未返回0x0002（完成）：resultRegister=${spec.resultRegister}，elapsedMs=$elapsedMs"
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
                            AtomicActionResult.Failed(code, messageOf(code)),
                            startedAtMs
                        )
                        else -> return finishWithTelemetry(
                            spec,
                            AtomicActionResult.ModbusError("${spec.name} 未知结果码：${formatCode(code)}"),
                            startedAtMs
                        )
                    }
                }
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        if (requireFreshStateBeforeSuccess && !hasObservedFreshState) {
            val message = buildStartupNotConfirmedMessage(spec, elapsedMs, AtomicActionCodes.IDLE)
            logStartupNotConfirmedFailure(spec, elapsedMs, AtomicActionCodes.IDLE)
            return finishWithTelemetry(
                spec,
                AtomicActionResult.Timeout(message),
                startedAtMs
            )
        }
        return finishWithTelemetry(
            spec,
            AtomicActionResult.Timeout("${spec.name} 执行超时：timeoutMs=${spec.timeoutMs}"),
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
                val currentCommand = formatCode(commandRead.value)
                logger(
                    "启动确认失败诊断：sessionId=${telemetryContext.sessionId}，action=${spec.name}，" +
                        "commandRegister（命令寄存器）=${spec.commandRegister}，当前命令=${commandValueDesc(commandRead.value)}，" +
                        "resultRegister（结果寄存器）=${spec.resultRegister}，当前结果=${resultCodeDesc(resultCode)}，elapsedMs=$elapsedMs"
                )
                when (commandRead.value) {
                    AtomicActionCodes.IDLE -> {
                        "${spec.name} 动作未确认启动，疑似下位机复位/命令丢失：" +
                            "resultRegister=${spec.resultRegister}，commandRegister=${spec.commandRegister}，" +
                            "currentCommand=$currentCommand，currentResult=${formatCode(resultCode)}，elapsedMs=$elapsedMs"
                    }
                    AtomicActionCodes.RUNNING -> {
                        "${spec.name} 动作未确认启动，下位机长时间未接收命令：" +
                            "resultRegister=${spec.resultRegister}，commandRegister=${spec.commandRegister}，" +
                            "currentCommand=$currentCommand，currentResult=${formatCode(resultCode)}，elapsedMs=$elapsedMs"
                    }
                    else -> {
                        "${spec.name} 动作未确认启动，未观察到0x0001（执行中）：" +
                            "resultRegister=${spec.resultRegister}，commandRegister=${spec.commandRegister}，" +
                            "currentCommand=$currentCommand，currentResult=${formatCode(resultCode)}，elapsedMs=$elapsedMs"
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
                "${spec.name} 动作未确认启动，未观察到0x0001（执行中）：" +
                    "resultRegister=${spec.resultRegister}，commandRegister=${spec.commandRegister}读取失败，" +
                    "currentResult=${formatCode(resultCode)}，elapsedMs=$elapsedMs"
            }
        }
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
            AtomicActionCodes.CLIENT_TAKE_TIMEOUT_DISCARDED -> "顾客取走相关错误"
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
            else -> "未知错误码 ${formatCode(code)}"
        }
    }
}
