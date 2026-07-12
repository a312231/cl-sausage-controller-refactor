package cn.niuyannet.kaochang.android.fragments

internal object LowerBoardMaintenancePolicy {
    const val CLEAR_REGISTER = 998
    const val SYSTEM_RESET_REGISTER = 999
    const val TRIGGER_VALUE = 1

    val actionResultRegisters = listOf(
        311, 321, 331, 341, 351, 361, 371,
        411, 421, 431, 441, 451
    )

    data class Decision(
        val allowed: Boolean,
        val reason: String
    )

    fun canRunMaintenance(
        moveStatus: Int,
        status201: Int,
        moveResult203: Int,
        atomicResultValues: Map<Int, Int>
    ): Decision {
        if (moveStatus != 0) {
            return Decision(
                allowed = false,
                reason = "上位机机械动作状态 moveStatus=$moveStatus，非空闲，不能执行下位机维护操作"
            )
        }
        if (status201 == 1) {
            return Decision(false, "下位机动作执行中：201=1")
        }
        if (moveResult203 == 1) {
            return Decision(false, "下位机搬盘执行中：203=1")
        }
        val runningAtomic = actionResultRegisters.firstOrNull { register ->
            atomicResultValues[register] == 1
        }
        if (runningAtomic != null) {
            return Decision(false, "下位机原子动作执行中：$runningAtomic=1")
        }
        return Decision(true, "允许执行")
    }

    fun canRunEmergencyReset(): Decision {
        return Decision(true, "允许紧急复位")
    }

    fun formatMaintenanceRegisterValue(address: Int, value: Int): String {
        val description = when (address) {
            CLEAR_REGISTER -> "清空 300/400 原子动作寄存器触发寄存器"
            SYSTEM_RESET_REGISTER -> "下位机系统软重启触发寄存器"
            else -> return "$value（0x${value.toString(16).uppercase().padStart(4, '0')}）"
        }
        return "$value（0x${value.toString(16).uppercase().padStart(4, '0')}，$description）"
    }
}
