package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceModbusRecoveryConnectorTest {
    @Test
    fun ensureConnectedReturnsTrueWithoutReconnectWhenAlreadyConnected() {
        var reconnectCalled = false

        val connected = MaintenanceModbusRecoveryConnector.ensureConnected(
            isConnected = { true },
            reconnect = {
                reconnectCalled = true
                it(false)
            },
            log = { }
        )

        assertTrue(connected)
        assertFalse(reconnectCalled)
    }

    @Test
    fun ensureConnectedTriggersReconnectWhenCurrentConnectionIsMissing() {
        var reconnectCalled = false

        val connected = MaintenanceModbusRecoveryConnector.ensureConnected(
            isConnected = { false },
            reconnect = {
                reconnectCalled = true
                it(true)
            },
            log = { }
        )

        assertTrue(connected)
        assertTrue(reconnectCalled)
    }

    @Test
    fun ensureConnectedReturnsFalseWhenReconnectFails() {
        val connected = MaintenanceModbusRecoveryConnector.ensureConnected(
            isConnected = { false },
            reconnect = { it(false) },
            log = { }
        )

        assertFalse(connected)
    }

    @Test
    fun ensureConnectedDoesNotStartSecondReconnectWhileReconnectIsRunning() {
        var reconnectCallCount = 0

        val firstConnected = MaintenanceModbusRecoveryConnector.ensureConnected(
            isConnected = { false },
            reconnect = {
                reconnectCallCount++
                MaintenanceModbusRecoveryConnector.ensureConnected(
                    isConnected = { false },
                    reconnect = { nestedCallback ->
                        reconnectCallCount++
                        nestedCallback(true)
                    },
                    log = { }
                )
                it(false)
            },
            log = { }
        )

        assertFalse(firstConnected)
        assertEquals(1, reconnectCallCount)
    }
}
