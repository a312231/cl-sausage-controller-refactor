package cn.niuyannet.kaochang.android.modbus

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper.ModbusOperationResult
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper.ModbusOperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VMModbusHelperDiagnosisTest {

    @Test
    fun diagnosisText_shouldUseChineseFieldsAndDynamicServerAddressForReadFailure() {
        val result = ModbusOperationResult(
            status = ModbusOperationStatus.DISCONNECTED,
            message = "timeout",
            exceptionClassName = "ModbusIOException",
            exceptionMessage = "timeout"
        )

        val text = VMModbusHelper.formatOperationDiagnosis(
            operationText = "读取",
            serverAddress = 7,
            registerAddress = 411,
            writeValue = null,
            result = result,
            connected = false,
            port = "/dev/ttyS7",
            baudRate = 115200
        )

        assertTrue(text.contains("操作=读取"))
        assertTrue(text.contains("寄存器地址=411"))
        assertTrue(text.contains("从站地址=7"))
        assertTrue(text.contains("连接状态=未连接"))
        assertTrue(text.contains("串口地址=/dev/ttyS7"))
        assertTrue(text.contains("波特率=115200"))
        assertTrue(text.contains("异常类型=ModbusIOException"))
        assertTrue(text.contains("异常信息=timeout"))
        assertFalse(text.contains("message="))
        assertFalse(text.contains("exceptionClass"))
        assertFalse(text.contains("exceptionMessage"))
        assertFalse(text.contains("serverAddress"))
        assertFalse(text.contains("operation="))
    }

    @Test
    fun diagnosisText_shouldIncludeWriteValueForWriteFailure() {
        val result = ModbusOperationResult(
            status = ModbusOperationStatus.PARAM_ERROR,
            message = "bad value"
        )

        val text = VMModbusHelper.formatOperationDiagnosis(
            operationText = "写入",
            serverAddress = 9,
            registerAddress = 410,
            writeValue = 1,
            result = result,
            connected = true,
            port = "/dev/ttyS2",
            baudRate = 9600
        )

        assertTrue(text.contains("操作=写入"))
        assertTrue(text.contains("寄存器地址=410"))
        assertTrue(text.contains("写入值=1"))
        assertTrue(text.contains("从站地址=9"))
        assertTrue(text.contains("连接状态=已连接"))
        assertTrue(text.contains("异常类型=无"))
        assertTrue(text.contains("异常信息=bad value"))
    }

    @Test
    fun modbusOperationResult_shouldKeepNewFieldsNullableByDefault() {
        val result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)

        assertNull(result.exceptionClassName)
        assertNull(result.exceptionMessage)
    }

    @Test
    fun diagnosisText_shouldTruncateTo300CharactersWithEllipsis() {
        val result = ModbusOperationResult(
            status = ModbusOperationStatus.UNKNOWN_ERROR,
            message = "x".repeat(500)
        )

        val text = VMModbusHelper.formatOperationDiagnosis(
            operationText = "读取",
            serverAddress = 7,
            registerAddress = 411,
            writeValue = null,
            result = result,
            connected = false,
            port = "/dev/ttyS7",
            baudRate = 115200
        )

        assertEquals(300, text.length)
        assertTrue(text.endsWith("..."))
    }

    @Test
    fun legacyReadHoldingRegisters_shouldStillReturnMinusOneWhenMasterMissing() {
        VMModbusHelper.isDebug = false
        VMModbusHelper.modbusMaster = null

        val value = VMModbusHelper.readHoldingRegisters(serverAddress = 7, startAddress = 411)

        assertEquals(-1, value)
    }

    @Test
    fun diagnosisPath_shouldNotCallConnectStatus() {
        val sourceRoot = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        ).first { it.exists() }
        val helperSource = sourceRoot
            .resolve("cn/niuyannet/kaochang/android/modbus/VMModbusHelper.kt")
            .readText()
        val gatewaySource = sourceRoot
            .resolve("cn/niuyannet/kaochang/android/services/actiongroup/VmAtomicModbusGateway.kt")
            .readText()
        val diagnosisSnapshotBlock = helperSource
            .substringAfter("fun currentDiagnosisSnapshot()")
            .substringBefore("fun formatOperationDiagnosis(")

        assertFalse(
            "currentDiagnosisSnapshot 必须只读 modbusMaster 本地状态，不能调用 connectStatus()",
            diagnosisSnapshotBlock.contains("connectStatus()")
        )
        assertFalse(
            "VmAtomicModbusGateway 的失败诊断路径不能调用 connectStatus()",
            gatewaySource.contains("connectStatus()")
        )
    }
}
