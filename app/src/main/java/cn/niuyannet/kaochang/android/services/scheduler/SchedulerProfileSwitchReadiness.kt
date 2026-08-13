package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import cn.niuyannet.kaochang.scheduler.SchedulerProfileSwitchBlocker
import cn.niuyannet.kaochang.scheduler.SchedulerProfileSwitchRuntimeSnapshot
import java.util.Collections
import java.util.concurrent.CancellationException

enum class SchedulerProfileSwitchReadinessCode {
    AVAILABLE,
    BLOCKED,
    UNAVAILABLE
}

enum class SchedulerProfileSwitchReadinessIssue {
    CONFIG_UNAVAILABLE,
    CONFIG_READ_FAILED,
    INVALID_RUNTIME_CONFIG,
    PAN_CACHE_UNAVAILABLE,
    PAN_CACHE_READ_FAILED,
    ORDER_STATE_READ_FAILED,
    ORDER_CLAIM_READ_FAILED,
    ACTION_GROUP_SESSION_CORRUPTED,
    ACTION_GROUP_SESSION_READ_FAILED,
    PAUSED_SUPPLEMENT_SESSION_CORRUPTED,
    PAUSED_SUPPLEMENT_SESSION_READ_FAILED,
    PREFETCH_SESSION_CORRUPTED,
    PREFETCH_SESSION_READ_FAILED,
    SUPPLEMENT_BATCH_INTENT_READ_FAILED,
    PAN_MOVE_JOURNAL_READ_FAILED,
    DISCARD_JOURNAL_READ_FAILED,
    EMERGENCY_RESET_FENCE_READ_FAILED,
    RESOURCE_LOCKS_READ_FAILED,
    PROCESS_OWNER_READ_FAILED
}

enum class SchedulerProfileSwitchReadinessDiagnostic(
    val blocker: SchedulerProfileSwitchBlocker
) {
    DEVICE_NOT_IDLE(SchedulerProfileSwitchBlocker.DEVICE_NOT_IDLE),
    ACTIVE_ORDER(SchedulerProfileSwitchBlocker.ACTIVE_ORDER),
    WHOLE_ORDER_CLAIM(SchedulerProfileSwitchBlocker.WHOLE_ORDER_CLAIM),
    OUT_ACTION_SESSION(SchedulerProfileSwitchBlocker.OUT_ACTION_SESSION),
    SUPPLEMENT_OWNER(SchedulerProfileSwitchBlocker.SUPPLEMENT_OWNER),
    CLEANING_OWNER(SchedulerProfileSwitchBlocker.CLEANING_OWNER),
    MAINTENANCE_OWNER(SchedulerProfileSwitchBlocker.MAINTENANCE_OWNER)
}

