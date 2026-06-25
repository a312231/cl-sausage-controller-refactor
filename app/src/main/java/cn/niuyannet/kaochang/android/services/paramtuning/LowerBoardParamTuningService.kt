package cn.niuyannet.kaochang.android.services.paramtuning

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicIoResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicReadResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicRegistersReadResult
import kotlinx.coroutines.delay

class LowerBoardParamTuningService(
    private val gateway: LowerBoardParamGateway,
    private val pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
    private val flashTimeoutMs: Long = DEFAULT_FLASH_TIMEOUT_MS,
    private val calibrationTimeoutMs: Long = DEFAULT_CALIBRATION_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun readAll(): ParamTuningReadResult {
        val status = readBlock(1120, 5) ?: return ParamTuningReadResult.Failure("读取参数状态 1120~1124 失败")
        val upperFirst = readBlock(1200, 32) ?: return ParamTuningReadResult.Failure("读取上平台参数 1200~1231 失败")
        val upperSecond = readBlock(1232, 13) ?: return ParamTuningReadResult.Failure("读取上平台参数 1232~1244 失败")
        val lower = readBlock(1300, 15) ?: return ParamTuningReadResult.Failure("读取下平台参数 1300~1314 失败")
        val calibration = readBlock(1400, 17) ?: return ParamTuningReadResult.Failure("读取校准寄存器 1400~1416 失败")
        val upper = upperFirst + upperSecond
        return ParamTuningReadResult.Success(
            LowerBoardParamSnapshot(
                status = ParamStatusSnapshot(
                    paramStatus1120 = status[0],
                    rawWordCount1122 = status[2],
                    paramVersion = (status[3] shl 16) or (status[4] and 0xFFFF)
                ),
                upperParams = parseParams(upper, 1200, LowerBoardParamArea.UPPER),
                lowerParams = parseParams(lower, 1300, LowerBoardParamArea.LOWER),
                calibration = parseCalibration(calibration)
            )
        )
    }

    suspend fun writeParam(fieldKey: String, input: ParamInput): ParamTuningResult {
        val definition = LowerBoardParamDefinitions.byKey[fieldKey]
            ?: return ParamTuningResult.Failure("未知参数：$fieldKey")
        return when (definition) {
            is LowerBoardParamDefinition.UInt16 -> {
                val value = (input as? ParamInput.UInt16Input)?.value
                    ?: return ParamTuningResult.Failure("参数 $fieldKey 需要 uint16 输入")
                writeRegister(definition.address, value)
            }
            is LowerBoardParamDefinition.Float32 -> {
                val value = (input as? ParamInput.FloatInput)?.value
                    ?: return ParamTuningResult.Failure("参数 $fieldKey 需要 float 输入")
                if (definition.coordinate && !isCoordinateInRange(value)) {
                    return ParamTuningResult.Failure("坐标超范围，请输入 -500.0~800.0mm")
                }
                writeFloat(definition.addressLow, value)
            }
        }
    }

    suspend fun saveFlash(): ParamTuningResult {
        return when (val write = writeRegister(1121, PARAM_CMD_SAVE)) {
            ParamTuningResult.Success -> pollRegister(
                address = 1120,
                timeoutMs = flashTimeoutMs,
                success = { it == PARAM_STATUS_SAVE_OK },
                failure = { value ->
                    if (value == PARAM_STATUS_SAVE_FAILED) {
                        ParamTuningResult.Failure("Flash 保存失败：1120=0x8001", value)
                    } else {
                        null
                    }
                },
                timeoutMessage = "Flash 保存超时"
            )
            is ParamTuningResult.Failure -> write
        }
    }

    suspend fun restoreDefaultsToRam(): ParamTuningResult {
        return when (val write = writeRegister(1121, PARAM_CMD_RESET_DEFAULT)) {
            ParamTuningResult.Success -> pollRegister(
                address = 1120,
                timeoutMs = flashTimeoutMs,
                success = { it == PARAM_STATUS_RESET_OK },
                failure = { null },
                timeoutMessage = "恢复默认参数超时"
            )
            is ParamTuningResult.Failure -> write
        }
    }

    suspend fun moveTo(x: Float, y: Float, z: Float): ParamTuningResult {
        val invalid = listOf(x, y, z).firstOrNull { !isCoordinateInRange(it) }
        if (invalid != null) {
            return ParamTuningResult.Failure("坐标超范围，请输入 -500.0~800.0mm")
        }
        when (val writeX = writeFloat(1404, x)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeX
        }
        when (val writeY = writeFloat(1406, y)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeY
        }
        when (val writeZ = writeFloat(1408, z)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeZ
        }
        when (val writeCommand = writeRegister(1400, CALIBRATION_CMD_MOVE)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeCommand
        }
        return pollCalibrationStatus(
            successCode = CALIBRATION_STATUS_DONE,
            timeoutMessage = "校准移动超时"
        )
    }

    suspend fun captureTarget(target: CalibrationTarget): ParamTuningResult {
        val validation = validateTarget(target)
        if (validation != null) {
            return validation
        }
        when (val status = gateway.readRegister(1401)) {
            is AtomicReadResult.Failure -> return ParamTuningResult.Failure("读取 1401 失败：${status.message}")
            is AtomicReadResult.Success -> {
                if (status.value != CALIBRATION_STATUS_DONE) {
                    return ParamTuningResult.Failure("捕获前必须先移动到位，要求 1401=0x0002，当前=${formatCode(status.value)}", status.value)
                }
            }
        }
        when (val writeType = writeRegister(1402, target.type)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeType
        }
        when (val writeSlot = writeRegister(1403, target.slot)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeSlot
        }
        when (val writeCommand = writeRegister(1400, CALIBRATION_CMD_CAPTURE)) {
            ParamTuningResult.Success -> Unit
            is ParamTuningResult.Failure -> return writeCommand
        }
        return pollCalibrationStatus(
            successCode = CALIBRATION_STATUS_CAPTURED,
            timeoutMessage = "捕获点位超时"
        )
    }

    suspend fun homeCalibration(): ParamTuningResult {
        return when (val write = writeRegister(1400, CALIBRATION_CMD_HOME)) {
            ParamTuningResult.Success -> pollCalibrationStatus(
                successCode = CALIBRATION_STATUS_DONE,
                timeoutMessage = "校准回零超时"
            )
            is ParamTuningResult.Failure -> write
        }
    }

    private suspend fun readBlock(startAddress: Int, quantity: Int): IntArray? {
        return when (val result = gateway.readRegisters(startAddress, quantity)) {
            is AtomicRegistersReadResult.Success -> result.values
            is AtomicRegistersReadResult.Failure -> null
        }
    }

    private fun parseParams(
        registers: IntArray,
        blockStart: Int,
        area: LowerBoardParamArea
    ): Map<String, ParamValue> {
        return LowerBoardParamDefinitions.all
            .filter { it.area == area }
            .associate { definition ->
                when (definition) {
                    is LowerBoardParamDefinition.UInt16 -> {
                        definition.key to ParamValue.UInt16Value(
                            address = definition.address,
                            key = definition.key,
                            displayName = definition.displayName,
                            value = registers[definition.address - blockStart]
                        )
                    }
                    is LowerBoardParamDefinition.Float32 -> {
                        val index = definition.addressLow - blockStart
                        definition.key to ParamValue.Float32Value(
                            addressLow = definition.addressLow,
                            addressHigh = definition.addressLow + 1,
                            key = definition.key,
                            displayName = definition.displayName,
                            value = LowerBoardParamCodec.wordsToFloat(registers[index], registers[index + 1])
                        )
                    }
                }
            }
    }

    private fun parseCalibration(registers: IntArray): CalibrationSnapshot {
        return CalibrationSnapshot(
            command1400 = registers[0],
            status1401 = registers[1],
            targetType1402 = registers[2],
            targetNo1403 = registers[3],
            inputX = LowerBoardParamCodec.wordsToFloat(registers[4], registers[5]),
            inputY = LowerBoardParamCodec.wordsToFloat(registers[6], registers[7]),
            inputZ = LowerBoardParamCodec.wordsToFloat(registers[8], registers[9]),
            currentX = LowerBoardParamCodec.wordsToFloat(registers[10], registers[11]),
            currentY = LowerBoardParamCodec.wordsToFloat(registers[12], registers[13]),
            currentZ = LowerBoardParamCodec.wordsToFloat(registers[14], registers[15]),
            lastError1416 = registers[16]
        )
    }

    private suspend fun writeFloat(addressLow: Int, value: Float): ParamTuningResult {
        val words = LowerBoardParamCodec.floatToWords(value)
        return when (val low = writeRegister(addressLow, words.low)) {
            ParamTuningResult.Success -> writeRegister(addressLow + 1, words.high)
            is ParamTuningResult.Failure -> low
        }
    }

    private suspend fun writeRegister(address: Int, value: Int): ParamTuningResult {
        return when (val result = gateway.writeRegister(address, value)) {
            AtomicIoResult.Success -> ParamTuningResult.Success
            is AtomicIoResult.Failure -> ParamTuningResult.Failure("写入 $address=$value 失败：${result.message}")
        }
    }

    private suspend fun pollCalibrationStatus(
        successCode: Int,
        timeoutMessage: String
    ): ParamTuningResult {
        return pollRegister(
            address = 1401,
            timeoutMs = calibrationTimeoutMs,
            success = { it == successCode },
            failure = { value ->
                if (value >= CALIBRATION_ERROR_BASE) {
                    ParamTuningResult.Failure("校准失败：1401=${formatCode(value)}", value)
                } else {
                    null
                }
            },
            timeoutMessage = timeoutMessage
        )
    }

    private suspend fun pollRegister(
        address: Int,
        timeoutMs: Long,
        success: (Int) -> Boolean,
        failure: (Int) -> ParamTuningResult.Failure?,
        timeoutMessage: String
    ): ParamTuningResult {
        val startedAtMs = nowMs()
        while (true) {
            val value = when (val read = gateway.readRegister(address)) {
                is AtomicReadResult.Success -> read.value
                is AtomicReadResult.Failure -> return ParamTuningResult.Failure("读取 $address 失败：${read.message}")
            }
            if (success(value)) {
                return ParamTuningResult.Success
            }
            val failureResult = failure(value)
            if (failureResult != null) {
                return failureResult
            }
            if (nowMs() - startedAtMs >= timeoutMs) {
                return ParamTuningResult.Failure(timeoutMessage)
            }
            if (pollDelayMs > 0L) {
                delay(pollDelayMs)
            }
        }
    }

    private fun validateTarget(target: CalibrationTarget): ParamTuningResult.Failure? {
        return when (target) {
            is CalibrationTarget.BakewareSlot -> {
                if (target.number !in 1..33) {
                    ParamTuningResult.Failure("烤位编号必须在 1~33")
                } else {
                    null
                }
            }
            else -> {
                if (target.slot != 0) {
                    ParamTuningResult.Failure("非烤位目标编号必须为 0")
                } else {
                    null
                }
            }
        }
    }

    private fun isCoordinateInRange(value: Float): Boolean {
        return value in MIN_COORDINATE_MM..MAX_COORDINATE_MM
    }

    private fun formatCode(code: Int): String {
        return "0x${code.toString(16).uppercase().padStart(4, '0')}"
    }

    companion object {
        private const val DEFAULT_POLL_DELAY_MS = 200L
        private const val DEFAULT_FLASH_TIMEOUT_MS = 15_000L
        private const val DEFAULT_CALIBRATION_TIMEOUT_MS = 60_000L
        private const val MIN_COORDINATE_MM = -500.0f
        private const val MAX_COORDINATE_MM = 800.0f
        private const val PARAM_CMD_SAVE = 1
        private const val PARAM_CMD_RESET_DEFAULT = 2
        private const val PARAM_STATUS_SAVE_OK = 0x0002
        private const val PARAM_STATUS_RESET_OK = 0x0003
        private const val PARAM_STATUS_SAVE_FAILED = 0x8001
        private const val CALIBRATION_CMD_MOVE = 1
        private const val CALIBRATION_CMD_CAPTURE = 2
        private const val CALIBRATION_CMD_HOME = 3
        private const val CALIBRATION_STATUS_DONE = 0x0002
        private const val CALIBRATION_STATUS_CAPTURED = 0x0003
        private const val CALIBRATION_ERROR_BASE = 0x00E1
    }
}
