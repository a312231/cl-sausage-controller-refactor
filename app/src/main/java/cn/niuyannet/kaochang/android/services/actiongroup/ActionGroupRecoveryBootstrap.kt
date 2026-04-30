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

    data class MaintenanceRequired(val reason: String) : ActionGroupRecoveryResult()
}

class ActionGroupRecoveryBootstrap(
    private val client: AtomicActionClient,
    private val sessionStore: ActionGroupSessionStore,
    private val resourceLocks: ResourceLocks,
    private val logger: (String) -> Unit = {}
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

        states.firstOrNull { state ->
            val value = (state.value as AtomicReadResult.Success).value
            value >= AtomicActionCodes.PARAM_ERROR
        }?.let { failedState ->
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
            sessionStore.clear()
            resourceLocks.clear()
            return ActionGroupRecoveryResult.Idle
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

        resourceLocks.clear()
        resourceLocks.forceAcquire(session.resourceMask, session.sessionId)
        logger(
            "动作组恢复接管开始：sessionId=${session.sessionId}，type=${session.type}，" +
                "runningAction=${runningStates.first().spec.name}，resourceLocks=${resourceLocks.snapshot()}"
        )
        val actionResult = when (session.type) {
            ActionGroupSessionType.IN_SUPPLEMENT -> InActionGroupRunner(client, logger)
                .run(session.sourceNo, session.targetNo ?: 0)
            ActionGroupSessionType.OUT_SELL,
            ActionGroupSessionType.OUT_MANUAL -> OutActionGroupRunner(client, logger)
                .run(session.sourceNo)
        }
        resourceLocks.release(session.sessionId)
        logger(
            "动作组恢复接管结束：sessionId=${session.sessionId}，type=${session.type}，result=$actionResult"
        )
        return ActionGroupRecoveryResult.TakeoverFinished(session, actionResult)
    }

    private suspend fun readAllStates(reader: AtomicActionStateReader): List<ActionState> {
        return allSpecs.map { spec -> ActionState(spec, reader.readResult(spec)) }
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
