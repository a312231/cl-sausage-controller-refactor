package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import kotlinx.coroutines.delay

sealed class ActionGroupResult {
    data object Success : ActionGroupResult()
    data object Discarded : ActionGroupResult()
    data class Failed(
        val actionName: String,
        val result: AtomicActionResult,
        val message: String
    ) : ActionGroupResult()
}

class InActionGroupRunner(
    private val client: AtomicActionClient,
    private val logger: (String) -> Unit = {},
    private val operationLogContext: ActionGroupOperationLogContext? = null,
    private val operationLogger: (String) -> Unit = {},
    private val failureLogger: (String) -> Unit = operationLogger
) {
    suspend fun run(
        sourceBoxNo: Int,
        targetPanNo: Int,
        resumeFromRunningState: Boolean = false
    ): ActionGroupResult {
        if (!SourceBoxPositionPolicy.isValidSourceBox(sourceBoxNo)) {
            return ActionGroupResult.Failed(
                "in",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "源肠箱编号必须在 ${SourceBoxPositionPolicy.validRangeText()}，当前=$sourceBoxNo"
            )
        }
        if (targetPanNo !in 1..33) {
            return ActionGroupResult.Failed(
                "in",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "目标烤盘编号必须在 1..33，当前=$targetPanNo"
            )
        }

        val steps = inActionSteps(sourceBoxNo, targetPanNo)
        logger(
            "补肠动作组计划：sourceBox=$sourceBoxNo，targetPan=$targetPanNo，" +
                "resumeFromRunningState（是否按恢复模式跳过已完成步骤）=$resumeFromRunningState，" +
                "steps=${steps.joinToString(" -> ") { it.spec.description }}"
        )
        return executeSteps(
            client = client,
            steps = steps,
            startIndex = resumeStartIndex(steps, client, logger, resumeFromRunningState),
            logger = logger,
            operationLogContext = operationLogContext,
            operationLogger = operationLogger,
            failureLogger = failureLogger
        )
    }
}

class OutActionGroupRunner(
    private val client: AtomicActionClient,
    private val logger: (String) -> Unit = {},
    private val operationLogContext: ActionGroupOperationLogContext? = null,
    private val operationLogger: (String) -> Unit = {},
    private val failureLogger: (String) -> Unit = operationLogger
) {
    suspend fun run(
        sourcePanNo: Int,
        resumeFromRunningState: Boolean = false,
        enableVoiceReminder: Boolean = true,
        onWindowOpened: suspend () -> Unit = {},
        windowOpenBufferMs: Long = 1_000L
    ): ActionGroupResult {
        if (sourcePanNo !in 1..33) {
            return ActionGroupResult.Failed(
                "out",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "源烤盘编号必须在 1..33，当前=$sourcePanNo"
            )
        }

        val steps = outActionSteps(sourcePanNo)
        logger(
            "出餐动作组计划：sourcePan=$sourcePanNo，检测方式=out4 收口结果，" +
                "resumeFromRunningState（是否按恢复模式跳过已完成步骤）=$resumeFromRunningState，" +
                "steps=${steps.joinToString(" -> ") { it.spec.description }}"
        )
        return executeSteps(
            client = client,
            steps = steps,
            startIndex = resumeStartIndex(steps, client, logger, resumeFromRunningState),
            logger = logger,
            operationLogContext = operationLogContext,
            operationLogger = operationLogger,
            failureLogger = failureLogger,
            enableVoiceReminder = enableVoiceReminder,
            onWindowOpened = onWindowOpened,
            windowOpenBufferMs = windowOpenBufferMs
        )
    }
}

data class ActionGroupOperationLogContext(
    val category: String,
    val subject: String? = null
)

internal data class ActionStep(
    val spec: AtomicActionSpec,
    val infoValue: Int,
    val retryableErrorCode: Int? = null,
    val maxAttempts: Int = 3
)

internal fun inActionSteps(sourceBoxNo: Int, targetPanNo: Int): List<ActionStep> {
    return listOf(
        ActionStep(AtomicActions.in1, sourceBoxNo),
        ActionStep(AtomicActions.in2, sourceBoxNo, AtomicActionCodes.IR_PICK_FAILED, maxAttempts = 2),
        ActionStep(AtomicActions.in3, 0),
        ActionStep(AtomicActions.in4, 0),
        ActionStep(AtomicActions.in5, 0, AtomicActionCodes.IR_THROW_FAILED),
        ActionStep(AtomicActions.in6, 0),
        ActionStep(AtomicActions.in7, targetPanNo)
    )
}

