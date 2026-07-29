package cn.niuyannet.kaochang.android.modbus

import android.os.Build
import android.os.SystemClock
import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.RepeatedLogGate
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
    private data class BoardSerialProfile(
        val name: String,
        val keywords: List<String>,
        val preferredPorts: List<String>
    )

    private val boardSerialProfiles = listOf(
        BoardSerialProfile(
            name = "rk3568_s",
            keywords = listOf("rk3568_s"),
            preferredPorts = listOf("/dev/ttyS7", "/dev/ttyS2", "/dev/ttyUSB0", "/dev/ttyUSB1")
        ),
        BoardSerialProfile(
            name = "rk3568_r",
            keywords = listOf("rk3568_r"),
            preferredPorts = listOf("/dev/ttyS2", "/dev/ttyS7", "/dev/ttyUSB0", "/dev/ttyUSB1")
        ),
        BoardSerialProfile(
            name = "rk3568_generic",
            keywords = listOf("rk3568"),
            preferredPorts = listOf("/dev/ttyS2", "/dev/ttyS7", "/dev/ttyUSB0", "/dev/ttyUSB1")
        )
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
        val message: String? = null,
        val exceptionClassName: String? = null,
        val exceptionMessage: String? = null
    ) {
        val isSuccess: Boolean
            get() = status == ModbusOperationStatus.SUCCESS
    }

    data class ModbusRegisterReadResult(
        val value: Int,
        val result: ModbusOperationResult
    ) {
        val isSuccess: Boolean
            get() = result.isSuccess && value >= 0
    }

    data class ModbusBatchReadResult(
        val values: IntArray?,
        val result: ModbusOperationResult
    ) {
        val isSuccess: Boolean
            get() = result.isSuccess && values != null
    }

    data class ModbusDiagnosisSnapshot(
        val connected: Boolean,
        val port: String,
        val baudRate: Int
    )

    var modbusMaster: ModbusMaster? = null
    var isLunXun = true
    var isDebug = false
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lunXunJob: Job? = null
    private const val RECENT_RECONNECT_RESULT_REUSE_MS = 2_000L
    private const val RECONNECT_FAILURE_SUMMARY_INTERVAL_MS = 30L * 60 * 1000
    private val reconnectFailureLogGate = RepeatedLogGate(RECONNECT_FAILURE_SUMMARY_INTERVAL_MS)
    private var lastReconnectFinishedAtMs = 0L
    private var lastReconnectStatus = false

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
        return 0
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

    private fun buildBoardFingerprint(): String {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT,
            Build.BOARD,
            Build.HARDWARE
        )
            .filterNotNull()
            .joinToString(" | ")
            .lowercase()
    }

    private fun resolveBoardSerialProfile(): BoardSerialProfile? {
        val fingerprint = buildBoardFingerprint()
        return boardSerialProfiles.firstOrNull { profile ->
            profile.keywords.any { keyword -> fingerprint.contains(keyword) }
        }
    }

    private fun resolveCandidatePorts(configuredAddress: String?): List<String> {
        val preferred = configuredAddress?.takeIf { it.isNotBlank() }
        val availablePorts = listAvailableSerialPorts()
        val boardProfile = resolveBoardSerialProfile()
        val profilePorts = boardProfile?.preferredPorts.orEmpty()
        return buildList {
            if (!preferred.isNullOrBlank()) {
                add(preferred)
            }
            profilePorts.forEach { port ->
                if (!contains(port) && availablePorts.contains(port)) {
                    add(port)
                }
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
                // 探测成功不记日志，每秒运行会刷屏；调用方按需记录连接状态变化
                return true
            } catch (e: Exception) {
                lastError = e
            }
        }
        LogUtils.d("串口已打开，但未收到有效的下位机响应：${lastError?.message}")
        return false
    }

    private fun logReconnectFailure(state: String, message: String) {
        val decision = reconnectFailureLogGate.evaluate(state, SystemClock.elapsedRealtime()) ?: return
        LogUtils.e("$message，suppressedCount=${decision.suppressedCount}")
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
                reconnectFailureLogGate.reset()
                lastReconnectFinishedAtMs = System.currentTimeMillis()
                lastReconnectStatus = true
                callback.invoke(true)
                return
            }

            val now = System.currentTimeMillis()
            if (lastReconnectFinishedAtMs > 0 &&
                now - lastReconnectFinishedAtMs <= RECENT_RECONNECT_RESULT_REUSE_MS
            ) {
                LogUtils.d(
                    "【Modbus】复用刚完成的重连结果，避免连续重复完整串口探测：" +
                        "status=$lastReconnectStatus，elapsedMs=${now - lastReconnectFinishedAtMs}"
                )
                callback.invoke(lastReconnectStatus)
                return
            }

            val appConfigBean = AppConfig.getAppConfig()
            Modbus.setLogLevel(Modbus.LogLevel.LEVEL_DEBUG)
            SerialUtils.setSerialPortFactory(SerialPortFactoryJSSC())
            val candidatePorts = resolveCandidatePorts(appConfigBean.modbusAddress)
            val boardProfile = resolveBoardSerialProfile()
            val boardFingerprint = buildBoardFingerprint()
            if (candidatePorts.isEmpty()) {
                val failure = "没有找到可用的 Modbus 串口候选，当前配置=${appConfigBean.modbusAddress}"
                logReconnectFailure(failure, failure)
                disconnectModbusMaster()
                lastReconnectFinishedAtMs = System.currentTimeMillis()
                lastReconnectStatus = false
                callback.invoke(false)
                return
            }

            LogUtils.d(
                "开始探测 Modbus 串口：当前配置=${appConfigBean.modbusAddress}，" +
                    "boardProfile=${boardProfile?.name ?: "none"}，" +
                    "boardFingerprint=$boardFingerprint，" +
                    "candidates=${candidatePorts.joinToString()}"
            )
            val probeResults = mutableListOf<String>()

            for (devicePath in candidatePorts) {
                disconnectModbusMaster()
                val probeResult = ModbusCandidateProbeRunner.probe(
                    devicePath = devicePath,
                    createMaster = {
                        ModbusMasterFactory.createModbusMasterRTU(
                            buildSerialParameters(devicePath, appConfigBean.modbusBaudRate)
                        )
                    },
                    connect = { master -> master.connect() },
                    hasResponse = { master -> hasModbusResponse(master) },
                    disconnect = { master -> master.disconnect() }
                )
                probeResults.add(probeResult.summary())
                if (!probeResult.isSuccess) {
                    val busyHint = if (probeResult.isPortBusy) {
                        "，判断=串口被占用或上次失败后未释放"
                    } else {
                        ""
                    }
                    LogUtils.d("串口探测失败：${probeResult.summary()}$busyHint")
                    continue
                }

                val master = probeResult.master!!
                modbusMaster = master
                reconnectFailureLogGate.reset()

                if (appConfigBean.modbusAddress != devicePath) {
                    LogUtils.i("【Modbus】自动探测到可用串口：${appConfigBean.modbusAddress} -> $devicePath，已更新配置")
                    appConfigBean.modbusAddress = devicePath
                    AppConfig.saveAppConfig(appConfigBean)
                } else {
                    LogUtils.i("【Modbus】串口连接成功：port=$devicePath")
                }

                lastReconnectFinishedAtMs = System.currentTimeMillis()
                lastReconnectStatus = true
                callback.invoke(true)
                return
            }

            val failure = "所有 Modbus 串口探测均失败：当前配置=${appConfigBean.modbusAddress}，" +
                "candidates=${candidatePorts.joinToString()}，" +
                "probeResults=${probeResults.joinToString("；")}"
            logReconnectFailure(failure, failure)
            disconnectModbusMaster()
            lastReconnectFinishedAtMs = System.currentTimeMillis()
            lastReconnectStatus = false
            callback.invoke(false)
        } catch (e: Exception) {
            e.printStackTrace()
            disconnectModbusMaster()
            lastReconnectFinishedAtMs = System.currentTimeMillis()
            lastReconnectStatus = false
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
        fun result(status: ModbusOperationStatus): ModbusOperationResult {
            return ModbusOperationResult(
                status = status,
                message = e.message,
                exceptionClassName = e.javaClass.simpleName,
                exceptionMessage = e.message
            )
        }
        return when (e) {
            is SlaveDeviceBusyException -> {
                LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                result(ModbusOperationStatus.BUSY)
            }

            is ModbusIOException -> {
                LogUtils.e("$actionName 失败：Modbus I/O 异常。${e.message}")
                result(ModbusOperationStatus.DISCONNECTED)
            }

            is IllegalDataAddressException, is IllegalDataValueException, is ModbusNumberException -> {
                LogUtils.e("$actionName 失败：寄存器地址或数值非法。${e.message}")
                result(ModbusOperationStatus.PARAM_ERROR)
            }

            is SlaveDeviceFailureException -> {
                LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                result(ModbusOperationStatus.DEVICE_FAILURE)
            }

            is ModbusProtocolException -> {
                when (e.getException()) {
                    ModbusExceptionCode.SLAVE_DEVICE_BUSY -> {
                        LogUtils.w("$actionName 失败：下位机忙。${e.message}")
                        result(ModbusOperationStatus.BUSY)
                    }

                    ModbusExceptionCode.SLAVE_DEVICE_FAILURE,
                    ModbusExceptionCode.GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND -> {
                        LogUtils.e("$actionName 失败：下位机故障。${e.message}")
                        result(ModbusOperationStatus.DEVICE_FAILURE)
                    }

                    ModbusExceptionCode.ILLEGAL_DATA_ADDRESS,
                    ModbusExceptionCode.ILLEGAL_DATA_VALUE,
                    ModbusExceptionCode.ILLEGAL_FUNCTION -> {
                        LogUtils.e("$actionName 失败：地址、功能码或数值非法。${e.message}")
                        result(ModbusOperationStatus.PARAM_ERROR)
                    }

                    else -> {
                        LogUtils.e("$actionName 失败：Modbus 协议异常。${e.message}")
                        result(ModbusOperationStatus.PROTOCOL_ERROR)
                    }
                }
            }

            else -> {
                LogUtils.e("$actionName 失败：未知异常。${e.message}")
                result(ModbusOperationStatus.UNKNOWN_ERROR)
            }
        }
    }

    private fun classifyExceptionStatus(e: Exception): ModbusOperationStatus {
        return when (e) {
            is SlaveDeviceBusyException -> ModbusOperationStatus.BUSY
            is ModbusIOException -> ModbusOperationStatus.DISCONNECTED
            is IllegalDataAddressException,
            is IllegalDataValueException,
            is ModbusNumberException -> ModbusOperationStatus.PARAM_ERROR
            is SlaveDeviceFailureException -> ModbusOperationStatus.DEVICE_FAILURE
            is ModbusProtocolException -> when (e.getException()) {
                ModbusExceptionCode.SLAVE_DEVICE_BUSY -> ModbusOperationStatus.BUSY
                ModbusExceptionCode.SLAVE_DEVICE_FAILURE,
                ModbusExceptionCode.GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND -> ModbusOperationStatus.DEVICE_FAILURE
                ModbusExceptionCode.ILLEGAL_DATA_ADDRESS,
                ModbusExceptionCode.ILLEGAL_DATA_VALUE,
                ModbusExceptionCode.ILLEGAL_FUNCTION -> ModbusOperationStatus.PARAM_ERROR
                else -> ModbusOperationStatus.PROTOCOL_ERROR
            }
            else -> ModbusOperationStatus.UNKNOWN_ERROR
        }
    }

    fun currentDiagnosisSnapshot(): ModbusDiagnosisSnapshot {
        val config = AppConfig.getAppConfig()
        return ModbusDiagnosisSnapshot(
            connected = modbusMaster != null,
            port = config.modbusAddress,
            baudRate = config.modbusBaudRate
        )
    }

    fun formatOperationDiagnosis(
        operationText: String,
        serverAddress: Int,
        registerAddress: Int,
        writeValue: Int? = null,
        result: ModbusOperationResult,
        snapshot: ModbusDiagnosisSnapshot = currentDiagnosisSnapshot()
    ): String {
        return formatOperationDiagnosis(
            operationText = operationText,
            serverAddress = serverAddress,
            registerAddress = registerAddress,
            writeValue = writeValue,
            result = result,
            connected = snapshot.connected,
            port = snapshot.port,
            baudRate = snapshot.baudRate
        )
    }

    fun formatOperationDiagnosis(
        operationText: String,
        serverAddress: Int,
        registerAddress: Int,
        writeValue: Int?,
        result: ModbusOperationResult,
        connected: Boolean,
        port: String,
        baudRate: Int
    ): String {
        val parts = mutableListOf(
            "操作=$operationText",
            "寄存器地址=$registerAddress"
        )
        if (writeValue != null) {
            parts += "写入值=$writeValue"
        }
        parts += "从站地址=$serverAddress"
        parts += "连接状态=${if (connected) "已连接" else "未连接"}"
        parts += "串口地址=${port.ifBlank { "未知" }}"
        parts += "波特率=$baudRate"
        parts += "异常类型=${result.exceptionClassName ?: "无"}"
        parts += "异常信息=${firstNonBlank(result.exceptionMessage, result.message, "无")}"
        return parts.joinToString("，")
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() } ?: ""
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
        return readHoldingRegisterWithDiagnosis(serverAddress, startAddress).value
    }

    fun readHoldingRegisterWithDiagnosis(serverAddress: Int, startAddress: Int): ModbusRegisterReadResult {
        if (isDebug) {
            return ModbusRegisterReadResult(
                value = 0,
                result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)
            )
        }
        val currentMaster = modbusMaster ?: return ModbusRegisterReadResult(
            value = -1,
            result = ModbusOperationResult(
                ModbusOperationStatus.DISCONNECTED,
                "RTU 主站未初始化，跳过寄存器读取 [$startAddress]"
            )
        )
        return try {
            val value = currentMaster.readHoldingRegisters(serverAddress, startAddress, 1)?.get(0) ?: -1
            if (value >= 0) {
                ModbusRegisterReadResult(
                    value = value,
                    result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)
                )
            } else {
                ModbusRegisterReadResult(
                    value = value,
                    result = ModbusOperationResult(
                        ModbusOperationStatus.DISCONNECTED,
                        "读取寄存器 $startAddress 返回 $value"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "读取寄存器失败：${e.message}")
            ModbusRegisterReadResult(
                value = -1,
                result = ModbusOperationResult(
                    status = classifyExceptionStatus(e),
                    message = e.message,
                    exceptionClassName = e.javaClass.simpleName,
                    exceptionMessage = e.message
                )
            )
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

    fun readHoldingRegistersWithDiagnosis(
        serverAddress: Int,
        startAddress: Int,
        quantity: Int
    ): ModbusBatchReadResult {
        if (isDebug) {
            return ModbusBatchReadResult(
                values = IntArray(quantity),
                result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)
            )
        }
        val currentMaster = modbusMaster ?: return ModbusBatchReadResult(
            values = null,
            result = ModbusOperationResult(
                ModbusOperationStatus.DISCONNECTED,
                "RTU 主站未初始化，跳过批量寄存器读取 [$startAddress,$quantity]"
            )
        )
        return try {
            val values = currentMaster.readHoldingRegisters(serverAddress, startAddress, quantity)
            if (values != null) {
                ModbusBatchReadResult(
                    values = values,
                    result = ModbusOperationResult(ModbusOperationStatus.SUCCESS)
                )
            } else {
                ModbusBatchReadResult(
                    values = null,
                    result = ModbusOperationResult(
                        ModbusOperationStatus.DISCONNECTED,
                        "批量读取寄存器 $startAddress 数量 $quantity 返回空"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "批量读取寄存器失败：${e.message}")
            ModbusBatchReadResult(
                values = null,
                result = ModbusOperationResult(
                    status = classifyExceptionStatus(e),
                    message = e.message,
                    exceptionClassName = e.javaClass.simpleName,
                    exceptionMessage = e.message
                )
            )
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
