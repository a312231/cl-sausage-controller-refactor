package cn.niuyannet.kaochang.android.fragments

import android.R as AndroidR
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentConfigBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.ui.AdminOperationLogger
import cn.niuyannet.kaochang.android.ui.registerAdminTextInputs
import cn.niuyannet.kaochang.android.ui.resetAdminIdleTimer
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.kongqw.serialportlibrary.Device
import com.kongqw.serialportlibrary.SerialPortFinder
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigFragment : Fragment() {
    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var configBean: AppConfigBean

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        registerAdminTextInputs(view)
        initViews()
        statusInit()

        binding.btnSaveApiBaseUrl.setOnClickListener {
            saveApiBaseUrl()
        }

        binding.btnSaveMqttConfig.setOnClickListener {
            val uiScope = viewLifecycleOwner.lifecycleScope
            val oldHost = configBean.mqttHost
            val oldPort = configBean.mqttPort
            val oldUsername = configBean.mqttUsername
            val oldPassword = configBean.mqttPassword
            configBean.mqttHost = binding.etMqttIp.text.toString()
            configBean.mqttPort = binding.etMqttPort.text.toString().toInt()
            configBean.mqttUsername = binding.etMqttUsername.text.toString()
            configBean.mqttPassword = binding.etMqttPassword.text.toString()
            val mqttChangeDetail = AdminOperationLogger.mqttConfigDetail(
                oldHost = oldHost,
                newHost = configBean.mqttHost,
                oldPort = oldPort,
                newPort = configBean.mqttPort,
                oldUsername = oldUsername,
                newUsername = configBean.mqttUsername,
                oldPassword = oldPassword,
                newPassword = configBean.mqttPassword
            )
            AdminOperationLogger.i(
                action = "保存MQTT配置",
                page = AdminOperationLogger.PAGE_CONFIG,
                result = AdminOperationLogger.RESULT_START,
                detail = mqttChangeDetail
            )
            AppConfig.saveAppConfig(configBean)

            ToastUtils.showShort("MQTT 配置已保存，正在连接")
            uiScope.launch(Dispatchers.IO) {
                VMMqttHelper.connect { connected ->
                    uiScope.launch mqttUi@{
                        if (_binding == null) {
                            return@mqttUi
                        }
                        updateMqttStatus(connected)
                        if (connected) {
                            ToastUtils.showShort("MQTT 连接成功")
                        } else {
                            ToastUtils.showShort("MQTT 连接失败，请检查网络或配置")
                        }
                    }
                    if (connected) {
                        AdminOperationLogger.i(
                            action = "保存MQTT配置",
                            page = AdminOperationLogger.PAGE_CONFIG,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = "连接结果=成功"
                        )
                        LogUtils.d("【配置页】MQTT 连接成功")
                    } else {
                        AdminOperationLogger.w(
                            action = "保存MQTT配置",
                            page = AdminOperationLogger.PAGE_CONFIG,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "失败原因=MQTT连接失败"
                        )
                        LogUtils.w("【配置页】MQTT 连接失败")
                    }
                }
            }
        }

        binding.btnSaveModbusConfig.setOnClickListener {
            val uiScope = viewLifecycleOwner.lifecycleScope
            val oldAddress = configBean.modbusAddress
            val oldBaudRate = configBean.modbusBaudRate
            configBean.modbusBaudRate = binding.etModbusBaudRate.text.toString().toInt()
            AdminOperationLogger.i(
                action = "保存Modbus配置",
                page = AdminOperationLogger.PAGE_CONFIG,
                result = AdminOperationLogger.RESULT_START,
                detail = "Modbus串口地址=${oldAddress}->${configBean.modbusAddress}，波特率=${oldBaudRate}->${configBean.modbusBaudRate}"
            )
            AppConfig.saveAppConfig(configBean)

            ToastUtils.showShort("MODBUS 配置已保存，正在连接")
            uiScope.launch(Dispatchers.IO) {
                VMModbusHelper.connectModbus { connected ->
                    uiScope.launch modbusUi@{
                        if (_binding == null) {
                            return@modbusUi
                        }
                        updateModbusStatus(connected)
                        if (connected) {
                            ToastUtils.showShort("MODBUS 连接成功")
                        } else {
                            ToastUtils.showShort("MODBUS 未连通，请检查下位机、模拟器或接线")
                        }
                    }
                    if (connected) {
                        AdminOperationLogger.i(
                            action = "保存Modbus配置",
                            page = AdminOperationLogger.PAGE_CONFIG,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = "Modbus串口地址=${configBean.modbusAddress}，波特率=${configBean.modbusBaudRate}，连接结果=成功"
                        )
                        LogUtils.d("【配置页】MODBUS 连接成功")
                    } else {
                        AdminOperationLogger.w(
                            action = "保存Modbus配置",
                            page = AdminOperationLogger.PAGE_CONFIG,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "Modbus串口地址=${configBean.modbusAddress}，波特率=${configBean.modbusBaudRate}，失败原因=未收到有效从站响应"
                        )
                        LogUtils.w("【配置页】MODBUS 连接失败：未收到有效从站响应")
                    }
                }
            }
        }

        binding.btndisModbusConfig.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    VMModbusHelper.disconnectModbusMaster()
                }
                if (_binding == null) {
                    return@launch
                }
                updateModbusStatus(false)
                ToastUtils.showShort("MODBUS 已断开")
                AdminOperationLogger.i(
                    action = "手动断开Modbus",
                    page = AdminOperationLogger.PAGE_CONFIG,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "Modbus串口地址=${configBean.modbusAddress}"
                )
                LogUtils.d("【配置页】MODBUS 已手动断开")
            }
        }

        binding.btnSaveStockConfig.setOnClickListener {
            saveStockConfig()
        }
    }

    private fun saveStockConfig() {
        val input = binding.etBoxRefillTargetNum.text?.toString()?.trim()
        val boxRefillTargetNum = input?.toIntOrNull()
        if (boxRefillTargetNum == null || !AppConfig.isValidBoxRefillTargetNum(boxRefillTargetNum)) {
            ToastUtils.showShort(
                "烤肠箱加满数量必须是 ${AppConfig.MIN_BOX_REFILL_TARGET_NUM}-${AppConfig.MAX_BOX_REFILL_TARGET_NUM}"
            )
            AdminOperationLogger.w(
                action = "保存烤肠箱加满目标库存",
                page = AdminOperationLogger.PAGE_CONFIG,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "输入值=$input，允许范围=${AppConfig.MIN_BOX_REFILL_TARGET_NUM}..${AppConfig.MAX_BOX_REFILL_TARGET_NUM}"
            )
            LogUtils.w(
                "【配置页】保存 boxRefillTargetNum（烤肠箱加满目标库存）失败：" +
                    "input=$input，允许范围=${AppConfig.MIN_BOX_REFILL_TARGET_NUM}..${AppConfig.MAX_BOX_REFILL_TARGET_NUM}"
            )
            return
        }

        configBean.boxRefillTargetNum = boxRefillTargetNum
        AppConfig.saveAppConfig(configBean)
        binding.etBoxRefillTargetNum.setText(boxRefillTargetNum.toString())
        ToastUtils.showShort("烤肠箱加满数量已保存")
        AdminOperationLogger.i(
            action = "保存烤肠箱加满目标库存",
            page = AdminOperationLogger.PAGE_CONFIG,
            result = AdminOperationLogger.RESULT_DONE,
            detail = "加满目标库存=$boxRefillTargetNum"
        )
        LogUtils.i("【配置页】已保存 boxRefillTargetNum（烤肠箱加满目标库存）=$boxRefillTargetNum")
    }

    private fun saveApiBaseUrl() {
        val input = binding.etApiBaseUrl.text?.toString()?.trim()
        val normalizedApiBaseUrl = normalizeValidApiBaseUrl(input)
        if (normalizedApiBaseUrl == null) {
            ToastUtils.showShort("云端HTTP地址必须是包含主机的 http/https 地址")
            AdminOperationLogger.w(
                action = "保存云端HTTP地址",
                page = AdminOperationLogger.PAGE_CONFIG,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "输入值=${input ?: ""}"
            )
            LogUtils.w("【配置页】保存云端HTTP地址失败：input=${input ?: ""}")
            return
        }

        val oldApiBaseUrl = configBean.apiBaseUrl
        configBean.apiBaseUrl = normalizedApiBaseUrl
        AppConfig.saveAppConfig(configBean)
        NetApi.refreshEndpointUrls()
        binding.etApiBaseUrl.setText(normalizedApiBaseUrl)
        ToastUtils.showShort("云端HTTP地址已保存")
        AdminOperationLogger.i(
            action = "保存云端HTTP地址",
            page = AdminOperationLogger.PAGE_CONFIG,
            result = AdminOperationLogger.RESULT_DONE,
            detail = "云端HTTP地址=$oldApiBaseUrl->$normalizedApiBaseUrl"
        )
        LogUtils.i("【配置页】已保存云端HTTP地址：$oldApiBaseUrl -> $normalizedApiBaseUrl")
    }

    private fun normalizeValidApiBaseUrl(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        val normalized = AppConfig.normalizeApiBaseUrl(value)
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        return if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            normalized
        } else {
            null
        }
    }

    private fun updateModbusStatus(connected: Boolean) {
        binding.tvModbusStatus.text = if (connected) "已连接" else "未连接"
    }

    private fun updateMqttStatus(connected: Boolean) {
        binding.tvMqttStatus.text = if (connected) "已连接" else "未连接"
    }

    /**
     * 初始化界面参数。
     */
    private fun initViews() {
        configBean = AppConfig.getAppConfig()
        binding.etApiBaseUrl.setText(configBean.apiBaseUrl)
        binding.etMqttIp.setText(configBean.mqttHost)
        binding.etMqttPort.setText(configBean.mqttPort.toString())
        binding.etMqttUsername.setText(configBean.mqttUsername)
        binding.etMqttPassword.setText(configBean.mqttPassword)
        binding.etModbusBaudRate.setText(configBean.modbusBaudRate.toString())
        binding.etBoxRefillTargetNum.setText(AppConfig.getBoxRefillTargetNum().toString())
        initSpinner()
    }

    private fun statusInit() {
        viewLifecycleOwner.lifecycleScope.launch {
            val modbusStatus = withContext(Dispatchers.IO) {
                VMModbusHelper.connectStatus()
            }
            if (_binding == null) {
                return@launch
            }
            updateModbusStatus(modbusStatus)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val mqttStatus = withContext(Dispatchers.IO) {
                VMMqttHelper.connectStatus()
            }
            if (_binding == null) {
                return@launch
            }
            updateMqttStatus(mqttStatus)
        }
    }

    private fun initSpinner() {
        val serialPortFinder = SerialPortFinder()
        val devices: ArrayList<Device> = serialPortFinder.devices
        val items = devices.map { it.file.absolutePath }
        val adapter = ArrayAdapter(requireActivity(), AndroidR.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(AndroidR.layout.simple_spinner_dropdown_item)
        binding.spinner.adapter = adapter

        val defaultPosition = items.indexOf(configBean.modbusAddress)
        if (defaultPosition >= 0) {
            binding.spinner.setSelection(defaultPosition)
            devices.first { it.file.absolutePath == configBean.modbusAddress }.apply {
                configBean.modbusAddress = file.absolutePath
            }
        }

        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                resetAdminIdleTimer("配置页选择串口")
                val selectedItem = parent.getItemAtPosition(position).toString()
                devices.first { it.file.absolutePath == selectedItem }.apply {
                    configBean.modbusAddress = file.absolutePath
                }
                LogUtils.d("【配置页】已选择串口：$selectedItem")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 未选择串口时无需额外处理。
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