internal fun outActionSteps(sourcePanNo: Int): List<ActionStep> {
    return listOf(
        ActionStep(AtomicActions.out1, sourcePanNo),
        ActionStep(AtomicActions.out2, 0),
        ActionStep(AtomicActions.out3, 0),
        ActionStep(AtomicActions.out4, 0),
        ActionStep(AtomicActions.out5, 0)
    )
}

private suspend fun executeSteps(
    client: AtomicActionClient,
    steps: List<ActionStep>,
    startIndex: Int,
    logger: (String) -> Unit,
    operationLogContext: ActionGroupOperationLogContext?,
    operationLogger: (String) -> Unit,
    failureLogger: (String) -> Unit,
    enableVoiceReminder: Boolean = false,
    onWindowOpened: suspend () -> Unit = {},
    windowOpenBufferMs: Long = 0L
): ActionGroupResult {
    for (step in steps.drop(startIndex)) {
        logger(
            "动作组步骤开始：action=${step.spec.name}，description=${step.spec.description}，" +
                "infoValue=${step.infoValue}，retryableErrorCode=${step.retryableErrorCode?.let { AtomicActionExecutor.formatCode(it) } ?: "NONE"}"
        )
        val stepStartedAtMs = System.currentTimeMillis()
        operationLogContext?.let { context ->
            operationLogger(formatOperationStepStart(context, step.spec))
        }
        val dispatchHook: (suspend () -> Unit)? = if (step.spec == AtomicActions.out4 && enableVoiceReminder) {
            {
                try {
                    onWindowOpened()
                } catch (t: Throwable) {
                    logger("【取肠语音】hook 异常：message=${t.message ?: t.javaClass.simpleName}")
                }
            }
        } else {
            null
        }
        val result = if (step.retryableErrorCode == null) {
            mapActionResult(step, client.execute(step.spec, step.infoValue, dispatchHook))
        } else {
            executeRetriableStep(client, step, logger)
        }
        val elapsedMs = (System.currentTimeMillis() - stepStartedAtMs).coerceAtLeast(0L)
        logger(
            "动作组步骤结束：action=${step.spec.name}，description=${step.spec.description}，" +
                "result=${actionGroupResultDesc(result)}"
        )
        operationLogContext?.let { context ->
            when (result) {
                ActionGroupResult.Success,
                ActionGroupResult.Discarded -> operationLogger(formatOperationStepFinished(context, step.spec, elapsedMs))
                is ActionGroupResult.Failed -> failureLogger(formatOperationStepFailed(context, step.spec, result))
            }
        }
        if (step.spec == AtomicActions.out4 && result == ActionGroupResult.Success) {
            if (windowOpenBufferMs > 0L) {
                logger("out4 已收口，等待 ${windowOpenBufferMs}ms 后进入 out5 关门回零")
                delay(windowOpenBufferMs)
            }
        }
        when (result) {
            ActionGroupResult.Success -> Unit
            ActionGroupResult.Discarded,
            is ActionGroupResult.Failed -> return result
        }
    }
    return ActionGroupResult.Success
}

private suspend fun executeRetriableStep(
    client: AtomicActionClient,
    step: ActionStep,
    logger: (String) -> Unit
): ActionGroupResult {
    var lastResult: AtomicActionResult = AtomicActionResult.Success
    for (attempt in 1..step.maxAttempts) {
        val result = client.execute(step.spec, step.infoValue)
        lastResult = result
        if (result == AtomicActionResult.Success) {
            return ActionGroupResult.Success
        }
        if (!isRetryable(result, step.retryableErrorCode!!) || attempt == step.maxAttempts) {
            return mapActionResult(step, result)
        }
        logger("重试原子动作：action=${step.spec.name}，attempt=$attempt/${step.maxAttempts}，reason=${describe(result)}")
    }
    return mapActionResult(step, lastResult)
}

private fun mapActionResult(step: ActionStep, result: AtomicActionResult): ActionGroupResult {
    return when {
        result == AtomicActionResult.Success -> ActionGroupResult.Success
        else -> ActionGroupResult.Failed(step.spec.name, result, "${step.spec.description}失败：${describe(result)}")
    }
}

