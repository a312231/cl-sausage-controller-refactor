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
import com.kongqw.serialportlibrary.SerialPortFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object VMModbusHelper {
    // 指定这块安卓 12 板卡固定优先走 ttyS7，避免串口枚举顺序变化导致连错设备。
    private const val preferredBoardSerialPort = "/dev/ttyS7"
    private val forcedSerialPortDeviceIds = setOf(
        "3a27355579c4dee5"
    )
    private data class ProbeRead(val startAddress: Int, val quantity: Int, val label: String)
    private val connectivityProbes = listOf(
        ProbeRead(0, 10, "bootstrap_0_9"),
        ProbeRead(201, 1, "device_status_201"),
        ProbeRead(200, 8, "command_window_200_207")
    )

    private val serialPortPrefixes = listOf(
        "/dev/ttyUSB",
        "/dev/ttyACM",
        "/dev/ttyCH343USB",
        "/dev/ttyS",
        "/dev/ttyFIQ",
        "/dev/ttyAMA",
        "/dev/ttyWK",
        "/dev/ttyXRUSB"
    )

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
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lunXunJob: Job? = null

    private fun buildSerialParameters(devicePath: String, baudRate: Int): SerialParameters {
        return SerialParameters().apply {
            device = devicePath
            parity = SerialPort.Parity.NONE
            dataBits = 8
            stopBits = 1
            when (baudRate) {
                9600 -> setBaudRate(SerialPort.BaudRate.BAUD_RATE_9600)
                115200 -> setBaudRate(SerialPort.BaudRate.BAUD_RATE_115200)
            }
        }
    }

    private fun serialPortPriority(path: String): Int {
        val index = serialPortPrefixes.indexOfFirst { prefix -> path.startsWith(prefix) }
        return if (index >= 0) index else serialPortPrefixes.size
    }

    private fun serialPortTieBreaker(path: String): Int {
        return when (path) {
            preferredBoardSerialPort -> -1
            else -> 0
        }
    }

    private fun listAvailableSerialPorts(): List<String> {
        val finderPorts = runCatching {
            SerialPortFinder().devices.mapNotNull { device -> device.file?.absolutePath }
        }.getOrElse {
            LogUtils.w("串口枚举失败（SerialPortFinder）：${it.message}")
            emptyList()
        }

        val devPorts = runCatching {
            File("/dev").listFiles()
                ?.map { file -> file.absolutePath }
                ?.filter { path -> serialPortPrefixes.any { prefix -> path.startsWith(prefix) } }
                .orEmpty()
        }.getOrElse {
            LogUtils.w("串口枚举失败（/dev）：${it.message}")
            emptyList()
        }

        return (finderPorts + devPorts)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String>({ serialPortPriority(it) }, { serialPortTieBreaker(it) }, { it }))
    }

    private fun shouldForcePreferredSerialPort(): Boolean {
        return forcedSerialPortDeviceIds.contains(AppConfig.getDeviceId())
    }

    private fun resolveCandidatePorts(configuredAddress: String?): List<String> {
        if (shouldForcePreferredSerialPort()) {
            return listOf(preferredBoardSerialPort)
        }
        val preferred = configuredAddress?.takeIf { it.isNotBlank() }
        val availablePorts = listAvailableSerialPorts()
        return buildList {
            if (!preferred.isNullOrBlank()) {
                add(preferred)
            }
            if (!contains(preferredBoardSerialPort) && availablePorts.contains(preferredBoardSerialPort)) {
                add(preferredBoardSerialPort)
            }
            availablePorts.forEach { port ->
                if (!contains(port)) {
                    add(port)
                }
            }
        }
    }

    private fun tryConnectivityProbe(master: ModbusMaster): Boolean {
        var lastError: Exception? = null
        connectivityProbes.forEach { probe ->
            try {
                master.readHoldingRegisters(1, probe.startAddress, probe.quantity)
                LogUtils.d("Modbus 连通性探测成功：${probe.label}")
                return true
            } catch (e: Exception) {
                lastError = e
            }
        }
        LogUtils.w("串口已打开，但未收到有效的下位机响应：${lastError?.message}")
        return false
    }

    private fun hasModbusResponse(master: ModbusMaster): Boolean {
        return tryConnectivityProbe(master)
    }

    @Synchronized
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
            Modbus.setLogLevel(Modbus.LogLevel.LEVEL_DEBUG)
            SerialUtils.setSerialPortFactory(SerialPortFactoryJSSC())
            if (shouldForcePreferredSerialPort() && appConfigBean.modbusAddress != preferredBoardSerialPort) {
                LogUtils.w(
                    "设备 ${AppConfig.getDeviceId()} 强制使用串口：" +
                        "${appConfigBean.modbusAddress} -> $preferredBoardSerialPort"
                )
                appConfigBean.modbusAddress = preferredBoardSerialPort
                AppConfig.saveAppConfig(appConfigBean)
            }
            val candidatePorts = resolveCandidatePorts(appConfigBean.modbusAddress)
            if (candidatePorts.isEmpty()) {
                LogUtils.e("没有找到可用的 Modbus 串口候选，当前配置=${appConfigBean.modbusAddress}")
                disconnectModbusMaster()
                callback.invoke(false)
                return
            }

            LogUtils.d(
                "开始探测 Modbus 串口：当前配置=${appConfigBean.modbusAddress}，" +
                    "candidates=${candidatePorts.joinToString()}"
            )
            var lastErrorMessage: String? = null

            for (devicePath in candidatePorts) {
                try {
                    disconnectModbusMaster()
                    val master = ModbusMasterFactory.createModbusMasterRTU(
                        buildSerialParameters(devicePath, appConfigBean.modbusBaudRate)
                    )
                    master.connect()
                    if (!hasModbusResponse(master)) {
                        runCatching { master.disconnect() }
                        lastErrorMessage = "串口 $devicePath 没有收到 Modbus 响应"
                        continue
                    }
                    modbusMaster = master

                    if (appConfigBean.modbusAddress != devicePath) {
                        LogUtils.w("自动探测到可用串口：${appConfigBean.modbusAddress} -> $devicePath")
                        appConfigBean.modbusAddress = devicePath
                        AppConfig.saveAppConfig(appConfigBean)
                    } else {
                        LogUtils.d("Modbus 串口连接成功：$devicePath")
                    }

                    callback.invoke(true)
                    return
                } catch (e: Exception) {
                    lastErrorMessage = e.message
                    LogUtils.w("串口探测失败：device=$devicePath, error=${e.message}")
                }
            }

            LogUtils.e(
                "所有 Modbus 串口探测均失败：当前配置=${appConfigBean.modbusAddress}，" +
                    "candidates=${candidatePorts.joinToString()}, lastError=$lastErrorMessage"
            )
            disconnectModbusMaster()
            callback.invoke(false)
        } catch (e: Exception) {
            e.printStackTrace()
            disconnectModbusMaster()
            callback.invoke(false)
        }
    }

    @Synchronized
    fun connectStatus(): Boolean {
        if (isDebug) {
            return true
        }
        return try {
            if (modbusMaster == null) {
                false
            } else {
                tryConnectivityProbe(modbusMaster!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            disconnectModbusMaster()
            Log.i("@@@", "Modbus RTU 已断开")
            false
        }
    }

    private fun classifyException(actionName: String, e: Exception): ModbusOperationResult {
        return when (e) {
            is SlaveDeviceBusyException -> {
                LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
            }

            is ModbusIOException -> {
                LogUtils.e("$actionName 失败：Modbus I/O 异常。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DISCONNECTED, e.message)
            }

            is IllegalDataAddressException, is IllegalDataValueException, is ModbusNumberException -> {
                LogUtils.e("$actionName 失败：寄存器地址或数值非法。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
            }

            is SlaveDeviceFailureException -> {
                LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
            }

            is ModbusProtocolException -> {
                when (e.getException()) {
                    ModbusExceptionCode.SLAVE_DEVICE_BUSY -> {
                        LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.BUSY, e.message)
                    }

                    ModbusExceptionCode.SLAVE_DEVICE_FAILURE,
                    ModbusExceptionCode.GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND -> {
                        LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.DEVICE_FAILURE, e.message)
                    }

                    ModbusExceptionCode.ILLEGAL_DATA_ADDRESS,
                    ModbusExceptionCode.ILLEGAL_DATA_VALUE,
                    ModbusExceptionCode.ILLEGAL_FUNCTION -> {
                        LogUtils.e("$actionName 失败：地址、功能码或数值非法。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PARAM_ERROR, e.message)
                    }

                    else -> {
                        LogUtils.e("$actionName 失败：Modbus 协议异常。${e.message}")
                        ModbusOperationResult(ModbusOperationStatus.PROTOCOL_ERROR, e.message)
                    }
                }
            }

            else -> {
                LogUtils.e("$actionName 失败：未知异常。${e.message}")
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
        val currentMaster = modbusMaster ?: return ModbusOperationResult(
            ModbusOperationStatus.DISCONNECTED,
            "RTU 主站未初始化，跳过线圈写入 [$startAddress]"
        )
        return try {
            currentMaster.writeSingleCoil(serverAddress, startAddress, flag)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("Write coil [$startAddress]", e)
        }
    }

    fun readHoldingRegisters(serverAddress: Int, startAddress: Int): Int {
        if (isDebug) {
            return 0
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, 1)?.get(0) ?: -1
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "读取寄存器失败：${e.message}")
            -1
        }
    }

    fun readHoldingRegisters(serverAddress: Int, startAddress: Int, quantity: Int): IntArray? {
        if (isDebug) {
            return IntArray(quantity)
        }
        return try {
            modbusMaster?.readHoldingRegisters(serverAddress, startAddress, quantity)
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "批量读取寄存器失败：${e.message}")
            null
        }
    }

    fun writeSingleRegister(serverAddress: Int, startAddress: Int, value: Int): ModbusOperationResult {
        if (isDebug) {
            return ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        }
        val currentMaster = modbusMaster ?: return ModbusOperationResult(
            ModbusOperationStatus.DISCONNECTED,
            "RTU 主站未初始化，跳过寄存器写入 [$startAddress]=$value"
        )
        return try {
            currentMaster.writeSingleRegister(serverAddress, startAddress, value)
            ModbusOperationResult(ModbusOperationStatus.SUCCESS)
        } catch (e: Exception) {
            e.printStackTrace()
            classifyException("Write register [$startAddress]=$value", e)
        }
    }

    @Synchronized
    fun disconnectModbusMaster() {
        runCatching { modbusMaster?.disconnect() }
        modbusMaster = null
    }

    @Deprecated("Use disconnectModbusMaster() instead")
    fun disConnectTcpMaster() {
        disconnectModbusMaster()
    }

    fun setLunXunCallBack(address: Int, callback: (value: Int) -> Unit) {
        lunXunJob?.cancel()
        lunXunJob = helperScope.launch {
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
