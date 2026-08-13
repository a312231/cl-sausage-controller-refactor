package cn.niuyannet.kaochang.recovery

enum class ManualRecoveryGateCode {
    MODBUS_DISCONNECTED,
    ACTION_GROUP_NOT_READY,
    LEGACY_ACTION_NOT_IDLE,
    PAN_MOVE_NOT_CONFIRMED,
    ACTION_GROUP_RESIDUE_CLOSEOUT_FAILED,
    ALLOWED
}

object ManualRecoveryGatePolicy {
    data class Decision(
        val code: ManualRecoveryGateCode,
        val canRecoverDeviceStatus: Boolean,
        val reason: String
    )

    fun evaluate(
        modbusConnected: Boolean,
        actionGroupReady: Boolean,
        legacyActionStatus201: Int,
        moveStatus: Int,
        panMoveResult203: Int?,
        targetOnlineStatus: Int
    ): Decision {
        return when {
            !modbusConnected -> Decision(
                ManualRecoveryGateCode.MODBUS_DISCONNECTED,
                false,
                "下位机串口未连接"
            )
            !actionGroupReady -> Decision(
                ManualRecoveryGateCode.ACTION_GROUP_NOT_READY,
                false,
                "300/400动作组恢复检查未通过"
            )
            legacyActionStatus201 != 0 -> Decision(
                ManualRecoveryGateCode.LEGACY_ACTION_NOT_IDLE,
                false,
                "201（旧动作状态寄存器）=$legacyActionStatus201（非0=旧协议动作未空闲）"
            )
            moveStatus == 3 && panMoveResult203 != 0 -> Decision(
                ManualRecoveryGateCode.PAN_MOVE_NOT_CONFIRMED,
                false,
                "moveStatus（机械动作状态）=3（烤盘搬移中），" +
                    "203（烤盘搬移结果寄存器）=${panMoveResult203 ?: "读取失败"}（非0=未确认完成）"
            )
            targetOnlineStatus == 1 -> Decision(
                ManualRecoveryGateCode.ALLOWED,
                true,
                "恢复到 onlineStatus（设备营业状态）=1（运营中），允许恢复设备状态，温控保持关闭等待人工开启算法"
            )
            else -> Decision(
                ManualRecoveryGateCode.ALLOWED,
                true,
                "恢复到 onlineStatus（设备营业状态）=$targetOnlineStatus（非运营中），允许恢复设备状态，温控保持关闭等待人工开启算法"
            )
        }
    }
}
