package cn.niuyannet.kaochang.android.fragments

import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryCode
import cn.niuyannet.kaochang.recovery.ManualFaultRecoveryResult

internal object ManualRecoveryFailureDisplayPolicy {
    fun errorText(error: Throwable?): String =
        if (error == null) "无" else "${error.javaClass.name}:${error.message ?: "无消息"}"

    fun detail(result: ManualFaultRecoveryResult): String {
        if (
            result is ManualFaultRecoveryResult.Rejected &&
            result.code == ManualFaultRecoveryCode.INITIAL_CONDITION_CHANGED
        ) {
            return "未重复执行温控恢复，请重新检查当前状态"
        }
        if (
            result is ManualFaultRecoveryResult.Failed &&
            result.code in setOf(
                ManualFaultRecoveryCode.INITIAL_CONDITION_EXCEPTION,
                ManualFaultRecoveryCode.STATE_SNAPSHOT_EXCEPTION
            )
        ) {
            return "恢复前检查未完成，未执行温控恢复；设备仍未恢复正常"
        }

        val rollback = when (result) {
            is ManualFaultRecoveryResult.Rejected -> result.rollbackOutcome
            is ManualFaultRecoveryResult.Failed -> result.rollbackOutcome
            is ManualFaultRecoveryResult.Success -> null
        } ?: return "恢复安全收口结果缺失；设备仍未恢复正常，请检查日志"

        return when {
            rollback.faultStateRestored && rollback.targetTemperatureZeroWritesSucceeded ->
                "故障状态已回退，三区目标温度写0调用均返回成功；设备仍未恢复正常，实际温控状态仍需现场确认"
            !rollback.faultStateRestored && rollback.targetTemperatureZeroWritesSucceeded ->
                "三区目标温度写0调用均返回成功，但故障状态耐久回退未确认；" +
                    "设备仍未恢复正常，请检查日志；实际温控状态仍需现场确认"
            rollback.faultStateRestored ->
                "故障状态已回退，但三区目标温度写0调用未全部成功；设备仍未恢复正常，请检查下位机"
            else ->
                "故障状态回退与三区目标温度写0调用均未确认；设备仍未恢复正常，请立即检查下位机"
        }
    }
}
