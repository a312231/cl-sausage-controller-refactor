package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.delay

object AtomicActionCodes {
    const val IDLE = 0x0000
    const val RUNNING = 0x0001
    const val PARAM_ERROR = 0x00E1
    const val X_RESET_FAILED = 0x00E2
    const val Y_RESET_FAILED = 0x00E3
    const val LIFT_FAILED = 0x00E4
    const val IR_PICK_FAILED = 0x00E5
    const val IR_THROW_FAILED = 0x00E6
    const val STICK_FAILED = 0x00E7
    const val CLIENT_TAKE_TIMEOUT_DISCARDED = 0x00E8
    const val BUSY = 0x00E9
}

data class AtomicActionSpec(
    val name: String,
    val commandRegister: Int,
    val resultRegister: Int,
    val infoRegister: Int,
    val timeoutMs: Long,
    val pollDelayMs: Long,
    val description: String
)

object AtomicActions {
    val in1 = AtomicActionSpec("in1", 310, 311, 312, 60_000L, 200L, "in1：移动到肠箱预推送位置")
    val in2 = AtomicActionSpec("in2", 320, 321, 322, 60_000L, 200L, "in2：取肠、分料、避让")
    val in3 = AtomicActionSpec("in3", 330, 331, 332, 60_000L, 200L, "in3：回到抛肠点")
    val in4 = AtomicActionSpec("in4", 340, 341, 342, 60_000L, 500L, "in4：升降台下降")
    val in5 = AtomicActionSpec("in5", 350, 351, 352, 60_000L, 300L, "in5：抛肠并检测是否抛出成功")
    val in6 = AtomicActionSpec("in6", 360, 361, 362, 60_000L, 500L, "in6：升降台上升")
    val in7 = AtomicActionSpec("in7", 370, 371, 372, 120_000L, 500L, "in7：上平台从升降台夹肠并放入烤盘")

    val out1 = AtomicActionSpec("out1", 410, 411, 412, 120_000L, 500L, "out1：从烤盘取熟肠到穿签位")
    val out2 = AtomicActionSpec("out2", 420, 421, 422, 90_000L, 500L, "out2：执行穿签")
    val out3 = AtomicActionSpec("out3", 430, 431, 432, 90_000L, 500L, "out3：从穿签位放到预推送位置")
    val out4 = AtomicActionSpec("out4", 440, 441, 442, 90_000L, 500L, "out4：开窗并推送")
    val out5 = AtomicActionSpec("out5", 450, 451, 452, 90_000L, 500L, "out5：检查顾客是否取走，超时未取由下位机自动丢弃")
}

sealed class AtomicIoResult {
    data object Success : AtomicIoResult()
    data class Failure(val message: String) : AtomicIoResult()
}

sealed class AtomicReadResult {
    data class Success(val value: Int) : AtomicReadResult()
    data class Failure(val message: String) : AtomicReadResult()
}

sealed class AtomicActionResult {
    data object Success : AtomicActionResult()
    data class Failed(val errorCode: Int, val message: String) : AtomicActionResult()
    data class Timeout(val message: String) : AtomicActionResult()
    data class ModbusError(val message: String) : AtomicActionResult()
}

interface AtomicModbusGateway {
    suspend fun writeRegister(address: Int, value: Int): AtomicIoResult
    suspend fun readRegister(address: Int): AtomicReadResult
}

interface AtomicActionClient {
    suspend fun execute(spec: AtomicActionSpec, infoValue: Int = 0): AtomicActionResult
}

interface AtomicActionStateReader {
    suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult
}

