package cn.niuyannet.kaochang.android.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.databinding.FragmentTestBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.PanMove203Decision
import cn.niuyannet.kaochang.android.services.PanMove203Policy
import cn.niuyannet.kaochang.android.services.TestPagePanMoveCommandPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetryContext
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionSpec
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActions
import cn.niuyannet.kaochang.android.services.actiongroup.VmAtomicModbusGateway
import cn.niuyannet.kaochang.android.services.paramtuning.CalibrationTarget
import cn.niuyannet.kaochang.android.services.paramtuning.LowerBoardParamDefinitions
import cn.niuyannet.kaochang.android.services.paramtuning.LowerBoardParamTuningService
import cn.niuyannet.kaochang.android.services.paramtuning.ParamInput
import cn.niuyannet.kaochang.android.services.paramtuning.ParamTuningMaintenanceGate
import cn.niuyannet.kaochang.android.services.paramtuning.ParamTuningReadResult
import cn.niuyannet.kaochang.android.services.paramtuning.ParamTuningResult
import cn.niuyannet.kaochang.android.services.paramtuning.ParamValue
import cn.niuyannet.kaochang.android.services.paramtuning.VmLowerBoardParamGateway
import cn.niuyannet.kaochang.android.ui.AdminOperationLogger
import cn.niuyannet.kaochang.android.ui.beginAdminBusy
import cn.niuyannet.kaochang.android.ui.endAdminBusy
import cn.niuyannet.kaochang.android.ui.registerAdminTextInputs
import cn.niuyannet.kaochang.android.ui.resetAdminIdleTimer
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TestAtomicActionItem(
    val label: String,
    val spec: AtomicActionSpec,
    val defaultInfoValue: Int
) {
    override fun toString(): String = label
}

