package cn.niuyannet.kaochang.android.services.actiongroup

data class ActionGroupRegisterState(
    val actionName: String,
    val resultRegister: Int,
    val value: Int?,
    val errorMessage: String? = null
) {
    fun desc(): String {
        return if (value != null) {
            "$actionName=${AtomicActionExecutor.resultCodeDesc(value)}"
        } else {
            "$actionName=读取失败（${errorMessage ?: "未知原因"}）"
        }
    }
}

data class ActionGroupRecoveryResidueEvidence(
    val reason: String,
    val modbusConnected: Boolean,
    val legacyActionStatus201: Int?,
    val moveStatus: Int,
    val session: ActionGroupSession?,
    val resultStates: List<ActionGroupRegisterState>
) {
    fun summary(): String {
        val sessionText = if (session == null) {
            "无"
        } else {
            "sessionId=${session.sessionId}，type=${session.type}，sourceNo=${session.sourceNo}，targetNo=${session.targetNo}"
        }
        return "reason=$reason，modbusConnected=$modbusConnected，" +
            "moveStatus（机械动作状态）=$moveStatus，" +
            "201（旧动作状态寄存器）=${legacyActionStatus201 ?: "读取失败"}，" +
            "session=$sessionText，resultStates=${resultStates.joinToString { it.desc() }}"
    }
}

data class ActionGroupManualResidueClearDecision(
    val canShowManualClear: Boolean,
    val reason: String
)

object ActionGroupManualResidueClearPolicy {
    fun evaluate(evidence: ActionGroupRecoveryResidueEvidence): ActionGroupManualResidueClearDecision {
        val session = evidence.session
        return when {
            !evidence.modbusConnected -> reject("Modbus 未连接，不能人工清理动作组残留")
            evidence.moveStatus != 0 -> reject(
                "moveStatus（机械动作状态）=${evidence.moveStatus}（非0=上位机机械动作未空闲）"
            )
            evidence.legacyActionStatus201 != 0 -> reject(
                "201（旧动作状态寄存器）=${evidence.legacyActionStatus201 ?: "读取失败"}（非0=旧协议动作未空闲）"
            )
            session == null -> reject("本地没有动作组残留会话")
            session.type != ActionGroupSessionType.IN_SUPPLEMENT -> reject(
                "仅允许 IN_SUPPLEMENT（补肠动作组）残留会话走人工确认清理"
            )
            evidence.resultStates.any { it.value == null } -> reject(
                "300/400动作组结果寄存器存在读取失败：${evidence.resultStates.first { it.value == null }.desc()}"
            )
            evidence.resultStates.any { it.value != AtomicActionCodes.IDLE } -> reject(
                "300/400动作组结果寄存器仍存在非空闲值：" +
                    evidence.resultStates.first { it.value != AtomicActionCodes.IDLE }.desc()
            )
            else -> ActionGroupManualResidueClearDecision(
                canShowManualClear = true,
                reason = "允许人工确认清理补肠动作组本地残留"
            )
        }
    }

    private fun reject(reason: String): ActionGroupManualResidueClearDecision =
        ActionGroupManualResidueClearDecision(
            canShowManualClear = false,
            reason = reason
        )
}
