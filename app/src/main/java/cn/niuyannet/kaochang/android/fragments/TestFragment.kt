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
    private val temperatureTargetRegisters = listOf(
        TemperatureTargetInput(area = 1, register = 503, label = "一区1~9目标温度", textProvider = { binding.etTempSet0.text?.toString()?.trim().orEmpty() }),
        TemperatureTargetInput(area = 2, register = 504, label = "二区13~21目标温度", textProvider = { binding.etTempSet1.text?.toString()?.trim().orEmpty() }),
        TemperatureTargetInput(area = 3, register = 505, label = "三区25~33目标温度", textProvider = { binding.etTempSet2.text?.toString()?.trim().orEmpty() })
    )
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

    private data class TemperatureTargetInput(
        val area: Int,
        val register: Int,
        val label: String,
        val textProvider: () -> String
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
            LogUtils.i(
                "【下位机测试】300/400原子动作开始：" +
                    "action（动作名）=${item.spec.name}，" +
                    "commandRegister（命令寄存器）=${item.spec.commandRegister}，" +
                    "resultRegister（结果寄存器）=${item.spec.resultRegister}，" +
                    "infoRegister（信息寄存器）=${item.spec.infoRegister}，" +
                    "infoValue（信息参数）=$infoValue，sessionId=$sessionId"
            )
            val result = withContext(Dispatchers.IO) {
                AtomicActionExecutor(
                    gateway = VmAtomicModbusGateway(KaoChangOperate.modbus_address),
                    logger = { message -> logTestAtomicActionDetail(message) },
                    telemetryContext = ActionGroupTelemetryContext(
                        sessionId = sessionId,
                        resourceLocks = "TEST_PAGE"
                    )
                ).execute(item.spec, infoValue)
            }
            binding.tvAtomicActionResult.text = describeAtomicActionResult(result)
            LogUtils.i(
                "【下位机测试】300/400原子动作结束：" +
                    "action（动作名）=${item.spec.name}，" +
                    "infoRegister（信息寄存器）=${item.spec.infoRegister}，" +
                    "infoValue（信息参数）=$infoValue，result=$result，sessionId=$sessionId"
            )
        }
    }

    private fun logTestAtomicActionDetail(message: String) {
        LogUtils.d("[300/400动作组] [测试下位机] $message")
        val fileLogMessage = when {
            message.startsWith("写入信息寄存器成功") ->
                message.replaceFirst("写入信息寄存器成功", "信息寄存器写入完成")
            message.startsWith("写入信息寄存器失败") ->
                message.replaceFirst("写入信息寄存器失败", "信息寄存器写入失败")
            message.startsWith("写入命令寄存器成功") ->
                message.replaceFirst("写入命令寄存器成功", "命令寄存器写入完成")
            message.startsWith("写入命令寄存器失败") ->
                message.replaceFirst("写入命令寄存器失败", "命令寄存器写入失败")
            message.startsWith("动作启动确认成功") ->
                message.replaceFirst("动作启动确认成功", "动作已确认启动")
            message.startsWith("动作未确认启动") ->
                message.replaceFirst("动作未确认启动", "动作启动确认超时")
            message.startsWith("动作启动失败") ->
                message.replaceFirst("动作启动失败", "动作启动确认失败")
            message.startsWith("接管原子动作轮询") ->
                message.replaceFirst("接管原子动作轮询", "动作已在执行中，接管结果寄存器等待")
            message.startsWith("原子动作完成") ->
                message.replaceFirst("原子动作完成", "原子动作最终结果")
            else -> null
        } ?: return
        LogUtils.i("【下位机测试】300/400原子动作明细：$fileLogMessage")
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
                val source = "测试下位机页面手动触发"
                LogUtils.i(
                    "【下位机测试】自清洁开始：target（目标）=烤盘$positionSn，" +
                        "source（来源）=$source"
                )
                val startedAtMs = System.currentTimeMillis()
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanPanPosition(
                        positionSn = positionSn,
                        source = source,
                        busyDuringClean = true
                    )
                }
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
                logTestSelfCleanFinished(report, startedAtMs)
            }
        }

        binding.btnRunSellPlatformSelfClean.setOnClickListener {
            lifecycleScope.launch {
                binding.tvSelfCleanResult.text = "正在执行出肠台自清洁..."
                val source = "测试下位机页面手动触发"
                LogUtils.i(
                    "【下位机测试】自清洁开始：target（目标）=出肠台，" +
                        "source（来源）=$source"
                )
                val startedAtMs = System.currentTimeMillis()
                val report = withContext(Dispatchers.IO) {
                    KaoChangOperate.cleanSellPlatform(
                        source = source,
                        busyDuringClean = true
                    )
                }
                binding.tvSelfCleanResult.text = buildSelfCleanReportText(report)
                logTestSelfCleanFinished(report, startedAtMs)
            }
        }
    }

    private fun logTestSelfCleanFinished(
        report: KaoChangOperate.SelfCleanExecutionReport,
        startedAtMs: Long
    ) {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val logMessage =
            "【下位机测试】自清洁结束：" +
                "target（目标）=${report.targetLabel}，" +
                "command（下位机自清洁指令）=${report.command}，" +
                "success（是否成功）=${report.success}，" +
                "status201（动作状态寄存器）=${formatSelfCleanStatus201(report.status201)}，" +
                "message（结果消息）=${report.message}，elapsedMs（耗时毫秒）=$elapsedMs"
        if (report.success) {
            LogUtils.i(logMessage)
        } else {
            LogUtils.w(logMessage)
        }
    }

    private fun buildSelfCleanReportText(report: KaoChangOperate.SelfCleanExecutionReport): String {
        val status201Text = formatSelfCleanStatus201(report.status201)
        return "目标=${report.targetLabel}\n" +
            "command（下位机自清洁指令）=${report.command}\n" +
            "status201（动作状态寄存器）=$status201Text\n" +
            "success=${report.success}\n" +
            "message=${report.message}"
    }

    private fun formatSelfCleanStatus201(status201: Int): String {
        return when (status201) {
            0 -> "0（空闲/完成）"
            1 -> "1（执行中超时）"
            2 -> "2（设备忙）"
            else -> "$status201（通信失败/未知）"
        }
    }

    private fun setupTemperatureControlListeners() {
        binding.btnReadTemps.setOnClickListener {
            readTemperatureDiagnostics()
        }

        binding.btnWriteTemps.setOnClickListener {
            writeTemperatureTargetsWithConfirmation()
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

    private fun readRegister(
        register: Int,
        quantity: Int,
        description: String,
        callback: (IntArray) -> Unit
    ) {
        lifecycleScope.launch {
            showToast("正在读取$description...")
            try {
                val (values, elapsedMs) = withContext(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    val readValues = KaoChangOperate.readHoldingRegisters(register, quantity)
                    readValues to (System.currentTimeMillis() - startMs)
                }
                if (values != null) {
                    LogUtils.i(
                        "【下位机测试】读寄存器成功：" +
                            "description（说明）=$description，" +
                            "startRegister（起始寄存器）=$register，quantity（数量）=$quantity，" +
                            "values（返回值）=${values.joinToString()}，elapsedMs（耗时毫秒）=$elapsedMs"
                    )
                    callback.invoke(values)
                } else {
                    LogUtils.w(
                        "【下位机测试】读寄存器失败：返回为空，" +
                            "description（说明）=$description，" +
                            "startRegister（起始寄存器）=$register，quantity（数量）=$quantity，elapsedMs（耗时毫秒）=$elapsedMs"
                    )
                    showToast("读取${description}失败：返回为空")
                }
            } catch (e: Exception) {
                LogUtils.e(
                    "【下位机测试】读寄存器异常：" +
                        "description（说明）=$description，" +
                        "startRegister（起始寄存器）=$register，quantity（数量）=$quantity，error=${e.message}"
                )
                showToast("读取${description}失败: ${e.message}")
            }
        }
    }

    private fun readTemperatureDiagnostics() {
        readRegister(500, 7, "温控诊断寄存器(500~506)") { value ->
            binding.tvTemperatureDiagnostic.text =
                "500（第1路平滑实时温度）=${value[0]}℃\n" +
                "501（第2路平滑实时温度）=${value[1]}℃\n" +
                "502（第3路平滑实时温度）=${value[2]}℃\n" +
                "506（功率或预留状态量）=${value[6]}（暂不解释具体含义）"
            binding.etTempSet0.setText(value[3].toString())
            binding.etTempSet1.setText(value[4].toString())
            binding.etTempSet2.setText(value[5].toString())
        }
    }

    private fun writeTemperatureTargetsWithConfirmation() {
        val parsedTargets = mutableListOf<Pair<TemperatureTargetInput, Int>>()
        temperatureTargetRegisters.forEach { input ->
            val text = input.textProvider()
            if (text.isBlank()) {
                return@forEach
            }
            val value = text.toIntOrNull()
            if (value == null) {
                showToast("${input.label} 必须是整数")
                binding.tvTemperatureWriteResult.text = "${input.label} 输入非法：$text"
                LogUtils.w(
                    "【下位机测试】目标温度输入非法：" +
                        "area（区域）=${input.area}，register（寄存器）=${input.register}，input=$text"
                )
                return
            }
            parsedTargets += input to value
        }

        if (parsedTargets.isEmpty()) {
            showToast("请输入至少一路目标温度")
            binding.tvTemperatureWriteResult.text = "未写入：请输入至少一路目标温度"
            return
        }

        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                showToast("当前正在自清洁，测试下位机写操作已禁用")
                return@launch
            }
            binding.tvTemperatureWriteResult.text = "正在写入并回读确认..."
            val results = withContext(Dispatchers.IO) {
                parsedTargets.map { (input, value) ->
                    writeRegisterAndReadBack(input.register, value, input.label)
                }
            }
            binding.tvTemperatureWriteResult.text = results.joinToString("\n") { result ->
                val statusText = if (result.confirmed) "一致" else "不一致/失败"
                "${result.description}：写入=${result.writeValue}，回读=${result.readBackValue ?: "失败"}，确认=$statusText"
            }
            readTemperatureDiagnostics()
        }
    }

    private fun writeRegisterAndReadBack(
        register: Int,
        value: Int,
        description: String
    ): RegisterWriteConfirmation {
        val startMs = System.currentTimeMillis()
        LogUtils.i(
            "【下位机测试】写寄存器开始：" +
                "description（说明）=$description，register（寄存器）=$register，value（写入值）=$value"
        )
        val writeResult = KaoChangOperate.writeSingleRegister(register, value)
        val readBack = KaoChangOperate.readHoldingRegisters(register, 1)?.firstOrNull()
        val elapsedMs = System.currentTimeMillis() - startMs
        val confirmed = writeResult.isSuccess && readBack == value
        val logMessage =
            "【下位机测试】写寄存器回读确认：" +
                "description（说明）=$description，register（寄存器）=$register，" +
                "writeValue（写入值）=$value，writeStatus（写入状态）=${writeResult.status}，" +
                "writeMessage（写入消息）=${writeResult.message ?: "无"}，" +
                "readBackValue（回读值）=${readBack ?: "读取失败"}，confirmed（一致）=$confirmed，" +
                "elapsedMs（耗时毫秒）=$elapsedMs"
        if (confirmed) {
            LogUtils.i(logMessage)
        } else {
            LogUtils.w(logMessage)
        }
        return RegisterWriteConfirmation(
            description = description,
            writeValue = value,
            readBackValue = readBack,
            confirmed = confirmed
        )
    }

    private data class RegisterWriteConfirmation(
        val description: String,
        val writeValue: Int,
        val readBackValue: Int?,
        val confirmed: Boolean
    )

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
