package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReadinessForUpgradeTest {

    @Test
    fun check_shouldAllowInstallWhenAllRegularGatesAreClear() {
        val result = DeviceReadinessForUpgrade.check(
            DeviceReadinessForUpgrade.State(
                moveStatus = 0,
                onlineStatus = 1,
                hasProcessingOrder = false,
                hasRecentBusyOrder = false,
                hasOrderTracking = false,
                mqttPendingCount = 0,
                pendingRuntimeAckCount = 0,
                pendingAvailableAckCount = 0
            )
        )

        assertTrue(result.ok)
        assertEquals(null, result.blockedBy)
    }

    @Test
    fun check_shouldBlockByFirstRegularGateReason() {
        val result = DeviceReadinessForUpgrade.check(
            DeviceReadinessForUpgrade.State(
                moveStatus = 1,
                onlineStatus = 4,
                hasProcessingOrder = true,
                hasRecentBusyOrder = true,
                hasOrderTracking = true,
                mqttPendingCount = 2,
                pendingRuntimeAckCount = 1,
                pendingAvailableAckCount = 1
            )
        )

        assertFalse(result.ok)
        assertEquals("机械动作状态不是空闲：moveStatus（机械动作状态）=1", result.blockedBy)
    }

    @Test
    fun check_shouldBlockMqttPendingAndAckBeforeInstall() {
        val mqttQueue = DeviceReadinessForUpgrade.check(clearState(mqttPendingCount = 2))
        assertFalse(mqttQueue.ok)
        assertEquals("MQTT 发送队列未清空：pendingCount=2", mqttQueue.blockedBy)

        val runtimeAck = DeviceReadinessForUpgrade.check(clearState(pendingRuntimeAckCount = 1))
        assertFalse(runtimeAck.ok)
        assertEquals("runtime_state 待确认未清空：pendingRuntimeAckCount=1", runtimeAck.blockedBy)

        val availableAck = DeviceReadinessForUpgrade.check(clearState(pendingAvailableAckCount = 1))
        assertFalse(availableAck.ok)
        assertEquals("available_state 待确认未清空：pendingAvailableAckCount=1", availableAck.blockedBy)
    }

    @Test
    fun check_shouldUseMinimalGateAfterForceUpdateDelayedTooLong() {
        val result = DeviceReadinessForUpgrade.check(
            clearState(
                onlineStatus = 4,
                hasProcessingOrder = true,
                hasRecentBusyOrder = true,
                mqttPendingCount = 5,
                pendingRuntimeAckCount = 3,
                pendingAvailableAckCount = 2
            ),
            minimalGate = true
        )

        assertTrue(result.ok)
    }

    @Test
    fun check_shouldStillBlockMinimalGateWhenMachineMovingOrOrderTrackingActive() {
        val moving = DeviceReadinessForUpgrade.check(clearState(moveStatus = 2), minimalGate = true)
        assertFalse(moving.ok)
        assertEquals("机械动作状态不是空闲：moveStatus（机械动作状态）=2", moving.blockedBy)

        val tracking = DeviceReadinessForUpgrade.check(clearState(hasOrderTracking = true), minimalGate = true)
        assertFalse(tracking.ok)
        assertEquals("订单自清洁跟踪未结束", tracking.blockedBy)
    }

    private fun clearState(
        moveStatus: Int = 0,
        onlineStatus: Int = 1,
        hasProcessingOrder: Boolean = false,
        hasRecentBusyOrder: Boolean = false,
        hasOrderTracking: Boolean = false,
        mqttPendingCount: Int = 0,
        pendingRuntimeAckCount: Int = 0,
        pendingAvailableAckCount: Int = 0
    ): DeviceReadinessForUpgrade.State {
        return DeviceReadinessForUpgrade.State(
            moveStatus = moveStatus,
            onlineStatus = onlineStatus,
            hasProcessingOrder = hasProcessingOrder,
            hasRecentBusyOrder = hasRecentBusyOrder,
            hasOrderTracking = hasOrderTracking,
            mqttPendingCount = mqttPendingCount,
            pendingRuntimeAckCount = pendingRuntimeAckCount,
            pendingAvailableAckCount = pendingAvailableAckCount
        )
    }
}