class SchedulerProfileSwitchReadinessResult private constructor(
    val code: SchedulerProfileSwitchReadinessCode,
    val runtimeSnapshot: SchedulerProfileSwitchRuntimeSnapshot?,
    issues: List<SchedulerProfileSwitchReadinessIssue>,
    diagnostics: List<SchedulerProfileSwitchReadinessDiagnostic>
) {
    val issues: List<SchedulerProfileSwitchReadinessIssue> =
        Collections.unmodifiableList(ArrayList(issues))
    val diagnostics: List<SchedulerProfileSwitchReadinessDiagnostic> =
        Collections.unmodifiableList(ArrayList(diagnostics))

    init {
        when (code) {
            SchedulerProfileSwitchReadinessCode.AVAILABLE -> require(
                runtimeSnapshot != null && issues.isEmpty() && diagnostics.isEmpty()
            ) { "Available scheduler readiness requires a blocker-free runtime snapshot" }

            SchedulerProfileSwitchReadinessCode.BLOCKED -> require(
                runtimeSnapshot != null && issues.isEmpty() && diagnostics.isNotEmpty()
            ) { "Blocked scheduler readiness requires a runtime snapshot and blockers" }

            SchedulerProfileSwitchReadinessCode.UNAVAILABLE -> require(
                runtimeSnapshot == null && issues.isNotEmpty() && diagnostics.isEmpty()
            ) { "Unavailable scheduler readiness requires issues and no runtime snapshot or diagnostics" }
        }
    }

    companion object {
        @JvmSynthetic
        internal fun available(
            runtimeSnapshot: SchedulerProfileSwitchRuntimeSnapshot
        ): SchedulerProfileSwitchReadinessResult = SchedulerProfileSwitchReadinessResult(
            code = SchedulerProfileSwitchReadinessCode.AVAILABLE,
            runtimeSnapshot = runtimeSnapshot,
            issues = emptyList(),
            diagnostics = emptyList()
        )

        @JvmSynthetic
        internal fun blocked(
            runtimeSnapshot: SchedulerProfileSwitchRuntimeSnapshot,
            diagnostics: List<SchedulerProfileSwitchReadinessDiagnostic>
        ): SchedulerProfileSwitchReadinessResult = SchedulerProfileSwitchReadinessResult(
            code = SchedulerProfileSwitchReadinessCode.BLOCKED,
            runtimeSnapshot = runtimeSnapshot,
            issues = emptyList(),
            diagnostics = diagnostics
        )

        @JvmSynthetic
        internal fun unavailable(
            issues: List<SchedulerProfileSwitchReadinessIssue>
        ): SchedulerProfileSwitchReadinessResult = SchedulerProfileSwitchReadinessResult(
            code = SchedulerProfileSwitchReadinessCode.UNAVAILABLE,
            runtimeSnapshot = null,
            issues = issues.distinct(),
            diagnostics = emptyList()
        )
    }
}

internal data class SchedulerProfileSwitchConfigEvidence(
    val moveStatus: Int,
    val onlineStatus: Int,
    val inspectionMode: Int,
    val errorStatus: Int,
    val isEnable: Int,
    val modeType: Int,
    val transitionMode: Int,
    val highConcurrencyResidualDrain: Int,
    val faultHeatPauseActive: Int,
    val hasFaultHeatRecoveryJournal: Boolean,
    val hasPendingDiscardPositions: Boolean,
    val pendingDisableActionGroupV2: Int,
    val useActionGroupV2: Int
)

internal data class SchedulerProfileSwitchOwnerEvidence(
    val algorithmIsEnabled: Boolean,
    val hasActiveCookingBatch: Boolean,
    val hasProcessingOrder: Boolean,
    val hasOrderPause: Boolean,
    val hasWholeOrderClaim: Boolean,
    val actionGroupSessionType: ActionGroupSessionType?,
    val hasPausedSupplementSession: Boolean,
    val hasPrefetchSession: Boolean,
    val hasSupplementBatchIntentEvidence: Boolean,
    val resourceLocksIdle: Boolean,
    val hasPanMoveJournal: Boolean,
    val hasDiscardJournal: Boolean,
    val hasEmergencyResetFence: Boolean,
    val manualTakeTaskActive: Boolean,
    val manualOutRecoveryReserved: Boolean,
    val supplementResumeRunning: Boolean,
    val actionGroupRecoveryVerified: Boolean,
    val hasGuardedSupplementCloseoutResidue: Boolean
)

internal data class SchedulerProfileSwitchEvidence(
    val config: SchedulerProfileSwitchConfigEvidence,
    val owners: SchedulerProfileSwitchOwnerEvidence,
    val issues: List<SchedulerProfileSwitchReadinessIssue>
)

