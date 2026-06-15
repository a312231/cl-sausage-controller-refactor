package cn.niuyannet.kaochang.android.fragments

import android.annotation.SuppressLint
import android.R as AndroidR
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import cn.niuyannet.kaochang.android.adapter.KaoPanAdapter
import cn.niuyannet.kaochang.android.databinding.FragmentMaintenanceBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.ConsumableStateResponseParser
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.FaultHeatPauseManager
import cn.niuyannet.kaochang.android.services.MaintenanceModbusRecoveryConnector
import cn.niuyannet.kaochang.android.services.BusinessTimeWindowHelper
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.ManualPanResetRuntimeStateHelper
import cn.niuyannet.kaochang.android.services.ManualRecoveryGatePolicy
import cn.niuyannet.kaochang.android.services.MoveStatusRecoveryPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupManualResidueClearPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryResidueEvidence
import cn.niuyannet.kaochang.android.ui.AdminOperationLogger
import cn.niuyannet.kaochang.android.ui.LogViewActivity
import cn.niuyannet.kaochang.android.ui.beginAdminBusy
import cn.niuyannet.kaochang.android.ui.endAdminBusy
import cn.niuyannet.kaochang.android.ui.resetAdminIdleTimer
import cn.niuyannet.kaochang.android.updates.AppUpdateManager
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.MaintenanceUiRefreshBridge
import com.blankj.utilcode.util.ToastUtils
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class MaintenanceFragment : Fragment() {
    private var _binding: FragmentMaintenanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var kaoPanAdapter: KaoPanAdapter
    private var refreshJob: Job? = null

    private val status = AtomicBoolean(true)
    private val manualRecoveryInProgress = AtomicBoolean(false)

    private var isAlgorithmCoolingDown = false
    private var ignoreAlgorithmSwitchChange = false
    private var ignoreInspectionSwitchChange = false
    private var algorithmCooldownJob: Job? = null
    private var clearPanCooldownJob: Job? = null
    private var resetPanCooldownJob: Job? = null
    private var algorithmSwitchFallbackTickCount = 0
    private val maintenanceUiRefreshCallback: (String) -> Unit = { reason ->
        activity?.runOnUiThread {
            if (!isAdded || _binding == null) {
                return@runOnUiThread
            }
            syncAlgorithmSwitchFromRuntimeState(
                reason = "远端事件驱动同步:$reason",
                forceLog = true
            )
            syncInspectionSwitchFromRuntimeState(
                reason = "远端事件驱动同步:$reason",
                forceLog = true
            )
        }
    }

    companion object {
        private const val MAINTENANCE_ACTION_COOLDOWN_SECONDS = 15
        private const val ALGORITHM_SWITCH_FALLBACK_SYNC_INTERVAL_SECONDS = 5
        private const val MANUAL_ACTION_IDLE_TIMEOUT_MS = 15_000L
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0（待投运）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    private fun modeText(value: Int): String = when (value) {
        1 -> "1（低并发）"
        2 -> "2（高并发）"
        else -> "$value（未知）"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0（无过渡态）"
        1 -> "1（低转高过渡）"
        2 -> "2（高转低过渡）"
        else -> "$value（未知）"
    }

    private fun residualDrainText(value: Int): String = when (value) {
        0 -> "0（无残留）"
        1 -> "1（高并发残留消化中）"
        else -> "$value（未知）"
    }

    private fun moveStatusText(value: Int): String = when (value) {
        0 -> "0（机械臂空闲）"
        1 -> "1（机械臂运动中）"
        5 -> "5（自清洁中）"
        else -> "$value（未知）"
    }

    private fun isEnableText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    private fun inspectionModeText(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（检修中）"
        else -> "$value（未知）"
    }

    private fun syncAlgorithmSwitchFromRuntimeState(reason: String, forceLog: Boolean = false) {
        val config = AppConfig.getAppConfig()
        val actualChecked = config.isEnable == 1
        val uiChecked = binding.swKzAlgorithm.isChecked
        val memoryChecked = KaoChangAlgorithm.isEnabled
        if (!forceLog && uiChecked == actualChecked) {
            return
        }

        setAlgorithmSwitchCheckedSilently(actualChecked)
        val message =
            "【维护操作】同步烤肠算法开关UI：" +
                "reason=$reason，" +
                "uiChecked=$uiChecked->$actualChecked，" +
                "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
                "algorithmMemoryEnabled（算法内存态）=$memoryChecked，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "moveStatus（机械动作状态）=${moveStatusText(config.moveStatus)}"
        when {
            uiChecked != actualChecked -> LogUtils.i(message)
            forceLog -> LogUtils.d(message)
        }
    }

    private fun syncInspectionSwitchFromRuntimeState(reason: String, forceLog: Boolean = false) {
        val config = AppConfig.getAppConfig()
        val actualChecked = config.inspectionMode == 1
        val uiChecked = binding.swInspectionMode.isChecked
        if (!forceLog && uiChecked == actualChecked) {
            return
        }

        setInspectionSwitchCheckedSilently(actualChecked)
        val message =
            "【检修模式】同步检修模式开关UI：" +
                "reason=$reason，" +
                "uiChecked=$uiChecked->$actualChecked，" +
                "inspectionMode（检修模式）=${inspectionModeText(config.inspectionMode)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}"
        when {
            uiChecked != actualChecked -> LogUtils.i(message)
            forceLog -> LogUtils.d(message)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMaintenanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        KaoChangAlgorithm.restoreRoastAlgorithmState()
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName
        binding.versionNo.text = "版本号 v-$versionName"
        binding.tvAppUpdateStatus.text = "更新：${AppUpdateManager.lastStatusText()}"
        MaintenanceUiRefreshBridge.register(maintenanceUiRefreshCallback)
        setupGrid()
        setupButtons()
        refreshConsumableState()
        syncAlgorithmSwitchFromRuntimeState("维护页初始化", forceLog = true)
        syncInspectionSwitchFromRuntimeState("维护页初始化", forceLog = true)
        updateManualSupplementConfirmButtonVisibility()
    }

    private fun clearRuntimeStateAfterManualPanReset(reason: String) {
        val config = AppConfig.getAppConfig()
        val resetResult = ManualPanResetRuntimeStateHelper.apply(config)
        if (!resetResult.changed) {
            LogUtils.d(
                "【维护操作】$reason，运行态已完成必要收口，保持原并发模式：" +
                    "available=0，availableCountdownLatched=0，" +
                    "modeType（并发模式）=${modeText(config.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
                    "highConcurrencyResidualDrain（高并发残留消化态）=${residualDrainText(config.highConcurrencyResidualDrain)}"
            )
            return
        }

        AppConfig.saveAppConfig(config)
        LogUtils.i(
            "【维护操作】$reason，已重置运行态：" +
                "available（二维码页预计时间）=${resetResult.oldAvailable}->0，" +
                "availableCountdownLatched（二维码页预计时间锁存标记）=${resetResult.oldAvailableCountdownLatched}->0，" +
                "modeType（并发模式）保持=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(resetResult.oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "highConcurrencyResidualDrain（高并发残留消化态）=${residualDrainText(resetResult.oldHighConcurrencyResidualDrain)}->${residualDrainText(config.highConcurrencyResidualDrain)}"
        )
        val runtimePublishOk = SendServerHelper.publishServiceUpdateStatus()
        val availablePublishOk = SendServerHelper.publishAvailableState("maintenance:$reason")
        if (!runtimePublishOk || !availablePublishOk) {
            LogUtils.w(
                "【维护操作】$reason，MQTT 发送队列拒绝，回退 HTTP 补偿：" +
                    "runtimePublishOk=$runtimePublishOk，availablePublishOk=$availablePublishOk"
            )
            DataManagementAPI.uploadDeviceInfo()
        }
    }

    private fun setMaintenanceActionButtonsEnabled(enabled: Boolean) {
        binding.btnCz.isEnabled = enabled
        binding.btnClearAll.isEnabled = enabled
        binding.btnRefillStickFull.isEnabled = enabled
        binding.btnClearWasteBucket.isEnabled = enabled
        binding.swKzAlgorithm.isEnabled = enabled
    }

    private fun setMaintenanceButtonsEnabled(enabled: Boolean) {
        binding.btnCz.isEnabled = enabled
        binding.btnClearAll.isEnabled = enabled
        binding.btnRefillStickFull.isEnabled = enabled
        binding.btnClearWasteBucket.isEnabled = enabled
    }

    private fun updateManualSupplementConfirmButtonVisibility() {
        binding.btnConfirmSupplementSafety.visibility =
            if (KaoChangOperate.pendingSupplementBatchRequiresManualConfirm() != null) View.VISIBLE else View.GONE
    }

    private fun setConsumableManualButtonsEnabled(enabled: Boolean) {
        binding.btnRefillStickFull.isEnabled = enabled
        binding.btnClearWasteBucket.isEnabled = enabled
    }

    private fun handleConsumableManualAction(
        actionName: String,
        confirmMessage: String,
        successMessage: String,
        apiCall: (((code: Int, content: String?) -> Unit)?) -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(actionName)
            .setMessage(confirmMessage)
            .setPositiveButton("确认") { _, _ ->
                AdminOperationLogger.i(
                    action = actionName,
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_START,
                    detail = "deviceCode=${AppConfig.getDeviceId()}，说明=现场耗材处理确认"
                )
                setConsumableManualButtonsEnabled(false)
                binding.tvCooldownHint.text = "正在上报$actionName"
                apiCall { code, content ->
                    activity?.runOnUiThread {
                        if (!isAdded || _binding == null) {
                            return@runOnUiThread
                        }
                        setConsumableManualButtonsEnabled(true)
                        binding.tvCooldownHint.text = ""
                        if (code == 0) {
                            AdminOperationLogger.i(
                                action = actionName,
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_DONE,
                                detail = "deviceCode=${AppConfig.getDeviceId()}"
                            )
                            ToastUtils.showLong(successMessage)
                            refreshConsumableState()
                        } else {
                            AdminOperationLogger.w(
                                action = actionName,
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "deviceCode=${AppConfig.getDeviceId()}，response=${content ?: "无"}"
                            )
                            ToastUtils.showLong("$actionName 失败，请稍后重试")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshConsumableState() {
        binding.tvConsumableState.text = "耗材：同步中"
        DataManagementAPI.refreshConsumableState { code, content ->
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) {
                    return@runOnUiThread
                }
                if (code != 0) {
                    binding.tvConsumableState.text = "耗材状态暂不可用"
                    return@runOnUiThread
                }
                val result = ConsumableStateResponseParser.parse(content)
                binding.tvConsumableState.text = ConsumableStateResponseParser.buildDisplayText(result.states)
            }
        }
    }

    private fun setAlgorithmSwitchCheckedSilently(checked: Boolean) {
        ignoreAlgorithmSwitchChange = true
        binding.swKzAlgorithm.isChecked = checked
        ignoreAlgorithmSwitchChange = false
    }

    private fun setInspectionSwitchCheckedSilently(checked: Boolean) {
        ignoreInspectionSwitchChange = true
        binding.swInspectionMode.isChecked = checked
        ignoreInspectionSwitchChange = false
    }

    private fun disableAlgorithmForMaintenance(reason: String) {
        if (KaoChangAlgorithm.isEnabled || AppConfig.getAppConfig().isEnable == 1) {
            LogUtils.i("【维护操作】$reason，立即关闭烤制算法并保持关闭，防止继续自动补肠")
            KaoChangAlgorithm.disableRoastAlgorithm(reason = reason)
        } else {
            LogUtils.i("【维护操作】$reason，烤制算法当前已关闭")
        }
        syncAlgorithmSwitchFromRuntimeState("维护强操作后同步:$reason", forceLog = true)
    }

    private fun finishMaintenanceAction() {
        binding.tvCooldownHint.text = ""
        syncAlgorithmSwitchFromRuntimeState("维护动作结束", forceLog = true)
    }

    private fun startMaintenanceCooldown(
        target: String,
        onFinish: () -> Unit
    ): Job {
        return startCountdown(
            seconds = MAINTENANCE_ACTION_COOLDOWN_SECONDS,
            onTick = { remaining ->
                when (target) {
                    "clear" -> binding.btnClearAll.text = "冷却${String.format("%2d", remaining)}秒"
                    "reset" -> binding.btnCz.text = "冷却${String.format("%2d", remaining)}秒"
                }
            },
            onFinish = onFinish
        )
    }

    private suspend fun tryRecoverIdleMoveStatus(reason: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus == 0) {
            return true
        }
        val statusReadings = withContext(Dispatchers.IO) {
            if (!VMModbusHelper.connectStatus()) {
                listOf(-1)
            } else {
                val first = VMModbusHelper.readHoldingRegisters(
                    KaoChangOperate.modbus_address,
                    KaoChangOperate.action_address_status
                )
                delay(500L)
                val second = VMModbusHelper.readHoldingRegisters(
                    KaoChangOperate.modbus_address,
                    KaoChangOperate.action_address_status
                )
                listOf(first, second)
            }
        }
        if (MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(statusReadings)) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【维护操作】检测到本地机械状态与下位机空闲状态不一致，已自动收口：" +
                    "reason=$reason，moveStatus（机械动作状态）=$oldMoveStatus->0，status201连续读数=$statusReadings"
            )
            return true
        }
        LogUtils.w(
            "【维护操作】跳过本地机械状态自动收口：" +
                "reason=$reason，moveStatus（机械动作状态）=${config.moveStatus}，status201连续读数=$statusReadings"
        )
        return false
    }

    private suspend fun evaluateManualRecoveryHeating(
        targetOnlineStatus: Int
    ): ManualRecoveryGatePolicy.Decision {
        return withContext(Dispatchers.IO) {
            if (!VMModbusHelper.connectStatus()) {
                return@withContext ManualRecoveryGatePolicy.evaluate(
                    modbusConnected = false,
                    actionGroupReady = false,
                    legacyActionStatus201 = -1,
                    moveStatus = AppConfig.getAppConfig().moveStatus,
                    panMoveResult203 = null,
                    targetOnlineStatus = targetOnlineStatus
                )
            }

            val actionGroupReady = KaoChangOperate.recoverActionGroupStateAfterModbusReady("维护页恢复正常状态前检查")
            val currentConfig = AppConfig.getAppConfig()
            val legacyActionStatus201 = VMModbusHelper.readHoldingRegisters(
                KaoChangOperate.modbus_address,
                KaoChangOperate.action_address_status
            )
            val panMoveResult203 = if (currentConfig.moveStatus == 3) {
                VMModbusHelper.readHoldingRegisters(
                    KaoChangOperate.modbus_address,
                    KaoChangOperate.action_address_move_result
                )
            } else {
                null
            }

            ManualRecoveryGatePolicy.evaluate(
                modbusConnected = true,
                actionGroupReady = actionGroupReady,
                legacyActionStatus201 = legacyActionStatus201,
                moveStatus = currentConfig.moveStatus,
                panMoveResult203 = panMoveResult203,
                targetOnlineStatus = targetOnlineStatus
            )
        }
    }

    private suspend fun applyManualRecoveredState(
        config: AppConfigBean,
        previousOnlineStatus: Int,
        previousErrorStatus: Int,
        previousIsEnable: Int,
        recoveredOnlineStatus: Int,
        recoveryDecision: ManualRecoveryGatePolicy.Decision
    ) {
        config.errorStatus = 0
        config.errorCode = null
        config.errorCategory = null
        config.errorReason = null
        config.onlineStatus = recoveredOnlineStatus
        config.isEnable = 0
        withContext(Dispatchers.IO) {
            AppConfig.saveAppConfig(config)
            KaoChangOperate.stopHeatingForFault("维护页恢复正常状态后保持算法关闭，等待现场手动开启算法")
        }
        val runtimePublishOk = withContext(Dispatchers.IO) {
            SendServerHelper.publishServiceUpdateStatus("runtime_state:manual_recovery")
        }
        if (!runtimePublishOk) {
            LogUtils.w(
                "【维护操作】恢复正常状态 MQTT 发送队列拒绝，回退 HTTP 补偿：" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${config.errorStatus}"
            )
            withContext(Dispatchers.IO) {
                DataManagementAPI.uploadDeviceInfo(source = "runtime_state:manual_recovery")
            }
        }
        ToastUtils.showLong("设备已恢复正常状态；请现场确认后手动开启烤制算法")
        AdminOperationLogger.w(
            action = "恢复正常状态",
            page = AdminOperationLogger.PAGE_PAN,
            result = AdminOperationLogger.RESULT_DONE,
            detail = "设备营业状态=${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(config.onlineStatus)}，" +
                "设备故障状态=$previousErrorStatus->${config.errorStatus}（正常），" +
                "烤肠算法开关=${isEnableText(previousIsEnable)}->${isEnableText(config.isEnable)}，" +
                "恢复闸门=${recoveryDecision.reason}，后续动作=现场手动开启烤制算法"
        )
    }

    private suspend fun handleManualRecoveryBlocked(
        targetOnlineStatus: Int,
        recoveryDecision: ManualRecoveryGatePolicy.Decision,
        allowManualResidueClearPrompt: Boolean
    ) {
        val evidence = withContext(Dispatchers.IO) {
            KaoChangOperate.buildActionGroupRecoveryResidueEvidence("维护页恢复正常状态被恢复闸门拦截")
        }
        val clearDecision = ActionGroupManualResidueClearPolicy.evaluate(evidence)
        AdminOperationLogger.w(
            action = "恢复正常状态",
            page = AdminOperationLogger.PAGE_PAN,
            result = AdminOperationLogger.RESULT_REJECT,
            detail = "拒绝原因=${recoveryDecision.reason}，目标设备营业状态=${onlineStatusText(targetOnlineStatus)}，" +
                "人工清理动作组残留允许=${clearDecision.canShowManualClear}，人工清理判断=${clearDecision.reason}，" +
                "动作组证据=${evidence.summary()}"
        )
        if (allowManualResidueClearPrompt && clearDecision.canShowManualClear) {
            showConfirmClearActionGroupResidueDialog(evidence)
        } else {
            ToastUtils.showLong("设备恢复检查未通过：${recoveryDecision.reason}")
        }
    }

    private fun showConfirmClearActionGroupResidueDialog(evidence: ActionGroupRecoveryResidueEvidence) {
        val session = evidence.session
        val message = buildString {
            append("检测到上位机存在补肠动作组本地残留，但下位机 300/400 结果寄存器均为空闲。\n\n")
            append("仅在现场已确认下位机复位完成、机械臂/升降台/夹爪空闲、烤盘和肠箱实物状态已核对、售卖口无卡肠时继续。\n\n")
            append("sessionId=${session?.sessionId ?: "无"}\n")
            append("type=${session?.type ?: "无"}\n")
            append("sourceNo=${session?.sourceNo ?: "无"}，targetNo=${session?.targetNo ?: "无"}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("确认清理本地动作组残留")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清理本地动作组残留") { _, _ ->
                retryManualRecoveryAfterClearingActionGroupResidue()
            }
            .show()
    }

    private fun retryManualRecoveryAfterClearingActionGroupResidue() {
        if (!manualRecoveryInProgress.compareAndSet(false, true)) {
            ToastUtils.showShort("正在恢复设备状态，请稍候")
            return
        }
        binding.btnErroStatus.isEnabled = false
        binding.tvCooldownHint.text = "正在清理动作组残留并恢复设备状态"
        lifecycleScope.launch {
            try {
                val cleared = withContext(Dispatchers.IO) {
                    KaoChangOperate.confirmClearActionGroupRecoveryResidue("维护页人工确认清理动作组残留")
                }
                if (!cleared) {
                    ToastUtils.showLong("动作组残留清理条件已变化，请重新检查下位机状态")
                    return@launch
                }
                val config = AppConfig.getAppConfig()
                val previousOnlineStatus = config.onlineStatus
                val previousErrorStatus = config.errorStatus
                val previousIsEnable = config.isEnable
                val recoveredOnlineStatus = resolveManualRecoveredOnlineStatus(config)
                val recoveryDecision = evaluateManualRecoveryHeating(recoveredOnlineStatus)
                if (!recoveryDecision.canRecoverDeviceStatus) {
                    handleManualRecoveryBlocked(
                        targetOnlineStatus = recoveredOnlineStatus,
                        recoveryDecision = recoveryDecision,
                        allowManualResidueClearPrompt = false
                    )
                    return@launch
                }
                applyManualRecoveredState(
                    config = config,
                    previousOnlineStatus = previousOnlineStatus,
                    previousErrorStatus = previousErrorStatus,
                    previousIsEnable = previousIsEnable,
                    recoveredOnlineStatus = recoveredOnlineStatus,
                    recoveryDecision = recoveryDecision
                )
            } finally {
                manualRecoveryInProgress.set(false)
                binding.btnErroStatus.isEnabled = true
                if (binding.tvCooldownHint.text.toString() == "正在清理动作组残留并恢复设备状态") {
                    binding.tvCooldownHint.text = ""
                }
            }
        }
    }

    private fun clearIdleMoveStatusAfterManualRecovery(reason: String) {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus == 0) {
            return
        }
        val oldMoveStatus = config.moveStatus
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
        LogUtils.w(
            "【维护操作】$reason，已清理本地机械状态残留：" +
                "moveStatus（机械动作状态）=$oldMoveStatus->0（空闲）"
        )
    }

    private suspend fun waitUntilMachineIdleOrTimeout(
        reason: String,
        waitingText: String,
        update: ((String) -> Unit)? = null,
        timeoutMs: Long = MANUAL_ACTION_IDLE_TIMEOUT_MS
    ): Boolean {
        val result = withTimeoutOrNull(timeoutMs) {
            while (AppConfig.getAppConfig().moveStatus != 0) {
                update?.invoke(waitingText)
                if (tryRecoverIdleMoveStatus(reason)) {
                    break
                }
                delay(1_000L)
            }
            true
        }
        return result == true && AppConfig.getAppConfig().moveStatus == 0
    }

    private fun startCountdown(
        seconds: Int,
        onTick: (Int) -> Unit,
        onFinish: () -> Unit
    ): Job {
        return lifecycleScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                onTick(remaining)
                delay(1_000L)
                remaining--
            }
            onFinish()
        }
    }

    private fun setupButtons() {
        binding.btnRefillStickFull.setOnClickListener {
            handleConsumableManualAction(
                actionName = "补满竹签",
                confirmMessage = "请确认已现场补满竹签到容量上限。\n\n确认后系统会把竹签库存重置为后端配置容量，默认 500。",
                successMessage = "竹签已记录为补满",
                apiCall = DataManagementAPI::refillStickFull
            )
        }

        binding.btnClearWasteBucket.setOnClickListener {
            handleConsumableManualAction(
                actionName = "清空垃圾桶",
                confirmMessage = "请确认已现场清空垃圾桶。\n\n确认后系统会把弃肠计数重置为 0。",
                successMessage = "垃圾桶已记录为清空",
                apiCall = DataManagementAPI::clearWasteBucket
            )
        }

        binding.btnClearAll.setOnClickListener {
            if (clearPanCooldownJob?.isActive == true) return@setOnClickListener

            AdminOperationLogger.i(
                action = "清空烤盘",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_START,
                detail = "说明=将关闭烤肠算法并等待机械臂空闲"
            )
            setMaintenanceActionButtonsEnabled(false)
            binding.tvCooldownHint.text = "正在清空烤盘"
            disableAlgorithmForMaintenance("点击清空烤盘")

            clearPanCooldownJob = lifecycleScope.launch {
                val adminBusy = beginAdminBusy("维护页清空烤盘")
                try {
                    val idleReady = waitUntilMachineIdleOrTimeout(
                        reason = "维护页点击清空烤盘等待空闲",
                        waitingText = "等待机械动作完成后清空烤盘",
                        update = {
                            binding.tvCooldownHint.text = it
                        }
                    )
                    if (!idleReady) {
                        AdminOperationLogger.w(
                            action = "清空烤盘",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "失败原因=等待机械臂空闲超时"
                        )
                        binding.tvCooldownHint.text = ""
                        setMaintenanceActionButtonsEnabled(true)
                        ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                        clearPanCooldownJob = null
                        return@launch
                    }
                    KaoPanHelper.clearKaoPan()
                    KaoPanHelper.init()
                    FaultHeatPauseManager.clearFaultHeatPauseSilently("维护页点击清空烤盘")
                    KaoChangOperate.clearPendingSupplementBatchIntent("维护页点击清空烤盘")
                    KaoChangOperate.stopAllHeatingIfNoSausage("维护页点击清空烤盘", force = true)
                    clearRuntimeStateAfterManualPanReset("点击清空烤盘")
                    AdminOperationLogger.i(
                        action = "清空烤盘",
                        page = AdminOperationLogger.PAGE_PAN,
                        result = AdminOperationLogger.RESULT_DONE,
                        detail = "烤肠算法开关=关闭，说明=进入15秒防重复操作冷却"
                    )
                    finishMaintenanceAction()
                    clearPanCooldownJob = startMaintenanceCooldown(target = "clear") {
                        setMaintenanceActionButtonsEnabled(true)
                        binding.btnClearAll.text = "清空烤盘"
                        binding.tvCooldownHint.text = ""
                        clearPanCooldownJob = null
                    }
                } finally {
                    endAdminBusy(adminBusy)
                }
            }
        }

        binding.swKzAlgorithm.setOnCheckedChangeListener { buttonView, isChecked ->
            fun restoreSwitchControls() {
                binding.tvCooldownHint.text = ""
                buttonView.isEnabled = true
                if (clearPanCooldownJob?.isActive != true) {
                    binding.btnClearAll.isEnabled = true
                }
                if (resetPanCooldownJob?.isActive != true) {
                    binding.btnCz.isEnabled = true
                }
            }

            fun startAlgorithmSwitchCooldown() {
                isAlgorithmCoolingDown = true
                buttonView.isEnabled = false
                setMaintenanceButtonsEnabled(false)
                algorithmCooldownJob?.cancel()
                algorithmCooldownJob = lifecycleScope.launch {
                    val idleReady = waitUntilMachineIdleOrTimeout(
                        reason = "维护页切换算法后冷却等待空闲",
                        waitingText = "等待机械动作完成",
                        update = { binding.tvCooldownHint.text = it }
                    )
                    if (!idleReady) {
                        LogUtils.w("【维护操作】算法开关冷却前等待机械空闲超时，结束冷却并恢复操作控件")
                        binding.tvCooldownHint.text = ""
                        buttonView.isEnabled = true
                        isAlgorithmCoolingDown = false
                        if (clearPanCooldownJob?.isActive != true) {
                            binding.btnClearAll.isEnabled = true
                        }
                        if (resetPanCooldownJob?.isActive != true) {
                            binding.btnCz.isEnabled = true
                        }
                        ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                        return@launch
                    }
                    var remaining = 10
                    while (remaining > 0) {
                        binding.tvCooldownHint.text = "冷却 ${String.format("%2d", remaining)} 秒"
                        delay(1_000L)
                        remaining--
                    }
                    binding.tvCooldownHint.text = ""
                    syncAlgorithmSwitchFromRuntimeState("算法开关冷却结束")
                    buttonView.isEnabled = true
                    isAlgorithmCoolingDown = false
                    if (clearPanCooldownJob?.isActive != true) {
                        binding.btnClearAll.isEnabled = true
                    }
                    if (resetPanCooldownJob?.isActive != true) {
                        binding.btnCz.isEnabled = true
                    }
                }
            }

            fun applyAlgorithmSwitch(enable: Boolean, reason: String) {
                val beforeIsEnable = AppConfig.getAppConfig().isEnable
                if (enable) {
                    KaoChangAlgorithm.enableRoastAlgorithm(reason = reason)
                } else {
                    KaoChangAlgorithm.disableRoastAlgorithm(reason = reason)
                }
                val afterConfig = AppConfig.getAppConfig()
                syncAlgorithmSwitchFromRuntimeState(
                    reason = "本地手动切换完成:$reason",
                    forceLog = true
                )
                AdminOperationLogger.i(
                    action = if (enable) "开启烤肠算法" else "关闭烤肠算法",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "烤肠算法开关=${isEnableText(beforeIsEnable)}->${isEnableText(afterConfig.isEnable)}，" +
                        "设备营业状态=${onlineStatusText(afterConfig.onlineStatus)}"
                )
                startAlgorithmSwitchCooldown()
            }

            val currentConfig = AppConfig.getAppConfig()
            if (ignoreAlgorithmSwitchChange) {
                LogUtils.d(
                    "【维护操作】烤肠算法开关监听被忽略：" +
                        "ignoreAlgorithmSwitchChange=true，requestedChecked=$isChecked，" +
                        "isEnable（烤肠算法开关）=${currentConfig.isEnable}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}"
                )
                return@setOnCheckedChangeListener
            }
            if (isAlgorithmCoolingDown) {
                AdminOperationLogger.w(
                    action = if (isChecked) "开启烤肠算法" else "关闭烤肠算法",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_REJECT,
                    detail = "拒绝原因=开关处于冷却中，烤肠算法开关=${isEnableText(currentConfig.isEnable)}，" +
                        "设备营业状态=${onlineStatusText(currentConfig.onlineStatus)}"
                )
                setAlgorithmSwitchCheckedSilently(!isChecked)
                return@setOnCheckedChangeListener
            }

            AdminOperationLogger.i(
                action = if (isChecked) "开启烤肠算法" else "关闭烤肠算法",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_START,
                detail = "当前烤肠算法开关=${isEnableText(currentConfig.isEnable)}，" +
                    "设备营业状态=${onlineStatusText(currentConfig.onlineStatus)}"
            )
            if (!isChecked) {
                applyAlgorithmSwitch(enable = false, reason = "维护页手动关闭算法")
                return@setOnCheckedChangeListener
            }

            buttonView.isEnabled = false
            setMaintenanceButtonsEnabled(false)
            binding.tvCooldownHint.text = "正在同步云端状态"
            LogUtils.i(
                "【维护操作】手动开启烤肠算法前，先同步云端设备状态：" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}，" +
                    "isEnable（烤肠算法开关）=${currentConfig.isEnable}"
            )
            DataManagementAPI.refreshRemoteDeviceState(logPrefix = "维护页开启算法前同步") { result ->
                activity?.runOnUiThread {
                    if (!isAdded || _binding == null) {
                        return@runOnUiThread
                    }
                    val latestConfig = AppConfig.getAppConfig()
                    if (!result.success) {
                        AdminOperationLogger.w(
                            action = "开启烤肠算法",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "失败原因=同步云端设备状态失败，设备营业状态=${onlineStatusText(latestConfig.onlineStatus)}，" +
                                "烤肠算法开关=${isEnableText(latestConfig.isEnable)}"
                        )
                        syncAlgorithmSwitchFromRuntimeState("开启算法前同步失败后回滚UI", forceLog = true)
                        ToastUtils.showShort("同步云端设备状态失败，请稍后重试")
                        restoreSwitchControls()
                        return@runOnUiThread
                    }

                    if (latestConfig.onlineStatus in setOf(0, 2, 3) || latestConfig.inspectionMode == 1 || latestConfig.errorStatus != 0) {
                        AdminOperationLogger.w(
                            action = "开启烤肠算法",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=设备状态不允许开启，设备营业状态=${onlineStatusText(latestConfig.onlineStatus)}，" +
                                "检修模式=${inspectionModeText(latestConfig.inspectionMode)}，设备故障状态=${latestConfig.errorStatus}，" +
                                "烤肠算法开关=${isEnableText(latestConfig.isEnable)}"
                        )
                        syncAlgorithmSwitchFromRuntimeState("开启算法前云端状态不允许后回滚UI", forceLog = true)
                        val tip = when {
                            latestConfig.inspectionMode == 1 -> "设备当前处于检修模式，请先退出检修模式再开启算法"
                            latestConfig.errorStatus != 0 -> "设备当前存在故障，请先恢复设备状态再开启算法"
                            latestConfig.onlineStatus == 0 -> "后台设备状态为待投运，请先改为运营中后再开启算法"
                            latestConfig.onlineStatus == 2 -> "后台设备状态为休息中，请先改为运营中后再开启算法"
                            latestConfig.onlineStatus == 3 -> "后台设备状态为维护中，请先改为运营中后再开启算法"
                            else -> "后台设备状态不允许开启算法"
                        }
                        ToastUtils.showShort(tip)
                        restoreSwitchControls()
                        return@runOnUiThread
                    }

                    LogUtils.i(
                        "【维护操作】手动开启烤肠算法前已同步到云端最新状态，允许本次开启：" +
                            "onlineStatus（设备营业状态）=${onlineStatusText(latestConfig.onlineStatus)}，" +
                            "isEnable（烤肠算法开关）=${latestConfig.isEnable}，" +
                            "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(latestConfig.restStatusSource)}"
                    )
                    binding.tvCooldownHint.text = ""
                    applyAlgorithmSwitch(enable = true, reason = "维护页手动开启算法（已先同步云端状态）")
                }
            }
        }

        binding.swInspectionMode.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreInspectionSwitchChange) {
                return@setOnCheckedChangeListener
            }
            val config = AppConfig.getAppConfig()
            if (isChecked) {
                AppConfig.saveInspectionSnapshot("维护页手动进入检修模式")
                config.inspectionMode = 1
                AppConfig.saveAppConfig(config)
                AdminOperationLogger.i(
                    action = "进入检修模式",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "检修模式=${inspectionModeText(config.inspectionMode)}，" +
                        "设备营业状态=${onlineStatusText(config.onlineStatus)}，烤肠算法开关=${isEnableText(config.isEnable)}"
                )
            } else {
                val snapshot = AppConfig.getInspectionSnapshot()
                config.inspectionMode = 0
                AppConfig.saveAppConfig(config)
                AdminOperationLogger.i(
                    action = "退出检修模式",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "检修模式=${inspectionModeText(config.inspectionMode)}，存在检修快照=${if (snapshot != null) "是" else "否"}，" +
                        "设备营业状态=${onlineStatusText(config.onlineStatus)}，烤肠算法开关=${isEnableText(config.isEnable)}"
                )
                AppConfig.clearInspectionSnapshot()
            }
            val runtimePublishOk = SendServerHelper.publishServiceUpdateStatus()
            if (!runtimePublishOk) {
                LogUtils.w("【检修模式】检修模式状态 MQTT 发送队列拒绝，回退 HTTP 补偿")
                DataManagementAPI.uploadDeviceInfo()
            }
            syncInspectionSwitchFromRuntimeState("本地手动切换检修模式完成", forceLog = true)
        }

        binding.btnConfirmSupplementSafety.setOnClickListener {
            val intent = KaoChangOperate.pendingSupplementBatchRequiresManualConfirm()
            if (intent == null) {
                ToastUtils.showShort("当前没有需要人工确认的续补批次")
                updateManualSupplementConfirmButtonVisibility()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("确认续补安全")
                .setMessage(
                    "请确认机械位置、25~33 半路烤肠和现场环境安全后再继续续补。\n\n" +
                        "批次=${intent.batchId}\n" +
                        "已补位置=${intent.targets.filter { it.completed }.map { it.positionSn }}\n" +
                        "待补位置=${intent.remainingTargets().map { it.positionSn }}"
                )
                .setPositiveButton("已确认安全") { _, _ ->
                    val ok = KaoChangOperate.confirmPendingSupplementBatchSafety("维护人员现场确认")
                    if (ok) {
                        ToastUtils.showShort("已确认续补安全")
                        AdminOperationLogger.w(
                            action = "确认续补安全",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_DONE,
                            detail = "批次=${intent.batchId}"
                        )
                    } else {
                        ToastUtils.showShort("确认失败，当前没有待确认批次")
                        AdminOperationLogger.w(
                            action = "确认续补安全",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_FAIL,
                            detail = "失败原因=当前没有待确认批次"
                        )
                    }
                    updateManualSupplementConfirmButtonVisibility()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showConfirmDiscardAndResetPanDialog() {
        val sausageCount = KaoPanHelper.getKaoPanList().count { it.isHasSausage }
        AlertDialog.Builder(requireContext())
            .setTitle("确认丢弃所有烤肠？")
            .setMessage(
                "该操作会关闭烤肠算法，等待机械空闲后丢弃当前烤盘内所有烤肠，并重置本地烤盘状态。\n\n" +
                    "当前本地记录有肠工位：$sausageCount 个\n\n" +
                    "请确认现场已允许丢弃，且没有正在出餐或人工处理中的烤肠。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确认丢弃并重置") { _, _ ->
                performDiscardAndResetPan()
            }
            .show()
    }

    private fun performDiscardAndResetPan() {
        if (resetPanCooldownJob?.isActive == true) return

        AdminOperationLogger.i(
            action = "丢弃所有烤肠并重置烤盘",
            page = AdminOperationLogger.PAGE_PAN,
            result = AdminOperationLogger.RESULT_START,
            detail = "说明=将关闭烤肠算法并等待机械臂空闲"
        )
        setMaintenanceActionButtonsEnabled(false)
        binding.tvCooldownHint.text = "正在丢弃并重置烤盘"
        disableAlgorithmForMaintenance("点击丢弃所有烤肠并重置烤盘")

        resetPanCooldownJob = lifecycleScope.launch {
            val adminBusy = beginAdminBusy("维护页丢弃所有烤肠并重置烤盘")
            try {
                val idleReady = waitUntilMachineIdleOrTimeout(
                    reason = "维护页点击重置烤盘等待空闲",
                    waitingText = "等待机械动作完成后重置烤盘",
                    update = { binding.tvCooldownHint.text = it }
                )
                if (!idleReady) {
                    AdminOperationLogger.w(
                        action = "丢弃所有烤肠并重置烤盘",
                        page = AdminOperationLogger.PAGE_PAN,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "失败原因=等待机械臂空闲超时"
                    )
                    binding.tvCooldownHint.text = ""
                    setMaintenanceActionButtonsEnabled(true)
                    ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                    resetPanCooldownJob = null
                    return@launch
                }
                KaoChangAlgorithm.resetKaoPan { current, total, _ ->
                    binding.tvCooldownHint.text = "正在丢弃烤肠（当前第 $current 根 / 共 $total 根）"
                }
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                FaultHeatPauseManager.clearFaultHeatPauseSilently("维护页点击丢弃所有烤肠并重置烤盘")
                KaoChangOperate.clearPendingSupplementBatchIntent("维护页点击丢弃所有烤肠并重置烤盘")
                KaoChangOperate.stopAllHeatingIfNoSausage("维护页点击丢弃所有烤肠并重置烤盘", force = true)
                clearRuntimeStateAfterManualPanReset("点击丢弃所有烤肠并重置烤盘")
                AdminOperationLogger.i(
                    action = "丢弃所有烤肠并重置烤盘",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "烤肠算法开关=关闭，说明=进入15秒防重复操作冷却"
                )
                finishMaintenanceAction()
                resetPanCooldownJob = startMaintenanceCooldown(target = "reset") {
                    setMaintenanceActionButtonsEnabled(true)
                    binding.btnCz.text = "丢弃所有烤肠并重置烤盘"
                    binding.tvCooldownHint.text = ""
                    resetPanCooldownJob = null
                }
            } finally {
                endAdminBusy(adminBusy)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupGrid() {
        kaoPanAdapter = KaoPanAdapter { showPurchaseOrderDialog(it) }
        binding.rvKaopan.apply {
            layoutManager = GridLayoutManager(context, 11)
            adapter = kaoPanAdapter
        }
        kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
        startRefreshTimer()

        binding.btnCz.setOnClickListener {
            if (resetPanCooldownJob?.isActive == true) return@setOnClickListener
            showConfirmDiscardAndResetPanDialog()
        }

        binding.btnUpdate.setOnClickListener {
            kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
            kaoPanAdapter.notifyDataSetChanged()
        }

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogViewActivity::class.java))
        }

        binding.btnCheckAppUpdate.setOnClickListener {
            binding.btnCheckAppUpdate.isEnabled = false
            binding.tvAppUpdateStatus.text = "更新：正在检查..."
            AppUpdateManager.checkNow(requireActivity(), manual = true)
            lifecycleScope.launch {
                delay(3_000L)
                if (_binding != null) {
                    binding.tvAppUpdateStatus.text = "更新：${AppUpdateManager.lastStatusText()}"
                    binding.btnCheckAppUpdate.isEnabled = true
                }
            }
        }

        binding.btnErroStatus.setOnClickListener {
            if (!manualRecoveryInProgress.compareAndSet(false, true)) {
                ToastUtils.showShort("正在恢复设备状态，请稍候")
                return@setOnClickListener
            }
            AdminOperationLogger.i(
                action = "恢复正常状态",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_START,
                detail = "说明=检查下位机连接并清除设备故障状态"
            )
            binding.btnErroStatus.isEnabled = false
            binding.tvCooldownHint.text = "正在恢复设备状态"
            lifecycleScope.launch {
                try {
                    // 下位机重新插线后，手动恢复需要主动触发一次 Modbus 候选串口探测，而不是只检查旧连接。
                    val modbusConnected = withContext(Dispatchers.IO) {
                        MaintenanceModbusRecoveryConnector.ensureConnected()
                    }
                    if (!modbusConnected) {
                        val latestConfig = AppConfig.getAppConfig()
                        ToastUtils.showLong("下位机串口仍未连接，请确认通信恢复后再操作")
                        AdminOperationLogger.w(
                            action = "恢复正常状态",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=下位机串口未连接，Modbus串口地址=${latestConfig.modbusAddress}，" +
                                "设备营业状态=${onlineStatusText(latestConfig.onlineStatus)}，设备故障状态=${latestConfig.errorStatus}，" +
                                "机械动作状态=${latestConfig.moveStatus}"
                        )
                        return@launch
                    }
                    val config = AppConfig.getAppConfig()
                    val previousOnlineStatus = config.onlineStatus
                    val previousErrorStatus = config.errorStatus
                    val previousIsEnable = config.isEnable
                    val recoveredOnlineStatus = resolveManualRecoveredOnlineStatus(config)
                    val recoveryDecision = evaluateManualRecoveryHeating(recoveredOnlineStatus)
                    if (!recoveryDecision.canRecoverDeviceStatus) {
                        handleManualRecoveryBlocked(
                            targetOnlineStatus = recoveredOnlineStatus,
                            recoveryDecision = recoveryDecision,
                            allowManualResidueClearPrompt = true
                        )
                        return@launch
                    }
                    applyManualRecoveredState(
                        config = config,
                        previousOnlineStatus = previousOnlineStatus,
                        previousErrorStatus = previousErrorStatus,
                        previousIsEnable = previousIsEnable,
                        recoveredOnlineStatus = recoveredOnlineStatus,
                        recoveryDecision = recoveryDecision
                    )
                } finally {
                    manualRecoveryInProgress.set(false)
                    binding.btnErroStatus.isEnabled = true
                    val currentHint = binding.tvCooldownHint.text.toString()
                    if (currentHint == "正在检查下位机通信" || currentHint == "正在恢复设备状态") {
                        binding.tvCooldownHint.text = ""
                    }
                }
            }
        }
    }

    private fun getCurrentTime(): String {
        return BusinessTimeWindowHelper.currentTimeString()
    }

    private fun resolveManualRecoveredOnlineStatus(config: AppConfigBean): Int {
        if (config.status != 1) {
            return 0
        }
        return if (BusinessTimeWindowHelper.isWithinBusinessHours(getCurrentTime(), config.businessTime)) {
            1
        } else {
            2
        }
    }

    /**
     * 手动从烤盘取出一根烤肠，仅用于维护页人工操作。
     */
    private fun showPurchaseOrderDialog(kaoPan: KaoPan) {
        if (!kaoPan.isHasSausage) {
            ToastUtils.showShort("没有烤肠")
            return
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("是否要取出烤肠？")
            .setPositiveButton("确定") { dialog, _ ->
                resetAdminIdleTimer("手动取肠弹窗确认")
                if (!status.compareAndSet(true, false)) {
                    ToastUtils.showShort("请等待当前操作完成")
                    return@setPositiveButton
                }
                val adminBusy = beginAdminBusy("维护页手动取肠")
                val tasteLabel = kaoPan.taste.productName?.takeIf { it.isNotBlank() }
                    ?: kaoPan.taste.tasteName?.takeIf { it.isNotBlank() }
                    ?: "未知口味"
                AdminOperationLogger.i(
                    action = "手动取肠",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_START,
                    detail = "烤盘位=${kaoPan.positionSn}，口味=$tasteLabel"
                )

                lifecycleScope.launch {
                    try {
                        val modbusConnected = withContext(Dispatchers.IO) {
                            MaintenanceModbusRecoveryConnector.ensureConnected()
                        }
                        if (!modbusConnected) {
                            ToastUtils.showShort("下位机未连接，请先恢复通信")
                            AdminOperationLogger.w(
                                action = "手动取肠",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_REJECT,
                                detail = "烤盘位=${kaoPan.positionSn}，口味=$tasteLabel，拒绝原因=下位机未连接，" +
                                    "Modbus串口地址=${AppConfig.getAppConfig().modbusAddress}"
                            )
                            return@launch
                        }
                        val idleReady = waitUntilMachineIdleOrTimeout(
                            reason = "维护页手动取肠等待空闲",
                            waitingText = "请等待上位机空闲"
                        )
                        if (!idleReady) {
                            AdminOperationLogger.w(
                                action = "手动取肠",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "烤盘位=${kaoPan.positionSn}，口味=$tasteLabel，失败原因=等待机械臂空闲超时，" +
                                    "机械动作状态=${AppConfig.getAppConfig().moveStatus}"
                            )
                            ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                            return@launch
                        }
                        val result = withContext(Dispatchers.IO) {
                            KaoChangOperate.takeSausage(kaoPan)
                        }
                        if (!result) {
                            AdminOperationLogger.w(
                                action = "手动取肠",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "烤盘位=${kaoPan.positionSn}，口味=$tasteLabel"
                            )
                            ToastUtils.showShort("手动取肠失败，请查看日志")
                        } else {
                            AdminOperationLogger.i(
                                action = "手动取肠",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_DONE,
                                detail = "烤盘位=${kaoPan.positionSn}，口味=$tasteLabel"
                            )
                        }
                    } finally {
                        dialog.dismiss()
                        status.set(true)
                        endAdminBusy(adminBusy)
                    }
                }
            }
            .setNegativeButton("取消") { dialog, _ ->
                resetAdminIdleTimer("手动取肠弹窗取消")
                dialog.dismiss()
            }
            .create()
        dialog.setOnShowListener {
            resetAdminIdleTimer("手动取肠弹窗显示")
        }
        dialog.show()
    }

    /**
     * 每秒刷新一次烤盘网格与错误状态显示。
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        algorithmSwitchFallbackTickCount = 0
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1_000L)
                }
            }.collect {
                kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
                kaoPanAdapter.notifyDataSetChanged()
                algorithmSwitchFallbackTickCount++
                if (algorithmSwitchFallbackTickCount >= ALGORITHM_SWITCH_FALLBACK_SYNC_INTERVAL_SECONDS) {
                    algorithmSwitchFallbackTickCount = 0
                    syncAlgorithmSwitchFromRuntimeState("5秒低频兜底同步")
                    syncInspectionSwitchFromRuntimeState("5秒低频兜底同步")
                }
                updateManualSupplementConfirmButtonVisibility()
                binding.tvAppUpdateStatus.text = "更新：${AppUpdateManager.lastStatusText()}"
                binding.tvErroStatus.apply {
                    val isNormal = AppConfig.getAppConfig().errorStatus == 0
                    text = if (isNormal) "状态：正常" else "状态：错误"
                    setTextColor(
                        resources.getColor(
                            if (isNormal) AndroidR.color.holo_green_dark else AndroidR.color.holo_red_dark
                        )
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        MaintenanceUiRefreshBridge.unregister(maintenanceUiRefreshCallback)
        refreshJob?.cancel()
        _binding = null
    }
}
