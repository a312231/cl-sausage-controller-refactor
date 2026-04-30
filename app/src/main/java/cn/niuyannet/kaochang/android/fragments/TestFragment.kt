package cn.niuyannet.kaochang.android.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentTestBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetryContext
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionSpec
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActions
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TestAtomicActionItem(
    val label: String,
    val spec: AtomicActionSpec,
    val defaultInfoValue: Int
) {
    override fun toString(): String = label
}

class TestFragment : Fragment() {
    private var _binding: FragmentTestBinding? = null
    private val binding: FragmentTestBinding
        get() = _binding!!
    private val atomicActionItems = listOf(
        TestAtomicActionItem("in1：移动到肠箱预推送位置", AtomicActions.in1, 1),
        TestAtomicActionItem("in2：取肠、分料、避让", AtomicActions.in2, 1),
        TestAtomicActionItem("in3：回到抛肠点", AtomicActions.in3, 0),
        TestAtomicActionItem("in4：升降台下降", AtomicActions.in4, 0),
        TestAtomicActionItem("in5：抛肠并检测是否抛出成功", AtomicActions.in5, 0),
        TestAtomicActionItem("in6：升降台上升", AtomicActions.in6, 0),
        TestAtomicActionItem("in7：上平台从升降台夹肠并放入烤盘", AtomicActions.in7, 1),
        TestAtomicActionItem("out1：从烤盘取熟肠到穿签位", AtomicActions.out1, 1),
        TestAtomicActionItem("out2：执行穿签", AtomicActions.out2, 0),
        TestAtomicActionItem("out3：从穿签位放到预推送位置", AtomicActions.out3, 0),
        TestAtomicActionItem("out4：开窗并推送", AtomicActions.out4, 0),
        TestAtomicActionItem("out5：光电检查顾客是否取走", AtomicActions.out5, 0)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActionControlListeners()
        setupSelfCleanListeners()
        setupTemperatureControlListeners()
        setupSystemInfoListeners()
    }

    private fun setupActionControlListeners() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, atomicActionItems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAtomicAction.adapter = adapter
        binding.spinnerAtomicAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshAtomicActionInfo(atomicActionItems[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshAtomicActionInfo(atomicActionItems.first())

        binding.btnRunAtomicAction.setOnClickListener {
            val item = selectedAtomicActionItem() ?: return@setOnClickListener
            val infoValue = binding.etAtomicInfoValue.text?.toString()?.trim()?.toIntOrNull()
            if (infoValue == null) {
                showToast("请输入信息寄存器参数值")
                return@setOnClickListener
            }
            executeAtomicActionFromTestPage(item, infoValue)
        }

        binding.btnReadAtomicRegisters.setOnClickListener {
            val item = selectedAtomicActionItem() ?: return@setOnClickListener
            readAtomicActionRegisters(item)
        }

        binding.btnReadSwitchControl.setOnClickListener {
            readSwitchControl()
        }
        binding.btnReadSwitchControlcheck.setOnClickListener {
            readSwitchControl()
        }
        binding.btnWriteSwitchControlcheck.setOnClickListener {
            var value = 0
            if (binding.cbHeater0.isChecked) value = value or 0x01
            if (binding.cbHeater1.isChecked) value = value or 0x02
            if (binding.cbHeater2.isChecked) value = value or 0x04
            if (binding.cbFridge.isChecked) value = value or 0x08
            if (binding.cbLight.isChecked) value = value or 0x10
            writeRegister(204, value, "开关量控制寄存器(204)")
        }
        binding.btnWriteSwitchControl.setOnClickListener {
            val value = binding.etSwitchControl.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                showToast("请输入开关量控制值")
            } else {
                writeRegister(204, value.toInt(), "开关量控制寄存器(204)")
            }
        }

        binding.btnReadLimitSwitchStatus.setOnClickListener {
            readRegister(205, 1, "行程开关状态寄存器(205)") { value ->
                val intValue = value[0]
                val binaryString = Integer.toBinaryString(intValue).padStart(16, '0')
                binding.tvLimitSwitchStatus.text = "二进制: $binaryString (十进制: $intValue)"
            }
        }
    }

    private fun selectedAtomicActionItem(): TestAtomicActionItem? {
        return binding.spinnerAtomicAction.selectedItem as? TestAtomicActionItem
    }

