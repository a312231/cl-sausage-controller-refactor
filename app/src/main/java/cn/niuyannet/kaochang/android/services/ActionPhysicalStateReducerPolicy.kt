package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor

internal object ActionPhysicalStateReducerPolicy {
    const val CHECKPOINT_SUPPLEMENT_PICKED_FROM_BOX = "SUPPLEMENT_PICKED_FROM_BOX"
    const val CHECKPOINT_SUPPLEMENT_PLACED_ON_PAN = "SUPPLEMENT_PLACED_ON_PAN"
    const val CHECKPOINT_OUT_REMOVED_FROM_PAN = "OUT_REMOVED_FROM_PAN"

    fun shouldApplySupplementPickedFromBox(telemetry: ActionGroupTelemetry): Boolean =
        isFinishedCheckpoint(telemetry, "in2", CHECKPOINT_SUPPLEMENT_PICKED_FROM_BOX) &&
            telemetry.boxNo != null &&
            telemetry.panNo != null

    fun shouldApplySupplementPlacedOnPan(telemetry: ActionGroupTelemetry): Boolean =
        isFinishedCheckpoint(telemetry, "in7", CHECKPOINT_SUPPLEMENT_PLACED_ON_PAN) &&
            telemetry.boxNo != null &&
            telemetry.panNo != null

    fun shouldApplyOutRemovedFromPan(telemetry: ActionGroupTelemetry): Boolean =
        isFinishedCheckpoint(telemetry, "out1", CHECKPOINT_OUT_REMOVED_FROM_PAN) &&
            telemetry.panNo != null

    fun isSupplementTelemetryForPlannedMove(
        telemetry: ActionGroupTelemetry,
        plannedBoxNo: Int,
        plannedPanNo: Int
    ): Boolean =
        telemetry.boxNo == plannedBoxNo && telemetry.panNo == plannedPanNo

    private fun isFinishedCheckpoint(
        telemetry: ActionGroupTelemetry,
        actionName: String,
        checkpoint: String
    ): Boolean =
        telemetry.eventPhase == AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED &&
            telemetry.actionName == actionName &&
            telemetry.physicalCheckpoint == checkpoint &&
            telemetry.errorCode == null
}
