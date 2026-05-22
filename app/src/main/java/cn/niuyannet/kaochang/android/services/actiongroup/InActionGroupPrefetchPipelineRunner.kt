package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class InActionGroupPipelineTask(
    val sourceBoxCandidates: List<Int>,
    val targetPanNo: Int
)

data class CompletedInActionGroupPipelineTask(
    val task: InActionGroupPipelineTask,
    val sourceBoxNo: Int,
    val targetPanNo: Int
)

sealed class InActionGroupPipelineResult {
    data class Success(
        val completed: List<CompletedInActionGroupPipelineTask>
    ) : InActionGroupPipelineResult()

    data class Paused(
        val completed: List<CompletedInActionGroupPipelineTask>
    ) : InActionGroupPipelineResult()

    data class Failed(
        val completed: List<CompletedInActionGroupPipelineTask>,
        val task: InActionGroupPipelineTask?,
        val sourceBoxNo: Int? = null,
        val failedSourceBoxNos: List<Int> = emptyList(),
        val actionName: String,
        val result: AtomicActionResult,
        val message: String,
        val hasPrefetchedNext: Boolean
    ) : InActionGroupPipelineResult()
}

class InActionGroupPrefetchPipelineRunner(
    private val client: AtomicActionClient,
    private val logger: (String) -> Unit = {},
    private val operationLogger: (String) -> Unit = {},
    private val failureLogger: (String) -> Unit = operationLogger,
    private val onTaskCompleted: (CompletedInActionGroupPipelineTask) -> Unit = {},
    private val shouldPauseAfterCurrentTask: () -> Boolean = { false },
    private val pauseReasonAfterCurrentTask: () -> String? = {
        if (shouldPauseAfterCurrentTask()) "检测到订单待出餐" else null
    }
) {
    suspend fun run(tasks: List<InActionGroupPipelineTask>): InActionGroupPipelineResult = coroutineScope {
        if (tasks.isEmpty()) {
            return@coroutineScope InActionGroupPipelineResult.Success(emptyList())
        }

        val completed = mutableListOf<CompletedInActionGroupPipelineTask>()
        var index = 0
        var currentPrefetch: Deferred<PrefetchResult> = startPrefetch(tasks[index])

        while (index < tasks.size) {
            val task = tasks[index]
            val prefetched = when (val result = currentPrefetch.await()) {
                is PrefetchResult.Success -> result
                is PrefetchResult.Failed -> return@coroutineScope result.toPipelineFailure(completed, task)
            }

            var nextPrefetch: Deferred<PrefetchResult>? = null
            var pauseRequestedAfterCurrent = false
            val startNextPrefetchIfNeeded = {
                if (nextPrefetch == null && index + 1 < tasks.size) {
                    val pauseReason = pauseReasonAfterCurrentTask()
                    if (pauseReason != null) {
                        pauseRequestedAfterCurrent = true
                        logger(
                            "in6 命令已下发，$pauseReason，当前根安全完成后暂停补肠批次：" +
                                "currentTargetPan=${task.targetPanNo}，nextTargetPan=${tasks[index + 1].targetPanNo}"
                        )
                    } else {
                        logger(
                            "in6 命令已下发，启动下一根预取：currentTargetPan=${task.targetPanNo}，" +
                                "nextTargetPan=${tasks[index + 1].targetPanNo}"
                        )
                        nextPrefetch = startPrefetch(tasks[index + 1])
                    }
                }
            }

            logger("当前根进入投放阶段：targetPan=${task.targetPanNo}，sourceBox=${prefetched.sourceBoxNo}")
            when (val in4 = executeLoggedAction(AtomicActions.in4, 0, task.targetPanNo, prefetched.sourceBoxNo)) {
                AtomicActionResult.Success -> Unit
                else -> return@coroutineScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in4", in4, nextPrefetch)
            }
            when (val in5 = executeRetriableAction(
                AtomicActions.in5,
                0,
                AtomicActionCodes.IR_THROW_FAILED,
                task.targetPanNo,
                prefetched.sourceBoxNo
            )) {
                AtomicActionResult.Success -> Unit
                else -> return@coroutineScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in5", in5, nextPrefetch)
            }
            when (val in6 = executeLoggedAction(
                AtomicActions.in6,
                0,
                task.targetPanNo,
                prefetched.sourceBoxNo,
                onCommandDispatched = startNextPrefetchIfNeeded
            )) {
                AtomicActionResult.Success -> Unit
                else -> return@coroutineScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in6", in6, nextPrefetch)
            }
            logger("in6 完成，立即执行 in7：targetPan=${task.targetPanNo}，sourceBox=${prefetched.sourceBoxNo}")
            when (val in7 = executeLoggedAction(AtomicActions.in7, task.targetPanNo, task.targetPanNo, prefetched.sourceBoxNo)) {
                AtomicActionResult.Success -> Unit
                else -> return@coroutineScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in7", in7, nextPrefetch)
            }

            val completedTask = CompletedInActionGroupPipelineTask(
                task = task,
                sourceBoxNo = prefetched.sourceBoxNo,
                targetPanNo = task.targetPanNo
            )
            completed += completedTask
            onTaskCompleted(completedTask)

            if (pauseRequestedAfterCurrent && index + 1 < tasks.size) {
                logger(
                    "补肠预取流水线已暂停：completedCount=${completed.size}，" +
                        "remainingCount=${tasks.size - index - 1}"
                )
                return@coroutineScope InActionGroupPipelineResult.Paused(completed)
            }

            val next = nextPrefetch ?: break
            index += 1
            currentPrefetch = next
        }

        InActionGroupPipelineResult.Success(completed)
    }

    private fun CoroutineScope.startPrefetch(task: InActionGroupPipelineTask): Deferred<PrefetchResult> {
        return async {
            runPrefetch(task)
        }
    }

    private suspend fun runPrefetch(task: InActionGroupPipelineTask): PrefetchResult {
        if (task.targetPanNo !in 1..33) {
            return PrefetchResult.Failed(
                actionName = "in",
                result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                message = "目标烤盘编号必须在 1..33，当前=${task.targetPanNo}"
            )
        }
        if (task.sourceBoxCandidates.isEmpty()) {
            return PrefetchResult.Failed(
                actionName = "in",
                result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                message = "源肠箱候选列表不能为空"
            )
        }

        var lastPickFailure: PrefetchResult.Failed? = null
        val failedPickSourceBoxNos = mutableListOf<Int>()
        task.sourceBoxCandidates.forEachIndexed { index, sourceBoxNo ->
            logger(
                "下一根进入预取阶段：targetPan=${task.targetPanNo}，sourceBox=$sourceBoxNo，" +
                    "candidateIndex=${index + 1}/${task.sourceBoxCandidates.size}"
            )
            if (!SourceBoxPositionPolicy.isValidSourceBox(sourceBoxNo)) {
                return PrefetchResult.Failed(
                    actionName = "in",
                    sourceBoxNo = sourceBoxNo,
                    result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                    message = "源肠箱编号必须在 ${SourceBoxPositionPolicy.validRangeText()}，当前=$sourceBoxNo"
                )
            }

            when (val in1 = executeLoggedAction(AtomicActions.in1, sourceBoxNo, task.targetPanNo, sourceBoxNo)) {
                AtomicActionResult.Success -> Unit
                else -> return PrefetchResult.Failed("in1", in1, describe(in1), sourceBoxNo)
            }

            when (val in2 = executeRetriableAction(
                AtomicActions.in2,
                sourceBoxNo,
                AtomicActionCodes.IR_PICK_FAILED,
                task.targetPanNo,
                sourceBoxNo,
                maxAttempts = 2
            )) {
                AtomicActionResult.Success -> Unit
                is AtomicActionResult.Failed -> {
                    val failedSourceBoxNos = if (in2.errorCode == AtomicActionCodes.IR_PICK_FAILED) {
                        (failedPickSourceBoxNos + sourceBoxNo).distinct()
                    } else {
                        failedPickSourceBoxNos.toList()
                    }
                    val failed = PrefetchResult.Failed(
                        actionName = "in2",
                        result = in2,
                        message = describe(in2),
                        sourceBoxNo = sourceBoxNo,
                        failedSourceBoxNos = failedSourceBoxNos
                    )
                    if (in2.errorCode == AtomicActionCodes.IR_PICK_FAILED && index < task.sourceBoxCandidates.lastIndex) {
                        logger("预取 in2 连续取肠失败，切换下一候选箱：sourceBox=$sourceBoxNo，targetPan=${task.targetPanNo}")
                        operationLogger("【补肠】烤盘${task.targetPanNo} in2（取肠、分料、避让）连续失败，切换下一候选烤肠箱：当前箱=$sourceBoxNo")
                        failedPickSourceBoxNos += sourceBoxNo
                        lastPickFailure = failed
                        return@forEachIndexed
                    }
                    return failed
                }
                else -> return PrefetchResult.Failed(
                    actionName = "in2",
                    result = in2,
                    message = describe(in2),
                    sourceBoxNo = sourceBoxNo,
                    failedSourceBoxNos = failedPickSourceBoxNos.toList()
                )
            }

            when (val in3 = executeLoggedAction(AtomicActions.in3, 0, task.targetPanNo, sourceBoxNo)) {
                AtomicActionResult.Success -> return PrefetchResult.Success(sourceBoxNo)
                else -> return PrefetchResult.Failed("in3", in3, describe(in3), sourceBoxNo)
            }
        }

        return lastPickFailure ?: PrefetchResult.Failed(
            actionName = "in2",
            result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
            message = "所有候选箱取肠失败"
        )
    }

    private suspend fun executeAction(
        spec: AtomicActionSpec,
        infoValue: Int,
        onCommandDispatched: (suspend () -> Unit)? = null
    ): AtomicActionResult {
        return client.execute(spec, infoValue, onCommandDispatched)
    }

    private suspend fun executeLoggedAction(
        spec: AtomicActionSpec,
        infoValue: Int,
        targetPanNo: Int,
        sourceBoxNo: Int,
        onCommandDispatched: (suspend () -> Unit)? = null
    ): AtomicActionResult {
        val startedAtMs = System.currentTimeMillis()
        operationLogger("【补肠】烤盘$targetPanNo ${actionLabel(spec)}开始：来源烤肠箱=$sourceBoxNo")
        val result = executeAction(spec, infoValue, onCommandDispatched)
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        if (result == AtomicActionResult.Success) {
            val suffix = if (spec.name == "in7") "，烤盘开始烤制计时" else ""
            operationLogger("【补肠】烤盘$targetPanNo ${actionFinishLabel(spec)} 完成$suffix，用时=${formatElapsedSeconds(elapsedMs)}秒")
        } else {
            failureLogger("【失败原因】补肠烤盘$targetPanNo ${failureText(spec, result)}")
        }
        return result
    }

    private suspend fun executeRetriableAction(
        spec: AtomicActionSpec,
        infoValue: Int,
        retryableErrorCode: Int,
        targetPanNo: Int,
        sourceBoxNo: Int,
        maxAttempts: Int = 3
    ): AtomicActionResult {
        var lastResult: AtomicActionResult = AtomicActionResult.Success
        for (attempt in 1..maxAttempts) {
            val startedAtMs = System.currentTimeMillis()
            operationLogger("【补肠】烤盘$targetPanNo ${actionLabel(spec)}开始：来源烤肠箱=$sourceBoxNo，第$attempt/${maxAttempts}次")
            val result = client.execute(spec, infoValue)
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            lastResult = result
            if (result == AtomicActionResult.Success) {
                operationLogger("【补肠】烤盘$targetPanNo ${actionFinishLabel(spec)} 完成，用时=${formatElapsedSeconds(elapsedMs)}秒")
                return AtomicActionResult.Success
            }
            if (!isRetryable(result, retryableErrorCode) || attempt == maxAttempts) {
                failureLogger("【失败原因】补肠烤盘$targetPanNo ${failureText(spec, result)}")
                return result
            }
            logger("重试原子动作：action=${spec.name}，attempt=$attempt/$maxAttempts，reason=${describe(result)}")
            operationLogger(
                "【补肠】烤盘$targetPanNo ${actionFinishLabel(spec)} 第$attempt/${maxAttempts}次失败：${describe(result)}，准备重试"
            )
        }
        return lastResult
    }

    private suspend fun deliveryFailure(
        completed: List<CompletedInActionGroupPipelineTask>,
        task: InActionGroupPipelineTask,
        sourceBoxNo: Int,
        actionName: String,
        result: AtomicActionResult,
        nextPrefetch: Deferred<PrefetchResult>?
    ): InActionGroupPipelineResult.Failed {
        val hasPrefetchedNext = when (nextPrefetch?.await()) {
            is PrefetchResult.Success -> true
            is PrefetchResult.Failed,
            null -> false
        }
        return InActionGroupPipelineResult.Failed(
            completed = completed,
            task = task,
            sourceBoxNo = sourceBoxNo,
            actionName = actionName,
            result = result,
            message = "${actionName} 投放阶段失败：${describe(result)}",
            hasPrefetchedNext = hasPrefetchedNext
        )
    }

    private fun PrefetchResult.Failed.toPipelineFailure(
        completed: List<CompletedInActionGroupPipelineTask>,
        task: InActionGroupPipelineTask
    ): InActionGroupPipelineResult.Failed {
        return InActionGroupPipelineResult.Failed(
            completed = completed,
            task = task,
            sourceBoxNo = sourceBoxNo,
            failedSourceBoxNos = failedSourceBoxNos,
            actionName = actionName,
            result = result,
            message = message,
            hasPrefetchedNext = false
        )
    }

    private sealed class PrefetchResult {
        data class Success(val sourceBoxNo: Int) : PrefetchResult()
        data class Failed(
            val actionName: String,
            val result: AtomicActionResult,
            val message: String,
            val sourceBoxNo: Int? = null,
            val failedSourceBoxNos: List<Int> = emptyList()
        ) : PrefetchResult()
    }

    private fun isRetryable(result: AtomicActionResult, retryableErrorCode: Int): Boolean {
        return result is AtomicActionResult.Failed && result.errorCode == retryableErrorCode
    }

    private fun describe(result: AtomicActionResult): String {
        return when (result) {
            AtomicActionResult.Success -> "成功"
            is AtomicActionResult.Failed -> result.message
            is AtomicActionResult.ModbusError -> result.message
            is AtomicActionResult.Timeout -> result.message
        }
    }

    private fun failureText(spec: AtomicActionSpec, result: AtomicActionResult): String {
        return "${actionLabel(spec)}失败：${describe(result)}"
    }

    private fun actionLabel(spec: AtomicActionSpec): String {
        return "${spec.name}（${spec.description.substringAfter('：', spec.description)}）"
    }

    private fun actionFinishLabel(spec: AtomicActionSpec): String {
        return if (spec.name == "in7") actionLabel(spec) else spec.name
    }

    private fun formatElapsedSeconds(elapsedMs: Long): String {
        return String.format(java.util.Locale.US, "%.1f", elapsedMs / 1000.0)
    }
}
