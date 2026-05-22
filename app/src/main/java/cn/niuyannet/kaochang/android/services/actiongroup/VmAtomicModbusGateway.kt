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

    companion object {
        private val modbusOperationMutex = Mutex()
    }
}
