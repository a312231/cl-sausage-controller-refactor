package cn.niuyannet.kaochang.android.services.actiongroup

data class OrphanDeliveryBusyRegisterState(
    val actionName: String,
    val register: Int,
    val value: Int?,
    val errorMessage: String? = null
)

data class OrphanDeliveryBusySnapshot(
    val modbusConnected: Boolean,
    val legacyActionStatus201: Int?,
    val commandStates: List<OrphanDeliveryBusyRegisterState>,
    val resultStates: List<OrphanDeliveryBusyRegisterState>
)

data class OrphanDeliveryBusyRecoveryDecision(
    val canRecover: Boolean,
    val reason: String
)

/**
 * 仅处理没有任何订单 owner 的孤儿出餐忙态。完成态同样拒绝，避免将上次动作结果误作本次空闲证明。
 */
object OrphanDeliveryBusyRecoveryPolicy {
    private val expectedActions = listOf(
        AtomicActions.in1,
        AtomicActions.in2,
        AtomicActions.in3,
        AtomicActions.in4,
        AtomicActions.in5,
        AtomicActions.in6,
        AtomicActions.in7,
        AtomicActions.out1,
        AtomicActions.out2,
        AtomicActions.out3,
        AtomicActions.out4,
        AtomicActions.out5
    )

    fun evaluate(
        moveStatus: Int,
        snapshots: List<OrphanDeliveryBusySnapshot>
    ): OrphanDeliveryBusyRecoveryDecision {
        if (moveStatus != 2) return reject("当前 moveStatus=$moveStatus，不是孤儿出餐忙态")
        if (snapshots.size != 2) return reject("必须连续读取两次下位机空闲快照，实际=${snapshots.size}次")
        snapshots.forEachIndexed { index, snapshot ->
            val prefix = "第${index + 1}次快照"
            if (!snapshot.modbusConnected) return reject("$prefix Modbus 未连接")
            if (snapshot.legacyActionStatus201 != AtomicActionCodes.IDLE) {
                return reject("$prefix 201 非空闲：${snapshot.legacyActionStatus201 ?: "读取失败"}")
            }
            validateStates(prefix, "命令", snapshot.commandStates, expectedActions.map { it.commandRegister })?.let { return reject(it) }
            validateStates(prefix, "结果", snapshot.resultStates, expectedActions.map { it.resultRegister })?.let { return reject(it) }
        }
        return OrphanDeliveryBusyRecoveryDecision(true, "两次下位机快照均确认动作组完全空闲")
    }

    private fun validateStates(
        prefix: String,
        stateType: String,
        states: List<OrphanDeliveryBusyRegisterState>,
        expectedRegisters: List<Int>
    ): String? {
        if (states.size != expectedActions.size || states.map { it.actionName }.toSet() != expectedActions.map { it.name }.toSet()) {
            return "$prefix $stateType 寄存器读取不完整"
        }
        if (states.map { it.register }.toSet() != expectedRegisters.toSet()) {
            return "$prefix $stateType 寄存器地址不匹配"
        }
        val unreadable = states.firstOrNull { it.value == null }
        if (unreadable != null) return "$prefix $stateType 寄存器读取失败：${unreadable.actionName}"
        val nonIdle = states.firstOrNull { it.value != AtomicActionCodes.IDLE }
        if (nonIdle != null) return "$prefix $stateType 寄存器非空闲：${nonIdle.actionName}=${nonIdle.value}"
        return null
    }

    private fun reject(reason: String) = OrphanDeliveryBusyRecoveryDecision(false, reason)
}