internal object SchedulerProfileSwitchReadinessMapper {
    fun evaluate(evidence: SchedulerProfileSwitchEvidence): SchedulerProfileSwitchReadinessResult {
        if (evidence.issues.isNotEmpty()) {
            return SchedulerProfileSwitchReadinessResult.unavailable(evidence.issues)
        }
        if (!evidence.config.isValid()) {
            return SchedulerProfileSwitchReadinessResult.unavailable(
                listOf(SchedulerProfileSwitchReadinessIssue.INVALID_RUNTIME_CONFIG)
            )
        }

        val config = evidence.config
        val owners = evidence.owners
        val deviceIdle = config.moveStatus == 0 &&
            config.isEnable == 0 &&
            !owners.algorithmIsEnabled &&
            config.onlineStatus != 4 &&
            !owners.hasActiveCookingBatch &&
            config.transitionMode == 0 &&
            config.highConcurrencyResidualDrain == 0 &&
            config.faultHeatPauseActive == 0 &&
            !config.hasFaultHeatRecoveryJournal &&
            !config.hasPendingDiscardPositions &&
            config.pendingDisableActionGroupV2 == 0 &&
            !owners.hasPanMoveJournal &&
            !owners.hasDiscardJournal &&
            !owners.hasEmergencyResetFence &&
            owners.resourceLocksIdle
        val hasActiveOrder = owners.hasProcessingOrder || owners.hasOrderPause
        val hasOutActionSession = owners.actionGroupSessionType in setOf(
            ActionGroupSessionType.OUT_SELL,
            ActionGroupSessionType.OUT_MANUAL
        ) || owners.manualTakeTaskActive || owners.manualOutRecoveryReserved
        val hasSupplementOwner = owners.actionGroupSessionType == ActionGroupSessionType.IN_SUPPLEMENT ||
            owners.hasPausedSupplementSession ||
            owners.hasPrefetchSession ||
            owners.hasSupplementBatchIntentEvidence ||
            owners.supplementResumeRunning
        val hasCleaningOwner = config.moveStatus == 5
        val hasMaintenanceOwner = config.inspectionMode == 1 ||
            config.onlineStatus == 3 ||
            config.errorStatus != 0 ||
            config.useActionGroupV2 != 1 ||
            !owners.actionGroupRecoveryVerified ||
            owners.hasGuardedSupplementCloseoutResidue

        val runtime = SchedulerProfileSwitchRuntimeSnapshot(
            deviceIdle = deviceIdle,
            hasActiveOrder = hasActiveOrder,
            hasWholeOrderClaim = owners.hasWholeOrderClaim,
            hasOutActionSession = hasOutActionSession,
            hasSupplementOwner = hasSupplementOwner,
            hasCleaningOwner = hasCleaningOwner,
            hasMaintenanceOwner = hasMaintenanceOwner
        )
        val diagnostics = buildList {
            if (!runtime.deviceIdle) add(SchedulerProfileSwitchReadinessDiagnostic.DEVICE_NOT_IDLE)
            if (runtime.hasActiveOrder) add(SchedulerProfileSwitchReadinessDiagnostic.ACTIVE_ORDER)
            if (runtime.hasWholeOrderClaim) add(SchedulerProfileSwitchReadinessDiagnostic.WHOLE_ORDER_CLAIM)
            if (runtime.hasOutActionSession) add(SchedulerProfileSwitchReadinessDiagnostic.OUT_ACTION_SESSION)
            if (runtime.hasSupplementOwner) add(SchedulerProfileSwitchReadinessDiagnostic.SUPPLEMENT_OWNER)
            if (runtime.hasCleaningOwner) add(SchedulerProfileSwitchReadinessDiagnostic.CLEANING_OWNER)
            if (runtime.hasMaintenanceOwner) add(SchedulerProfileSwitchReadinessDiagnostic.MAINTENANCE_OWNER)
        }
        return if (diagnostics.isEmpty()) {
            SchedulerProfileSwitchReadinessResult.available(runtime)
        } else {
            SchedulerProfileSwitchReadinessResult.blocked(runtime, diagnostics)
        }
    }

    private fun SchedulerProfileSwitchConfigEvidence.isValid(): Boolean =
        moveStatus in 0..5 &&
            onlineStatus in 0..4 &&
            inspectionMode in 0..1 &&
            errorStatus in 0..2 &&
            isEnable in 0..1 &&
            modeType in 1..2 &&
            transitionMode in 0..2 &&
            highConcurrencyResidualDrain in 0..1 &&
            faultHeatPauseActive in 0..1 &&
            pendingDisableActionGroupV2 in 0..1 &&
            useActionGroupV2 in 0..1
}

internal inline fun <T> captureSchedulerProfileSwitchEvidence(
    issue: SchedulerProfileSwitchReadinessIssue,
    issues: MutableList<SchedulerProfileSwitchReadinessIssue>,
    block: () -> T
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw interrupted
} catch (_: Exception) {
    issues += issue
    null
}
