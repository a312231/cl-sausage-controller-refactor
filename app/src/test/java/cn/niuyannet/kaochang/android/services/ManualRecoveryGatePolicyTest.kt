package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRecoveryGatePolicyTest {

    @Test
    fun evaluate_shouldRejectWhenActionGroupRecoveryFailedEvenIfMachineLooksIdle() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            actionGroupReady = false,
            legacyActionStatus201 = 0,
            moveStatus = 0,
            panMoveResult203 = null,
            targetOnlineStatus = 1
        )

        assertEquals(false, result.canRecoverDeviceStatus)
        assertEquals("300/400动作组恢复检查未通过", result.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenModbusDisconnected() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = false,
            actionGroupReady = true,
            legacyActionStatus201 = 0,
            moveStatus = 0,
            panMoveResult203 = null,
            targetOnlineStatus = 1
        )

        assertEquals(false, result.canRecoverDeviceStatus)
        assertEquals("下位机串口未连接", result.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenLegacyActionIsNotIdle() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            actionGroupReady = true,
            legacyActionStatus201 = 1,
            moveStatus = 0,
            panMoveResult203 = null,
            targetOnlineStatus = 1
        )

        assertEquals(false, result.canRecoverDeviceStatus)
        assertEquals("201（旧动作状态寄存器）=1（非0=旧协议动作未空闲）", result.reason)
    }

    @Test
    fun evaluate_shouldRejectWhenPanMoveResultIsNotIdle() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            actionGroupReady = true,
            legacyActionStatus201 = 0,
            moveStatus = 3,
            panMoveResult203 = 1,
            targetOnlineStatus = 1
        )

        assertEquals(false, result.canRecoverDeviceStatus)
        assertEquals(
            "moveStatus（机械动作状态）=3（烤盘搬移中），203（烤盘搬移结果寄存器）=1（非0=未确认完成）",
            result.reason
        )
    }

    @Test
    fun evaluate_shouldAllowRecoverWhenOperating() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            actionGroupReady = true,
            legacyActionStatus201 = 0,
            moveStatus = 0,
            panMoveResult203 = null,
            targetOnlineStatus = 1
        )

        assertEquals(true, result.canRecoverDeviceStatus)
        assertEquals(
            "恢复到 onlineStatus（设备营业状态）=1（运营中），允许恢复设备状态，温控保持关闭等待人工开启算法",
            result.reason
        )
    }

    @Test
    fun evaluate_shouldAllowRecoverWhenResting() {
        val result = ManualRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            actionGroupReady = true,
            legacyActionStatus201 = 0,
            moveStatus = 0,
            panMoveResult203 = null,
            targetOnlineStatus = 2
        )

        assertEquals(true, result.canRecoverDeviceStatus)
        assertEquals(
            "恢复到 onlineStatus（设备营业状态）=2（非运营中），允许恢复设备状态，温控保持关闭等待人工开启算法",
            result.reason
        )
    }
}
