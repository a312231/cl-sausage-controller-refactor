package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VmAtomicModbusGatewayBatchReadTest {

    @After
    fun tearDown() {
        VMModbusHelper.isDebug = false
        VMModbusHelper.modbusMaster = null
    }

    @Test
    fun readRegisters_shouldReturnCompleteValuesWhenDebugModeEnabled() = runBlocking {
        VMModbusHelper.isDebug = true
        VMModbusHelper.modbusMaster = null
        val gateway = VmAtomicModbusGateway(serverAddress = 1, operationDelayMs = 0L)

        val result = gateway.readRegisters(startAddress = 1200, quantity = 3)

        assertTrue(result is AtomicRegistersReadResult.Success)
        assertArrayEquals(intArrayOf(0, 0, 0), (result as AtomicRegistersReadResult.Success).values)
    }

    @Test
    fun readRegisters_shouldReturnFailureWithDiagnosisWhenReadReturnsNull() = runBlocking {
        VMModbusHelper.isDebug = false
        VMModbusHelper.modbusMaster = null
        val gateway = VmAtomicModbusGateway(serverAddress = 1, operationDelayMs = 0L)

        val result = gateway.readRegisters(startAddress = 1200, quantity = 32)

        assertTrue(result is AtomicRegistersReadResult.Failure)
        val message = (result as AtomicRegistersReadResult.Failure).message
        assertTrue(message.contains("操作=读取"))
        assertTrue(message.contains("寄存器地址=1200"))
        assertTrue(message.contains("读取数量=32"))
        assertTrue(message.contains("从站地址=1"))
        assertTrue(message.contains("连接状态=未连接"))
        assertTrue(message.contains("RTU 主站未初始化"))
        assertFalse(message.contains("串口地址=未知"))
        assertFalse(message.contains("波特率=0"))
        assertFalse(message.contains("批量读取寄存器返回为空"))
    }

    @Test
    fun readRegisters_shouldUseExistingMutexAndKeepDelayOutsideLock() {
        val sourceRoot = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        ).first { it.exists() }
        val source = sourceRoot
            .resolve("cn/niuyannet/kaochang/android/services/actiongroup/VmAtomicModbusGateway.kt")
            .readText()
        val methodBody = source
            .substringAfter("suspend fun readRegisters")
            .substringBefore("companion object")

        assertTrue(methodBody.contains("delay(operationDelayMs)"))
        assertTrue(methodBody.contains("modbusOperationMutex.withLock"))
        assertTrue(methodBody.contains("readHoldingRegistersWithDiagnosis"))
        assertTrue(
            "delay 必须在锁外，避免持锁等待",
            methodBody.indexOf("delay(operationDelayMs)") < methodBody.indexOf("modbusOperationMutex.withLock")
        )
        assertFalse(methodBody.contains("connectStatus()"))
    }
}
