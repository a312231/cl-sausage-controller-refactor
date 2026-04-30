package cn.niuyannet.kaochang.android.services.actiongroup

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
    suspend fun run(sourceBoxNo: Int, targetPanNo: Int): ActionGroupResult {
        if (sourceBoxNo !in 1..22) {
            return ActionGroupResult.Failed(
                "in",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "源肠箱编号必须在 1..22，当前=$sourceBoxNo"
            )
        }
        if (targetPanNo !in 1..33) {
            return ActionGroupResult.Failed(
                "in",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "目标烤盘编号必须在 1..33，当前=$targetPanNo"
            )
        }

        val steps = listOf(
            ActionStep(AtomicActions.in1, sourceBoxNo),
            ActionStep(AtomicActions.in2, sourceBoxNo, AtomicActionCodes.IR_PICK_FAILED),
            ActionStep(AtomicActions.in3, 0),
            ActionStep(AtomicActions.in4, 0),
            ActionStep(AtomicActions.in5, 0, AtomicActionCodes.IR_THROW_FAILED),
            ActionStep(AtomicActions.in6, 0),
            ActionStep(AtomicActions.in7, targetPanNo)
        )
        logger(
            "补肠动作组计划：sourceBox=$sourceBoxNo，targetPan=$targetPanNo，" +
                "steps=${steps.joinToString(" -> ") { it.spec.description }}"
        )
        return executeSteps(
            client = client,
            steps = steps,
            startIndex = resumeStartIndex(steps, client, logger),
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
    suspend fun run(sourcePanNo: Int): ActionGroupResult {
        if (sourcePanNo !in 1..33) {
            return ActionGroupResult.Failed(
                "out",
                AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                "源烤盘编号必须在 1..33，当前=$sourcePanNo"
            )
        }

        val steps = listOf(
            ActionStep(AtomicActions.out1, sourcePanNo),
            ActionStep(AtomicActions.out2, 0, AtomicActionCodes.STICK_FAILED),
            ActionStep(AtomicActions.out3, 0),
            ActionStep(AtomicActions.out4, 0),
            ActionStep(AtomicActions.out5, 0, discardedOnCustomerTimeout = true)
        )
        logger(
            "出餐动作组计划：sourcePan=$sourcePanNo，检测方式=out5 光电传感器，" +
                "steps=${steps.joinToString(" -> ") { it.spec.description }}"
        )
        return executeSteps(
            client = client,
            steps = steps,
            startIndex = resumeStartIndex(steps, client, logger),
            logger = logger,
            operationLogContext = operationLogContext,
            operationLogger = operationLogger,
            failureLogger = failureLogger
        )
    }
}

data class ActionGroupOperationLogContext(
    val category: String,
    val subject: String? = null
)

private data class ActionStep(
    val spec: AtomicActionSpec,
    val infoValue: Int,
    val retryableErrorCode: Int? = null,
    val discardedOnCustomerTimeout: Boolean = false
)

private suspend fun executeSteps(
    client: AtomicActionClient,
    steps: List<ActionStep>,
    startIndex: Int,
    logger: (String) -> Unit,
    operationLogContext: ActionGroupOperationLogContext?,
    operationLogger: (String) -> Unit,
    failureLogger: (String) -> Unit
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
        val result = if (step.retryableErrorCode == null) {
            mapActionResult(step, client.execute(step.spec, step.infoValue))
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
    for (attempt in 1..3) {
        val result = client.execute(step.spec, step.infoValue)
        lastResult = result
        if (result == AtomicActionResult.Success) {
            return ActionGroupResult.Success
        }
        if (!isRetryable(result, step.retryableErrorCode!!) || attempt == 3) {
            return mapActionResult(step, result)
        }
        logger("重试原子动作：action=${step.spec.name}，attempt=$attempt/3，reason=${describe(result)}")
    }
    return mapActionResult(step, lastResult)
}

private fun mapActionResult(step: ActionStep, result: AtomicActionResult): ActionGroupResult {
    return when {
        result == AtomicActionResult.Success -> ActionGroupResult.Success
        step.discardedOnCustomerTimeout &&
            result is AtomicActionResult.Failed &&
            result.errorCode == AtomicActionCodes.CLIENT_TAKE_TIMEOUT_DISCARDED -> ActionGroupResult.Discarded
        else -> ActionGroupResult.Failed(step.spec.name, result, "${step.spec.description}失败：${describe(result)}")
    }
}

private suspend fun resumeStartIndex(
    steps: List<ActionStep>,
    client: AtomicActionClient,
    logger: (String) -> Unit
): Int {
    val reader = client as? AtomicActionStateReader ?: return 0
    steps.forEachIndexed { index, step ->
        when (val state = reader.readResult(step.spec)) {
            is AtomicReadResult.Failure -> Unit
            is AtomicReadResult.Success -> {
                if (state.value == AtomicActionCodes.RUNNING) {
                    logger("动作组恢复接管：从 ${step.spec.name} 开始轮询，跳过此前已非执行中的动作")
                    return index
                }
            }
        }
    }
    return 0
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
        "${actionText}启动失败：下位机未确认执行，结果寄存器${spec.resultRegister}一直为0x0000（旧成功/空闲）"
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
        ActionGroupResult.Discarded -> "DISCARDED（顾客超时未取，out5 已自动丢弃）"
        is ActionGroupResult.Failed -> "FAILED（${result.actionName}：${result.message}）"
    }
}