    private fun refreshAtomicActionInfo(item: TestAtomicActionItem) {
        binding.tvAtomicActionDescription.text = item.spec.description
        binding.tvAtomicActionRegisters.text =
            "命令寄存器=${item.spec.commandRegister}，结果寄存器=${item.spec.resultRegister}，信息寄存器=${item.spec.infoRegister}"
        binding.etAtomicInfoValue.setText(item.defaultInfoValue.toString())
        binding.tvAtomicActionResult.text = "未执行"
    }

    private fun executeAtomicActionFromTestPage(item: TestAtomicActionItem, infoValue: Int) {
        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                showToast("当前正在自清洁，测试下位机动作已禁用")
                return@launch
            }
            val sessionId = "test_${item.spec.name}_${System.currentTimeMillis()}"
            binding.tvAtomicActionResult.text = "执行中：sessionId=$sessionId"
            val result = withContext(Dispatchers.IO) {
                AtomicActionExecutor(
                    gateway = VmAtomicModbusGateway(KaoChangOperate.modbus_address),
                    logger = { message -> LogUtils.d("[300/400动作组] [测试下位机] $message") },
                    telemetryContext = ActionGroupTelemetryContext(
                        sessionId = sessionId,
                        resourceLocks = "TEST_PAGE"
                    )
                ).execute(item.spec, infoValue)
            }
            binding.tvAtomicActionResult.text = describeAtomicActionResult(result)
        }
    }

    private fun readAtomicActionRegisters(item: TestAtomicActionItem) {
        lifecycleScope.launch {
            binding.tvAtomicActionResult.text = "正在读取 ${item.spec.name} 寄存器..."
            val text = withContext(Dispatchers.IO) {
                val commandValue = KaoChangOperate.readHoldingRegisters(item.spec.commandRegister, 1)?.firstOrNull()
                val resultValue = KaoChangOperate.readHoldingRegisters(item.spec.resultRegister, 1)?.firstOrNull()
                val infoValue = KaoChangOperate.readHoldingRegisters(item.spec.infoRegister, 1)?.firstOrNull()
                buildString {
                    append("action=${item.spec.name}\n")
                    append("commandRegister（命令寄存器）=${item.spec.commandRegister}，value=${formatRegisterValue(commandValue)}\n")
                    append("resultRegister（结果寄存器）=${item.spec.resultRegister}，value=${formatRegisterValue(resultValue)}\n")
                    append("infoRegister（信息寄存器）=${item.spec.infoRegister}，value=${formatRegisterValue(infoValue)}")
                }
            }
            binding.tvAtomicActionResult.text = text
        }
    }

    private fun formatRegisterValue(value: Int?): String {
        return value?.let { "$it（${AtomicActionExecutor.formatCode(it)}）" } ?: "读取失败"
    }

    private fun describeAtomicActionResult(result: AtomicActionResult): String {
        return when (result) {
            AtomicActionResult.Success -> "SUCCESS（动作成功）"
            is AtomicActionResult.Failed -> "FAILED（${AtomicActionExecutor.formatCode(result.errorCode)}：${result.message}）"
            is AtomicActionResult.ModbusError -> "MODBUS_ERROR（${result.message}）"
            is AtomicActionResult.Timeout -> "TIMEOUT（${result.message}）"
        }
    }

    private fun setupSelfCleanListeners() {
        binding.btnRunPanSelfClean.setOnClickListener {
            val text = binding.etSelfCleanPosition.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                showToast("请输入 1~33 号烤盘位")
                return@setOnClickListener
            }
            val positionSn = text.toIntOrNull()
            if (positionSn == null || positionSn !in 1..33) {
                showToast("烤盘位必须在 1~33 之间")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                binding.tvSelfCleanResult.text = "正在执行烤盘 ${positionSn} 自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanPanPosition(
                        positionSn = positionSn,
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
            }
        }

        binding.btnRunSellPlatformSelfClean.setOnClickListener {
            lifecycleScope.launch {
                binding.tvSelfCleanResult.text = "正在执行出肠台自清洁..."
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanSellPlatform(
                        source = "测试下位机页面手动触发",
                        busyDuringClean = true
                    )
                }
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
            }
        }
    }

    private fun buildSelfCleanReportText(report: KaoChangOperate.SelfCleanExecutionReport): String {
        val status201Text = when (report.status201) {
            0 -> "0（空闲/完成）"
            1 -> "1（执行中超时）"
            2 -> "2（设备忙）"
            else -> "${report.status201}（通信失败/未知）"
        }
        return "目标=${report.targetLabel}\n" +
            "command（下位机自清洁指令）=${report.command}\n" +
            "status201（动作状态寄存器）=$status201Text\n" +
            "success=${report.success}\n" +
            "message=${report.message}"
    }

    private fun setupTemperatureControlListeners() {
        binding.btnReadTemps.setOnClickListener {
            readRegister(500, 3, "烤盘温度寄存器(500~502)") { value ->
                binding.tvPlateTemp0.text = "温度0: ${value[0]}°C"
                binding.tvPlateTemp1.text = "温度1: ${value[1]}°C"
                binding.tvPlateTemp2.text = "温度2: ${value[2]}°C"
            }
        }

        binding.btnWriteTemps.setOnClickListener {
            val temp0 = binding.etTempSet0.text?.toString()?.trim().orEmpty()
            val temp1 = binding.etTempSet1.text?.toString()?.trim().orEmpty()
            val temp2 = binding.etTempSet2.text?.toString()?.trim().orEmpty()
            if (temp0.isEmpty() || temp1.isEmpty() || temp2.isEmpty()) {
                showToast("请输入全部温度值")
                return@setOnClickListener
            }
            writeRegister(503, temp0.toInt(), "温度写寄存器(503)")
            writeRegister(504, temp1.toInt(), "温度写寄存器(504)")
            writeRegister(505, temp2.toInt(), "温度写寄存器(505)")
        }

        binding.btnReadTemps2.setOnClickListener {
            readRegister(503, 3, "温度写寄存器(503~505)") { value ->
                binding.etTempSet0.setText(value[0].toString())
                binding.etTempSet1.setText(value[1].toString())
                binding.etTempSet2.setText(value[2].toString())
            }
        }
    }

    private fun setupSystemInfoListeners() {
        binding.btnReadHardwareVersion.setOnClickListener {
            readRegister(900, 2, "硬件版本寄存器(900~901)") { value ->
                binding.tvHardwareVersion.text = decodeAsciiWords(value)
            }
        }

        binding.btnReadFirmwareVersion.setOnClickListener {
            readRegister(902, 2, "固件版本寄存器(902~903)") { value ->
                binding.tvFirmwareVersion.text = decodeAsciiWords(value)
            }
        }
    }

    private fun decodeAsciiWords(value: IntArray): String {
        return value.joinToString("") { word ->
            word.toString(16)
                .padStart(4, '0')
                .chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
        }
    }

    private fun readSwitchControl() {
        readRegister(204, 1, "开关量控制寄存器(204)") { value ->
            val intValue = value[0]
            binding.etSwitchControl.setText(intValue.toString())
            binding.cbHeater0.isChecked = (intValue and 0x01) != 0
            binding.cbHeater1.isChecked = (intValue and 0x02) != 0
            binding.cbHeater2.isChecked = (intValue and 0x04) != 0
            binding.cbFridge.isChecked = (intValue and 0x08) != 0
            binding.cbLight.isChecked = (intValue and 0x10) != 0
        }
    }

    private fun readRegister(
        register: Int,
        quantity: Int,
        description: String,
        callback: (IntArray) -> Unit
    ) {
        lifecycleScope.launch {
            showToast("正在读取$description...")
            try {
                val values = withContext(Dispatchers.IO) {
                    KaoChangOperate.readHoldingRegisters(register, quantity)
                }
                if (values != null) {
                    callback.invoke(values)
                } else {
                    showToast("读取${description}失败：返回为空")
                }
            } catch (e: Exception) {
                showToast("读取${description}失败: ${e.message}")
            }
        }
    }

    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                showToast("当前正在自清洁，测试下位机写操作已禁用")
                return@launch
            }
            showToast("正在写入$description...")
            try {
                withContext(Dispatchers.IO) {
                    KaoChangOperate.writeSingleRegister(register, value)
                }
            } catch (e: Exception) {
                showToast("写入${description}失败: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
