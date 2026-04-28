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
    private val logger: (String) -> Unit = {}
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
        return executeSteps(client, steps, resumeStartIndex(steps, client, logger), logger)
    }
}

class OutActionGroupRunner(
    private val client: AtomicActionClient,
    private val logger: (String) -> Unit = {}
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
        return executeSteps(client, steps, resumeStartIndex(steps, client, logger), logger)
    }
}

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
    logger: (String) -> Unit
): ActionGroupResult {
    for (step in steps.drop(startIndex)) {
        val result = if (step.retryableErrorCode == null) {
            mapActionResult(step, client.execute(step.spec, step.infoValue))
        } else {
            executeRetriableStep(client, step, logger)
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