private suspend fun resumeStartIndex(
    steps: List<ActionStep>,
    client: AtomicActionClient,
    logger: (String) -> Unit,
    resumeFromRunningState: Boolean
): Int {
    if (!resumeFromRunningState) {
        return 0
    }
    val reader = client as? AtomicActionStateReader ?: return 0
    val states = steps.map { step -> step to reader.readResult(step.spec) }
    states.forEachIndexed { index, (step, state) ->
        if (state is AtomicReadResult.Success && state.value == AtomicActionCodes.RUNNING) {
            logger("动作组恢复接管：从 ${step.spec.name} 开始轮询，跳过此前已非执行中的动作")
            return index
        }
    }
    states.forEachIndexed { index, (step, state) ->
        when (state) {
            is AtomicReadResult.Failure -> Unit
            is AtomicReadResult.Success -> {
                if (state.value != AtomicActionCodes.COMPLETED) {
                    if (index > 0) {
                        logger("动作组恢复接管：跳过前 $index 个已完成动作，从 ${step.spec.name} 继续执行")
                    }
                    return index
                }
            }
        }
    }
    logger("动作组恢复接管：所有步骤结果寄存器均为0x0002（完成），无需重新下发动作")
    return steps.size
}

private fun isRetryable(result: AtomicActionResult, retryableErrorCode: Int): Boolean {
    return result is AtomicActionResult.Failed && result.errorCode == retryableErrorCode
}

private fun describe(result: AtomicActionResult): String {
    return when (result) {
        AtomicActionResult.Success -> "成功"
        is AtomicActionResult.Failed -> "${AtomicActionExecutor.formatCode(result.errorCode)}（${result.message}）"
        is AtomicActionResult.ModbusError -> "Modbus 读写失败：${result.message}"
        is AtomicActionResult.Timeout -> "执行超时：${result.message}"
    }
}

private fun formatOperationStepStart(
    context: ActionGroupOperationLogContext,
    spec: AtomicActionSpec
): String {
    return "${operationPrefix(context)}${actionLabel(spec)}开始"
}

private fun formatOperationStepFinished(
    context: ActionGroupOperationLogContext,
    spec: AtomicActionSpec,
    elapsedMs: Long
): String {
    val actionText = if (context.category == "补肠" && spec.name == "in7") {
        actionLabel(spec)
    } else {
        spec.name
    }
    val suffix = if (context.category == "补肠" && spec.name == "in7") {
        "，烤盘开始烤制计时"
    } else {
        ""
    }
    return "${operationPrefix(context)}$actionText 完成$suffix，用时=${formatElapsedSeconds(elapsedMs)}秒"
}

private fun formatOperationStepFailed(
    context: ActionGroupOperationLogContext,
    spec: AtomicActionSpec,
    result: ActionGroupResult.Failed
): String {
    val prefix = "【失败原因】${context.subject?.let { "$it " } ?: ""}"
    val actionText = actionLabel(spec)
    val atomicResult = result.result
    val reason = if (atomicResult is AtomicActionResult.Timeout &&
        atomicResult.message.contains("未确认启动")
    ) {
        AtomicActionFailureFormatter.startupNotConfirmedText(actionText, spec.resultRegister, atomicResult.message)
    } else {
        "${actionText}失败：${describe(atomicResult)}"
    }
    return "$prefix$reason"
}

private fun operationPrefix(context: ActionGroupOperationLogContext): String {
    val subject = context.subject?.let { "$it " } ?: ""
    return "【${context.category}】$subject"
}

private fun actionLabel(spec: AtomicActionSpec): String {
    return "${spec.name}（${spec.description.substringAfter('：', spec.description)}）"
}

private fun formatElapsedSeconds(elapsedMs: Long): String {
    return String.format(java.util.Locale.US, "%.1f", elapsedMs / 1000.0)
}

private fun actionGroupResultDesc(result: ActionGroupResult): String {
    return when (result) {
        ActionGroupResult.Success -> "SUCCESS（动作成功）"
        ActionGroupResult.Discarded -> "DISCARDED（历史兼容结果，4F 主流程不再产生）"
        is ActionGroupResult.Failed -> "FAILED（${result.actionName}：${result.message}）"
    }
}
