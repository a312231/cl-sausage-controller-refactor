package cn.niuyannet.kaochang.android.services.actiongroup

interface ActionGroupSessionStore {
    fun load(): ActionGroupSession?
    fun save(session: ActionGroupSession)
    fun clear()
}

sealed class ActionGroupRecoveryResult {
    data object Idle : ActionGroupRecoveryResult()
    data class TakeoverFinished(
        val session: ActionGroupSession,
        val actionResult: ActionGroupResult
    ) : ActionGroupRecoveryResult()

    data class StaleSessionCleared(
        val session: ActionGroupSession,
        val reason: String
    ) : ActionGroupRecoveryResult()

    data class PreparedClaimAbandoned(
        val session: ActionGroupSession
    ) : ActionGroupRecoveryResult()

    data class MaintenanceRequired(
        val reason: String,
        val recoverableResidueSession: ActionGroupSession? = null
    ) : ActionGroupRecoveryResult()

    data class IsolatedSupplementFailurePreserved(
        val session: ActionGroupSession,
        val actionName: String,
        val errorCode: Int
    ) : ActionGroupRecoveryResult()
}

class ActionGroupRecoveryBootstrap(
    private val client: AtomicActionClient,
    private val sessionStore: ActionGroupSessionStore,
    private val resourceLocks: ResourceLocks,
    private val logger: (String) -> Unit = {},
    private val allowKnownIsolatedTerminalFailure: (ActionGroupSession, String, Int) -> Boolean = { _, _, _ -> false },
    private val clearSessionIfCurrent: (ActionGroupSession) -> Boolean = { expected ->
        val current = sessionStore.load()
        if (current?.sessionId != expected.sessionId ||
            current.mechanicalClaimRevision != expected.mechanicalClaimRevision
        ) {
            false
        } else {
            sessionStore.clear()
            true
        }
    },
    private val abandonPreparedClaim: (ActionGroupSession) -> Boolean = clearSessionIfCurrent,
    private val moveStatusProvider: () -> Int = { 0 }
) {
    suspend fun recover(): ActionGroupRecoveryResult {
        val reader = client as? AtomicActionStateReader
            ?: return ActionGroupRecoveryResult.MaintenanceRequired("动作组恢复失败：AtomicActionClient 不支持读取结果寄存器")

        val states = readAllStates(reader)
        states.firstOrNull { it.value is AtomicReadResult.Failure }?.let { failed ->
            val message = (failed.value as AtomicReadResult.Failure).message
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组恢复失败：读取 ${failed.spec.name} 结果寄存器 ${failed.spec.resultRegister} 失败：$message"
            )
        }

        val terminalFailures = states.filter { state ->
            val value = (state.value as AtomicReadResult.Success).value
            value >= AtomicActionCodes.PARAM_ERROR
        }
        if (terminalFailures.size == 1) {
            val failedState = terminalFailures.single()
            val code = (failedState.value as AtomicReadResult.Success).value
            val session = sessionStore.load()
            if (session != null && allowKnownIsolatedTerminalFailure(session, failedState.spec.name, code)) {
                if (!clearSessionIfCurrent(session)) {
                    return ActionGroupRecoveryResult.MaintenanceRequired(
                        "动作组已知隔离故障会话发生 owner/revision 变化，拒绝清理新会话：" +
                            "sessionId=${session.sessionId}，revision=${session.mechanicalClaimRevision}"
                    )
                }
                resourceLocks.release(session.sessionId)
                logger(
                    "动作组恢复识别到已持久化下平台隔离终态，保持半故障并清理本地补肠会话：" +
                        "sessionId=${session.sessionId}，action=${failedState.spec.name}，" +
                        "code=${AtomicActionExecutor.formatCode(code)}"
                )
                return ActionGroupRecoveryResult.IsolatedSupplementFailurePreserved(
                    session = session,
                    actionName = failedState.spec.name,
                    errorCode = code
                )
            }
        }
        terminalFailures.firstOrNull()?.let { failedState ->
            val code = (failedState.value as AtomicReadResult.Success).value
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组存在遗留错误：action=${failedState.spec.name}，resultRegister=${failedState.spec.resultRegister}，" +
                    "code=${AtomicActionExecutor.formatCode(code)}（${AtomicActionExecutor.messageOf(code)}）"
            )
        }

        val runningStates = states.filter { state ->
            (state.value as AtomicReadResult.Success).value == AtomicActionCodes.RUNNING
        }
        if (runningStates.isEmpty()) {
            val session = sessionStore.load()
            if (session != null) {
                val sessionSteps = stepsForSession(session)
                val sessionValues = sessionSteps.map { step ->
                    (states.first { state -> state.spec == step.spec }.value as AtomicReadResult.Success).value
                }
                if (session.mechanicalClaimState == MechanicalClaimState.PREPARED) {
                    if (sessionValues.all { it == AtomicActionCodes.IDLE }) {
                        if (!abandonPreparedClaim(session)) {
                            return ActionGroupRecoveryResult.MaintenanceRequired(
                                "动作组 PREPARED 会话安全放弃失败，保持忙态：sessionId=${session.sessionId}"
                            )
                        }
                        resourceLocks.release(session.sessionId)
                        return ActionGroupRecoveryResult.PreparedClaimAbandoned(session)
                    }
                    return ActionGroupRecoveryResult.MaintenanceRequired(
                        "动作组 PREPARED 会话出现非空闲寄存器，拒绝猜测命令是否下发：" +
                            "sessionId=${session.sessionId}，states=${formatSessionStates(sessionSteps, sessionValues)}"
                    )
                }
                if (sessionValues.all { it == AtomicActionCodes.COMPLETED }) {
                    resourceLocks.release(session.sessionId)
                    logger(
                        "动作组恢复完成：按结果寄存器=0x0002（完成）推断本会话已完成，" +
                            "sessionId=${session.sessionId}，type=${session.type}，sourceNo=${session.sourceNo}，targetNo=${session.targetNo}"
                    )
                    return ActionGroupRecoveryResult.TakeoverFinished(session, ActionGroupResult.Success)
                }
                if (sessionValues.any { it == AtomicActionCodes.COMPLETED }) {
                    if (!hasOnlyCompletedPrefixThenIdle(sessionValues)) {
                        return ActionGroupRecoveryResult.MaintenanceRequired(
                            "动作组恢复失败：本地会话存在非连续完成态，需人工确认物理位置，" +
                                "sessionId=${session.sessionId}，type=${session.type}，" +
                                "states=${formatSessionStates(sessionSteps, sessionValues)}"
                        )
                    }
                    val claimFailure = runCatching {
                        resourceLocks.forceAcquire(session.resourceMask, session.sessionId)
                    }.exceptionOrNull()
                    if (claimFailure != null) {
                        return ActionGroupRecoveryResult.MaintenanceRequired(
                            "动作组恢复资源 owner 冲突，拒绝清理其他 owner：" +
                                "sessionId=${session.sessionId}，error=${claimFailure.message}"
                        )
                    }
                    logger(
                        "动作组恢复接管开始：sessionId=${session.sessionId}，type=${session.type}，" +
                            "已识别连续完成前缀，resourceLocks=${resourceLocks.snapshot()}"
                    )
                    val actionResult = runSession(session, resumeFromRunningState = true)
                    resourceLocks.release(session.sessionId)
                    logger(
                        "动作组恢复接管结束：sessionId=${session.sessionId}，type=${session.type}，result=$actionResult"
                    )
                    return ActionGroupRecoveryResult.TakeoverFinished(session, actionResult)
                }
                if (session.type == ActionGroupSessionType.OUT_SELL || session.type == ActionGroupSessionType.OUT_MANUAL) {
                    if (!clearSessionIfCurrent(session)) {
                        return ActionGroupRecoveryResult.MaintenanceRequired(
                            "出餐动作组旧会话发生 owner/revision 变化，拒绝清理新会话：" +
                                "sessionId=${session.sessionId}，revision=${session.mechanicalClaimRevision}"
                        )
                    }
                    resourceLocks.release(session.sessionId)
                    val reason = "出餐动作组旧会话已收口：本地仍存在动作会话但下位机结果寄存器全部空闲，" +
                        "按下位机空闲状态解除本地会话锁，sessionId=${session.sessionId}，type=${session.type}，" +
                        "sourceNo=${session.sourceNo}，targetNo=${session.targetNo}"
                    logger(reason)
                    return ActionGroupRecoveryResult.StaleSessionCleared(session, reason)
                }
                return ActionGroupRecoveryResult.MaintenanceRequired(
                    "动作组恢复失败：本地仍存在动作会话但下位机结果寄存器全部空闲，疑似下位机复位/动作结果丢失，" +
                        "sessionId=${session.sessionId}，type=${session.type}，sourceNo=${session.sourceNo}，targetNo=${session.targetNo}，需人工确认物理位置",
                    recoverableResidueSession = session.takeIf { it.type == ActionGroupSessionType.IN_SUPPLEMENT }
                )
            }
            if (moveStatusProvider() == 1) {
                return ActionGroupRecoveryResult.MaintenanceRequired(
                    "动作组 moveStatus=1 但没有 owner，会话证据缺失，禁止自动清零"
                )
            }
            return if (resourceLocks.isIdle()) {
                ActionGroupRecoveryResult.Idle
            } else {
                ActionGroupRecoveryResult.MaintenanceRequired(
                    "动作组没有本地会话但仍存在未知资源 owner，禁止全局清锁：${resourceLocks.snapshot()}"
                )
            }
        }
        if (runningStates.size > 1) {
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组恢复失败：同时发现多个执行中动作=${runningStates.joinToString { it.spec.name }}"
            )
        }

        val session = sessionStore.load()
            ?: return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组恢复失败：${runningStates.first().spec.name} 正在执行，但没有本地会话，需人工确认物理位置"
            )
        if (session.mechanicalClaimState == MechanicalClaimState.PREPARED) {
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组 PREPARED 会话发现执行中寄存器，拒绝猜测命令是否下发：" +
                    "sessionId=${session.sessionId}，running=${runningStates.first().spec.name}"
            )
        }
        val runningActionName = runningStates.single().spec.name
        if (session.checkpointVersion > 0L &&
            !session.currentAction.isNullOrBlank() &&
            session.currentAction != runningActionName
        ) {
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组恢复失败：会话检查点不匹配，拒绝猜测物理位置，" +
                    "sessionId=${session.sessionId}，persistedAction=${session.currentAction}，" +
                    "runningAction=$runningActionName，checkpointVersion=${session.checkpointVersion}"
            )
        }

        val claimFailure = runCatching {
            resourceLocks.forceAcquire(session.resourceMask, session.sessionId)
        }.exceptionOrNull()
        if (claimFailure != null) {
            return ActionGroupRecoveryResult.MaintenanceRequired(
                "动作组恢复资源 owner 冲突，拒绝清理其他 owner：" +
                    "sessionId=${session.sessionId}，error=${claimFailure.message}"
            )
        }
        logger(
            "动作组恢复接管开始：sessionId=${session.sessionId}，type=${session.type}，" +
                "runningAction=${runningStates.first().spec.name}，resourceLocks=${resourceLocks.snapshot()}"
        )
        val actionResult = runSession(session, resumeFromRunningState = true)
        resourceLocks.release(session.sessionId)
        logger(
            "动作组恢复接管结束：sessionId=${session.sessionId}，type=${session.type}，result=$actionResult"
        )
        return ActionGroupRecoveryResult.TakeoverFinished(session, actionResult)
    }

    private suspend fun runSession(
        session: ActionGroupSession,
        resumeFromRunningState: Boolean
    ): ActionGroupResult {
        return when (session.type) {
            ActionGroupSessionType.IN_SUPPLEMENT -> InActionGroupRunner(client, logger)
                .run(session.sourceNo, session.targetNo ?: 0, resumeFromRunningState)
            ActionGroupSessionType.OUT_SELL,
            ActionGroupSessionType.OUT_MANUAL -> OutActionGroupRunner(client, logger)
                .run(
                    sourcePanNo = session.sourceNo,
                    resumeFromRunningState = resumeFromRunningState,
                    enableVoiceReminder = false
                )
        }
    }

    private suspend fun readAllStates(reader: AtomicActionStateReader): List<ActionState> {
        return allSpecs.map { spec -> ActionState(spec, reader.readResult(spec)) }
    }

    private fun stepsForSession(session: ActionGroupSession): List<ActionStep> {
        return when (session.type) {
            ActionGroupSessionType.IN_SUPPLEMENT -> inActionSteps(session.sourceNo, session.targetNo ?: 0)
            ActionGroupSessionType.OUT_SELL,
            ActionGroupSessionType.OUT_MANUAL -> outActionSteps(session.sourceNo)
        }
    }

    private fun hasOnlyCompletedPrefixThenIdle(values: List<Int>): Boolean {
        var hasSeenIdle = false
        values.forEach { value ->
            when (value) {
                AtomicActionCodes.COMPLETED -> {
                    if (hasSeenIdle) {
                        return false
                    }
                }
                AtomicActionCodes.IDLE -> hasSeenIdle = true
                else -> return false
            }
        }
        return true
    }

    private fun formatSessionStates(steps: List<ActionStep>, values: List<Int>): String {
        return steps.zip(values)
            .joinToString(",") { (step, value) ->
                "${step.spec.name}=${AtomicActionExecutor.resultCodeDesc(value)}"
            }
    }

    private data class ActionState(
        val spec: AtomicActionSpec,
        val value: AtomicReadResult
    )

    companion object {
        val inSpecs = listOf(
            AtomicActions.in1,
            AtomicActions.in2,
            AtomicActions.in3,
            AtomicActions.in4,
            AtomicActions.in5,
            AtomicActions.in6,
            AtomicActions.in7
        )
        val outSpecs = listOf(
            AtomicActions.out1,
            AtomicActions.out2,
            AtomicActions.out3,
            AtomicActions.out4,
            AtomicActions.out5
        )
        val allSpecs = inSpecs + outSpecs
    }
}
