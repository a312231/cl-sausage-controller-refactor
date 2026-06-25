package cn.niuyannet.kaochang.android.services.paramtuning

import cn.niuyannet.kaochang.android.fragments.LowerBoardMaintenancePolicy

/**
 * Pure-logic gate for param tuning / calibration mutations.
 *
 * Layer 1: device-level state (onlineStatus, errorStatus, inspectionMode, moveStatus).
 * Layer 2: register-level idle check (delegates to [LowerBoardMaintenancePolicy.canRunMaintenance]).
 *
 * No Android Context dependency -- safe for JVM unit tests.
 */
object ParamTuningMaintenanceGate {

    data class GateDecision(
        val allowed: Boolean,
        val reason: String
    )

    /**
     * Checks both device-level state and register-level idle state.
     *
     * @param onlineStatus  AppConfigBean.onlineStatus (must be 3 = maintenance)
     * @param errorStatus   AppConfigBean.errorStatus (must be 0 = normal)
     * @param inspectionMode AppConfigBean.inspectionMode (must be 0 = normal)
     * @param moveStatus    AppConfigBean.moveStatus (must be 0 = idle)
     * @param status201     register 201 value (-1 if read failed)
     * @param moveResult203 register 203 value (-1 if read failed)
     * @param atomicResultValues  register values keyed by register address
     */
    fun check(
        onlineStatus: Int,
        errorStatus: Int,
        inspectionMode: Int,
        moveStatus: Int,
        status201: Int,
        moveResult203: Int,
        atomicResultValues: Map<Int, Int>
    ): GateDecision {
        // Layer 1: device-level state
        if (onlineStatus != 3) {
            val desc = when (onlineStatus) {
                0 -> "待投运"
                1 -> "运营中"
                2 -> "休息中"
                4 -> "工作中"
                else -> "未知($onlineStatus)"
            }
            return GateDecision(false, "设备营业状态为$desc，调参/校准仅允许在维护中(onlineStatus=3)执行")
        }
        if (errorStatus != 0) {
            return GateDecision(false, "设备存在故障(errorStatus=$errorStatus)，请先恢复设备状态")
        }
        if (inspectionMode != 0) {
            return GateDecision(false, "设备处于检修模式(inspectionMode=$inspectionMode)，请先退出检修模式")
        }
        if (moveStatus != 0) {
            return GateDecision(false, "设备机械动作非空闲(moveStatus=$moveStatus)，请等待动作完成")
        }

        // Layer 2: register-level idle check (delegate to existing policy)
        val registerDecision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = moveStatus,
            status201 = status201,
            moveResult203 = moveResult203,
            atomicResultValues = atomicResultValues
        )
        if (!registerDecision.allowed) {
            return GateDecision(false, registerDecision.reason)
        }

        return GateDecision(true, "允许执行")
    }
}
