package cn.niuyannet.kaochang.android.modbus

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.invertor.modbus.Modbus
import com.invertor.modbus.ModbusMaster
import com.invertor.modbus.ModbusMasterFactory
import com.invertor.modbus.exception.IllegalDataAddressException
import com.invertor.modbus.exception.IllegalDataValueException
import com.invertor.modbus.exception.ModbusIOException
import com.invertor.modbus.exception.ModbusNumberException
import com.invertor.modbus.exception.ModbusProtocolException
import com.invertor.modbus.exception.SlaveDeviceBusyException
import com.invertor.modbus.exception.SlaveDeviceFailureException
import com.invertor.modbus.serial.SerialParameters
import com.invertor.modbus.serial.SerialPort
import com.invertor.modbus.serial.SerialPortFactoryJSSC
import com.invertor.modbus.serial.SerialUtils
import com.invertor.modbus.utils.ModbusExceptionCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modbus RTU 通信工具。
 *
 * 当前项目通过串口 RTU 与下位机通信，这里维护的是 RTU 主站对象。
 *
 * 约定：
 * - `connectStatus()` 只负责探活，用来判断“上下位机是否还能正常通信”。
 * - 201 寄存器的 0/1/2 语义属于动作状态，由上层单独解释。
 * - 读写寄存器方法会尽量区分通信层、协议层和参数层错误，并把结果返回给调用方。
 */
object VMModbusHelper {
    enum class ModbusOperationStatus {
        SUCCESS,
        DISCONNECTED,
        BUSY,
        PARAM_ERROR,
        DEVICE_FAILURE,
        PROTOCOL_ERROR,
        UNKNOWN_ERROR
    }

    data class ModbusOperationResult(
        val status: ModbusOperationStatus,
        val message: String? = null
    ) {
        val isSuccess: Boolean
            get() = status == ModbusOperationStatus.SUCCESS
    }

    var modbusMaster: ModbusMaster? = null
    var isLunXun = true
    var isDebug = false

    fun connectModbus(callback: (status: Boolean) -> Unit) {
        if (isDebug) {
            callback.invoke(true)
            return
        }

        try {
            if (connectStatus()) {
                callback.invoke(true)
                return
            }
            val appConfigBean = AppConfig.getAppConfig()
            val serialParameters = SerialParameters().apply {
                device = appConfigBean.modbusAddress
                parity = SerialPort.Parity.NONE
                dataBits = 8
                stopBits = 1
                if (appConfigBean.modbusBaudRate == 9600) {
                    setBaudRate(SerialPort.BaudRate.BAUD_RATE_9600)
                } else if (appConfigBean.modbusBaudRate == 115200) {
                    setBaudRate(SerialPort.BaudRate.BAUD_RATE_115200)
                }
            }
            Modbus.setLogLevel(Modbus.LogLevel.LEVEL_DEBUG)
            SerialUtils.setSerialPortFactory(SerialPortFactoryJSSC())
            modbusMaster = ModbusMasterFactory.createModbusMasterRTU(serialParameters)
            modbusMaster?.connect()
            callback.invoke(true)
        } catch (e: Exception) {
            e.printStackTrace()
            callback.invoke(false)
        }
    }

