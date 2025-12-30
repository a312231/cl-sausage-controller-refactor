package cn.niuyannet.kaochang.android.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentTestBinding
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestFragment : Fragment() {
    private lateinit var binding: FragmentTestBinding
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 设置动作控制区域的按钮点击事件
        setupActionControlListeners()
        // 设置温度控制区域的按钮点击事件
        setupTemperatureControlListeners()
        // 设置系统信息区域的按钮点击事件
        setupSystemInfoListeners()
    }
    
    private fun setupActionControlListeners() {
        // 执行动作组
        binding.btnReadActionGroup.setOnClickListener {
            readRegister(200, 1,"执行动作组状态读取") { value ->
                binding.etActionGroup.setText(value[0].toString())
            }
        }
        binding.btnWriteActionGroup.setOnClickListener {
            val value = binding.etActionGroup.text.toString()
            if (value.isNotEmpty()) {
                writeRegister(200, value.toInt(), "执行动作组写入")
            } else {
                showToast("请输入动作组值")
            }
        }
        
        // 动作组执行结果
        binding.btnReadActionGroupResult.setOnClickListener {
            readRegister(201, 1,"动作组执行结果") { value ->

                val resultText = when (value[0]) {
                    0 -> "正常"
                    1 -> "运动中"
                    else -> "错误码: ${value[0]}"
                }
                binding.tvActionGroupResult.text = resultText
            }
        }
        
        // 烤盘搬移
        binding.btnReadMovePlate.setOnClickListener {
            readRegister(202, 1,"烤盘搬移") { value ->
                //（无符号8位整型，起始位置）	（无符号8位整型，结束位置）
                val intValue = value[0]
                // 取前8位为起始位置
                val startPosition = (intValue shr 8) and 0xFF
                // 取后8位为结束位置
                val endPosition = intValue and 0xFF
                
                binding.etStartPosition.setText(startPosition.toString())
                binding.etEndPosition.setText(endPosition.toString())
            }
        }
        
        binding.btnWriteMovePlate.setOnClickListener {
            val startPos = binding.etStartPosition.text.toString()
            val endPos = binding.etEndPosition.text.toString()
            if (startPos.isNotEmpty() && endPos.isNotEmpty()) {
                val value = (startPos.toInt() shl 8) or endPos.toInt()
                writeRegister(202, value, "烤盘搬移")
            } else {
                showToast("请输入起始位置和结束位置")
            }
        }
        
        // 烤盘搬移执行结果
        binding.btnReadMovePlateResult.setOnClickListener {
            readRegister(203, 1,"烤盘搬移执行结果") { value ->
                val resultText = when (value[0]) {
                    0 -> "正常"
                    1 -> "运动中"
                    else -> "错误码: ${value[0]}"
                }
                binding.tvMovePlateResult.text = resultText
            }
        }
        
        // 开关量控制
        binding.btnReadSwitchControl.setOnClickListener {
            readRegister(204, 1,"开关量控制") { value ->
                val intValue = value[0]
                //val binaryString = Integer.toBinaryString(intValue).padStart(16, '0')
                binding.etSwitchControl.setText(intValue.toString())
                //设置开关状态
                binding.cbHeater0.isChecked = (intValue and 0x01) != 0
                binding.cbHeater1.isChecked = (intValue and 0x02) != 0
                binding.cbHeater2.isChecked = (intValue and 0x04) != 0
                binding.cbFridge.isChecked = (intValue and 0x08) != 0
                binding.cbLight.isChecked = (intValue and 0x10) != 0
            }
        }
        //读取开关的状态
        binding.btnReadSwitchControlcheck.setOnClickListener {
            readRegister(204, 1,"开关量控制") { value ->
                val intValue = value[0]
                //val binaryString = Integer.toBinaryString(intValue).padStart(16, '0')
                binding.etSwitchControl.setText(intValue.toString())
                //设置开关状态
                binding.cbHeater0.isChecked = (intValue and 0x01) != 0
                binding.cbHeater1.isChecked = (intValue and 0x02) != 0
                binding.cbHeater2.isChecked = (intValue and 0x04) != 0
                binding.cbFridge.isChecked = (intValue and 0x08) != 0
                binding.cbLight.isChecked = (intValue and 0x10) != 0
            }
        }
        binding.btnWriteSwitchControlcheck.setOnClickListener {
            //根据开关状态写入
            var intValue = 0
            if (binding.cbHeater0.isChecked) {
                intValue = intValue or 0x01
            }
            if (binding.cbHeater1.isChecked) {
                intValue = intValue or 0x02
            }
            if (binding.cbHeater2.isChecked) {
                intValue = intValue or 0x04
            }
            if (binding.cbFridge.isChecked) {
                intValue = intValue or 0x08
            }
            if (binding.cbLight.isChecked) {
                intValue = intValue or 0x10
            }
            writeRegister(204, intValue, "开关量控制")
        }

        // 写入开关量控制
        binding.btnWriteSwitchControl.setOnClickListener {
            val value = binding.etSwitchControl.text.toString()
            if (value.isNotEmpty()) {
                writeRegister(204, value.toInt(), "开关量控制")
            } else {
                showToast("请输入开关量控制值")
            }
        }
        
        // 行程开关状态
        binding.btnReadLimitSwitchStatus.setOnClickListener {
            readRegister(205, 1,"行程开关状态") { value ->
                val intValue = value[0]
                val binaryString = Integer.toBinaryString(intValue).padStart(16, '0')
                binding.tvLimitSwitchStatus.text = "二进制: $binaryString (十进制: $intValue)"
            }
        }
    }
    
    private fun setupTemperatureControlListeners() {
        // 读取烤盘温度
        binding.btnReadTemps.setOnClickListener {
            readRegister(500,3, "烤盘温度") { value ->
                binding.tvPlateTemp0.text = "温度0: ${value[0]}°C"
                binding.tvPlateTemp1.text = "温度1: ${value[1]}°C"
                binding.tvPlateTemp2.text = "温度2: ${value[2]}°C"
            }
        }
        
        // 设置烤盘温度
        binding.btnWriteTemps.setOnClickListener {
            val temp0 = binding.etTempSet0.text.toString()
            val temp1 = binding.etTempSet1.text.toString()
            val temp2 = binding.etTempSet2.text.toString()
            if (temp0.isNotEmpty() && temp1.isNotEmpty() && temp2.isNotEmpty()) {
                writeRegister(503, temp0.toInt(), "烤盘温度设置0")
                writeRegister(504, temp1.toInt(), "烤盘温度设置1")
                writeRegister(505, temp2.toInt(), "烤盘温度设置2")
            } else {
                showToast("请输入所有温度值")
            }
        }
        binding.btnReadTemps2.setOnClickListener {
            readRegister(503,3, "烤盘温度") { value ->
                binding.etTempSet0.setText("${value[0]}")
                binding.etTempSet1.setText("${value[1]}")
                binding.etTempSet2.setText("${value[2]}")
            }
        }
    }
    
    private fun setupSystemInfoListeners() {
        // 读取硬件版本
        binding.btnReadHardwareVersion.setOnClickListener {
            readRegister(900,2, "硬件版本") { value ->
                // 将10进制转16进制，再转ASCII字符串
                val hex0 = value[0].toString(16).padStart(4, '0')
                val hex1 = value[1].toString(16).padStart(4, '0')
                val ascii0 = hex0.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                val ascii1 = hex1.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                binding.tvHardwareVersion.text = ascii0 + ascii1
            }
        }
        // 读取固件版本
        binding.btnReadFirmwareVersion.setOnClickListener {
            readRegister(902,2, "固件版本") { value ->
                // 将10进制转16进制，再转ASCII字符串
                val hex0 = value[0].toString(16).padStart(4, '0')
                val hex1 = value[1].toString(16).padStart(4, '0')
                val ascii0 = hex0.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                val ascii1 = hex1.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                binding.tvFirmwareVersion.text = ascii0 + ascii1
            }
        }

    }

    /**
     * 读取寄存器
     */
    private fun readRegister(register: Int,quantity:Int, description: String, callback: (IntArray) -> Unit) {
        lifecycleScope.launch {
            showToast("正在读取${description}...")
            try {
                withContext(Dispatchers.IO) {
                    var values=KaoChangOperate.readHoldingRegisters(register,quantity)
                    values?.let {
                        GlobalScope.launch(Dispatchers.Main) {
                            callback.invoke(values)
                        }
                    }
                }
            } catch (e: Exception) {
                showToast("读取${description}失败: ${e.message}")
            }
        }
    }
    
    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            showToast("正在写入${description}...")
            try {
                withContext(Dispatchers.IO) {
                   KaoChangOperate.writeSingleRegister(register,value)
                }
            } catch (e: Exception) {
                showToast("写入${description}失败: ${e.message}")
            }
        }
    }


    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
} 