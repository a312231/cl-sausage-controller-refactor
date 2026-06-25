package cn.niuyannet.kaochang.android.services.paramtuning

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicIoResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicReadResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicRegistersReadResult
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway

interface LowerBoardParamGateway {
    suspend fun readRegister(address: Int): AtomicReadResult
    suspend fun readRegisters(startAddress: Int, quantity: Int): AtomicRegistersReadResult
    suspend fun writeRegister(address: Int, value: Int): AtomicIoResult
}

class VmLowerBoardParamGateway(
    serverAddress: Int,
    operationDelayMs: Long = 150L
) : LowerBoardParamGateway {
    private val gateway = VmAtomicModbusGateway(serverAddress, operationDelayMs)

    override suspend fun readRegister(address: Int): AtomicReadResult {
        return gateway.readRegister(address)
    }

    override suspend fun readRegisters(startAddress: Int, quantity: Int): AtomicRegistersReadResult {
        return gateway.readRegisters(startAddress, quantity)
    }

    override suspend fun writeRegister(address: Int, value: Int): AtomicIoResult {
        return gateway.writeRegister(address, value)
    }
}

data class ModbusFloatWords(val low: Int, val high: Int)

object LowerBoardParamCodec {
    fun floatToWords(value: Float): ModbusFloatWords {
        val bits = value.toRawBits()
        return ModbusFloatWords(
            low = bits and 0xFFFF,
            high = (bits ushr 16) and 0xFFFF
        )
    }

    fun wordsToFloat(lowWord: Int, highWord: Int): Float {
        val bits = (highWord shl 16) or (lowWord and 0xFFFF)
        return Float.fromBits(bits)
    }
}

sealed class ParamValue {
    abstract val key: String
    abstract val displayName: String

    data class UInt16Value(
        val address: Int,
        override val key: String,
        override val displayName: String,
        val value: Int
    ) : ParamValue()

    data class Float32Value(
        val addressLow: Int,
        val addressHigh: Int,
        override val key: String,
        override val displayName: String,
        val value: Float
    ) : ParamValue()
}

sealed class ParamInput {
    data class UInt16Input(val value: Int) : ParamInput()
    data class FloatInput(val value: Float) : ParamInput()
}

data class ParamStatusSnapshot(
    val paramStatus1120: Int,
    val rawWordCount1122: Int,
    val paramVersion: Int
)

data class CalibrationSnapshot(
    val command1400: Int,
    val status1401: Int,
    val targetType1402: Int,
    val targetNo1403: Int,
    val inputX: Float,
    val inputY: Float,
    val inputZ: Float,
    val currentX: Float,
    val currentY: Float,
    val currentZ: Float,
    val lastError1416: Int
)

data class LowerBoardParamSnapshot(
    val status: ParamStatusSnapshot,
    val upperParams: Map<String, ParamValue>,
    val lowerParams: Map<String, ParamValue>,
    val calibration: CalibrationSnapshot
)

sealed class ParamTuningReadResult {
    data class Success(val snapshot: LowerBoardParamSnapshot) : ParamTuningReadResult()
    data class Failure(val message: String) : ParamTuningReadResult()
}

sealed class ParamTuningResult {
    data object Success : ParamTuningResult()
    data class Failure(val message: String, val code: Int? = null) : ParamTuningResult()
}

sealed class CalibrationTarget(
    val type: Int,
    val slot: Int,
    val supportsZ: Boolean
) {
    data class BakewareSlot(val number: Int) : CalibrationTarget(1, number, true)
    data object Lift : CalibrationTarget(2, 0, true)
    data object Stick : CalibrationTarget(3, 0, true)
    data object PushReady : CalibrationTarget(4, 0, true)
    data object Discard : CalibrationTarget(5, 0, true)
    data object PushTo : CalibrationTarget(6, 0, false)
    data object PushBack : CalibrationTarget(7, 0, false)
}

