package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

data class InActionGroupPipelineTask(
    val sourceBoxCandidates: List<Int>,
    val targetPanNo: Int
)

data class CompletedInActionGroupPipelineTask(
    val task: InActionGroupPipelineTask,
    val sourceBoxNo: Int,
    val targetPanNo: Int
)

data class InActionGroupPipelineActionContext(
    val task: InActionGroupPipelineTask,
    val sourceBoxNo: Int,
    val targetPanNo: Int,
    val actionName: String,
    val laneRole: SupplementLaneRole = SupplementLaneRole.DELIVERY
)

sealed class InActionGroupPipelineResult {
    data class Success(
        val completed: List<CompletedInActionGroupPipelineTask>
    ) : InActionGroupPipelineResult()

    data class Paused(
        val completed: List<CompletedInActionGroupPipelineTask>,
        val pausedAfterAction: String? = null
    ) : InActionGroupPipelineResult()

    data class Failed(
        val completed: List<CompletedInActionGroupPipelineTask>,
        val task: InActionGroupPipelineTask?,
        val sourceBoxNo: Int? = null,
        val failedSourceBoxNos: List<Int> = emptyList(),
        val actionName: String,
        val result: AtomicActionResult,
        val message: String,
        val hasPrefetchedNext: Boolean,
        val laneRole: SupplementLaneRole
    ) : InActionGroupPipelineResult()
}

