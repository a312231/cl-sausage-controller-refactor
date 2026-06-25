package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VmAtomicModbusGateway(
    private val serverAddress: Int,
    private val operationDelayMs: Long = 150L
) : AtomicModbusGateway {
    override suspend fun writeRegister(address: Int, value: Int): AtomicIoResult {
        if (operationDelayMs > 0L) {
            delay(operationDelayMs)
        }
        val result = modbusOperationMutex.withLock {
            VMModbusHelper.writeSingleRegister(serverAddress, address, value)
        }
        return if (result.isSuccess) {
            AtomicIoResult.Success
        } else {
            AtomicIoResult.Failure(
                VMModbusHelper.formatOperationDiagnosis(
                    operationText = "写入",
                    serverAddress = serverAddress,
                    registerAddress = address,
                    writeValue = value,
                    result = result
                )
            )
        }
    }

    override suspend fun readRegister(address: Int): AtomicReadResult {
        if (operationDelayMs > 0L) {
            delay(operationDelayMs)
        }
        val result = modbusOperationMutex.withLock {
            VMModbusHelper.readHoldingRegisterWithDiagnosis(serverAddress, address)
        }
        return if (result.isSuccess) {
            AtomicReadResult.Success(result.value)
        } else {
            AtomicReadResult.Failure(
                VMModbusHelper.formatOperationDiagnosis(
                    operationText = "读取",
                    serverAddress = serverAddress,
                    registerAddress = address,
                    result = result.result
                )
            )
        }
    }

    suspend fun readRegisters(startAddress: Int, quantity: Int): AtomicRegistersReadResult {
        if (operationDelayMs > 0L) {
            delay(operationDelayMs)
        }
        val result = modbusOperationMutex.withLock {
            VMModbusHelper.readHoldingRegistersWithDiagnosis(serverAddress, startAddress, quantity)
        }
        return if (result.isSuccess) {
            AtomicRegistersReadResult.Success(result.values!!)
        } else {
            AtomicRegistersReadResult.Failure(formatBatchReadDiagnosis(startAddress, quantity, result.result))
        }
    }

    private fun formatBatchReadDiagnosis(
        startAddress: Int,
        quantity: Int,
        result: VMModbusHelper.ModbusOperationResult
    ): String {
        return listOf(
            "操作=读取",
            "寄存器地址=$startAddress",
            "读取数量=$quantity",
            "从站地址=$serverAddress",
            "连接状态=${if (VMModbusHelper.modbusMaster != null) "已连接" else "未连接"}",
            "异常类型=${result.exceptionClassName ?: "无"}",
            "异常信息=${firstNonBlank(result.exceptionMessage, result.message, "无")}"
        ).joinToString("，")
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
    }

    companion object {
        private val modbusOperationMutex = Mutex()
    }
}
