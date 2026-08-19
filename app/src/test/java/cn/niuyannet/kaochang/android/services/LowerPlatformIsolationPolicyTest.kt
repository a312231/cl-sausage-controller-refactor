package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineResult
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineTask
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.services.actiongroup.ResourceMask
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementActionLifecycle
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementLaneRole
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchLaneCheckpoint
import cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPipelineSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerPlatformIsolationPolicyTest {

    @Test
    fun parallelSemiFaultClosure_shouldAllowOnlyFreshIn1ToIn3WithMatchingPhysicalFacts() {
        val delivery = SupplementPrefetchLaneCheckpoint(
            sourceBoxNo = 9,
            sourceBoxCandidates = listOf(9),
            targetPanNo = 28,
            currentAction = "in1",
            lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
            lastConfirmedStage = "in1"
        )
        val session = SupplementPrefetchPipelineSession(
            sessionId = "session-semi-fault",
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
            deliveryLane = delivery,
            mechanicalClaimState = MechanicalClaimState.CLAIMED,
            mechanicalClaimRevision = 3L
        )

        assertTrue(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                actionName = "in1",
                sourceBoxNo = 9,
                targetPanNo = 28,
                result = failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = true),
                session = session,
                expectedSessionId = session.sessionId,
                laneRole = SupplementLaneRole.DELIVERY,
                durableStateSaved = true
            )
        )
        assertTrue(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                actionName = "in2",
                sourceBoxNo = 9,
                targetPanNo = 28,
                result = failed(AtomicActionCodes.X_RESET_FAILED, fresh = true),
                session = session.copy(
                    deliveryLane = delivery.copy(currentAction = "in2", lastConfirmedStage = "in2")
                ),
                expectedSessionId = session.sessionId,
                laneRole = SupplementLaneRole.DELIVERY,
                durableStateSaved = true
            )
        )
        assertTrue(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                actionName = "in3",
                sourceBoxNo = 9,
                targetPanNo = 28,
                result = failed(AtomicActionCodes.IN3_X_RESET_FAILED, fresh = true),
                session = session.copy(
                    deliveryLane = delivery.copy(
                        currentAction = "in3",
                        lastConfirmedStage = "in3",
                        boxDeductionApplied = true
                    )
                ),
                expectedSessionId = session.sessionId,
                laneRole = SupplementLaneRole.DELIVERY,
                durableStateSaved = true
            )
        )

        listOf(
            "in4" to failed(AtomicActionCodes.PARAM_ERROR, fresh = true),
            "in1" to failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = false)
        ).forEach { (action, result) ->
            assertFalse(
                ParallelSupplementSemiFaultClosurePolicy.canClose(
                    action,
                    9,
                    28,
                    result,
                    session.copy(deliveryLane = delivery.copy(currentAction = action, lastConfirmedStage = action)),
                    session.sessionId,
                    SupplementLaneRole.DELIVERY,
                    durableStateSaved = true
                )
            )
        }
        assertFalse(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 9, 28, AtomicActionResult.ModbusError("drop"), session,
                session.sessionId, SupplementLaneRole.DELIVERY, durableStateSaved = true
            )
        )
        assertFalse(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 9, 28, AtomicActionResult.Timeout("timeout"), session,
                session.sessionId, SupplementLaneRole.DELIVERY, durableStateSaved = true
            )
        )
        assertFalse(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 9, 28, failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = true), session,
                session.sessionId, SupplementLaneRole.DELIVERY, durableStateSaved = false
            )
        )
        assertFalse(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 9, 28, failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = true),
                session.copy(resourceMask = ResourceMask.bottom), session.sessionId,
                SupplementLaneRole.DELIVERY, durableStateSaved = true
            )
        )

        val completedDelivery = delivery.copy(
            currentAction = "in7",
            lastConfirmedStage = "in7",
            lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED,
            boxDeductionApplied = true,
            panPlacementApplied = true
        )
        val failedPrefetch = SupplementPrefetchLaneCheckpoint(
            sourceBoxNo = 10,
            sourceBoxCandidates = listOf(10),
            targetPanNo = 29,
            currentAction = "in1",
            lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
            lastConfirmedStage = "in1"
        )
        val prefetchSession = session.copy(
            deliveryLane = completedDelivery,
            prefetchLane = failedPrefetch
        )
        assertTrue(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 10, 29, failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = true),
                prefetchSession, session.sessionId, SupplementLaneRole.PREFETCH, durableStateSaved = true
            )
        )
        assertFalse(
            ParallelSupplementSemiFaultClosurePolicy.canClose(
                "in1", 10, 29, failed(AtomicActionCodes.IN1_X_RESET_FAILED, fresh = true),
                prefetchSession.copy(
                    deliveryLane = completedDelivery.copy(lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED)
                ),
                session.sessionId, SupplementLaneRole.PREFETCH, durableStateSaved = true
            )
        )
    }

    @Test
    fun rawTimeout_shouldNeverBypassExplicitTerminalStateResolution() {
        val timeout = AtomicActionResult.Timeout("in4 timeout")

        assertEquals(
            LowerPlatformIsolationPolicy.Decision.NotIsolatable,
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                SupplementBatchType.HIGH_HEAT_25_33, "in4", timeout, false
            )
        )
        assertEquals(
            LowerPlatformIsolationPolicy.Decision.NotIsolatable,
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                SupplementBatchType.HIGH_HEAT_25_33, "in4", AtomicActionResult.ModbusError("drop"), false
            )
        )
    }

    @Test
    fun supplementActionFailure_shouldClassifyLowerPlatformFailuresWithoutHidingIn5ResidueRisk() {
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in1",
            failed(AtomicActionCodes.IN1_X_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in2",
            failed(AtomicActionCodes.X_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in3",
            failed(AtomicActionCodes.IN3_Y_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in3",
            failed(AtomicActionCodes.IN3_X_RESET_FAILED)
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in4",
            failed(AtomicActionCodes.PARAM_ERROR),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in4",
            failed(AtomicActionCodes.PARAM_ERROR)
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED)
        ))

        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in2",
            failed(AtomicActionCodes.IR_PICK_FAILED),
            hasPrefetchedNext = false
        ))
    }

    @Test
    fun supplementActionFailure_shouldKeepHardFailureForUpperPlatformRiskAndUntrustedResults() {
        listOf(
            AtomicActionCodes.LIFT_FAILED,
            AtomicActionCodes.LIFT_DOWN_LOCKED_ROTOR,
            AtomicActionCodes.LIFT_UP_LOCKED_ROTOR,
            AtomicActionCodes.LIFT_NOT_BOTTOM
        ).forEach { errorCode ->
            assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
                "in4",
                failed(errorCode),
                hasPrefetchedNext = false
            ))
            assertFalse(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
                SupplementBatchType.HIGH_HEAT_25_33,
                "in4",
                failed(errorCode),
                hasPrefetchedNext = false
            ))
        }
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in6",
            failed(AtomicActionCodes.LIFT_UP_LOCKED_ROTOR),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in7",
            failed(AtomicActionCodes.IN7_SAU_ADD_BUSY),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "out1",
            failed(AtomicActionCodes.OUT_SLOT_PARAM),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = true
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED)
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            AtomicActionResult.Timeout("超时"),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            AtomicActionResult.ModbusError("通信失败"),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in1",
            failed(AtomicActionCodes.IN1_X_RESET_FAILED)
        ))
    }

    @Test
    fun prefetchedNext_shouldNotByItselfEscalateOrdinaryLowerPlatformFailure() {
        assertEquals(
            LowerPlatformIsolationPolicy.Decision.IsolatedSilent,
            LowerPlatformIsolationPolicy.classifySupplementActionFailure(
                actionName = "in1",
                result = failed(AtomicActionCodes.IN1_X_RESET_FAILED),
                hasPrefetchedNext = true
            )
        )
    }

    @Test
    fun parallelIn2E5_shouldCloseAtLaneLevelButKeepUncertainEvidenceFailClosed() {
        assertEquals(
            LowerPlatformIsolationPolicy.Decision.IsolatedSilent,
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                batchType = SupplementBatchType.LOW_HEAT_13_15,
                actionName = "in2",
                result = failed(AtomicActionCodes.IR_PICK_FAILED),
                hasPrefetchedNext = true,
                parallelSessionActive = true
            )
        )
        assertEquals(
            LowerPlatformIsolationPolicy.Decision.NotIsolatable,
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                batchType = SupplementBatchType.LOW_HEAT_13_15,
                actionName = "in2",
                result = AtomicActionResult.ModbusError("drop"),
                hasPrefetchedNext = true,
                parallelSessionActive = true
            )
        )
    }

    @Test
    fun parallelE5Closure_shouldRequireSafeFailedLaneAndCompletedDeliveryEvidence() {
        val failure = InActionGroupPipelineResult.Failed(
            completed = emptyList(),
            task = InActionGroupPipelineTask(listOf(18), 14),
            sourceBoxNo = 18,
            failedSourceBoxNos = listOf(18),
            actionName = "in2",
            result = failed(AtomicActionCodes.IR_PICK_FAILED),
            message = "e5",
            hasPrefetchedNext = false,
            laneRole = SupplementLaneRole.PREFETCH
        )
        val completedDelivery = SupplementPrefetchLaneCheckpoint(
            sourceBoxNo = 17,
            sourceBoxCandidates = listOf(17),
            targetPanNo = 13,
            currentAction = "in7",
            lifecycle = SupplementActionLifecycle.PHYSICAL_APPLIED,
            lastConfirmedStage = "in7",
            boxDeductionApplied = true,
            panPlacementApplied = true
        )
        val failedPrefetch = SupplementPrefetchLaneCheckpoint(
            sourceBoxNo = 18,
            sourceBoxCandidates = listOf(18),
            targetPanNo = 14,
            currentAction = "in2",
            lifecycle = SupplementActionLifecycle.TERMINAL_CONFIRMED,
            lastConfirmedStage = "in2"
        )
        val session = SupplementPrefetchPipelineSession(
            sessionId = "session-e5",
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
            deliveryLane = completedDelivery,
            prefetchLane = failedPrefetch
        )

        assertTrue(ParallelSupplementE5ClosurePolicy.canRelease(failure, session, "session-e5"))
        assertFalse(
            ParallelSupplementE5ClosurePolicy.canRelease(
                failure,
                session.copy(deliveryLane = completedDelivery.copy(panPlacementApplied = false)),
                "session-e5"
            )
        )
        assertFalse(ParallelSupplementE5ClosurePolicy.canRelease(failure, session, "other-owner"))
    }

    @Test
    fun supplementBatchFailure_shouldIsolateAllLowerPlatformActionsForHeatBatchButNotInitialization() {
        assertFalse(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
            batchType = SupplementBatchType.LOW_HEAT_13_15,
            actionName = "in2",
            result = failed(AtomicActionCodes.IR_PICK_FAILED),
            hasPrefetchedNext = true
        ))
        assertEquals(
            LowerPlatformIsolationPolicy.Decision.NotIsolatable,
            LowerPlatformIsolationPolicy.classifyForSupplementBatch(
                batchType = SupplementBatchType.LOW_HEAT_13_15,
                actionName = "in2",
                result = failed(AtomicActionCodes.IR_PICK_FAILED),
                hasPrefetchedNext = false
            )
        )
        assertTrue(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
            batchType = SupplementBatchType.HIGH_HEAT_25_33,
            actionName = "in5",
            result = failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = true
        ))
        assertFalse(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
            batchType = SupplementBatchType.LOW_HEAT_13_15,
            actionName = "in6",
            result = failed(AtomicActionCodes.LIFT_UP_LOCKED_ROTOR),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
            batchType = SupplementBatchType.LOW_INITIALIZATION_1_9,
            actionName = "in3",
            result = failed(AtomicActionCodes.IN3_Y_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.shouldTreatAsLowerPlatformSemiFault(
            batchType = SupplementBatchType.HIGH_INITIALIZATION_1_21,
            actionName = "in5",
            result = failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = false
        ))
    }

    @Test
    fun buildReason_shouldDescribeIsolationWithoutMaintenanceWording() {
        val reason = LowerPlatformIsolationPolicy.buildIsolationReason(
            actionName = "in5",
            result = failed(AtomicActionCodes.IR_THROW_FAILED),
            targetPanNo = 9,
            sourceBoxNo = 12,
            hasPrefetchedNext = false
        )

        assertTrue(reason.contains("下平台隔离"))
        assertTrue(reason.contains("action=in5"))
        assertTrue(reason.contains("targetPan=9"))
        assertTrue(reason.contains("sourceBox=12"))
        assertFalse(reason.contains("维护"))
    }

    private fun failed(code: Int, fresh: Boolean = false): AtomicActionResult.Failed =
        AtomicActionResult.Failed(code, "failure", freshExecutionObserved = fresh)
}
