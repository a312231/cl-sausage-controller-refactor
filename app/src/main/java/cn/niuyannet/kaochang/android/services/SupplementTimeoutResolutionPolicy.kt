package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes

internal object SupplementTimeoutResolutionPolicy {
    enum class Resolution {
        KEEP_RUNNING,
        RECONCILE_COMPLETED,
        ISOLATE_TERMINAL_ERROR,
        HARD_FAILURE
    }

    fun resolve(
        resultCode: Int?,
        readsSucceeded: Boolean,
        actionName: String,
        isHeatSupplementBatch: Boolean,
        isFinalCycle: Boolean
    ): Resolution {
        if (!readsSucceeded || resultCode == null) return Resolution.HARD_FAILURE
        return when (resultCode) {
            AtomicActionCodes.RUNNING -> if (isFinalCycle) Resolution.HARD_FAILURE else Resolution.KEEP_RUNNING
            AtomicActionCodes.COMPLETED -> Resolution.RECONCILE_COMPLETED
            in AtomicActionCodes.PARAM_ERROR..AtomicActionCodes.MOVE_STATE_ERROR -> {
                if (
                    !LowerPlatformIsolationPolicy.isUnsafeLiftTerminalError(resultCode) &&
                    isHeatSupplementBatch &&
                    actionName in ISOLATABLE_TIMEOUT_ACTIONS
                ) {
                    Resolution.ISOLATE_TERMINAL_ERROR
                } else {
                    Resolution.HARD_FAILURE
                }
            }
            else -> Resolution.HARD_FAILURE
        }
    }

    private val ISOLATABLE_TIMEOUT_ACTIONS = setOf("in1", "in2", "in3", "in4", "in5")
}
