package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryBootstrap
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResidueEvidence
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRegisterState
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchLaneCheckpoint
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSupplementPrefetchRecoveryPolicyTest {

    @Test
    fun evaluate_shouldAllowMatchingFailedLaneAfterTwoIdleSnapshots() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot())
        )

        assertTrue(decision.canRecover)
    }

    @Test
    fun evaluate_shouldRejectDifferentBatch() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-other",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot())
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("批次不一致"))
    }

    @Test
    fun evaluate_shouldRejectPendingOrder() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(pendingOrderNo = "ORDER-1"),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot())
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("订单"))
    }

    @Test
    fun evaluate_shouldRejectSingleSnapshot() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot())
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("连续两次"))
    }

    @Test
    fun evaluate_shouldAllowTerminalActionErrorResidueAfterTwoIdleSnapshots() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot("in1" to AtomicActionCodes.IN1_Y_RESET_FAILED))
        )

        assertTrue(decision.canRecover)
    }

    @Test
    fun evaluate_shouldRejectRunningActionRegister() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot("in1" to AtomicActionCodes.RUNNING))
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("执行中"))
    }

    @Test
    fun evaluate_shouldRejectActionMismatch() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in2",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot())
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("不一致"))
    }

    @Test
    fun evaluate_shouldRejectWhenAlgorithmEnabled() {
        val decision = ManualSupplementPrefetchRecoveryPolicy.evaluate(
            pendingBatchId = "batch-1",
            failedTargetPanNo = 13,
            failedActionName = "in1",
            session = session(),
            hasPausedSupplementSession = false,
            hasPanMoveJournal = false,
            snapshots = listOf(idleSnapshot(), idleSnapshot()),
            algorithmEnabled = true
        )

        assertFalse(decision.canRecover)
        assertTrue(decision.reason.contains("算法"))
    }

    private fun session(pendingOrderNo: String? = null): SupplementPrefetchPipelineSession =
        SupplementPrefetchPipelineSession(
            sessionId = "in_pipeline:1",
            batchId = "batch-1",
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
            deliveryLane = SupplementPrefetchLaneCheckpoint(
                sourceBoxNo = 15,
                targetPanNo = 13,
                currentAction = "in1",
                lifecycle = SupplementActionLifecycle.RUNNING_CONFIRMED
            ),
            pendingOrderNo = pendingOrderNo
        )

    private fun idleSnapshot(
        override: Pair<String, Int>? = null
    ): ActionGroupRecoveryResidueEvidence =
        ActionGroupRecoveryResidueEvidence(
            reason = "test",
            modbusConnected = true,
            legacyActionStatus201 = 0,
            moveStatus = 1,
            session = null,
            resultStates = ActionGroupRecoveryBootstrap.allSpecs.map { spec ->
                ActionGroupRegisterState(
                    actionName = spec.name,
                    resultRegister = spec.resultRegister,
                    value = if (override?.first == spec.name) override.second else AtomicActionCodes.IDLE
                )
            }
        )
}
