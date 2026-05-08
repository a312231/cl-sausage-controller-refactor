package cn.niuyannet.kaochang.android.ui

enum class OrderModbusReadyForDeliveryStage(val stageText: String) {
    ALREADY_CONNECTED("已连接"),
    RECONNECTED("重连成功"),
    RECONNECT_FAILED("重连失败"),
    ACTION_GROUP_RECOVERY_FAILED("动作组恢复失败")
}

data class OrderModbusReadyForDeliveryResult(
    val canStartDelivery: Boolean,
    val stage: OrderModbusReadyForDeliveryStage,
    val reason: String
) {
    val stageText: String
        get() = stage.stageText

    fun toDiagnosticMessage(
        orderNo: String,
        itemId: Int,
        onlineStatusText: String,
        errorStatus: Int,
        moveStatus: Int,
        modbusAddress: String
    ): String {
        return "orderNo（订单号）=$orderNo，" +
            "itemId（订单明细ID）=$itemId，" +
            "stage（失败阶段）=$stageText，" +
            "reason（失败原因）=$reason，" +
            "onlineStatus（设备营业状态）=$onlineStatusText，" +
            "errorStatus（设备故障状态）=$errorStatus，" +
            "moveStatus（机械动作状态）=$moveStatus，" +
            "modbusAddress（Modbus串口地址）=$modbusAddress"
    }

    companion object {
        fun alreadyConnected(): OrderModbusReadyForDeliveryResult {
            return OrderModbusReadyForDeliveryResult(
                canStartDelivery = true,
                stage = OrderModbusReadyForDeliveryStage.ALREADY_CONNECTED,
                reason = "Modbus 已连接且动作组恢复检查通过"
            )
        }

        fun reconnected(attempt: Int): OrderModbusReadyForDeliveryResult {
            return OrderModbusReadyForDeliveryResult(
                canStartDelivery = true,
                stage = OrderModbusReadyForDeliveryStage.RECONNECTED,
                reason = "Modbus 重连成功且动作组恢复检查通过：attempt=$attempt"
            )
        }

        fun reconnectFailed(detail: String): OrderModbusReadyForDeliveryResult {
            return OrderModbusReadyForDeliveryResult(
                canStartDelivery = false,
                stage = OrderModbusReadyForDeliveryStage.RECONNECT_FAILED,
                reason = "Modbus 重连失败：$detail"
            )
        }

        fun actionGroupRecoveryFailed(detail: String): OrderModbusReadyForDeliveryResult {
            return OrderModbusReadyForDeliveryResult(
                canStartDelivery = false,
                stage = OrderModbusReadyForDeliveryStage.ACTION_GROUP_RECOVERY_FAILED,
                reason = detail
            )
        }
    }
}