    /**
     * 仅用于通信探活，不参与解释设备忙闲状态。
     */
    fun connectStatus(): Boolean {
        if (isDebug) {
            return true
        }
        return try {
            if (modbusMaster == null) {
                false
            } else {
                modbusMaster?.readHoldingRegisters(1, 0, 10)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.i("@@@", "Modbus RTU 连接已断开")
            false
        }
    }

    private fun classifyException(actionName: String, e: Exception): ModbusOperationResult {
        return when (e) {
            is SlaveDeviceBusyException -> {
                LogUtils.w("$actionName 失败：下位机返回设备忙，建议短暂等待后重试。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
            }

            is ModbusIOException -> {
                LogUtils.e("$actionName 失败：Modbus 通信异常，可能断连或超时。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DISCONNECTED, e.message)
            }

            is IllegalDataAddressException, is IllegalDataValueException, is ModbusNumberException -> {
                LogUtils.e("$actionName 失败：寄存器地址或参数不合法。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
            }

            is SlaveDeviceFailureException -> {
                LogUtils.e("$actionName 失败：下位机返回设备故障。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
            }

            is ModbusProtocolException -> {
                when (e.getException()) {
                    ModbusExceptionCode.SLAVE_DEVICE_BUSY -> {
                        LogUtils.w("$actionName 失败：下位机返回设备忙，建议短暂等待后重试。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
                    }

                    ModbusExceptionCode.SLAVE_DEVICE_FAILURE,
                    ModbusExceptionCode.GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND -> {
                        LogUtils.e("$actionName 失败：下位机返回设备故障。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
                    }

                    ModbusExceptionCode.ILLEGAL_DATA_ADDRESS,
                    ModbusExceptionCode.ILLEGAL_DATA_VALUE,
                    ModbusExceptionCode.ILLEGAL_FUNCTION -> {
                        LogUtils.e("$actionName 失败：寄存器地址、功能码或参数不合法。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
                    }

                    else -> {
                        LogUtils.e("$actionName 失败：Modbus 协议异常。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PROTOCOL_ERROR, e.message)
                    }
                }
            }

            else -> {
                LogUtils.e("$actionName 失败：未知类型异常。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.UNKNOWN_ERROR, e.message)
            }
        }
    }

    fun readCoils(serverAddress: Int, startAddress: Int): Boolean {
        return modbusMaster?.readCoils(serverAddress, startAddress, 1)?.get(0) ?: false
    }

    fun writeSingleCoil(serverAddress: Int, startAddress: Int, flag: Boolean): ModbusOperationResult {
        if (isDebug) {
            return ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        }
        val currentMaster = modbusMaster
            ?: return ModbusOperationResult(
                ModbusOperationStatus.DISCONNECTED,
                "RTU 主站未初始化，写线圈[$startAddress] 未执行"
            )
        return try {
            currentMaster.writeSingleCoil(serverAddress, startAddress, flag)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("写入线圈[$startAddress]", e)
        }
    }

    /**
     * 读取单个保持寄存器。
     * 失败时返回 -1，由上层按“通信失败/状态未知”处理。
     */
    fun readHoldingRegisters(serverAddress: Int, startAddress: Int): Int {
        if (isDebug) {
            return 0
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, 1)?.get(0) ?: -1
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "读取寄存器出错：${e.message}")
            -1
        }
    }

    /**
     * 读取多个保持寄存器。
     * 失败时返回 null，由调用方决定是否重试或进入故障处理。
     */
    fun readHoldingRegisters(serverAddress: Int, startAddress: Int, quantity: Int): IntArray? {
        if (isDebug) {
            return IntArray(quantity)
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, quantity)
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "读取多个寄存器出错：${e.message}")
            null
        }
    }

    fun writeSingleRegister(serverAddress: Int, startAddress: Int, value: Int): ModbusOperationResult {
        if (isDebug) {
            return ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        }
        val currentMaster = modbusMaster
            ?: return ModbusOperationResult(
                ModbusOperationStatus.DISCONNECTED,
                "RTU 主站未初始化，写寄存器[$startAddress]=$value 未执行"
            )
        return try {
            currentMaster.writeSingleRegister(serverAddress, startAddress, value)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("写入保持寄存器[$startAddress]=$value", e)
        }
    }

    fun disconnectModbusMaster() {
        modbusMaster?.disconnect()
        modbusMaster = null
    }

    @Deprecated("请改用 disconnectModbusMaster()，避免误解为 Modbus TCP 主站")
    fun disConnectTcpMaster() {
        disconnectModbusMaster()
    }

    fun setLunXunCallBack(address: Int, callback: (value: Int) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            while (isLunXun) {
                var value = -1
                try {
                    value = if (modbusMaster?.readCoils(1, address, 1)?.get(0) == true) {
                        1
                    } else {
                        0
                    }
                    delay(50)
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(2000)
                }
                callback.invoke(value)
            }
        }
    }
}
