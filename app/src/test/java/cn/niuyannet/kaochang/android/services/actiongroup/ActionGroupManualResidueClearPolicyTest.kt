package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionGroupManualResidueClearPolicyTest {

    @Test
    fun evaluate_shouldAllowInSupplementResidueWhenAllEvidenceIsIdle() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults()
            )
        )

        assertEquals(true, decision.canShowManualClear)
        assertEquals("允许人工确认清理补肠动作组本地残留", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectOutSellSessionBecauseRecoveryAutoClearsIt() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = session(ActionGroupSessionType.OUT_SELL),
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("仅允许 IN_SUPPLEMENT（补肠动作组）残留会话走人工确认清理", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectOutManualSessionBecauseRecoveryAutoClearsIt() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = session(ActionGroupSessionType.OUT_MANUAL),
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("仅允许 IN_SUPPLEMENT（补肠动作组）残留会话走人工确认清理", decision.reason)
    }

    @Test
    fun evaluate_shouldAllowWhenResultRegisterHasTerminalErrorResidue() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults() + ("in1" to AtomicActionCodes.IN1_Y_RESET_FAILED)
            )
        )

        assertEquals(true, decision.canShowManualClear)
        assertEquals("允许人工确认清理补肠动作组本地残留", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenAnyResultRegisterIsRunning() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults() + ("in3" to AtomicActionCodes.RUNNING)
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("300/400动作组结果寄存器仍存在执行中值：in3=0x0001（执行中）", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenLegacyActionStatusIsNotIdle() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                legacyActionStatus201 = 1,
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("201（旧动作状态寄存器）=1（非0=旧协议动作未空闲）", decision.reason)
    }

    @Test
    fun evaluate_shouldAllowSupplementRecoveryMoveStatusWithTerminalErrorResidue() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                moveStatus = 1,
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults() + ("in1" to AtomicActionCodes.IN1_Y_RESET_FAILED)
            )
        )

        assertEquals(true, decision.canShowManualClear)
        assertEquals("允许人工确认清理补肠动作组本地残留", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenMoveStatusIsNotSupplementRecoveryState() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                moveStatus = 2,
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("moveStatus（机械动作状态）=2（非补肠恢复态，禁止人工清理）", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenModbusDisconnected() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                modbusConnected = false,
                session = session(ActionGroupSessionType.IN_SUPPLEMENT),
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("Modbus 未连接，不能人工清理动作组残留", decision.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenSessionMissing() {
        val decision = ActionGroupManualResidueClearPolicy.evaluate(
            evidence(
                session = null,
                resultValues = idleResults()
            )
        )

        assertEquals(false, decision.canShowManualClear)
        assertEquals("本地没有动作组残留会话", decision.reason)
    }

    private fun session(type: ActionGroupSessionType): ActionGroupSession =
        ActionGroupSession(
            sessionId = "session-1",
            type = type,
            sourceNo = 1,
            targetNo = 2,
            resourceMask = ResourceMask.bottom
        )

    private fun evidence(
        modbusConnected: Boolean = true,
        legacyActionStatus201: Int? = 0,
        moveStatus: Int = 0,
        session: ActionGroupSession?,
        resultValues: Map<String, Int?>
    ): ActionGroupRecoveryResidueEvidence =
        ActionGroupRecoveryResidueEvidence(
            reason = "test",
            modbusConnected = modbusConnected,
            legacyActionStatus201 = legacyActionStatus201,
            moveStatus = moveStatus,
            session = session,
            resultStates = resultValues.map { (name, value) ->
                ActionGroupRegisterState(
                    actionName = name,
                    resultRegister = 300,
                    value = value
                )
            }
        )

    private fun idleResults(): Map<String, Int?> =
        ActionGroupRecoveryBootstrap.allSpecs.associate { it.name to AtomicActionCodes.IDLE }
}
