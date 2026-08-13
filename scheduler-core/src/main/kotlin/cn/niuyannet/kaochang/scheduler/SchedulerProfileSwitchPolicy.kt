package cn.niuyannet.kaochang.scheduler

import java.util.Collections

data class SchedulerProfileSwitchRuntimeSnapshot(
    val deviceIdle: Boolean,
    val hasActiveOrder: Boolean,
    val hasWholeOrderClaim: Boolean,
    val hasOutActionSession: Boolean,
    val hasSupplementOwner: Boolean,
    val hasCleaningOwner: Boolean,
    val hasMaintenanceOwner: Boolean
)

enum class SchedulerProfileSwitchBlocker {
    DEVICE_NOT_IDLE,
    ACTIVE_ORDER,
    WHOLE_ORDER_CLAIM,
    OUT_ACTION_SESSION,
    SUPPLEMENT_OWNER,
    CLEANING_OWNER,
    MAINTENANCE_OWNER
}

enum class SchedulerProfileSwitchDecisionCode {
    NO_CHANGE,
    SWITCH_ALLOWED,
    REQUESTED_PROFILE_ID_INVALID,
    REQUESTED_PROFILE_NOT_REGISTERED,
    CURRENT_PROFILE_ID_INVALID,
    CURRENT_PROFILE_NOT_REGISTERED,
    RUNTIME_BUSY
}

class SchedulerProfileSwitchPlan private constructor(
    val code: SchedulerProfileSwitchDecisionCode,
    val currentProfileId: SchedulerProfileId?,
    val requestedProfileId: SchedulerProfileId?,
    blockers: List<SchedulerProfileSwitchBlocker>
) {
    val blockers: List<SchedulerProfileSwitchBlocker> =
        Collections.unmodifiableList(ArrayList(blockers))

    val requiresPersistence: Boolean
        get() = code == SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED

    init {
        when (code) {
            SchedulerProfileSwitchDecisionCode.NO_CHANGE -> require(
                currentProfileId != null && currentProfileId == requestedProfileId && blockers.isEmpty()
            ) { "NO_CHANGE requires one registered profile id and no blockers" }

            SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED -> require(
                currentProfileId != null && requestedProfileId != null &&
                    currentProfileId != requestedProfileId && blockers.isEmpty()
            ) { "SWITCH_ALLOWED requires distinct registered profile ids and no blockers" }

            SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_ID_INVALID -> require(
                currentProfileId == null && requestedProfileId == null && blockers.isEmpty()
            ) { "invalid requested profile id must not carry parsed ids or blockers" }

            SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_NOT_REGISTERED,
            SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_ID_INVALID -> require(
                currentProfileId == null && requestedProfileId != null && blockers.isEmpty()
            ) { "$code requires only a parsed requested profile id" }

            SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_NOT_REGISTERED -> require(
                currentProfileId != null && requestedProfileId != null && blockers.isEmpty()
            ) { "unregistered current profile requires both parsed ids" }

            SchedulerProfileSwitchDecisionCode.RUNTIME_BUSY -> require(
                currentProfileId != null && requestedProfileId != null &&
                    currentProfileId != requestedProfileId && blockers.isNotEmpty()
            ) { "RUNTIME_BUSY requires a real profile change and at least one blocker" }
        }
    }

    companion object {
        @JvmSynthetic
        internal fun evaluate(
            catalog: SchedulerProfileCatalog,
            currentId: String?,
            requestedId: String?,
            runtime: SchedulerProfileSwitchRuntimeSnapshot
        ): SchedulerProfileSwitchPlan {
            val requested = parseSchedulerProfileIdStrict(requestedId)
                ?: return rejected(SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_ID_INVALID)
            if (catalog.find(requested) == null) {
                return rejected(
                    code = SchedulerProfileSwitchDecisionCode.REQUESTED_PROFILE_NOT_REGISTERED,
                    requestedProfileId = requested
                )
            }

            val current = parseSchedulerProfileIdStrict(currentId)
                ?: return rejected(
                    code = SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_ID_INVALID,
                    requestedProfileId = requested
                )
            if (catalog.find(current) == null) {
                return rejected(
                    code = SchedulerProfileSwitchDecisionCode.CURRENT_PROFILE_NOT_REGISTERED,
                    currentProfileId = current,
                    requestedProfileId = requested
                )
            }

            if (current == requested) {
                return SchedulerProfileSwitchPlan(
                    code = SchedulerProfileSwitchDecisionCode.NO_CHANGE,
                    currentProfileId = current,
                    requestedProfileId = requested,
                    blockers = emptyList()
                )
            }

            val blockers = runtime.blockers()
            if (blockers.isNotEmpty()) {
                return SchedulerProfileSwitchPlan(
                    code = SchedulerProfileSwitchDecisionCode.RUNTIME_BUSY,
                    currentProfileId = current,
                    requestedProfileId = requested,
                    blockers = blockers
                )
            }

            return SchedulerProfileSwitchPlan(
                code = SchedulerProfileSwitchDecisionCode.SWITCH_ALLOWED,
                currentProfileId = current,
                requestedProfileId = requested,
                blockers = emptyList()
            )
        }

        private fun rejected(
            code: SchedulerProfileSwitchDecisionCode,
            currentProfileId: SchedulerProfileId? = null,
            requestedProfileId: SchedulerProfileId? = null
        ): SchedulerProfileSwitchPlan = SchedulerProfileSwitchPlan(
            code = code,
            currentProfileId = currentProfileId,
            requestedProfileId = requestedProfileId,
            blockers = emptyList()
        )

        private fun SchedulerProfileSwitchRuntimeSnapshot.blockers(): List<SchedulerProfileSwitchBlocker> =
            buildList {
                if (!deviceIdle) add(SchedulerProfileSwitchBlocker.DEVICE_NOT_IDLE)
                if (hasActiveOrder) add(SchedulerProfileSwitchBlocker.ACTIVE_ORDER)
                if (hasWholeOrderClaim) add(SchedulerProfileSwitchBlocker.WHOLE_ORDER_CLAIM)
                if (hasOutActionSession) add(SchedulerProfileSwitchBlocker.OUT_ACTION_SESSION)
                if (hasSupplementOwner) add(SchedulerProfileSwitchBlocker.SUPPLEMENT_OWNER)
                if (hasCleaningOwner) add(SchedulerProfileSwitchBlocker.CLEANING_OWNER)
                if (hasMaintenanceOwner) add(SchedulerProfileSwitchBlocker.MAINTENANCE_OWNER)
            }
    }
}

object SchedulerProfileSwitchPolicy {
    fun plan(
        catalog: SchedulerProfileCatalog,
        currentId: String?,
        requestedId: String?,
        runtime: SchedulerProfileSwitchRuntimeSnapshot
    ): SchedulerProfileSwitchPlan = SchedulerProfileSwitchPlan.evaluate(
        catalog = catalog,
        currentId = currentId,
        requestedId = requestedId,
        runtime = runtime
    )
}
