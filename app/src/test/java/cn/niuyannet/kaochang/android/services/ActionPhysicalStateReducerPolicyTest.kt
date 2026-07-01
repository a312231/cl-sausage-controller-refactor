package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPhysicalStateReducerPolicyTest {

    @Test
    fun shouldApplySupplementPickedFromBox_onlyForFinishedIn2CheckpointWithCoordinates() {
        assertTrue(
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 3, panNo = 9)
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = null, panNo = 9)
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 3, panNo = 9)
                    .copy(eventPhase = "running_confirmed")
            )
        )
    }

    @Test
    fun shouldApplySupplementPlacedOnPan_onlyForFinishedIn7CheckpointWithCoordinates() {
        assertTrue(
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(
                telemetry("in7", "SUPPLEMENT_PLACED_ON_PAN", boxNo = 3, panNo = 9)
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(
                telemetry("in6", "SUPPLEMENT_PLACED_ON_PAN", boxNo = 3, panNo = 9)
            )
        )
    }

    @Test
    fun shouldRejectSupplementPhysicalCheckpointWhenTelemetryCoordinatesDifferFromPlan() {
        val plannedBoxNo = 3
        val plannedPanNo = 9

        assertTrue(
            ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = plannedBoxNo, panNo = plannedPanNo),
                plannedBoxNo = plannedBoxNo,
                plannedPanNo = plannedPanNo
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 4, panNo = plannedPanNo),
                plannedBoxNo = plannedBoxNo,
                plannedPanNo = plannedPanNo
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                telemetry("in7", "SUPPLEMENT_PLACED_ON_PAN", boxNo = plannedBoxNo, panNo = 10),
                plannedBoxNo = plannedBoxNo,
                plannedPanNo = plannedPanNo
            )
        )
    }

    @Test
    fun shouldApplyOutRemovedFromPan_onlyForFinishedOut1CheckpointWithPan() {
        assertTrue(
            ActionPhysicalStateReducerPolicy.shouldApplyOutRemovedFromPan(
                telemetry("out1", "OUT_REMOVED_FROM_PAN", boxNo = null, panNo = 9)
            )
        )
        assertFalse(
            ActionPhysicalStateReducerPolicy.shouldApplyOutRemovedFromPan(
                telemetry("out1", "OUT_REMOVED_FROM_PAN", boxNo = null, panNo = null)
            )
        )
    }

    private fun telemetry(
        actionName: String,
        checkpoint: String,
        boxNo: Int?,
        panNo: Int?
    ): ActionGroupTelemetry =
        ActionGroupTelemetry(
            eventId = "$actionName:event",
            eventPhase = AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED,
            sessionId = "session",
            actionName = actionName,
            registerAddress = 1,
            resultRegister = 2,
            errorCode = null,
            errorMessage = null,
            boxNo = boxNo,
            panNo = panNo,
            orderId = null,
            resourceLocks = null,
            physicalCheckpoint = checkpoint,
            physicalLocation = null,
            elapsedMs = 10L
        )
}