class AtomicActionExecutor(
    private val gateway: AtomicModbusGateway,
    private val logger: (String) -> Unit
) : AtomicActionClient, AtomicActionStateReader {

    override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
        return gateway.readRegister(spec.resultRegister)
    }

    override suspend fun execute(spec: AtomicActionSpec, infoValue: Int): AtomicActionResult {
        return when (val current = readResult(spec)) {
            is AtomicReadResult.Failure -> AtomicActionResult.ModbusError(
                "${spec.name} 启动前读取结果寄存器 ${spec.resultRegister} 失败：${current.message}"
            )
            is AtomicReadResult.Success -> {
                if (current.value == AtomicActionCodes.RUNNING) {
                    logger("接管原子动作轮询：action=${spec.name}，resultRegister=${spec.resultRegister}")
                    pollUntilFinish(spec, startedAtMs = System.currentTimeMillis())
                } else {
                    startAndPoll(spec, infoValue)
                }
            }
        }
    }

    private suspend fun startAndPoll(spec: AtomicActionSpec, infoValue: Int): AtomicActionResult {
        logger(
            "启动原子动作：action=${spec.name}，cmd=${spec.commandRegister}，result=${spec.resultRegister}，" +
                "info=${spec.infoRegister}，infoValue=$infoValue，description=${spec.description}"
        )
        when (val writeInfo = gateway.writeRegister(spec.infoRegister, infoValue)) {
            is AtomicIoResult.Failure -> return AtomicActionResult.ModbusError(
                "${spec.name} 写信息寄存器 ${spec.infoRegister} 失败：${writeInfo.message}"
            )
            AtomicIoResult.Success -> Unit
        }
        when (val writeCommand = gateway.writeRegister(spec.commandRegister, 1)) {
            is AtomicIoResult.Failure -> return AtomicActionResult.ModbusError(
                "${spec.name} 写命令寄存器 ${spec.commandRegister} 失败：${writeCommand.message}"
            )
            AtomicIoResult.Success -> Unit
        }
        return pollUntilFinish(spec, startedAtMs = System.currentTimeMillis())
    }

    private suspend fun pollUntilFinish(spec: AtomicActionSpec, startedAtMs: Long): AtomicActionResult {
        while (System.currentTimeMillis() - startedAtMs <= spec.timeoutMs) {
            when (val read = gateway.readRegister(spec.resultRegister)) {
                is AtomicReadResult.Failure -> return AtomicActionResult.ModbusError(
                    "${spec.name} 读取结果寄存器 ${spec.resultRegister} 失败：${read.message}"
                )
                is AtomicReadResult.Success -> {
                    val code = read.value
                    logger(
                        "轮询原子动作：action=${spec.name}，resultRegister=${spec.resultRegister}，" +
                            "resultCode=${formatCode(code)}，elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                    when {
                        code == AtomicActionCodes.IDLE -> return AtomicActionResult.Success
                        code == AtomicActionCodes.RUNNING -> delay(spec.pollDelayMs)
                        code >= AtomicActionCodes.PARAM_ERROR -> return AtomicActionResult.Failed(code, messageOf(code))
                        else -> return AtomicActionResult.ModbusError("${spec.name} 未知结果码：${formatCode(code)}")
                    }
                }
            }
        }
        return AtomicActionResult.Timeout("${spec.name} 执行超时：timeoutMs=${spec.timeoutMs}")
    }

    companion object {
        fun formatCode(code: Int): String = "0x${code.toString(16).uppercase().padStart(4, '0')}"

        fun messageOf(code: Int): String = when (code) {
            AtomicActionCodes.PARAM_ERROR -> "参数错误"
            AtomicActionCodes.X_RESET_FAILED -> "X 复位失败"
            AtomicActionCodes.Y_RESET_FAILED -> "Y 复位失败"
            AtomicActionCodes.LIFT_FAILED -> "升降台故障"
            AtomicActionCodes.IR_PICK_FAILED -> "取肠红外未确认有肠"
            AtomicActionCodes.IR_THROW_FAILED -> "抛肠红外未确认抛出成功"
            AtomicActionCodes.STICK_FAILED -> "穿签失败"
            AtomicActionCodes.CLIENT_TAKE_TIMEOUT_DISCARDED -> "顾客超时未取，已自动丢弃"
            AtomicActionCodes.BUSY -> "忙/资源不可用"
            else -> "未知错误码 ${formatCode(code)}"
        }
    }
}