private data class CalibrationTargetItem(
    val label: String,
    val type: Int,
    val needsSlot: Boolean,
    val supportsZ: Boolean
) {
    override fun toString(): String = label

    fun toTarget(slot: Int): CalibrationTarget = when (type) {
        1 -> CalibrationTarget.BakewareSlot(slot)
        2 -> CalibrationTarget.Lift
        3 -> CalibrationTarget.Stick
        4 -> CalibrationTarget.PushReady
        5 -> CalibrationTarget.Discard
        6 -> CalibrationTarget.PushTo
        7 -> CalibrationTarget.PushBack
        else -> error("unknown calibration target type: $type")
    }
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
    private val paramTuningService by lazy {
        LowerBoardParamTuningService(VmLowerBoardParamGateway(KaoChangOperate.modbus_address))
    }
    private val calibrationTargetItems = listOf(
        CalibrationTargetItem("1-烤位(编号 1~33)", 1, needsSlot = true, supportsZ = true),
        CalibrationTargetItem("2-升降台取肠点", 2, needsSlot = false, supportsZ = true),
        CalibrationTargetItem("3-穿签点", 3, needsSlot = false, supportsZ = true),
        CalibrationTargetItem("4-推送准备位", 4, needsSlot = false, supportsZ = true),
        CalibrationTargetItem("5-废弃池", 5, needsSlot = false, supportsZ = true),
        CalibrationTargetItem("6-售卖推送终点(XY)", 6, needsSlot = false, supportsZ = false),
        CalibrationTargetItem("7-推送回退点(XY)", 7, needsSlot = false, supportsZ = false)
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
        TestAtomicActionItem("out5：关门回零/出餐收尾", AtomicActions.out5, 0)
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
        registerAdminTextInputs(view)
        setupActionControlListeners()
        setupSelfCleanListeners()
        setupTemperatureControlListeners()
        setupSystemInfoListeners()
        setupParamTuningListeners()
    }

    private fun setupActionControlListeners() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, atomicActionItems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAtomicAction.adapter = adapter
        binding.spinnerAtomicAction.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                resetAdminIdleTimer("测试页选择原子动作")
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

        binding.btnBusinessActionSelfCheck.setOnClickListener {
            readBusinessActionProtocolStatus()
        }

        binding.btnRunPanMoveCommand.setOnClickListener {
            executePanMoveCommandFromTestPage()
        }

        binding.btnClearLowerBoardRegisters.setOnClickListener {
            clearLowerBoardRegisters()
        }

        binding.btnResetLowerBoardSystem.setOnClickListener {
            resetLowerBoardSystem()
        }

        binding.btnEmergencyResetLowerBoardSystem.setOnClickListener {
            emergencyResetLowerBoardSystem()
        }

        binding.etRegisterStart.setText("201")
        binding.etRegisterQuantity.setText("1")
        binding.btnReadCustomRegisters.setOnClickListener {
            readCustomRegisters()
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
                AdminOperationLogger.w(
                    action = "执行300/400原子动作",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "拒绝原因=当前正在自清洁，动作名=${item.spec.name}"
                )
                showToast("当前正在自清洁，测试下位机动作已禁用")
                return@launch
            }
            val adminBusy = beginAdminBusy("测试下位机原子动作:${item.spec.name}")
            val sessionId = "test_${item.spec.name}_${System.currentTimeMillis()}"
            try {
                binding.tvAtomicActionResult.text = "执行中：动作名=${item.spec.name}"
                AdminOperationLogger.i(
                    action = "执行300/400原子动作",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_START,
                    detail = "动作名=${item.spec.name}，命令寄存器=${item.spec.commandRegister}，" +
                        "结果寄存器=${item.spec.resultRegister}，信息寄存器=${item.spec.infoRegister}，信息参数=$infoValue"
                )
                val result = withContext(Dispatchers.IO) {
                    AtomicActionExecutor(
                        gateway = VmAtomicModbusGateway(KaoChangOperate.modbus_address),
                        logger = { message -> logTestAtomicActionDetail(message) },
                        telemetryContext = ActionGroupTelemetryContext(
                            sessionId = sessionId,
                            resourceLocks = "TEST_PAGE"
                        ),
                        diagnosisSnapshotProvider = { VMModbusHelper.currentDiagnosisSnapshot() },
                        modbusServerAddress = KaoChangOperate.modbus_address
                    ).execute(item.spec, infoValue)
                }
                binding.tvAtomicActionResult.text = describeAtomicActionResult(result)
                val success = result is AtomicActionResult.Success
                val resultDetail = "动作名=${item.spec.name}，信息寄存器=${item.spec.infoRegister}，信息参数=$infoValue，执行结果=${describeAtomicActionResult(result)}"
                if (success) {
                    AdminOperationLogger.i(
                        action = "执行300/400原子动作",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_DONE,
                        detail = resultDetail
                    )
                } else {
                    AdminOperationLogger.w(
                        action = "执行300/400原子动作",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = resultDetail
                    )
                }
            } finally {
                endAdminBusy(adminBusy)
            }
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

    private fun executePanMoveCommandFromTestPage() {
        val sourceText = binding.etPanMoveSource.text?.toString()?.trim().orEmpty()
        val targetText = binding.etPanMoveTarget.text?.toString()?.trim().orEmpty()
        val sourcePosition = sourceText.toIntOrNull()
        val targetPosition = targetText.toIntOrNull()
        if (sourcePosition == null) {
            showToast("请输入源烤盘位 1~33")
            binding.tvPanMoveCommandResult.text = "执行失败：源烤盘位非法（$sourceText）"
            return
        }
        if (targetPosition == null) {
            showToast("请输入目标烤盘位 1~33")
            binding.tvPanMoveCommandResult.text = "执行失败：目标烤盘位非法（$targetText）"
            return
        }
        val validation = TestPagePanMoveCommandPolicy.validate(sourcePosition, targetPosition)
        if (!validation.ok) {
            showToast(validation.message)
            binding.tvPanMoveCommandResult.text = "执行失败：${validation.message}"
            return
        }

        lifecycleScope.launch {
            if (AppConfig.getAppConfig().moveStatus == 5) {
                AdminOperationLogger.w(
                    action = "测试烤盘搬移202/203",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，拒绝原因=当前正在自清洁"
                )
                showToast("当前正在自清洁，测试下位机动作已禁用")
                return@launch
            }

            val commandValue = TestPagePanMoveCommandPolicy.encodeCommand(sourcePosition, targetPosition)
            val commandHex = formatCommandHex(commandValue)
            val adminBusy = beginAdminBusy("测试下位机烤盘搬移202/203")
            binding.btnRunPanMoveCommand.isEnabled = false
            binding.tvPanMoveCommandResult.text =
                "准备写入 202=$commandHex（源烤盘=$sourcePosition，目标烤盘=$targetPosition）\n" +
                    "提示：该操作仅测试下位机搬移动作，不会更新本地烤盘库存。"
            try {
                AdminOperationLogger.i(
                    action = "测试烤盘搬移202/203",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_START,
                    detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，202=$commandHex，说明=裸下位机协议测试，不更新本地库存或云端"
                )
                val resultText = withContext(Dispatchers.IO) {
                    runPanMoveCommandAndWait(sourcePosition, targetPosition, commandValue)
                }
                binding.tvPanMoveCommandResult.text = resultText
            } finally {
                binding.btnRunPanMoveCommand.isEnabled = true
                endAdminBusy(adminBusy)
            }
        }
    }

    private suspend fun runPanMoveCommandAndWait(
        sourcePosition: Int,
        targetPosition: Int,
        commandValue: Int
    ): String {
        val commandHex = formatCommandHex(commandValue)
        if (!VMModbusHelper.connectStatus()) {
            AdminOperationLogger.w(
                action = "测试烤盘搬移202/203",
                page = AdminOperationLogger.PAGE_TEST,
                result = AdminOperationLogger.RESULT_REJECT,
                detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，拒绝原因=下位机未连接，Modbus串口地址=${AppConfig.getAppConfig().modbusAddress}"
            )
            return "执行失败：下位机未连接\n202=$commandHex\n提示：该操作未更新本地烤盘库存。"
        }

        val writeResult = KaoChangOperate.writeSingleRegister2(KaoChangOperate.action_address_move, commandValue)
        if (!writeResult.isSuccess) {
            AdminOperationLogger.w(
                action = "测试烤盘搬移202/203",
                page = AdminOperationLogger.PAGE_TEST,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，202=$commandHex，失败原因=写202失败，status=${writeResult.status}"
            )
            return "写入 202 失败：status=${writeResult.status}\n202=$commandHex\n提示：该操作未更新本地烤盘库存。"
        }

        val startedAtMs = System.currentTimeMillis()
        val timeoutMs = 60_000L
        var hasObservedRunning = false
        while (true) {
            delay(PanMove203Policy.pollDelayMs)
            val result203 = KaoChangOperate.readHoldingRegisters(KaoChangOperate.action_address_move_result, 1)?.firstOrNull() ?: -1
            when (PanMove203Policy.evaluate(result203, hasObservedRunning)) {
                PanMove203Decision.Running -> {
                    hasObservedRunning = true
                    LogUtils.d("测试下位机烤盘搬移执行中：source=$sourcePosition，target=$targetPosition，203=1")
                }
                PanMove203Decision.Success -> {
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    AdminOperationLogger.i(
                        action = "测试烤盘搬移202/203",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_DONE,
                        detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，202=$commandHex，203=${formatPanMoveResultRegisterValue(result203)}，耗时=${elapsedMs}ms，说明=未更新本地库存或云端"
                    )
                    return buildPanMoveCommandReport(sourcePosition, targetPosition, commandHex, result203, elapsedMs, "搬移完成")
                }
                PanMove203Decision.WaitingForStart -> {
                    LogUtils.d("测试下位机烤盘搬移等待启动：source=$sourcePosition，target=$targetPosition，203=$result203")
                }
                PanMove203Decision.ReadFailed,
                PanMove203Decision.Failed -> {
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    AdminOperationLogger.w(
                        action = "测试烤盘搬移202/203",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，202=$commandHex，203=${formatPanMoveResultRegisterValue(result203)}，耗时=${elapsedMs}ms"
                    )
                    return buildPanMoveCommandReport(sourcePosition, targetPosition, commandHex, result203, elapsedMs, "搬移失败")
                }
            }

            if (System.currentTimeMillis() - startedAtMs > timeoutMs) {
                val timeoutResult203 = KaoChangOperate.readHoldingRegisters(KaoChangOperate.action_address_move_result, 1)?.firstOrNull() ?: -1
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                AdminOperationLogger.w(
                    action = "测试烤盘搬移202/203",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "源烤盘位=$sourcePosition，目标烤盘位=$targetPosition，202=$commandHex，203=${formatPanMoveResultRegisterValue(timeoutResult203)}，失败原因=等待203完成超时，耗时=${elapsedMs}ms"
                )
                return buildPanMoveCommandReport(sourcePosition, targetPosition, commandHex, timeoutResult203, elapsedMs, "等待 203 完成超时")
            }
        }
    }

    private fun buildPanMoveCommandReport(
        sourcePosition: Int,
        targetPosition: Int,
        commandHex: String,
        result203: Int,
        elapsedMs: Long,
        message: String
    ): String {
        return "源烤盘=$sourcePosition，目标烤盘=$targetPosition\n" +
            "202（搬移命令）=$commandHex\n" +
            "203（搬移结果）=${formatPanMoveResultRegisterValue(result203)}\n" +
            "耗时=${elapsedMs}ms\n" +
            "结果=$message\n" +
            "提示：该操作仅测试下位机搬移动作，不会更新本地烤盘库存或云端可售状态。"
    }

    private fun clearLowerBoardRegisters() {
        lifecycleScope.launch {
            setLowerBoardMaintenanceButtonsEnabled(false)
            binding.tvAtomicActionResult.text = "正在检查下位机空闲状态，准备清空寄存器残留..."
            try {
                val startedAtMs = System.currentTimeMillis()
                val resultText = withContext(Dispatchers.IO) {
                    val guard = readLowerBoardMaintenanceGuard()
                    val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
                        moveStatus = AppConfig.getAppConfig().moveStatus,
                        status201 = guard.status201 ?: -1,
                        moveResult203 = guard.moveResult203 ?: -1,
                        atomicResultValues = guard.atomicResults.filterValues { it != null }.mapValues { it.value!! }
                    )
                    if (!decision.allowed) {
                        AdminOperationLogger.w(
                            action = "清空下位机寄存器残留",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=${decision.reason}，检查结果=${guard.summary()}"
                        )
                        return@withContext "清空寄存器残留被拒绝：${decision.reason}\n${guard.summary()}"
                    }
                    AdminOperationLogger.i(
                        action = "清空下位机寄存器残留",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_START,
                        detail = "写入 998=1，检查结果=${guard.summary()}"
                    )
                    val writeResult = KaoChangOperate.writeSingleRegister(
                        LowerBoardMaintenancePolicy.CLEAR_REGISTER,
                        LowerBoardMaintenancePolicy.TRIGGER_VALUE
                    )
                    val readBack = KaoChangOperate.readHoldingRegisters(
                        LowerBoardMaintenancePolicy.CLEAR_REGISTER,
                        1
                    )?.firstOrNull()
                    val afterResults = readAtomicResultRegisters()
                    val allCleared = writeResult.isSuccess &&
                        readBack == 0 &&
                        afterResults.values.all { it == 0 }
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    val detail = "写入状态=${writeResult.status}，写入消息=${writeResult.message ?: "无"}，" +
                        "998回读=${readBack ?: "读取失败"}，清理后=${formatRegisterSnapshot(afterResults)}，耗时=${elapsedMs}ms"
                    if (allCleared) {
                        AdminOperationLogger.i(
                            action = "清空下位机寄存器残留",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = detail
                        )
                    } else {
                        AdminOperationLogger.w(
                            action = "清空下位机寄存器残留",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = detail
                        )
                    }
                    LogUtils.i("【下位机测试】清空寄存器残留：$detail")
                    buildString {
                        append(if (allCleared) "清空寄存器残留完成" else "清空寄存器残留未确认完成")
                        append("\n写入 998=1：${writeResult.status}${writeResult.message?.let { "（$it）" } ?: ""}")
                        append("\n998 回读：${readBack ?: "读取失败"}（期望 0）")
                        append("\n清理后结果寄存器：${formatRegisterSnapshot(afterResults)}")
                    }
                }
                binding.tvAtomicActionResult.text = resultText
            } catch (e: Exception) {
                binding.tvAtomicActionResult.text = "清空寄存器残留异常：${e.message}"
                AdminOperationLogger.e(
                    action = "清空下位机寄存器残留",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "失败原因=${e.message}"
                )
            } finally {
                setLowerBoardMaintenanceButtonsEnabled(true)
            }
        }
    }

    private fun resetLowerBoardSystem() {
        lifecycleScope.launch {
            setLowerBoardMaintenanceButtonsEnabled(false)
            binding.tvAtomicActionResult.text = "正在检查下位机空闲状态，准备下发 999 复位..."
            try {
                val resultText = withContext(Dispatchers.IO) {
                    val guard = readLowerBoardMaintenanceGuard()
                    val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
                        moveStatus = AppConfig.getAppConfig().moveStatus,
                        status201 = guard.status201 ?: -1,
                        moveResult203 = guard.moveResult203 ?: -1,
                        atomicResultValues = guard.atomicResults.filterValues { it != null }.mapValues { it.value!! }
                    )
                    if (!decision.allowed) {
                        AdminOperationLogger.w(
                            action = "下位机复位",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=${decision.reason}，检查结果=${guard.summary()}"
                        )
                        return@withContext "下位机复位被拒绝：${decision.reason}\n${guard.summary()}"
                    }
                    AdminOperationLogger.i(
                        action = "下位机复位",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_START,
                        detail = "写入 999=1，检查结果=${guard.summary()}"
                    )
                    val writeResult = KaoChangOperate.writeSingleRegister(
                        LowerBoardMaintenancePolicy.SYSTEM_RESET_REGISTER,
                        LowerBoardMaintenancePolicy.TRIGGER_VALUE
                    )
                    val success = writeResult.isSuccess
                    val detail = "写入状态=${writeResult.status}，写入消息=${writeResult.message ?: "无"}；" +
                        "999 写入后下位机会约 50ms 内软重启，不做立即回读"
                    if (success) {
                        AdminOperationLogger.i(
                            action = "下位机复位",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = detail
                        )
                    } else {
                        AdminOperationLogger.w(
                            action = "下位机复位",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = detail
                        )
                    }
                    LogUtils.i("【下位机测试】下位机复位：$detail")
                    if (success) {
                        "999 已下发，等待下位机重启并完成机械回零\n写入状态=${writeResult.status}"
                    } else {
                        "999 下发失败：${writeResult.status}${writeResult.message?.let { "（$it）" } ?: ""}"
                    }
                }
                binding.tvAtomicActionResult.text = resultText
            } catch (e: Exception) {
                binding.tvAtomicActionResult.text = "下位机复位异常：${e.message}"
                AdminOperationLogger.e(
                    action = "下位机复位",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "失败原因=${e.message}"
                )
            } finally {
                setLowerBoardMaintenanceButtonsEnabled(true)
            }
        }
    }

    private fun emergencyResetLowerBoardSystem() {
        lifecycleScope.launch {
            setLowerBoardMaintenanceButtonsEnabled(false)
            binding.tvAtomicActionResult.text = "正在下发紧急复位 999=1..."
            try {
                val resultText = withContext(Dispatchers.IO) {
                    val decision = LowerBoardMaintenancePolicy.canRunEmergencyReset()
                    if (!decision.allowed) {
                        return@withContext "紧急复位被拒绝：${decision.reason}"
                    }
                    AdminOperationLogger.w(
                        action = "下位机紧急复位",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_START,
                        detail = "绕过空闲检查，直接写入 999=1；moveStatus=${AppConfig.getAppConfig().moveStatus}"
                    )
                    val writeResult = KaoChangOperate.writeSingleRegister(
                        LowerBoardMaintenancePolicy.SYSTEM_RESET_REGISTER,
                        LowerBoardMaintenancePolicy.TRIGGER_VALUE
                    )
                    val detail = "写入状态=${writeResult.status}，写入消息=${writeResult.message ?: "无"}；" +
                        "紧急复位不等待动作空闲，也不做立即回读"
                    if (writeResult.isSuccess) {
                        AdminOperationLogger.w(
                            action = "下位机紧急复位",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = detail
                        )
                        "紧急复位已下发，下位机当前动作将被中断\n请人工复核机械位置、烤肠箱和烤盘状态后再恢复运行"
                    } else {
                        AdminOperationLogger.e(
                            action = "下位机紧急复位",
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = detail
                        )
                        "紧急复位下发失败：${writeResult.status}${writeResult.message?.let { "（$it）" } ?: ""}"
                    }
                }
                binding.tvAtomicActionResult.text = resultText
            } catch (e: Exception) {
                binding.tvAtomicActionResult.text = "紧急复位异常：${e.message}"
                AdminOperationLogger.e(
                    action = "下位机紧急复位",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "失败原因=${e.message}"
                )
            } finally {
                setLowerBoardMaintenanceButtonsEnabled(true)
            }
        }
    }

    private fun setLowerBoardMaintenanceButtonsEnabled(enabled: Boolean) {
        binding.btnClearLowerBoardRegisters.isEnabled = enabled
        binding.btnResetLowerBoardSystem.isEnabled = enabled
        binding.btnEmergencyResetLowerBoardSystem.isEnabled = enabled
    }

    private fun readLowerBoardMaintenanceGuard(): LowerBoardMaintenanceGuard {
        return LowerBoardMaintenanceGuard(
            status201 = KaoChangOperate.readHoldingRegisters(201, 1)?.firstOrNull(),
            moveResult203 = KaoChangOperate.readHoldingRegisters(203, 1)?.firstOrNull(),
            atomicResults = readAtomicResultRegisters()
        )
    }

    private fun readAtomicResultRegisters(): Map<Int, Int?> {
        return LowerBoardMaintenancePolicy.actionResultRegisters.associateWith { register ->
            KaoChangOperate.readHoldingRegisters(register, 1)?.firstOrNull()
        }
    }

    private fun formatRegisterSnapshot(values: Map<Int, Int?>): String {
        return values.entries.joinToString("；") { (register, value) ->
            "$register=${value ?: "读取失败"}"
        }
    }

    private data class LowerBoardMaintenanceGuard(
        val status201: Int?,
        val moveResult203: Int?,
        val atomicResults: Map<Int, Int?>
    ) {
        fun summary(): String {
            return "201=${status201 ?: "读取失败"}，203=${moveResult203 ?: "读取失败"}，" +
                "原子结果=${formatRegisterSnapshot(atomicResults)}"
        }

        private fun formatRegisterSnapshot(values: Map<Int, Int?>): String {
            return values.entries.joinToString("；") { (register, value) ->
                "$register=${value ?: "读取失败"}"
            }
        }
    }

    private fun readBusinessActionProtocolStatus() {
        lifecycleScope.launch {
            binding.btnBusinessActionSelfCheck.isEnabled = false
            binding.tvAtomicActionResult.text = "业务动作协议自检中..."
            try {
                val results = withContext(Dispatchers.IO) {
                    buildList {
                        add(
                            BusinessActionRegisterRead(
                                label = "201（机械动作状态）",
                                value = KaoChangOperate.readHoldingRegisters(201, 1)?.firstOrNull(),
                                kind = BusinessActionRegisterKind.LEGACY_MOVE_STATUS
                            )
                        )
                        add(
                            BusinessActionRegisterRead(
                                label = "202（烤盘搬移命令）",
                                value = KaoChangOperate.readHoldingRegisters(202, 1)?.firstOrNull(),
                                kind = BusinessActionRegisterKind.PAN_MOVE_COMMAND
                            )
                        )
                        add(
                            BusinessActionRegisterRead(
                                label = "203（烤盘搬移结果）",
                                value = KaoChangOperate.readHoldingRegisters(203, 1)?.firstOrNull(),
                                kind = BusinessActionRegisterKind.PAN_MOVE_RESULT
                            )
                        )
                        atomicActionItems.forEach { item ->
                            add(
                                BusinessActionRegisterRead(
                                    label = "${item.spec.name} 结果寄存器 ${item.spec.resultRegister}",
                                    value = KaoChangOperate.readHoldingRegisters(item.spec.resultRegister, 1)?.firstOrNull(),
                                    kind = BusinessActionRegisterKind.ATOMIC_RESULT
                                )
                            )
                        }
                    }
                }
                val summary = results.joinToString(separator = "\n") { read ->
                    "${read.label}=${formatBusinessActionRegisterValue(read)}"
                }
                binding.tvAtomicActionResult.text = summary
                LogUtils.d(
                    "测试下位机业务动作协议自检完成：" +
                        "Modbus串口地址=${AppConfig.getAppConfig().modbusAddress}，" +
                        "自检结果=${results.joinToString("；") { read -> "${read.label}=${formatBusinessActionRegisterValue(read)}" }}"
                )
            } catch (e: Exception) {
                binding.tvAtomicActionResult.text = "业务动作协议自检异常：${e.message}"
                AdminOperationLogger.e(
                    action = "业务动作协议自检",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "失败原因=${e.message}"
                )
            } finally {
                binding.btnBusinessActionSelfCheck.isEnabled = true
            }
        }
    }

    private fun readCustomRegisters() {
        val startRegisterText = binding.etRegisterStart.text?.toString()?.trim().orEmpty()
        val quantityText = binding.etRegisterQuantity.text?.toString()?.trim().orEmpty()
        val startRegister = startRegisterText.toIntOrNull()
        val quantity = quantityText.toIntOrNull()

        if (startRegister == null || startRegister < 0) {
            showToast("起始寄存器必须是非负整数")
            binding.tvCustomRegisterReadResult.text = "读取失败：起始寄存器非法（$startRegisterText）"
            return
        }
        if (quantity == null || quantity !in 1..10) {
            showToast("读取数量必须在 1~10 之间")
            binding.tvCustomRegisterReadResult.text = "读取失败：数量必须在 1~10 之间（$quantityText）"
            return
        }

        lifecycleScope.launch {
            binding.btnReadCustomRegisters.isEnabled = false
            binding.tvCustomRegisterReadResult.text = "正在读取寄存器 $startRegister~${startRegister + quantity - 1}..."
            try {
                val (values, elapsedMs) = withContext(Dispatchers.IO) {
                    val startMs = System.currentTimeMillis()
                    val readValues = KaoChangOperate.readHoldingRegisters(startRegister, quantity)
                    readValues to (System.currentTimeMillis() - startMs)
                }
                if (values == null) {
                    binding.tvCustomRegisterReadResult.text =
                        "读取失败：寄存器 $startRegister~${startRegister + quantity - 1} 返回为空"
                    AdminOperationLogger.w(
                        action = "通用读取寄存器",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "起始寄存器=$startRegister，数量=$quantity，失败原因=返回为空，耗时=${elapsedMs}ms"
                    )
                    return@launch
                }
                val resultText = buildCustomRegisterReadText(startRegister, values, elapsedMs)
                binding.tvCustomRegisterReadResult.text = resultText
                LogUtils.d(
                    "测试下位机通用读寄存器成功：" +
                        "起始寄存器=$startRegister，数量=$quantity，" +
                        "返回值=${values.joinToString()}，耗时=${elapsedMs}ms"
                )
            } catch (e: Exception) {
                binding.tvCustomRegisterReadResult.text = "读取异常：${e.message}"
                AdminOperationLogger.e(
                    action = "通用读取寄存器",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "起始寄存器=$startRegister，数量=$quantity，失败原因=${e.message}"
                )
            } finally {
                binding.btnReadCustomRegisters.isEnabled = true
            }
        }
    }

    private fun buildCustomRegisterReadText(
        startRegister: Int,
        values: IntArray,
        elapsedMs: Long
    ): String {
        return buildString {
            append("读取范围：")
            append(startRegister)
            append("~")
            append(startRegister + values.size - 1)
            append("，耗时=")
            append(elapsedMs)
            append("ms")
            values.forEachIndexed { index, value ->
                val address = startRegister + index
                append("\n")
                append(address)
                append(" = ")
                append(formatCustomRegisterValue(address, value))
            }
        }
    }

    private fun formatCustomRegisterValue(address: Int, value: Int): String {
        val atomicItem = atomicActionItems.firstOrNull { item ->
            address == item.spec.commandRegister ||
                address == item.spec.resultRegister ||
                address == item.spec.infoRegister
        }
        return when {
            address == 201 -> formatBusinessActionRegisterValue(
                BusinessActionRegisterRead(
                    label = "201（机械动作状态）",
                    value = value,
                    kind = BusinessActionRegisterKind.LEGACY_MOVE_STATUS
                )
            )
            address == 202 -> formatPanMoveCommandRegisterValue(value)
            address == 203 -> formatPanMoveResultRegisterValue(value)
            address == LowerBoardMaintenancePolicy.CLEAR_REGISTER ||
                address == LowerBoardMaintenancePolicy.SYSTEM_RESET_REGISTER ->
                LowerBoardMaintenancePolicy.formatMaintenanceRegisterValue(address, value)
            atomicItem != null && address == atomicItem.spec.commandRegister ->
                "${AtomicActionExecutor.commandValueDesc(value)}，${atomicItem.spec.name} 命令寄存器"
            atomicItem != null && address == atomicItem.spec.resultRegister ->
                "${formatAtomicResultRegisterValue(value)}，${atomicItem.spec.name} 结果寄存器"
            atomicItem != null && address == atomicItem.spec.infoRegister ->
                "${formatRegisterValue(value)}，${atomicItem.spec.name} 信息寄存器"
            address in 500..502 -> "$value（${AtomicActionExecutor.formatCode(value)}，实时温度/温控状态）"
            address in 503..505 -> "$value（${AtomicActionExecutor.formatCode(value)}，目标温度）"
            address == 506 -> "$value（${AtomicActionExecutor.formatCode(value)}，功率或预留状态量）"
            address in 900..903 -> "$value（${AtomicActionExecutor.formatCode(value)}，版本 ASCII 字寄存器）"
            else -> "$value（${AtomicActionExecutor.formatCode(value)}）"
        }
    }

    private data class BusinessActionRegisterRead(
        val label: String,
        val value: Int?,
        val kind: BusinessActionRegisterKind
    )

    private enum class BusinessActionRegisterKind {
        LEGACY_MOVE_STATUS,
        PAN_MOVE_COMMAND,
        PAN_MOVE_RESULT,
        ATOMIC_RESULT
    }

    private fun formatBusinessActionRegisterValue(read: BusinessActionRegisterRead): String {
        val value = read.value ?: return "读取失败"
        return when (read.kind) {
            BusinessActionRegisterKind.LEGACY_MOVE_STATUS -> when (value) {
                0 -> "0（机械动作空闲）"
                1 -> "1（机械动作执行中）"
                2 -> "2（设备忙）"
                else -> "$value（未知机械动作状态）"
            }
            BusinessActionRegisterKind.PAN_MOVE_COMMAND -> formatPanMoveCommandRegisterValue(value)
            BusinessActionRegisterKind.PAN_MOVE_RESULT -> formatPanMoveResultRegisterValue(value)
            BusinessActionRegisterKind.ATOMIC_RESULT -> formatAtomicResultRegisterValue(value)
        }
    }

    private fun formatAtomicResultRegisterValue(value: Int): String {
        return when {
            value == AtomicActionCodes.IDLE -> "0x0000（空闲/成功）"
            value == AtomicActionCodes.RUNNING -> "0x0001（执行中）"
            value == AtomicActionCodes.COMPLETED -> "0x0002（动作完成）"
            value >= AtomicActionCodes.PARAM_ERROR -> "0x${value.toString(16).uppercase().padStart(4, '0')}（${AtomicActionExecutor.messageOf(value)}）"
            else -> "0x${value.toString(16).uppercase().padStart(4, '0')}（未知结果码）"
        }
    }

    private fun formatPanMoveCommandRegisterValue(value: Int): String {
        if (value == 0) {
            return "0x0000（无烤盘搬移命令）"
        }
        val source = (value shr 8) and 0xFF
        val target = value and 0xFF
        return "0x${value.toString(16).uppercase().padStart(4, '0')}（来源烤盘=$source，目标烤盘=$target）"
    }

    private fun formatCommandHex(value: Int): String {
        return "0x${value.toString(16).uppercase().padStart(4, '0')}"
    }

    private fun formatPanMoveResultRegisterValue(value: Int): String {
        return when {
            value == AtomicActionCodes.IDLE -> "0x0000（空闲/等待接收）"
            value == AtomicActionCodes.RUNNING -> "0x0001（执行中）"
            value == AtomicActionCodes.COMPLETED -> "0x0002（搬移完成）"
            value >= AtomicActionCodes.PARAM_ERROR -> "0x${value.toString(16).uppercase().padStart(4, '0')}（${AtomicActionExecutor.messageOf(value)}）"
            else -> "0x${value.toString(16).uppercase().padStart(4, '0')}（未知搬移结果码）"
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
                val adminBusy = beginAdminBusy("测试下位机烤盘自清洁")
                binding.tvSelfCleanResult.text = "4F 当前自清洁为 no-op，不会真实机械清洁；准备记录烤盘 ${positionSn} 手动触发结果..."
                val source = "测试下位机页面手动触发"
                try {
                    AdminOperationLogger.i(
                        action = "执行烤盘自清洁",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_START,
                        detail = "烤盘位=$positionSn"
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
                } finally {
                    endAdminBusy(adminBusy)
                }
            }
        }

        binding.btnRunSellPlatformSelfClean.setOnClickListener {
            lifecycleScope.launch {
                val adminBusy = beginAdminBusy("测试下位机出肠台自清洁")
                binding.tvSelfCleanResult.text = "4F 当前自清洁为 no-op，不会真实机械清洁；准备记录出肠台手动触发结果..."
                val source = "测试下位机页面手动触发"
                try {
                    AdminOperationLogger.i(
                        action = "执行出肠台自清洁",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_START,
                        detail = "目标=出肠台"
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
                } finally {
                    endAdminBusy(adminBusy)
                }
            }
        }
    }

    private fun logTestSelfCleanFinished(
        report: KaoChangOperate.SelfCleanExecutionReport,
        startedAtMs: Long
    ) {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val actionName = if (report.targetLabel == "出肠台") "执行出肠台自清洁" else "执行烤盘自清洁"
        val logDetail =
            "目标=${report.targetLabel}，下位机自清洁指令=${report.command}，" +
                "动作状态寄存器201=${formatSelfCleanStatus201(report.status201)}，" +
                "结果消息=${report.message}，耗时=${elapsedMs}ms"
        if (report.success) {
            AdminOperationLogger.i(
                action = actionName,
                page = AdminOperationLogger.PAGE_TEST,
                result = AdminOperationLogger.RESULT_DONE,
                detail = logDetail
            )
        } else {
            AdminOperationLogger.w(
                action = actionName,
                page = AdminOperationLogger.PAGE_TEST,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = logDetail
            )
        }
    }

    private fun buildSelfCleanReportText(report: KaoChangOperate.SelfCleanExecutionReport): String {
        val status201Text = formatSelfCleanStatus201(report.status201)
        return "目标=${report.targetLabel}\n" +
            "提示：4F 当前自清洁为 no-op，不会真实机械清洁\n" +
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
                    LogUtils.d(
                        "测试下位机读寄存器成功：" +
                            "说明=$description，起始寄存器=$register，数量=$quantity，" +
                            "返回值=${values.joinToString()}，耗时=${elapsedMs}ms"
                    )
                    callback.invoke(values)
                } else {
                    AdminOperationLogger.w(
                        action = "读取寄存器",
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "说明=$description，起始寄存器=$register，数量=$quantity，失败原因=返回为空，耗时=${elapsedMs}ms"
                    )
                    showToast("读取${description}失败：返回为空")
                }
            } catch (e: Exception) {
                AdminOperationLogger.e(
                    action = "读取寄存器",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "说明=$description，起始寄存器=$register，数量=$quantity，失败原因=${e.message}"
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
                AdminOperationLogger.w(
                    action = "写入目标温度",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "区域=${input.area}，寄存器=${input.register}，输入值=$text，失败原因=目标温度必须是整数"
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
                AdminOperationLogger.w(
                    action = "写入目标温度",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "拒绝原因=当前正在自清洁"
                )
                showToast("当前正在自清洁，测试下位机写操作已禁用")
                return@launch
            }
            AdminOperationLogger.i(
                action = "写入目标温度",
                page = AdminOperationLogger.PAGE_TEST,
                result = AdminOperationLogger.RESULT_START,
                detail = parsedTargets.joinToString("；") { (input, value) ->
                    "区域=${input.area}，寄存器=${input.register}，写入值=$value"
                }
            )
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
            val allConfirmed = results.all { it.confirmed }
            val resultDetail = results.joinToString("；") { result ->
                "${result.description}，写入值=${result.writeValue}，回读值=${result.readBackValue ?: "读取失败"}，确认=${if (result.confirmed) "一致" else "不一致/失败"}"
            }
            if (allConfirmed) {
                AdminOperationLogger.i(
                    action = "写入目标温度",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = resultDetail
                )
            } else {
                AdminOperationLogger.w(
                    action = "写入目标温度",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = resultDetail
                )
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
        val writeResult = KaoChangOperate.writeSingleRegister(register, value)
        val readBack = KaoChangOperate.readHoldingRegisters(register, 1)?.firstOrNull()
        val elapsedMs = System.currentTimeMillis() - startMs
        val confirmed = writeResult.isSuccess && readBack == value
        LogUtils.d(
            "测试下位机写寄存器回读确认：" +
                "说明=$description，寄存器=$register，写入值=$value，写入状态=${writeResult.status}，" +
                "写入消息=${writeResult.message ?: "无"}，回读值=${readBack ?: "读取失败"}，确认一致=$confirmed，耗时=${elapsedMs}ms"
        )
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

    // ---- 参数调节 / 校准 ----

    private val highValueParamKeys = listOf(
        "stick_x", "stick_y", "stick_z",
        "lift_x", "lift_y", "lift_z",
        "push_ready_x", "push_ready_y", "push_ready_z",
        "discard_x", "discard_y", "discard_z",
        "push_to_x", "push_to_y",
        "push_back_x", "push_back_y",
        "exit_x", "exit_y",
        "throw_y",
        "x_push_ofs", "x_reverse_push_ofs"
    )

    // key -> 该字段的可编辑输入框（在 setupParamTuningListeners 中按 highValueParamKeys 动态构建）
    private val paramInputFields = mutableMapOf<String, EditText>()

    // 上次 readAll 读到的当前值快照，用于「只写改过的字段」改值检测
    private val lastReadParamValues = mutableMapOf<String, Float>()

    private fun setupParamTuningListeners() {
        buildParamInputRows()
        val targetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, calibrationTargetItems)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCalibrationTarget.adapter = targetAdapter
        binding.spinnerCalibrationTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                resetAdminIdleTimer("测试页选择校准目标")
                val item = calibrationTargetItems[position]
                binding.etCalibrationSlot.isEnabled = item.needsSlot
                if (!item.needsSlot) {
                    binding.etCalibrationSlot.setText("")
                }
                updateCalibrationTargetHint(item)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.etCalibrationSlot.isEnabled = calibrationTargetItems.first().needsSlot
        updateCalibrationTargetHint(calibrationTargetItems.first())

        binding.btnParamTuningReadAll.setOnClickListener {
            readAllParamTuning()
        }

        binding.btnParamTuningRefresh.setOnClickListener {
            readAllParamTuning()
        }

        binding.btnParamTuningWrite.setOnClickListener {
            writeChangedParamsWithGate()
        }

        binding.btnCalibrationMoveTo.setOnClickListener {
            val x = binding.etCalibrationX.text?.toString()?.trim()?.toFloatOrNull()
            val y = binding.etCalibrationY.text?.toString()?.trim()?.toFloatOrNull()
            val z = binding.etCalibrationZ.text?.toString()?.trim()?.toFloatOrNull()
            if (x == null || y == null || z == null) {
                showToast("请输入有效的 X/Y/Z 坐标")
                return@setOnClickListener
            }
            runParamTuningMutation("校准移动到输入XYZ") {
                paramTuningService.moveTo(x, y, z)
            }
        }

        binding.btnCalibrationCapture.setOnClickListener {
            val targetItem = binding.spinnerCalibrationTarget.selectedItem as? CalibrationTargetItem
            if (targetItem == null) {
                showToast("请选择校准目标")
                return@setOnClickListener
            }
            val slot = if (targetItem.needsSlot) {
                val slotText = binding.etCalibrationSlot.text?.toString()?.trim()
                val parsed = slotText?.toIntOrNull()
                if (parsed == null || parsed !in 1..33) {
                    showToast("烤位编号必须在 1~33 之间")
                    return@setOnClickListener
                }
                parsed
            } else {
                0
            }
            val target = targetItem.toTarget(slot)
            if (target is CalibrationTarget.BakewareSlot) {
                showBakewareCaptureConfirmDialog(target) {
                    runParamTuningMutation("捕获烤位${target.number}") {
                        paramTuningService.captureTarget(target)
                    }
                }
            } else {
                runParamTuningMutation("捕获${targetItem.label}") {
                    paramTuningService.captureTarget(target)
                }
            }
        }

        binding.btnCalibrationHome.setOnClickListener {
            runParamTuningMutation("校准回零") {
                paramTuningService.homeCalibration()
            }
        }

        binding.btnParamTuningSaveFlash.setOnClickListener {
            runParamTuningMutation(
                actionName = "保存Flash",
                resultView = binding.tvParamTuningSaveResult
            ) {
                paramTuningService.saveFlash()
            }
        }

        binding.btnParamTuningRestoreDefaults.setOnClickListener {
            runParamTuningMutation(
                actionName = "恢复默认到RAM",
                resultView = binding.tvParamTuningSaveResult
            ) {
                paramTuningService.restoreDefaultsToRam()
            }
        }
    }

    private fun updateCalibrationTargetHint(item: CalibrationTargetItem) {
        binding.etCalibrationZ.isEnabled = true
        if (item.supportsZ) {
            binding.etCalibrationZ.hint = "Z (mm)"
            binding.tvCalibrationTargetHint.text = "当前目标保存 X/Y/Z；捕获前请先移动到输入 XYZ。"
        } else {
            binding.etCalibrationZ.hint = "Z 仅用于移动定位"
            binding.tvCalibrationTargetHint.text = "当前目标仅保存 X/Y；Z 仍用于移动定位，捕获时不会保存 Z。"
        }
    }

    private fun readAllParamTuning() {
        lifecycleScope.launch {
            setParamTuningButtonsEnabled(false)
            binding.tvParamTuningStatus.text = "正在读取全部参数..."
            try {
                val result = withContext(Dispatchers.IO) {
                    paramTuningService.readAll()
                }
                when (result) {
                    is ParamTuningReadResult.Success -> {
                        val snapshot = result.snapshot
                        val latestConfig = AppConfig.getAppConfig()
                        val readOnlyWarning = if (latestConfig.onlineStatus != 3) {
                            "当前非维护中(onlineStatus=${latestConfig.onlineStatus})，仅允许查看，不允许写入/校准\n"
                        } else {
                            ""
                        }
                        binding.tvParamTuningStatus.text = buildString {
                            append(readOnlyWarning)
                            append("参数状态 1120=")
                            append(formatParamTuningCode(snapshot.status.paramStatus1120))
                            append("  参数版本=")
                            append(formatParamTuningCode(snapshot.status.paramVersion))
                            append("\n校准状态 1401=")
                            append(formatParamTuningCode(snapshot.calibration.status1401))
                            append("  最近错误 1416=")
                            append(formatParamTuningCode(snapshot.calibration.lastError1416))
                            append("\n当前 XYZ=(")
                            append(String.format("%.2f", snapshot.calibration.currentX))
                            append(", ")
                            append(String.format("%.2f", snapshot.calibration.currentY))
                            append(", ")
                            append(String.format("%.2f", snapshot.calibration.currentZ))
                            append(")")
                            append("\n提示：固件 PARAM_READ_FROM_FLASH=false，保存 Flash 不等于重启后自动加载")
                        }
                        val allParams = snapshot.upperParams + snapshot.lowerParams
                        prefillParamInputs(allParams)
                    }
                    is ParamTuningReadResult.Failure -> {
                        binding.tvParamTuningStatus.text = "读取失败：${result.message}"
                    }
                }
            } catch (e: Exception) {
                binding.tvParamTuningStatus.text = "读取异常：${e.message}"
                AdminOperationLogger.e(
                    action = "读取调参参数",
                    page = AdminOperationLogger.PAGE_TEST,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "失败原因=${e.message}"
                )
            } finally {
                setParamTuningButtonsEnabled(true)
            }
        }
    }

    /**
     * 按 highValueParamKeys 动态构建「标签 + 输入框」行，填充到 llParamTuningInputs 容器。
     * 标签取自参数定义的 displayName，初始无值，等 readAll 成功后预填。
     */
    private fun buildParamInputRows() {
        val container = binding.llParamTuningInputs
        container.removeAllViews()
        paramInputFields.clear()
        val labelWidthPx = (context?.resources?.displayMetrics?.density ?: 1f).let { (it * 140).toInt() }
        highValueParamKeys.forEach { key ->
            val displayName = LowerBoardParamDefinitions.byKey[key]?.displayName ?: key
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(labelWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "$displayName($key)"
                textSize = 13f
            }
            val edit = EditText(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                hint = "mm"
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                textSize = 13f
            }
            row.addView(label)
            row.addView(edit)
            container.addView(row)
            paramInputFields[key] = edit
        }
        // 这些输入框是动态创建的（晚于 onViewCreated 里的 registerAdminTextInputs(view)），
        // 单独注册一次，保证现场输入数值时不会触发管理页空闲超时退出。
        registerAdminTextInputs(container)
    }

    /**
     * readAll 成功后用读到的当前值预填输入框，并更新改值检测用的快照缓存。
     */
    private fun prefillParamInputs(allParams: Map<String, ParamValue>) {
        lastReadParamValues.clear()
        highValueParamKeys.forEach { key ->
            val edit = paramInputFields[key] ?: return@forEach
            when (val param = allParams[key]) {
                is ParamValue.Float32Value -> {
                    val displayText = String.format("%.2f", param.value)
                    edit.setText(displayText)
                    // 缓存按显示文本回解析的值，使未改动字段在改值检测中精确相等，避免 %.2f 取整误差误触发写入
                    lastReadParamValues[key] = displayText.toFloat()
                }
                else -> {
                    // 高价值列表当前全为 float 字段；理论不会走到这里
                    edit.setText("")
                }
            }
        }
    }

    /**
     * 写入参数修改：走现有 runParamTuningMutation 双层门控（一次刷新云端 + 门控检查），
     * 通过后在同一 lambda 内顺序写多个被修改的字段。
     */
    private fun writeChangedParamsWithGate() {
        if (lastReadParamValues.isEmpty()) {
            showToast("请先点击「读取全部参数」再写入")
            binding.tvParamTuningWriteResult.text = "未写入：请先读取一次参数"
            return
        }
        runParamTuningMutation(
            actionName = "写入参数修改",
            resultView = binding.tvParamTuningWriteResult,
            action = { writeChangedParams() }
        )
    }

    /**
     * 在门控通过后顺序写入「能解析且与上次读取值不同」的字段。
     * 任一字段失败立即停止并点名失败字段。全部成功返回 Success。
     */
    private suspend fun writeChangedParams(): ParamTuningResult {
        val toWrite = withContext(Dispatchers.Main) {
            collectChangedParamInputs()
        }
        if (toWrite is ChangedParamCollect.InvalidInput) {
            return ParamTuningResult.Failure("字段「${toWrite.key}」输入非法：${toWrite.text}")
        }
        val changes = (toWrite as ChangedParamCollect.Ok).changes
        if (changes.isEmpty()) {
            return ParamTuningResult.Failure("没有需要写入的字段：所有输入框为空或与当前值相同")
        }
        val written = mutableListOf<String>()
        for ((key, value) in changes) {
            val result = paramTuningService.writeParam(key, ParamInput.FloatInput(value))
            if (result is ParamTuningResult.Failure) {
                // 失败统一由 runParamTuningMutation 落 RESULT_FAIL 日志（message 已点名字段），此处只把已写字段带回 message
                return ParamTuningResult.Failure(
                    "字段「$key」写入失败：${result.message}（已写入：${written.joinToString().ifEmpty { "无" }}）",
                    result.code
                )
            }
            written += key
            lastReadParamValues[key] = value
        }
        LogUtils.i("【下位机测试】写入参数修改成功：成功写入字段=${written.joinToString()}")
        return ParamTuningResult.Success
    }

    private sealed class ChangedParamCollect {
        data class Ok(val changes: List<Pair<String, Float>>) : ChangedParamCollect()
        data class InvalidInput(val key: String, val text: String) : ChangedParamCollect()
    }

    /**
     * 读取各输入框，筛出「能解析成数值且与上次读取值不同」的字段。
     * 输入框为空 -> 跳过；输入非数字 -> 直接判非法（点名字段）。
     */
    private fun collectChangedParamInputs(): ChangedParamCollect {
        val changes = mutableListOf<Pair<String, Float>>()
        highValueParamKeys.forEach { key ->
            val edit = paramInputFields[key] ?: return@forEach
            val text = edit.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                return@forEach
            }
            val value = text.toFloatOrNull()
                ?: return ChangedParamCollect.InvalidInput(key, text)
            val lastValue = lastReadParamValues[key]
            if (lastValue != null && lastValue == value) {
                return@forEach
            }
            changes += key to value
        }
        return ChangedParamCollect.Ok(changes)
    }

    /**
     * Dual-layer gate for all param tuning mutations.
     *
     * Outer: refreshRemoteDeviceState (async callback, failure = hard reject, never fall back to cache)
     * Inner: ParamTuningMaintenanceGate.check() = device state (onlineStatus/errorStatus/inspectionMode/moveStatus)
     *        + register-level idle check (delegates to LowerBoardMaintenancePolicy.canRunMaintenance)
     */
    private fun runParamTuningMutation(
        actionName: String,
        resultView: TextView = binding.tvCalibrationResult,
        action: suspend () -> ParamTuningResult
    ) {
        setParamTuningButtonsEnabled(false)
        resultView.text = "正在同步云端设备状态..."

        DataManagementAPI.refreshRemoteDeviceState(logPrefix = "测试页调参前同步") { result ->
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread

                if (!result.success) {
                    AdminOperationLogger.w(
                        action = actionName,
                        page = AdminOperationLogger.PAGE_TEST,
                        result = AdminOperationLogger.RESULT_REJECT,
                        detail = "拒绝原因=同步云端设备状态失败"
                    )
                    resultView.text = "同步云端设备状态失败，请稍后重试"
                    showToast("同步云端设备状态失败，请稍后重试")
                    setParamTuningButtonsEnabled(true)
                    return@runOnUiThread
                }

                val latestConfig = AppConfig.getAppConfig()

                lifecycleScope.launch {
                    val guard = withContext(Dispatchers.IO) { readLowerBoardMaintenanceGuard() }
                    val decision = ParamTuningMaintenanceGate.check(
                        onlineStatus = latestConfig.onlineStatus,
                        errorStatus = latestConfig.errorStatus,
                        inspectionMode = latestConfig.inspectionMode,
                        moveStatus = latestConfig.moveStatus,
                        status201 = guard.status201 ?: -1,
                        moveResult203 = guard.moveResult203 ?: -1,
                        atomicResultValues = guard.atomicResults.filterValues { it != null }.mapValues { it.value!! }
                    )
                    if (!decision.allowed) {
                        AdminOperationLogger.w(
                            action = actionName,
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=${decision.reason}，检查结果=${guard.summary()}"
                        )
                        resultView.text = "操作被拒绝：${decision.reason}"
                        showToast(decision.reason)
                        setParamTuningButtonsEnabled(true)
                        return@launch
                    }

                    val adminBusy = beginAdminBusy("测试下位机调参:$actionName")
                    try {
                        AdminOperationLogger.i(
                            action = actionName,
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_START,
                            detail = "检查结果=${guard.summary()}"
                        )
                        resultView.text = "执行中：$actionName..."
                        val tuningResult = withContext(Dispatchers.IO) { action() }
                        when (tuningResult) {
                            is ParamTuningResult.Success -> {
                                AdminOperationLogger.i(
                                    action = actionName,
                                    page = AdminOperationLogger.PAGE_TEST,
                                    result = AdminOperationLogger.RESULT_DONE,
                                    detail = "操作成功"
                                )
                                val successMessage = buildSuccessMessage(actionName)
                                resultView.text = successMessage
                            }
                            is ParamTuningResult.Failure -> {
                                AdminOperationLogger.w(
                                    action = actionName,
                                    page = AdminOperationLogger.PAGE_TEST,
                                    result = AdminOperationLogger.RESULT_FAIL,
                                    detail = "失败原因=${tuningResult.message}，code=${tuningResult.code ?: "无"}"
                                )
                                resultView.text = "$actionName 失败：${tuningResult.message}"
                            }
                        }
                    } catch (e: Exception) {
                        AdminOperationLogger.e(
                            action = actionName,
                            page = AdminOperationLogger.PAGE_TEST,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "异常=${e.message}"
                        )
                        resultView.text = "$actionName 异常：${e.message}"
                    } finally {
                        endAdminBusy(adminBusy)
                        setParamTuningButtonsEnabled(true)
                    }
                }
            }
        }
    }

    private fun buildSuccessMessage(actionName: String): String {
        return when {
            actionName == "保存Flash" ->
                "$actionName 完成\n提示：固件 PARAM_READ_FROM_FLASH=false，保存 Flash 不等于重启后自动加载"
            actionName.startsWith("恢复默认") ->
                "$actionName 完成\n提示：仅恢复 RAM 中参数为出厂默认值，需再执行「保存 Flash」才能持久化"
            actionName == "校准回零" ->
                "$actionName 完成\n提示：固件回零顺序为先 Z 后 XY"
            actionName == "写入参数修改" ->
                "$actionName 完成\n提示：仅改写 RAM，需再执行「保存 Flash」才能持久化"
            else -> "$actionName 完成"
        }
    }

    private fun showBakewareCaptureConfirmDialog(target: CalibrationTarget.BakewareSlot, onConfirm: () -> Unit) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle("烤位捕获确认")
            .setMessage(
                "即将捕获烤位 ${target.number} 的当前位置。\n\n" +
                    "捕获任一烤位会重写全局 Z 与 X 公式，影响全部 33 个烤位，" +
                    "并改写本排 Y 坐标。\n\n" +
                    "请确认机械臂已在正确位置后再继续。"
            )
            .setPositiveButton("确认捕获") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setParamTuningButtonsEnabled(enabled: Boolean) {
        binding.btnParamTuningReadAll.isEnabled = enabled
        binding.btnParamTuningRefresh.isEnabled = enabled
        binding.btnParamTuningWrite.isEnabled = enabled
        binding.btnCalibrationMoveTo.isEnabled = enabled
        binding.btnCalibrationCapture.isEnabled = enabled
        binding.btnCalibrationHome.isEnabled = enabled
        binding.btnParamTuningSaveFlash.isEnabled = enabled
        binding.btnParamTuningRestoreDefaults.isEnabled = enabled
    }

    private fun formatParamTuningCode(code: Int): String {
        return "0x${code.toString(16).uppercase().padStart(4, '0')}"
    }
}
