package cn.niuyannet.kaochang.android.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.databinding.FragmentConfigBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.kongqw.serialportlibrary.Device
import com.kongqw.serialportlibrary.SerialPortFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {
    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    lateinit var configBean: AppConfigBean
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
        initViews()
        statusInit()
        binding.btnSaveMqttConfig.setOnClickListener {
            configBean.mqttHost=binding.etMqttIp.text.toString()
            configBean.mqttPort=binding.etMqttPort.text.toString().toInt()
            configBean. mqttUsername=binding.etMqttUsername.text.toString()
            configBean.mqttPassword=binding.etMqttPassword.text.toString()
            AppConfig.saveAppConfig(configBean)
            GlobalScope.launch(Dispatchers.IO) {
                VMMqttHelper.connect {
                    GlobalScope.launch(Dispatchers.Main) {
                        if (it) {
                            binding.tvMqttStatus.text = "已连接"
                        }else{
                            binding.tvMqttStatus.text = "未连接"
                        }
                    }
                    LogUtils.d("MQTT连接成功！")
                }
            }
            ToastUtils.showShort("保存成功")
        }
        binding.btnSaveModbusConfig.setOnClickListener {
            configBean.modbusBaudRate=binding.etModbusBaudRate.text.toString().toInt()
//            configBean.modbusDevices=binding.spinner.selectedItem.toString()
            AppConfig.saveAppConfig(configBean)
            GlobalScope.launch(Dispatchers.IO) {
                VMModbusHelper.connectModbus {
                    GlobalScope.launch(Dispatchers.Main) {
                        if (it) {
                            binding.tvModbusStatus.text = "已连接"
                        }else{
                            binding.tvModbusStatus.text = "未连接"
                        }
                    }
                    LogUtils.d("Modbus连接成功！")
                }
            }
            ToastUtils.showShort("保存成功")
        }
        binding.btndisModbusConfig.setOnClickListener {
            GlobalScope.launch(Dispatchers.IO) {
                VMModbusHelper.disConnectTcpMaster()
                GlobalScope.launch(Dispatchers.Main) {
                    ToastUtils.showShort("断开连接成功")
                }
            }
        }
    }

    /**
     * 初始化views参数
     */
    private fun initViews(){
        configBean=AppConfig.getAppConfig()
        binding.etMqttIp.setText(configBean.mqttHost)
         binding.etMqttPort.setText(configBean.mqttPort.toString())
        binding.etMqttUsername.setText(configBean.mqttUsername)
        binding.etMqttPassword.setText(configBean.mqttPassword)
        binding.etModbusBaudRate.setText(configBean.modbusBaudRate.toString())
        initSpinner()
    }
    fun statusInit(){
        GlobalScope.launch(Dispatchers.IO) {
            var status=VMModbusHelper.connectStatus()
            GlobalScope.launch(Dispatchers.Main) {
                if (status) {
                    binding.tvModbusStatus.text = "已连接"
                }else{
                    binding.tvModbusStatus.text = "未连接"
                }
            }
        }
        GlobalScope.launch(Dispatchers.IO) {
            var status=     VMMqttHelper.connectStatus()
            GlobalScope.launch(Dispatchers.Main) {
                if (status) {
                    binding.tvMqttStatus.text = "已连接"
                }else{
                    binding.tvMqttStatus.text = "未连接"
                }
            }
        }
    }
    private fun initSpinner(){
        val serialPortFinder = SerialPortFinder()
        val devices: ArrayList<Device> = serialPortFinder.devices
        val items = devices.map { it.file.absolutePath }
        val adapter = ArrayAdapter(requireActivity(), android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinner.adapter = adapter
        // 设置默认选中ttyCH343USB2
        val defaultPosition = items.indexOf(configBean.modbusAddress)
        if (defaultPosition >= 0) {
            binding.spinner.setSelection(defaultPosition)
            devices.first { it.file.absolutePath == configBean.modbusAddress }?.apply {
                configBean.modbusAddress= file.absolutePath.toString()
            }
        }
        binding.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View,
                position: Int,
                id: Long
            ) {
                val selectedItem = parent.getItemAtPosition(position).toString()
                devices.first { it.file.absolutePath==selectedItem }.apply {
                    configBean.modbusAddress=file.absolutePath.toString()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 当没有选中项时调用
            }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}