class InActionGroupPrefetchPipelineRunner(
    private val client: AtomicActionClient,
    private val logger: (String) -> Unit = {},
    private val operationLogger: (String) -> Unit = {},
    private val failureLogger: (String) -> Unit = operationLogger,
    private val onTaskCompleted: (CompletedInActionGroupPipelineTask) -> Unit = {},
    private val onPickFailureSkipped: (Int) -> Unit = {},
    private val onPickSucceeded: (Int) -> Unit = {},
    private val onCurrentActionChanged: (InActionGroupPipelineActionContext) -> Unit = {},
    private val actionClientProvider: (InActionGroupPipelineActionContext) -> AtomicActionClient = { client },
    private val pauseReasonAfterFallbackSource: (InActionGroupPipelineTask, Int) -> String? = { _, _ -> null },
    private val pauseReasonBeforePick: () -> String? = { null },
    private val pauseReasonAfterPrefetchAction: (String) -> String? = { null },
    private val shouldPauseAfterCurrentTask: () -> Boolean = { false },
    private val pauseReasonAfterCurrentTask: () -> String? = {
        if (shouldPauseAfterCurrentTask()) "检测到订单待出餐" else null
    },
    private val parallelEnabled: Boolean = false,
    private val beforeDeliveryCommit: (InActionGroupPipelineActionContext) -> Boolean = { true },
    private val onActionCommandWritten: (InActionGroupPipelineActionContext) -> Unit = {},
    private val onActionRunningConfirmed: (InActionGroupPipelineActionContext) -> Unit = {},
    private val onActionTerminalConfirmed: (InActionGroupPipelineActionContext, AtomicActionResult) -> Unit = { _, _ -> },
    private val onPrefetchPromoted: (InActionGroupPipelineActionContext) -> Unit = {},
    private val onPausedPrefetchPromoted: (InActionGroupPipelineActionContext) -> Unit = {},
    private val onTerminalPrefetchPickFailure: (InActionGroupPipelineActionContext) -> Unit = {}
) {
    suspend fun run(tasks: List<InActionGroupPipelineTask>): InActionGroupPipelineResult = supervisorScope {
        if (tasks.isEmpty()) {
            return@supervisorScope InActionGroupPipelineResult.Success(emptyList())
        }

        val completed = mutableListOf<CompletedInActionGroupPipelineTask>()
        var index = 0
        var currentPrefetch: Deferred<PrefetchResult> = startPrefetch(tasks[index], SupplementLaneRole.DELIVERY)
        var currentPrefetchRole = SupplementLaneRole.DELIVERY

        while (index < tasks.size) {
            val task = tasks[index]
            val prefetched = when (val result = currentPrefetch.await()) {
                is PrefetchResult.Success -> result
                is PrefetchResult.Paused -> {
                    logger("补肠预取在 in2 前暂停：targetPan=${task.targetPanNo}，reason=${result.reason}")
                    return@supervisorScope InActionGroupPipelineResult.Paused(completed, result.completedActionName)
                }
                is PrefetchResult.Failed -> return@supervisorScope result.toPipelineFailure(completed, task)
            }

            if (parallelEnabled && currentPrefetchRole == SupplementLaneRole.PREFETCH) {
                onPrefetchPromoted(
                    InActionGroupPipelineActionContext(
                        task = task,
                        sourceBoxNo = prefetched.sourceBoxNo,
                        targetPanNo = task.targetPanNo,
                        actionName = "in3",
                        laneRole = SupplementLaneRole.DELIVERY
                    )
                )
            }

            var nextPrefetch: Deferred<PrefetchResult>? = null
            val stopPrefetchAdvancement = AtomicBoolean(false)
            var pauseRequestedAfterCurrent = false
            val fallbackPauseReason = if (prefetched.sourceBoxNo != task.sourceBoxCandidates.first()) {
                pauseReasonAfterFallbackSource(task, prefetched.sourceBoxNo)
            } else {
                null
            }
            val startNextPrefetchIfNeeded: suspend () -> Unit = {
                if (nextPrefetch == null && index + 1 < tasks.size) {
                    val pauseReason = fallbackPauseReason ?: pauseReasonAfterCurrentTask()
                    if (pauseReason != null) {
                        pauseRequestedAfterCurrent = true
                        logger(
                            "in6 命令已下发，$pauseReason，当前根安全完成后暂停补肠批次：" +
                                "currentTargetPan=${task.targetPanNo}，nextTargetPan=${tasks[index + 1].targetPanNo}"
                        )
                    } else if (parallelEnabled) {
                        nextPrefetch = startPrefetch(tasks[index + 1], SupplementLaneRole.PREFETCH) {
                            stopPrefetchAdvancement.get()
                        }
                        logger(
                            "in6 已确认运行，开始下一根预取：currentTargetPan=${task.targetPanNo}，" +
                                "nextTargetPan=${tasks[index + 1].targetPanNo}"
                        )
                    } else {
                        logger(
                            "in6 命令已下发，已登记下一根任务；为保证崩溃恢复坐标唯一，" +
                                "等待当前 in7 完成后再开始预取：currentTargetPan=${task.targetPanNo}，" +
                                "nextTargetPan=${tasks[index + 1].targetPanNo}"
                        )
                    }
                }
            }

            logger("当前根进入投放阶段：targetPan=${task.targetPanNo}，sourceBox=${prefetched.sourceBoxNo}")
            var currentDeliveryAction: InActionGroupPipelineActionContext? = null
            try {
                val in4Context = InActionGroupPipelineActionContext(
                    task = task,
                    sourceBoxNo = prefetched.sourceBoxNo,
                    targetPanNo = task.targetPanNo,
                    actionName = "in4"
                )
                if (!beforeDeliveryCommit(in4Context)) {
                    logger(
                        "订单已获得补肠提交权，预取停在 in3：sourceBox=${prefetched.sourceBoxNo}，" +
                            "targetPan=${task.targetPanNo}"
                    )
                    return@supervisorScope InActionGroupPipelineResult.Paused(completed, "in3")
                }
                currentDeliveryAction = notifyCurrentAction(
                    task,
                    prefetched.sourceBoxNo,
                    task.targetPanNo,
                    "in4",
                    SupplementLaneRole.DELIVERY
                )
                when (val in4 = executeLoggedAction(task, AtomicActions.in4, 0, prefetched.sourceBoxNo)) {
                    AtomicActionResult.Success -> Unit
                    else -> return@supervisorScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in4", in4, nextPrefetch)
                }
                currentDeliveryAction = notifyCurrentAction(
                    task,
                    prefetched.sourceBoxNo,
                    task.targetPanNo,
                    "in5",
                    SupplementLaneRole.DELIVERY
                )
                when (val in5 = executeRetriableAction(
                    AtomicActions.in5,
                    0,
                    AtomicActionCodes.IR_THROW_FAILED,
                    task,
                    prefetched.sourceBoxNo
                )) {
                    AtomicActionResult.Success -> Unit
                    else -> return@supervisorScope deliveryFailure(completed, task, prefetched.sourceBoxNo, "in5", in5, nextPrefetch)
                }
                currentDeliveryAction = notifyCurrentAction(
                    task,
                    prefetched.sourceBoxNo,
                    task.targetPanNo,
                    "in6",
                    SupplementLaneRole.DELIVERY
                )
                var runningConfirmed = false
                when (val in6 = executeLoggedAction(
                    task,
                    AtomicActions.in6,
                    0,
                    prefetched.sourceBoxNo,
                    onCommandDispatched = if (parallelEnabled) null else startNextPrefetchIfNeeded,
                    onRunningConfirmed = if (parallelEnabled) {
                        {
                            runningConfirmed = true
                            startNextPrefetchIfNeeded()
                        }
                    } else {
                        null
                    }
                )) {
                    AtomicActionResult.Success -> Unit
                    else -> {
                        stopPrefetchAdvancement.set(true)
                        return@supervisorScope deliveryFailure(
                            completed,
                            task,
                            prefetched.sourceBoxNo,
                            "in6",
                            in6,
                            nextPrefetch
                        )
                    }
                }
                if (parallelEnabled && !runningConfirmed && nextPrefetch == null) {
                    logger(
                        "in6 未观察到运行确认，完成后安全启动下一根预取：" +
                            "currentTargetPan=${task.targetPanNo}，nextTargetPan=${tasks.getOrNull(index + 1)?.targetPanNo}"
                    )
                    startNextPrefetchIfNeeded()
                }
                logger("in6 完成，立即执行 in7：targetPan=${task.targetPanNo}，sourceBox=${prefetched.sourceBoxNo}")
                currentDeliveryAction = notifyCurrentAction(
                    task,
                    prefetched.sourceBoxNo,
                    task.targetPanNo,
                    "in7",
                    SupplementLaneRole.DELIVERY
                )
                when (val in7 = executeLoggedAction(task, AtomicActions.in7, task.targetPanNo, prefetched.sourceBoxNo)) {
                    AtomicActionResult.Success -> Unit
                    else -> {
                        stopPrefetchAdvancement.set(true)
                        return@supervisorScope deliveryFailure(
                            completed,
                            task,
                            prefetched.sourceBoxNo,
                            "in7",
                            in7,
                            nextPrefetch
                        )
                    }
                }
                val completedTask = CompletedInActionGroupPipelineTask(
                    task = task,
                    sourceBoxNo = prefetched.sourceBoxNo,
                    targetPanNo = task.targetPanNo
                )
                onTaskCompleted(completedTask)
                completed += completedTask
            } catch (e: CancellationException) {
                throw e
            } catch (throwable: Throwable) {
                stopPrefetchAdvancement.set(true)
                return@supervisorScope deliveryExceptionFailure(
                    completed = completed,
                    task = task,
                    currentAction = currentDeliveryAction,
                    throwable = throwable,
                    nextPrefetch = nextPrefetch
                )
            }

            val pauseReasonAfterIn7 = pauseReasonAfterCurrentTask()
            if (index + 1 < tasks.size && (pauseRequestedAfterCurrent || pauseReasonAfterIn7 != null)) {
                val nextTask = tasks[index + 1]
                when (val settledPrefetch = nextPrefetch?.await()) {
                    is PrefetchResult.Success -> onPrefetchPromoted(
                        InActionGroupPipelineActionContext(
                            task = nextTask,
                            sourceBoxNo = settledPrefetch.sourceBoxNo,
                            targetPanNo = nextTask.targetPanNo,
                            actionName = "in3",
                            laneRole = SupplementLaneRole.DELIVERY
                        )
                    )
                    is PrefetchResult.Paused -> onPausedPrefetchPromoted(
                        InActionGroupPipelineActionContext(
                            task = nextTask,
                            sourceBoxNo = settledPrefetch.sourceBoxNo,
                            targetPanNo = nextTask.targetPanNo,
                            actionName = settledPrefetch.completedActionName,
                            laneRole = SupplementLaneRole.DELIVERY
                        )
                    )
                    is PrefetchResult.Failed ->
                        return@supervisorScope settledPrefetch.toPipelineFailure(completed, nextTask)
                    null -> Unit
                }
                logger(
                    "补肠预取流水线已暂停：completedCount=${completed.size}，" +
                        "remainingCount=${tasks.size - index - 1}，" +
                        "reason=${pauseReasonAfterIn7 ?: "已在 in6 下发时登记暂停"}"
                )
                return@supervisorScope InActionGroupPipelineResult.Paused(completed, "in7")
            }

            val next = nextPrefetch ?: if (index + 1 < tasks.size) {
                startPrefetch(
                    tasks[index + 1],
                    if (parallelEnabled) SupplementLaneRole.PREFETCH else SupplementLaneRole.DELIVERY
                )
            } else {
                break
            }
            index += 1
            currentPrefetch = next
            currentPrefetchRole = if (parallelEnabled) SupplementLaneRole.PREFETCH else SupplementLaneRole.DELIVERY
        }

        InActionGroupPipelineResult.Success(completed)
    }

    private fun CoroutineScope.startPrefetch(
        task: InActionGroupPipelineTask,
        laneRole: SupplementLaneRole,
        shouldStopBeforeNextAction: () -> Boolean = { false }
    ): Deferred<PrefetchResult> {
        return async {
            runPrefetch(task, laneRole, shouldStopBeforeNextAction)
        }
    }

    private suspend fun runPrefetch(
        task: InActionGroupPipelineTask,
        laneRole: SupplementLaneRole,
        shouldStopBeforeNextAction: () -> Boolean
    ): PrefetchResult {
        validatePrefetchTask(task, laneRole)?.let { return it }
        var currentAction: InActionGroupPipelineActionContext? = null
        fun recordCurrentAction(sourceBoxNo: Int, actionName: String) {
            currentAction = notifyCurrentAction(task, sourceBoxNo, task.targetPanNo, actionName, laneRole)
        }
        return try {
            runPrefetchInternal(task, ::recordCurrentAction, shouldStopBeforeNextAction, laneRole)
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            val action = currentAction
            val actionName = action?.actionName ?: "in"
            PrefetchResult.Failed(
                actionName = actionName,
                result = throwableToResult(actionName, throwable),
                laneRole = laneRole,
                message = "$actionName 预取阶段异常：${describeThrowable(throwable)}",
                sourceBoxNo = action?.sourceBoxNo
            )
        }
    }

    private suspend fun runPrefetchInternal(
        task: InActionGroupPipelineTask,
        recordCurrentAction: (sourceBoxNo: Int, actionName: String) -> Unit,
        shouldStopBeforeNextAction: () -> Boolean,
        laneRole: SupplementLaneRole
    ): PrefetchResult {
        var lastPickFailure: PrefetchResult.Failed? = null
        val failedPickSourceBoxNos = mutableListOf<Int>()
        task.sourceBoxCandidates.forEachIndexed { index, sourceBoxNo ->
            logger(
                "下一根进入预取阶段：targetPan=${task.targetPanNo}，sourceBox=$sourceBoxNo，" +
                    "candidateIndex=${index + 1}/${task.sourceBoxCandidates.size}"
            )
            if (!SourceBoxPositionPolicy.isValidSourceBox(sourceBoxNo)) {
                val reason = "源肠箱编号必须在 ${SourceBoxPositionPolicy.validRangeText()}，当前=$sourceBoxNo"
                SendServerHelper.publishIllegalSourceBoxBlocked(sourceBoxNo, task.targetPanNo, reason)
                return PrefetchResult.Failed(
                    actionName = "in",
                    laneRole = laneRole,
                    sourceBoxNo = sourceBoxNo,
                    result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                    message = reason
                )
            }

            recordCurrentAction(sourceBoxNo, "in1")
            when (val in1 = executeLoggedAction(
                task,
                AtomicActions.in1,
                sourceBoxNo,
                sourceBoxNo,
                laneRole = laneRole
            )) {
                AtomicActionResult.Success -> Unit
                else -> return PrefetchResult.Failed("in1", in1, describe(in1), laneRole, sourceBoxNo)
            }

            if (shouldStopBeforeNextAction()) {
                return PrefetchResult.Paused(
                    "当前投放动作失败，预取在当前原子动作收口后停止",
                    "in1",
                    sourceBoxNo
                )
            }

            pauseReasonBeforePick()?.let { reason ->
                return PrefetchResult.Paused(reason, "in1", sourceBoxNo)
            }

            recordCurrentAction(sourceBoxNo, "in2")
            when (val in2 = executeRetriableAction(
                AtomicActions.in2,
                sourceBoxNo,
                AtomicActionCodes.IR_PICK_FAILED,
                task,
                sourceBoxNo,
                maxAttempts = 2,
                laneRole = laneRole
            )) {
                AtomicActionResult.Success -> onPickSucceeded(sourceBoxNo)
                is AtomicActionResult.Failed -> {
                    val failedSourceBoxNos = if (in2.errorCode == AtomicActionCodes.IR_PICK_FAILED) {
                        (failedPickSourceBoxNos + sourceBoxNo).distinct()
                    } else {
                        failedPickSourceBoxNos.toList()
                    }
                    val failed = PrefetchResult.Failed(
                        actionName = "in2",
                        laneRole = laneRole,
                        result = in2,
                        message = describe(in2),
                        sourceBoxNo = sourceBoxNo,
                        failedSourceBoxNos = failedSourceBoxNos
                    )
                    if (
                        in2.errorCode == AtomicActionCodes.IR_PICK_FAILED &&
                        index < task.sourceBoxCandidates.lastIndex &&
                        !shouldStopBeforeNextAction()
                    ) {
                        logger("预取 in2 连续取肠失败，切换下一候选箱：sourceBox=$sourceBoxNo，targetPan=${task.targetPanNo}")
                        operationLogger("【补肠】烤盘${task.targetPanNo} in2（取肠、分料、避让）连续失败，切换下一候选烤肠箱：当前箱=$sourceBoxNo")
                        failedPickSourceBoxNos += sourceBoxNo
                        onPickFailureSkipped(sourceBoxNo)
                        lastPickFailure = failed
                        return@forEachIndexed
                    }
                    if (laneRole == SupplementLaneRole.PREFETCH &&
                        in2.errorCode == AtomicActionCodes.IR_PICK_FAILED
                    ) {
                        onTerminalPrefetchPickFailure(
                            InActionGroupPipelineActionContext(
                                task = task,
                                sourceBoxNo = sourceBoxNo,
                                targetPanNo = task.targetPanNo,
                                actionName = "in2",
                                laneRole = laneRole
                            )
                        )
                    }
                    return failed
                }
                else -> return PrefetchResult.Failed(
                    actionName = "in2",
                    laneRole = laneRole,
                    result = in2,
                    message = describe(in2),
                    sourceBoxNo = sourceBoxNo,
                    failedSourceBoxNos = failedPickSourceBoxNos.toList()
                )
            }

            if (shouldStopBeforeNextAction()) {
                return PrefetchResult.Paused(
                    "当前投放动作失败，预取在当前原子动作收口后停止",
                    "in2",
                    sourceBoxNo
                )
            }

            pauseReasonAfterPrefetchAction("in2")?.let { reason ->
                return PrefetchResult.Paused(reason, "in2", sourceBoxNo)
            }

            recordCurrentAction(sourceBoxNo, "in3")
            when (val in3 = executeLoggedAction(
                task,
                AtomicActions.in3,
                0,
                sourceBoxNo,
                laneRole = laneRole
            )) {
                AtomicActionResult.Success -> {
                    pauseReasonAfterPrefetchAction("in3")?.let { reason ->
                        return PrefetchResult.Paused(reason, "in3", sourceBoxNo)
                    }
                    return PrefetchResult.Success(sourceBoxNo)
                }
                else -> return PrefetchResult.Failed("in3", in3, describe(in3), laneRole, sourceBoxNo)
            }
        }

        return lastPickFailure ?: PrefetchResult.Failed(
            actionName = "in2",
            result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
            message = "所有候选箱取肠失败",
            laneRole = laneRole
        )
    }

    private fun validatePrefetchTask(
        task: InActionGroupPipelineTask,
        laneRole: SupplementLaneRole
    ): PrefetchResult.Failed? {
        if (task.targetPanNo !in 1..33) {
            return PrefetchResult.Failed(
                actionName = "in",
                result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                message = "目标烤盘编号必须在 1..33，当前=${task.targetPanNo}",
                laneRole = laneRole
            )
        }
        if (task.sourceBoxCandidates.isEmpty()) {
            return PrefetchResult.Failed(
                actionName = "in",
                result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                message = "源肠箱候选列表不能为空",
                laneRole = laneRole
            )
        }
        task.sourceBoxCandidates.firstOrNull { !SourceBoxPositionPolicy.isValidSourceBox(it) }?.let { sourceBoxNo ->
            val reason = "源肠箱编号必须在 ${SourceBoxPositionPolicy.validRangeText()}，当前=$sourceBoxNo"
            publishIllegalSourceBoxBlockedSafely(sourceBoxNo, task.targetPanNo, reason)
            return PrefetchResult.Failed(
                actionName = "in",
                sourceBoxNo = sourceBoxNo,
                result = AtomicActionResult.Failed(AtomicActionCodes.PARAM_ERROR, "参数错误"),
                message = reason,
                laneRole = laneRole
            )
        }
        return null
    }

    private fun publishIllegalSourceBoxBlockedSafely(sourceBoxNo: Int, targetPanNo: Int, reason: String) {
        try {
            SendServerHelper.publishIllegalSourceBoxBlocked(sourceBoxNo, targetPanNo, reason)
        } catch (throwable: Throwable) {
            logger(
                "非法源肠箱拦截上报失败，不影响本次参数错误拦截：sourceBox=$sourceBoxNo，" +
                    "targetPan=$targetPanNo，error=${describeThrowable(throwable)}"
            )
        }
    }

    private suspend fun executeAction(
        actionClient: AtomicActionClient,
        spec: AtomicActionSpec,
        infoValue: Int,
        onCommandDispatched: (suspend () -> Unit)? = null,
        onRunningConfirmed: (suspend () -> Unit)? = null
    ): AtomicActionResult {
        return try {
            actionClient.execute(spec, infoValue, onCommandDispatched, onRunningConfirmed)
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            throwableToResult(spec.name, throwable)
        }
    }

    private suspend fun executeLoggedAction(
        task: InActionGroupPipelineTask,
        spec: AtomicActionSpec,
        infoValue: Int,
        sourceBoxNo: Int,
        onCommandDispatched: (suspend () -> Unit)? = null,
        onRunningConfirmed: (suspend () -> Unit)? = null,
        laneRole: SupplementLaneRole = SupplementLaneRole.DELIVERY
    ): AtomicActionResult {
        val actionContext = InActionGroupPipelineActionContext(
            task = task,
            sourceBoxNo = sourceBoxNo,
            targetPanNo = task.targetPanNo,
            actionName = spec.name,
            laneRole = laneRole
        )
        val startedAtMs = System.currentTimeMillis()
        operationLogger("【补肠】烤盘${task.targetPanNo} ${actionLabel(spec)}开始：来源烤肠箱=$sourceBoxNo")
        val result = executeAction(
            actionClientProvider(actionContext),
            spec,
            infoValue,
            onCommandDispatched = {
                onActionCommandWritten(actionContext)
                onCommandDispatched?.invoke()
            },
            onRunningConfirmed = {
                onActionRunningConfirmed(actionContext)
                onRunningConfirmed?.invoke()
            }
        )
        if (result !is AtomicActionResult.Timeout && result !is AtomicActionResult.ModbusError) {
            onActionTerminalConfirmed(actionContext, result)
        }
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        if (result == AtomicActionResult.Success) {
            val suffix = if (spec.name == "in7") "，烤盘开始烤制计时" else ""
            operationLogger("【补肠】烤盘${task.targetPanNo} ${actionFinishLabel(spec)} 完成$suffix，用时=${formatElapsedSeconds(elapsedMs)}秒")
        } else {
            failureLogger("【失败原因】补肠烤盘${task.targetPanNo} ${failureText(spec, result)}")
        }
        return result
    }

    private suspend fun executeRetriableAction(
        spec: AtomicActionSpec,
        infoValue: Int,
        retryableErrorCode: Int,
        task: InActionGroupPipelineTask,
        sourceBoxNo: Int,
        maxAttempts: Int = 3,
        laneRole: SupplementLaneRole = SupplementLaneRole.DELIVERY
    ): AtomicActionResult {
        var lastResult: AtomicActionResult = AtomicActionResult.Success
        for (attempt in 1..maxAttempts) {
            val actionContext = InActionGroupPipelineActionContext(
                task = task,
                sourceBoxNo = sourceBoxNo,
                targetPanNo = task.targetPanNo,
                actionName = spec.name,
                laneRole = laneRole
            )
            val startedAtMs = System.currentTimeMillis()
            operationLogger("【补肠】烤盘${task.targetPanNo} ${actionLabel(spec)}开始：来源烤肠箱=$sourceBoxNo，第$attempt/${maxAttempts}次")
            val result = executeAction(
                actionClientProvider(actionContext),
                spec,
                infoValue,
                onCommandDispatched = { onActionCommandWritten(actionContext) },
                onRunningConfirmed = { onActionRunningConfirmed(actionContext) }
            )
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            lastResult = result
            if (result == AtomicActionResult.Success) {
                onActionTerminalConfirmed(actionContext, result)
                operationLogger("【补肠】烤盘${task.targetPanNo} ${actionFinishLabel(spec)} 完成，用时=${formatElapsedSeconds(elapsedMs)}秒")
                return AtomicActionResult.Success
            }
            if (!isRetryable(result, retryableErrorCode) || attempt == maxAttempts) {
                if (result !is AtomicActionResult.Timeout && result !is AtomicActionResult.ModbusError) {
                    onActionTerminalConfirmed(actionContext, result)
                }
                failureLogger("【失败原因】补肠烤盘${task.targetPanNo} ${failureText(spec, result)}")
                return result
            }
            logger("重试原子动作：action=${spec.name}，attempt=$attempt/$maxAttempts，reason=${describe(result)}")
            operationLogger(
                "【补肠】烤盘${task.targetPanNo} ${actionFinishLabel(spec)} 第$attempt/${maxAttempts}次失败：${describe(result)}，准备重试"
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
            is PrefetchResult.Paused -> false
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
            hasPrefetchedNext = hasPrefetchedNext,
            laneRole = SupplementLaneRole.DELIVERY
        )
    }

    private suspend fun deliveryExceptionFailure(
        completed: List<CompletedInActionGroupPipelineTask>,
        task: InActionGroupPipelineTask,
        currentAction: InActionGroupPipelineActionContext?,
        throwable: Throwable,
        nextPrefetch: Deferred<PrefetchResult>?
    ): InActionGroupPipelineResult.Failed {
        val actionName = currentAction?.actionName ?: "in"
        val result = throwableToResult(actionName, throwable)
        val hasPrefetchedNext = when (nextPrefetch?.await()) {
            is PrefetchResult.Success -> true
            is PrefetchResult.Paused -> false
            is PrefetchResult.Failed,
            null -> false
        }
        return InActionGroupPipelineResult.Failed(
            completed = completed,
            task = task,
            sourceBoxNo = currentAction?.sourceBoxNo,
            actionName = actionName,
            result = result,
            message = "${actionName} 投放阶段异常：${describeThrowable(throwable)}",
            hasPrefetchedNext = hasPrefetchedNext,
            laneRole = currentAction?.laneRole ?: SupplementLaneRole.DELIVERY
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
            hasPrefetchedNext = false,
            laneRole = laneRole
        )
    }

    private sealed class PrefetchResult {
        data class Success(val sourceBoxNo: Int) : PrefetchResult()
        data class Paused(
            val reason: String,
            val completedActionName: String,
            val sourceBoxNo: Int
        ) : PrefetchResult()
        data class Failed(
            val actionName: String,
            val result: AtomicActionResult,
            val message: String,
            val laneRole: SupplementLaneRole,
            val sourceBoxNo: Int? = null,
            val failedSourceBoxNos: List<Int> = emptyList()
        ) : PrefetchResult()
    }

    private fun isRetryable(result: AtomicActionResult, retryableErrorCode: Int): Boolean {
        return result is AtomicActionResult.Failed && result.errorCode == retryableErrorCode
    }

    private fun notifyCurrentAction(
        task: InActionGroupPipelineTask,
        sourceBoxNo: Int,
        targetPanNo: Int,
        actionName: String,
        laneRole: SupplementLaneRole = SupplementLaneRole.DELIVERY
    ): InActionGroupPipelineActionContext {
        val context = InActionGroupPipelineActionContext(
            task = task,
            sourceBoxNo = sourceBoxNo,
            targetPanNo = targetPanNo,
            actionName = actionName,
            laneRole = laneRole
        )
        onCurrentActionChanged(context)
        return context
    }

    private fun throwableToResult(actionName: String, throwable: Throwable): AtomicActionResult =
        AtomicActionResult.ModbusError("$actionName 执行异常：${describeThrowable(throwable)}")

    private fun describeThrowable(throwable: Throwable): String {
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message == null) {
            throwable::class.java.simpleName
        } else {
            "${throwable::class.java.simpleName}: $message"
        }
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
