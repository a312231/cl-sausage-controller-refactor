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

data class ManualDeviceRecoveryCloseoutDecision(
    val canCloseout: Boolean,
    val reason: String
)

data class ManualDeviceRecoveryCloseoutResult(
    val ok: Boolean,
    val reason: String
)

object ManualDeviceRecoveryCloseoutPolicy {
    fun evaluate(
        evidence: ActionGroupRecoveryResidueEvidence,
        hasProcessingOrder: Boolean,
        hasPanMoveJournal: Boolean,
        hasDiscardJournal: Boolean,
        durableEvidenceCorrupted: Boolean
    ): ManualDeviceRecoveryCloseoutDecision {
        val expectedActions = ActionGroupRecoveryBootstrap.allSpecs.map { it.name }.toSet()
        return when {
            !evidence.modbusConnected -> reject("下位机未连接")
            evidence.legacyActionStatus201 != AtomicActionCodes.IDLE ->
                reject("下位机仍在执行旧动作")
            hasProcessingOrder -> reject("订单仍在执行，暂不能恢复")
            hasDiscardJournal -> reject("存在待核对的丢弃记录，请先完成专用恢复")
            hasPanMoveJournal -> reject("存在待核对的搬盘记录，请先完成专用恢复")
            durableEvidenceCorrupted -> reject("双 lane 本地恢复记录损坏，禁止自动清理")
            evidence.moveStatus !in 0..2 -> reject("当前机械状态需要专用恢复")
            evidence.resultStates.size != expectedActions.size ||
                evidence.resultStates.map { it.actionName }.toSet() != expectedActions ->
                reject("动作结果寄存器读取不完整")
            evidence.resultStates.any { it.value == null } ->
                reject("动作结果寄存器读取失败")
            evidence.resultStates.any {
                it.value != AtomicActionCodes.IDLE && it.value != AtomicActionCodes.COMPLETED
            } -> reject("动作结果寄存器未收口")
            else -> ManualDeviceRecoveryCloseoutDecision(
                canCloseout = true,
                reason = "下位机已空闲，允许人工恢复本地残留"
            )
        }
    }

    private fun reject(reason: String) = ManualDeviceRecoveryCloseoutDecision(
        canCloseout = false,
        reason = reason
    )
}

object ActionGroupManualResidueClearPolicy {
    fun evaluate(evidence: ActionGroupRecoveryResidueEvidence): ActionGroupManualResidueClearDecision {
        val session = evidence.session
        return when {
            !evidence.modbusConnected -> reject("Modbus 未连接，不能人工清理动作组残留")
            evidence.moveStatus !in 0..1 -> reject(
                "moveStatus（机械动作状态）=${evidence.moveStatus}（非补肠恢复态，禁止人工清理）"
            )
            evidence.legacyActionStatus201 != 0 -> reject(
                "201（旧动作状态寄存器）=${evidence.legacyActionStatus201 ?: "读取失败"}（非0=旧协议动作未空闲）"
            )
            session == null && evidence.moveStatus != 1 -> reject("本地没有动作组残留会话")
            session == null && evidence.resultStates.any { it.value == null } -> reject(
                "300/400动作组结果寄存器存在读取失败：${evidence.resultStates.first { it.value == null }.desc()}"
            )
            session == null && evidence.resultStates.any { it.value != AtomicActionCodes.IDLE } -> reject(
                "无会话孤儿机械忙态要求全部300/400动作组结果寄存器为0：" +
                    evidence.resultStates.first { it.value != AtomicActionCodes.IDLE }.desc()
            )
            session == null -> ActionGroupManualResidueClearDecision(
                canShowManualClear = true,
                reason = "允许人工确认清理无会话孤儿机械忙态"
            )
            session.type != ActionGroupSessionType.IN_SUPPLEMENT -> reject(
                "仅允许 IN_SUPPLEMENT（补肠动作组）残留会话走人工确认清理"
            )
            evidence.resultStates.any { it.value == null } -> reject(
                "300/400动作组结果寄存器存在读取失败：${evidence.resultStates.first { it.value == null }.desc()}"
            )
            evidence.resultStates.any { it.value == AtomicActionCodes.RUNNING } -> reject(
                "300/400动作组结果寄存器仍存在执行中值：" +
                    evidence.resultStates.first { it.value == AtomicActionCodes.RUNNING }.desc()
            )
            else -> ActionGroupManualResidueClearDecision(
                canShowManualClear = true,
                reason = "允许人工确认清理补肠动作组本地残留"
            )
        }
    }

    fun evaluateOrphanBusyStateSnapshots(
        snapshots: List<ActionGroupRecoveryResidueEvidence>
    ): ActionGroupManualResidueClearDecision {
        if (snapshots.size != 2) {
            return reject("必须连续读取两次动作组空闲证据，实际=${snapshots.size}次")
        }
        snapshots.forEachIndexed { index, evidence ->
            if (evidence.session != null) {
                return reject("第${index + 1}次动作组空闲证据出现本地动作组会话")
            }
            val decision = evaluate(evidence)
            if (!decision.canShowManualClear) {
                return reject("第${index + 1}次动作组空闲证据未通过：${decision.reason}")
            }
        }
        return ActionGroupManualResidueClearDecision(
            canShowManualClear = true,
            reason = "连续两次确认无会话孤儿机械忙态已空闲"
        )
    }

    private fun reject(reason: String): ActionGroupManualResidueClearDecision =
        ActionGroupManualResidueClearDecision(
            canShowManualClear = false,
            reason = reason
        )
}
