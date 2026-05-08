package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderModbusReadyForDeliveryResultTest {

    @Test
    fun alreadyConnected_shouldAllowDeliveryAndDescribeStage() {
        val result = OrderModbusReadyForDeliveryResult.alreadyConnected()

        assertTrue(result.canStartDelivery)
        assertEquals("已连接", result.stageText)
        assertEquals("Modbus 已连接且动作组恢复检查通过", result.reason)
    }

    @Test
    fun reconnectFailed_shouldBlockDeliveryAndFormatDiagnostics() {
        val result = OrderModbusReadyForDeliveryResult.reconnectFailed(
            detail = "3次重连后仍未连通"
        )

        assertFalse(result.canStartDelivery)
        assertEquals("重连失败", result.stageText)
        assertTrue(result.reason.contains("3次重连后仍未连通"))
    }

    @Test
    fun actionGroupRecoveryFailed_shouldBlockDeliveryAndFormatDiagnostics() {
        val result = OrderModbusReadyForDeliveryResult.actionGroupRecoveryFailed(
            detail = "动作组恢复失败：读取 out1 结果寄存器 411 失败"
        )

        assertFalse(result.canStartDelivery)
        assertEquals("动作组恢复失败", result.stageText)
        assertTrue(result.reason.contains("out1"))
        assertTrue(result.reason.contains("411"))
    }

    @Test
    fun toDiagnosticMessage_shouldIncludeOrderStateAndModbusAddress() {
        val result = OrderModbusReadyForDeliveryResult.reconnectFailed("连续3次重连后仍未连通")

        val message = result.toDiagnosticMessage(
            orderNo = "CL1",
            itemId = 1882,
            onlineStatusText = "4（工作中/正在出餐）",
            errorStatus = 2,
            moveStatus = 0,
            modbusAddress = "/dev/ttyS7"
        )

        assertTrue(message.contains("orderNo（订单号）=CL1"))
        assertTrue(message.contains("itemId（订单明细ID）=1882"))
        assertTrue(message.contains("stage（失败阶段）=重连失败"))
        assertTrue(message.contains("onlineStatus（设备营业状态）=4（工作中/正在出餐）"))
        assertTrue(message.contains("modbusAddress（Modbus串口地址）=/dev/ttyS7"))
    }
}
