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
            AtomicIoResult.Failure("${result.status} ${result.message ?: ""}".trim())
        }
    }

    override suspend fun readRegister(address: Int): AtomicReadResult {
        if (operationDelayMs > 0L) {
            delay(operationDelayMs)
        }
        val value = modbusOperationMutex.withLock {
            VMModbusHelper.readHoldingRegisters(serverAddress, address)
        }
        return if (value >= 0) {
            AtomicReadResult.Success(value)
        } else {
            AtomicReadResult.Failure("读取寄存器 $address 返回 $value")
        }
    }

    companion object {
        private val modbusOperationMutex = Mutex()
    }
}
