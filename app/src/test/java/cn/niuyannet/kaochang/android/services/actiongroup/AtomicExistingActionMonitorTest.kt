package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicExistingActionMonitorTest {

    @Test
    fun runningThenCompleted_shouldOnlyReadAndNeverWriteCommand() = runBlocking {
        val gateway = MonitorGateway(listOf(AtomicActionCodes.RUNNING, AtomicActionCodes.COMPLETED))
        val monitor = AtomicExistingActionMonitor(gateway, pollDelayMs = 0L)

        val result = monitor.awaitExisting(AtomicActions.in6)

        assertEquals(ExistingActionObservation.Completed, result)
        assertTrue(gateway.commandWrites.isEmpty())
    }

    @Test
    fun firstCompleted_shouldReturnCompletedWithoutPollingOrWriting() = runBlocking {
        val gateway = MonitorGateway(listOf(AtomicActionCodes.COMPLETED))
        val monitor = AtomicExistingActionMonitor(gateway, pollDelayMs = 0L)

        assertEquals(ExistingActionObservation.Completed, monitor.awaitExisting(AtomicActions.in7))
        assertEquals(1, gateway.readCount)
        assertTrue(gateway.commandWrites.isEmpty())
    }

    private class MonitorGateway(private val values: List<Int>) : AtomicModbusGateway {
        var readCount = 0
        val commandWrites = mutableListOf<Pair<Int, Int>>()

        override suspend fun writeRegister(address: Int, value: Int): AtomicIoResult {
            commandWrites += address to value
            return AtomicIoResult.Success
        }

        override suspend fun readRegister(address: Int): AtomicReadResult {
            val value = values.getOrElse(readCount) { values.last() }
            readCount += 1
            return AtomicReadResult.Success(value)
        }
    }
}
