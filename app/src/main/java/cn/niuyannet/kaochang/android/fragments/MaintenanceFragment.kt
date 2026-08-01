package cn.niuyannet.kaochang.android.fragments

import android.annotation.SuppressLint
import android.R as AndroidR
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
import cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilder
import cn.niuyannet.kaochang.android.services.MaintenanceModbusRecoveryConnector
import cn.niuyannet.kaochang.android.services.BusinessTimeWindowHelper
import cn.niuyannet.kaochang.android.services.DiscardCheckpoint
import cn.niuyannet.kaochang.android.services.DiscardJournal
import cn.niuyannet.kaochang.android.services.DiscardPhysicalResult
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.ManualPanPositionDataClearExecutor
import cn.niuyannet.kaochang.android.services.ManualPanPositionDataClearPolicy
import cn.niuyannet.kaochang.android.services.ManualPanResetRuntimeStateHelper
import cn.niuyannet.kaochang.android.services.ManualRecoveryGatePolicy
import cn.niuyannet.kaochang.android.services.ManualFaultRecoveryResult
import cn.niuyannet.kaochang.android.services.ManualRecoveryCloudConfirmationPolicy
import cn.niuyannet.kaochang.android.services.MoveStatusRecoveryPolicy
import cn.niuyannet.kaochang.android.services.LowerPlatformRuntimeStateReporter
import cn.niuyannet.kaochang.android.services.LowToHighDemandGate
import cn.niuyannet.kaochang.android.services.OrphanSupplementReturnCoordinator
import cn.niuyannet.kaochang.android.services.OrphanSupplementReturnPlanner
import cn.niuyannet.kaochang.android.services.PanPhysicalStateDeltaReporter
import cn.niuyannet.kaochang.android.services.SupplementBatchType
import cn.niuyannet.kaochang.android.services.SelfCleanManager
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
import cn.niuyannet.kaochang.android.utils.OrderStateManager
import cn.niuyannet.kaochang.android.utils.RepeatedLogGate
import com.blankj.utilcode.util.ToastUtils
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

class MaintenanceFragment : Fragment() {
    private var _binding: FragmentMaintenanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var kaoPanAdapter: KaoPanAdapter
    private var refreshJob: Job? = null

    private val status = AtomicBoolean(true)
    private val manualRecoveryInProgress = AtomicBoolean(false)
    private val operationGate = MaintenanceOperationGate()

    private var isAlgorithmCoolingDown = false
    private var ignoreAlgorithmSwitchChange = false
    private var ignoreInspectionSwitchChange = false
    private var algorithmCooldownJob: Job? = null
    private var clearPanCooldownJob: Job? = null
    private var resetPanCooldownJob: Job? = null
    private var algorithmSwitchFallbackTickCount = 0
    private var remoteRuntimeStatusRefreshTickCount = 0
    private var remoteRuntimeStatusRefreshInFlight = false
    private var cloudRuntimeStatusState: MaintenanceRuntimeStatusDisplayPolicy.CloudState =
        MaintenanceRuntimeStatusDisplayPolicy.CloudState.Loading
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
            refreshRemoteRuntimeStatusForDisplay("远端事件驱动同步:$reason")
        }
    }

    companion object {
        private const val MAINTENANCE_ACTION_COOLDOWN_SECONDS = 15
        private const val ALGORITHM_SWITCH_FALLBACK_SYNC_INTERVAL_SECONDS = 5
        private const val REMOTE_RUNTIME_STATUS_REFRESH_INTERVAL_SECONDS = 10
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
        cloudRuntimeStatusState = MaintenanceRuntimeStatusDisplayPolicy.CloudState.Loading
        remoteRuntimeStatusRefreshInFlight = false
        remoteRuntimeStatusRefreshTickCount = 0
        updateRuntimeStatusUi()
        refreshRemoteRuntimeStatusForDisplay("维护页初始化")
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

    private fun setFaultRecoveryConflictingControlsEnabled(enabled: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.btnErroStatus.isEnabled = enabled
        currentBinding.swKzAlgorithm.isEnabled = enabled
        currentBinding.swInspectionMode.isEnabled = enabled
        currentBinding.btnCz.isEnabled = enabled && resetPanCooldownJob?.isActive != true
        currentBinding.btnClearAll.isEnabled = enabled && clearPanCooldownJob?.isActive != true
    }

    private fun finishFaultRecoveryOperation(
        token: MaintenanceOperationToken,
        expectedHint: String
    ) {
        if (!operationGate.finish(token)) return
        manualRecoveryInProgress.set(false)
        status.set(true)
        val currentBinding = _binding ?: return
        setFaultRecoveryConflictingControlsEnabled(true)
        if (currentBinding.tvCooldownHint.text.toString() == expectedHint) {
            currentBinding.tvCooldownHint.text = ""
        }
    }

    private fun updateManualSupplementConfirmButtonVisibility() {
        updateLowerPlatformUi()
    }

    private fun updateLowerPlatformUi() {
        val config = AppConfig.getAppConfig()
        val intent = KaoChangOperate.pendingSupplementBatchRequiresManualConfirm()
        val orphanPreview = KaoChangOperate.previewOrphanSupplementReturns()
        val hasOrphanSources = orphanPreview.bindings.isNotEmpty() || orphanPreview.failure != null
        val needsLowerPlatformRecovery =
            LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(config)

        val actionName = config.lowerPlatformIsolationActionName.takeIf { it.isNotBlank() } ?: "未知动作"
        val statusText: String
        val statusColor: Int
        when {
            hasOrphanSources -> {
                statusText = "补热区存在未绑定烤肠，需要按当前空位建立回搬目标"
                statusColor = AndroidR.color.holo_orange_dark
            }
            config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED -> {
                statusText = "下平台半故障：暂停自动补肠，待人工确认，动作=$actionName"
                statusColor = AndroidR.color.holo_orange_dark
            }
            config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED -> {
                statusText = "下平台：供给降级，请检查烤肠箱/取肠红外"
                statusColor = AndroidR.color.holo_orange_dark
            }
            else -> {
                statusText = "下平台：正常"
                statusColor = AndroidR.color.holo_green_dark
            }
        }
        binding.tvLowerPlatformStatus.text = statusText
        binding.tvLowerPlatformStatus.setTextColor(resources.getColor(statusColor))

        binding.btnConfirmSupplementSafety.visibility =
            if (intent != null || needsLowerPlatformRecovery) View.VISIBLE else View.GONE
        binding.btnConfirmSupplementSafety.text = when {
            intent != null && needsLowerPlatformRecovery -> "确认续补安全并恢复补肠"
            intent != null -> "确认续补安全"
            needsLowerPlatformRecovery -> "确认下平台安全并恢复补肠"
            else -> "确认续补安全"
        }
        binding.btnRebindOrphanReturns.visibility = if (hasOrphanSources) View.VISIBLE else View.GONE
    }

    private fun updateRuntimeStatusUi() {
        val config = AppConfig.getAppConfig()
        val canRetryCloudOnly = ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(
            onlineStatus = config.onlineStatus,
            targetOnlineStatus = resolveManualRecoveredOnlineStatus(config),
            errorStatus = config.errorStatus,
            moveStatus = config.moveStatus,
            isEnable = config.isEnable,
            faultPauseActive = FaultHeatPauseManager.shouldSkipBakingCompletion(config)
        )
        val display = MaintenanceRuntimeStatusDisplayPolicy.build(
            localOnlineStatus = config.onlineStatus,
            localErrorStatus = config.errorStatus,
            cloudState = cloudRuntimeStatusState,
            needsLocalRecovery = needsLocalFaultRecovery(config),
            canRetryCloudOnly = canRetryCloudOnly
        )

        binding.tvErroStatus.text = display.faultText
        binding.tvErroStatus.setTextColor(
            ContextCompat.getColor(requireContext(), runtimeStatusColor(display.faultLevel))
        )
        binding.tvOnlineStatus.text = display.onlineText
        binding.tvOnlineStatus.setTextColor(
            ContextCompat.getColor(requireContext(), runtimeStatusColor(display.onlineLevel))
        )
        binding.btnErroStatus.text = if (KaoChangOperate.pendingDiscardJournalForRecovery() != null) {
            "核对丢弃残留"
        } else {
            display.recoveryButtonText
        }
    }

    private suspend fun confirmDiscardPhysicalResult(journal: DiscardJournal): DiscardPhysicalResult? =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("核对工位 ${journal.positionSn} 的丢弃结果")
                .setMessage(
                    "丢弃命令已经尝试下发，但上位机没有收到确定结果。请现场查看工位和机械位置后选择。\n\n" +
                        "不要只根据页面数据或 201=0 判断。"
                )
                .setPositiveButton("工位已空，确认已丢弃") { _, _ ->
                    if (continuation.isActive) continuation.resume(DiscardPhysicalResult.DISCARDED)
                }
                .setNegativeButton("烤肠仍在，确认未丢弃") { _, _ ->
                    if (continuation.isActive) continuation.resume(DiscardPhysicalResult.NOT_DISCARDED)
                }
                .setNeutralButton("取消") { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun runtimeStatusColor(level: MaintenanceRuntimeStatusDisplayPolicy.Level): Int =
        when (level) {
            MaintenanceRuntimeStatusDisplayPolicy.Level.NORMAL -> AndroidR.color.holo_green_dark
            MaintenanceRuntimeStatusDisplayPolicy.Level.WARNING -> AndroidR.color.holo_orange_dark
            MaintenanceRuntimeStatusDisplayPolicy.Level.ERROR -> AndroidR.color.holo_red_dark
        }

    private fun refreshRemoteRuntimeStatusForDisplay(reason: String) {
        if (remoteRuntimeStatusRefreshInFlight) return
        remoteRuntimeStatusRefreshInFlight = true
        DataManagementAPI.preflightRemoteDeviceState(
            logPrefix = "维护页状态展示:$reason"
        ) { result ->
            activity?.runOnUiThread {
                remoteRuntimeStatusRefreshInFlight = false
                if (!isAdded || _binding == null) {
                    return@runOnUiThread
                }
                cloudRuntimeStatusState = if (result.success) {
                    MaintenanceRuntimeStatusDisplayPolicy.CloudState.Available(
                        onlineStatus = result.onlineStatus,
                        errorStatus = result.errorStatus
                    )
                } else {
                    MaintenanceRuntimeStatusDisplayPolicy.CloudState.Unavailable
                }
                updateRuntimeStatusUi()
            }
        }
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

    private suspend fun tryRecoverIdleMoveStatus(
        reason: String,
        allowSupplementPrefetchAbandon: Boolean = false,
        blockedLogGate: RepeatedLogGate
    ): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.moveStatus == 0 && !allowSupplementPrefetchAbandon) {
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
        if (
            allowSupplementPrefetchAbandon &&
            KaoChangOperate.abandonSupplementPrefetchForManualPanClear(statusReadings, reason)
        ) {
            return true
        }
        val actionGroupIdle = KaoChangOperate.isActionGroupIdleForMoveStatusRecovery()
        if (MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(statusReadings, actionGroupIdle)) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【维护操作】检测到本地机械状态与下位机空闲状态不一致，已自动收口：" +
                    "reason=$reason，moveStatus（机械动作状态）=$oldMoveStatus->0，" +
                    "status201连续读数=$statusReadings，actionGroup=${KaoChangOperate.actionGroupRuntimeSnapshotForLog()}"
            )
            return true
        }
        val runtimeSnapshot = KaoChangOperate.actionGroupRuntimeSnapshotForLog()
        val blockedState = "${config.moveStatus}|$statusReadings|$actionGroupIdle|$runtimeSnapshot"
        if (blockedLogGate.evaluate(blockedState, System.currentTimeMillis()) != null) {
            LogUtils.w(
                "【维护操作】跳过本地机械状态自动收口：" +
                    "reason=$reason，moveStatus（机械动作状态）=${config.moveStatus}，" +
                    "status201连续读数=$statusReadings，actionGroupIdle=$actionGroupIdle，" +
                    "actionGroup=$runtimeSnapshot"
            )
        }
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
        previousOnlineStatus: Int,
        previousErrorStatus: Int,
        previousIsEnable: Int,
        recoveredOnlineStatus: Int,
        recoveryDecision: ManualRecoveryGatePolicy.Decision
    ): Boolean {
        val recoveryResult = withContext(Dispatchers.IO) {
            KaoChangOperate.recoverFaultHeatingAndRuntimeState(
                targetOnlineStatus = recoveredOnlineStatus,
                reason = "维护页恢复正常状态"
            )
        }
        if (recoveryResult !is ManualFaultRecoveryResult.Success) {
            val failureReason = when (recoveryResult) {
                is ManualFaultRecoveryResult.Rejected -> recoveryResult.reason
                is ManualFaultRecoveryResult.Failed -> recoveryResult.reason
                is ManualFaultRecoveryResult.Success -> ""
            }
            val failureDetail = if (
                recoveryResult is ManualFaultRecoveryResult.Rejected &&
                failureReason == "恢复提交前运行态已变化"
            ) {
                "未重复执行温控恢复，请重新检查当前状态"
            } else {
                "已回退故障状态并停止加热"
            }
            ToastUtils.showLong("设备恢复未完成：$failureReason；$failureDetail")
            AdminOperationLogger.w(
                action = "恢复正常状态",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "失败原因=$failureReason，恢复闸门=${recoveryDecision.reason}"
            )
            return false
        }
        val config = AppConfig.getAppConfig()
        val cloudDecision = confirmManualRecoveryInCloud(recoveredOnlineStatus)
        if (!cloudDecision.confirmed) {
            ToastUtils.showLong(
                "本机恢复已完成，但后台未确认恢复：${cloudDecision.reason}；" +
                    "烤制算法保持关闭，请再次点击恢复正常状态"
            )
            AdminOperationLogger.w(
                action = "恢复正常状态",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_FAIL,
                detail = "本机恢复完成但云端确认失败，原因=${cloudDecision.reason}，" +
                    "设备营业状态=${onlineStatusText(config.onlineStatus)}，设备故障状态=${config.errorStatus}"
            )
            return false
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
        return true
    }

    private fun needsLocalFaultRecovery(config: AppConfigBean): Boolean =
        config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
            config.onlineStatus == 3 ||
            FaultHeatPauseManager.shouldSkipBakingCompletion(config)

    private suspend fun confirmManualRecoveryInCloud(
        targetOnlineStatus: Int
    ): ManualRecoveryCloudConfirmationPolicy.Decision {
        val ack = suspendCancellableCoroutine<SendServerHelper.RuntimeStateServerAck> { continuation ->
            val ackListener = SendServerHelper.createRuntimeStateAckListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            continuation.invokeOnCancellation {
                SendServerHelper.cancelRuntimeStateAckListener(ackListener)
            }
            val queued = SendServerHelper.publishServiceUpdateStatus(
                source = "runtime_state:manual_recovery",
                forceNewSeq = true,
                ackListener = ackListener
            )
            if (!queued && continuation.isActive) {
                continuation.resume(
                    SendServerHelper.RuntimeStateServerAck(
                        runtimeSeq = -1L,
                        received = false,
                        applied = false,
                        reason = "publish_failed",
                        onlineStatus = AppConfig.getAppConfig().onlineStatus,
                        errorStatus = AppConfig.getAppConfig().errorStatus
                    )
                )
            }
        }
        val currentConfig = AppConfig.getAppConfig()
        val localStateStillRecovered = ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(
            onlineStatus = currentConfig.onlineStatus,
            targetOnlineStatus = targetOnlineStatus,
            errorStatus = currentConfig.errorStatus,
            moveStatus = currentConfig.moveStatus,
            isEnable = currentConfig.isEnable,
            faultPauseActive = FaultHeatPauseManager.shouldSkipBakingCompletion(currentConfig)
        )
        return ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = targetOnlineStatus,
            ackReceived = ack.received,
            ackApplied = ack.applied,
            remoteOnlineStatus = ack.onlineStatus,
            remoteErrorStatus = ack.errorStatus,
            localStateStillRecovered = localStateStillRecovered
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
            if (session == null) {
                append("检测到上位机没有动作组会话，但仍残留 moveStatus=1；下位机 201 和 300/400 寄存器当前均为空闲。\n\n")
                append("是否已人工取走中途烤肠，并核对肠箱、滚筒、升降台和烤盘状态？确认后将连续两次复查下位机寄存器，再清理本地孤儿机械忙态。\n\n")
            } else {
                append("检测到上位机存在补肠动作组本地残留，但下位机 300/400 结果寄存器均为空闲。\n\n")
                append("仅在现场已确认下位机复位完成、机械臂/升降台/夹爪空闲、烤盘和肠箱实物状态已核对、售卖口无卡肠时继续。\n\n")
            }
            append("sessionId=${session?.sessionId ?: "无"}\n")
            append("type=${session?.type ?: "无"}\n")
            append("sourceNo=${session?.sourceNo ?: "无"}，targetNo=${session?.targetNo ?: "无"}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (session == null) "确认清理孤儿机械忙态" else "确认清理本地动作组残留")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (session == null) "已核对并继续恢复" else "确认清理本地动作组残留") { _, _ ->
                retryManualRecoveryAfterClearingActionGroupResidue()
            }
            .show()
    }

    private fun retryManualRecoveryAfterClearingActionGroupResidue() {
        if (!manualRecoveryInProgress.compareAndSet(false, true)) {
            ToastUtils.showShort("正在恢复设备状态，请稍候")
            return
        }
        if (!status.compareAndSet(true, false)) {
            manualRecoveryInProgress.set(false)
            ToastUtils.showShort("请等待当前维护操作完成")
            return
        }
        val operationToken = operationGate.begin(MaintenanceOperationKind.FAULT_RECOVERY)
        setFaultRecoveryConflictingControlsEnabled(false)
        binding.tvCooldownHint.text = "正在清理动作组残留并恢复设备状态"
        viewLifecycleOwner.lifecycleScope.launch {
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
                    previousOnlineStatus = previousOnlineStatus,
                    previousErrorStatus = previousErrorStatus,
                    previousIsEnable = previousIsEnable,
                    recoveredOnlineStatus = recoveredOnlineStatus,
                    recoveryDecision = recoveryDecision
                )
            } finally {
                finishFaultRecoveryOperation(
                    operationToken,
                    "正在清理动作组残留并恢复设备状态"
                )
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
        timeoutMs: Long = MANUAL_ACTION_IDLE_TIMEOUT_MS,
        allowSupplementPrefetchAbandon: Boolean = false
    ): Boolean {
        val blockedLogGate = RepeatedLogGate(summaryIntervalMs = null)
        val waitStartedAtMs = SystemClock.elapsedRealtime()
        var observedBlocked = false
        val result = withTimeoutOrNull(timeoutMs) {
            while (
                AppConfig.getAppConfig().moveStatus != 0 ||
                !KaoChangOperate.isActionGroupIdleForMoveStatusRecovery()
            ) {
                observedBlocked = true
                update?.invoke(waitingText)
                if (tryRecoverIdleMoveStatus(reason, allowSupplementPrefetchAbandon, blockedLogGate)) {
                    if (KaoChangOperate.isActionGroupIdleForMoveStatusRecovery()) {
                        break
                    }
                }
                delay(1_000L)
            }
            true
        }
        val idleReady = result == true &&
            AppConfig.getAppConfig().moveStatus == 0 &&
            KaoChangOperate.isActionGroupIdleForMoveStatusRecovery()
        if (idleReady && observedBlocked) {
            val config = AppConfig.getAppConfig()
            LogUtils.i(
                "【维护操作】等待机械空闲已结束：reason=$reason，" +
                    "waitedMs=${SystemClock.elapsedRealtime() - waitStartedAtMs}，" +
                    "moveStatus（机械动作状态）=${config.moveStatus}，" +
                    "actionGroup=${KaoChangOperate.actionGroupRuntimeSnapshotForLog()}"
            )
        }
        return idleReady
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
                        },
                        allowSupplementPrefetchAbandon = true
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
                    if (KaoChangOperate.isActionGroupRecoveryBlocked()) {
                        binding.tvCooldownHint.text = ""
                        setMaintenanceActionButtonsEnabled(true)
                        ToastUtils.showShort("存在未收口的动作恢复记录，请先完成恢复后再清空烤盘")
                        clearPanCooldownJob = null
                        return@launch
                    }
                    KaoPanHelper.getKaoPanList()
                        .filter { it.positionSn in 1..21 && it.isHasSausage }
                        .map { it.positionSn }
                        .forEach { positionSn ->
                            LowToHighDemandGate.recordNonDemandEmpty(positionSn, "manual_clear")
                        }
                    KaoPanHelper.clearKaoPan()
                    KaoPanHelper.init()
                    PanPhysicalStateDeltaReporter.reportPansChanged(
                        changedPans = KaoPanHelper.getKaoPanList(),
                        reason = "maintenance_pan_clear:all",
                        source = "maintenance_pan_clear_all"
                    )
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
                val currentBinding = _binding ?: return
                currentBinding.tvCooldownHint.text = ""
                setFaultRecoveryConflictingControlsEnabled(true)
            }

            fun startAlgorithmSwitchCooldown() {
                isAlgorithmCoolingDown = true
                buttonView.isEnabled = false
                setMaintenanceButtonsEnabled(false)
                algorithmCooldownJob?.cancel()
                algorithmCooldownJob = viewLifecycleOwner.lifecycleScope.launch {
                    val idleReady = waitUntilMachineIdleOrTimeout(
                        reason = "维护页切换算法后冷却等待空闲",
                        waitingText = "等待机械动作完成",
                        update = { binding.tvCooldownHint.text = it }
                    )
                    if (!idleReady) {
                        LogUtils.w("【维护操作】算法开关冷却前等待机械空闲超时，结束冷却并恢复操作控件")
                        binding.tvCooldownHint.text = ""
                        isAlgorithmCoolingDown = false
                        setFaultRecoveryConflictingControlsEnabled(true)
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
                    isAlgorithmCoolingDown = false
                    setFaultRecoveryConflictingControlsEnabled(true)
                }
            }

            fun applyAlgorithmSwitch(enable: Boolean, reason: String): Boolean {
                val beforeIsEnable = AppConfig.getAppConfig().isEnable
                val applied = if (enable) {
                    KaoChangAlgorithm.enableRoastAlgorithm(reason = reason)
                } else {
                    KaoChangAlgorithm.disableRoastAlgorithm(reason = reason)
                }
                if (!applied) {
                    syncAlgorithmSwitchFromRuntimeState("本地手动切换失败:$reason", forceLog = true)
                    AdminOperationLogger.w(
                        action = if (enable) "开启烤肠算法" else "关闭烤肠算法",
                        page = AdminOperationLogger.PAGE_PAN,
                        result = AdminOperationLogger.RESULT_FAIL,
                        detail = "本地运行态或温控检查未通过"
                    )
                    return false
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
                return true
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

            val operationToken = operationGate.begin(MaintenanceOperationKind.ALGORITHM_PREFLIGHT)
            setFaultRecoveryConflictingControlsEnabled(false)
            binding.tvCooldownHint.text = "正在同步云端状态"
            LogUtils.i(
                "【维护操作】手动开启烤肠算法前，先同步云端设备状态：" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}，" +
                    "isEnable（烤肠算法开关）=${currentConfig.isEnable}"
            )
            DataManagementAPI.preflightRemoteDeviceState(logPrefix = "维护页开启算法前预检") { result ->
                activity?.runOnUiThread {
                    if (!operationGate.isCurrent(operationToken) || !isAdded || _binding == null) {
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
                        syncAlgorithmSwitchFromRuntimeState("开启算法前预检失败后回滚UI", forceLog = true)
                        ToastUtils.showShort("同步云端设备状态失败，请稍后重试")
                        operationGate.finish(operationToken)
                        restoreSwitchControls()
                        return@runOnUiThread
                    }

                    if (result.onlineStatus in setOf(0, 2, 3) || result.inspectionMode == 1 || result.errorStatus != 0) {
                        AdminOperationLogger.w(
                            action = "开启烤肠算法",
                            page = AdminOperationLogger.PAGE_PAN,
                            result = AdminOperationLogger.RESULT_REJECT,
                            detail = "拒绝原因=云端设备状态不允许开启，设备营业状态=${onlineStatusText(result.onlineStatus)}，" +
                                "检修模式=${inspectionModeText(result.inspectionMode)}，设备故障状态=${result.errorStatus}，" +
                                "烤肠算法开关=${isEnableText(latestConfig.isEnable)}"
                        )
                        syncAlgorithmSwitchFromRuntimeState("开启算法前云端状态不允许后回滚UI", forceLog = true)
                        val tip = when {
                            result.inspectionMode == 1 -> "设备当前处于检修模式，请先退出检修模式再开启算法"
                            result.errorStatus != 0 -> "设备当前存在故障，请先恢复设备状态再开启算法"
                            result.onlineStatus == 0 -> "后台设备状态为待投运，请先改为运营中后再开启算法"
                            result.onlineStatus == 2 -> "后台设备状态为休息中，请先改为运营中后再开启算法"
                            result.onlineStatus == 3 -> "后台设备状态为维护中，请先改为运营中后再开启算法"
                            else -> "后台设备状态不允许开启算法"
                        }
                        ToastUtils.showShort(tip)
                        operationGate.finish(operationToken)
                        restoreSwitchControls()
                        return@runOnUiThread
                    }

                    LogUtils.i(
                        "【维护操作】手动开启烤肠算法前已同步到云端最新状态，允许本次开启：" +
                            "onlineStatus（云端设备营业状态）=${onlineStatusText(result.onlineStatus)}，" +
                            "isEnable（烤肠算法开关）=${latestConfig.isEnable}，" +
                            "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(latestConfig.restStatusSource)}"
                    )
                    binding.tvCooldownHint.text = ""
                    val applied = applyAlgorithmSwitch(enable = true, reason = "维护页手动开启算法（已通过云端只读预检）")
                    operationGate.finish(operationToken)
                    if (!applied) {
                        ToastUtils.showShort("设备当前状态或温控未通过，算法未开启")
                        restoreSwitchControls()
                    }
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
            if (intent == null && !LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(AppConfig.getAppConfig())) {
                ToastUtils.showShort("当前没有需要人工确认的续补批次")
                updateLowerPlatformUi()
                return@setOnClickListener
            }
            if (intent == null) {
                val isolationActionName =
                    AppConfig.getAppConfig().lowerPlatformIsolationActionName.takeIf { it.isNotBlank() } ?: "未知动作"
                AlertDialog.Builder(requireContext())
                    .setTitle("确认下平台隔离恢复")
                    .setMessage(
                        "请确认滚筒、升降台、下平台和机械臂现场已安全，可恢复自动补肠和自清洁。\n\n" +
                            "当前隔离动作=$isolationActionName"
                    )
                    .setPositiveButton("已确认安全") { _, _ ->
                        val ok = LowerPlatformRuntimeStateReporter.confirmLowerPlatformIsolationAfterManualAction("维护人员现场确认下平台隔离")
                        if (ok) {
                            ToastUtils.showShort("已恢复下平台自动调度")
                            updateManualSupplementConfirmButtonVisibility()
                        } else {
                            ToastUtils.showShort("当前没有可恢复的下平台隔离状态")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("确认续补安全")
                .setMessage(
                    "请确认滚筒、升降台、下平台、机械位置、25~33 半路烤肠和现场环境安全后再继续续补。\n\n" +
                        "批次=${intent.batchId}\n" +
                        "已补位置=${intent.targets.filter { it.completed }.map { it.positionSn }}\n" +
                        "待补位置=${intent.remainingTargets().map { it.positionSn }}"
                )
                .setPositiveButton("已确认安全") { _, _ ->
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            KaoChangOperate.confirmPendingSupplementBatchSafety("维护人员现场确认")
                        }
                        if (result.ok) {
                            val isolationRecovered =
                                LowerPlatformRuntimeStateReporter.confirmLowerPlatformIsolationAfterManualAction(
                                    "维护人员现场确认续补安全"
                                )
                            ToastUtils.showShort(if (isolationRecovered) "已确认续补安全并恢复下平台补肠" else "已确认续补安全")
                            AdminOperationLogger.w(
                                action = "确认续补安全",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_DONE,
                                detail = "批次=${intent.batchId}，下平台隔离恢复=${if (isolationRecovered) "是" else "否"}"
                            )
                        } else {
                            ToastUtils.showShort("确认失败：${result.reason}")
                            AdminOperationLogger.w(
                                action = "确认续补安全",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "失败原因=${result.reason}"
                            )
                        }
                        updateManualSupplementConfirmButtonVisibility()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnRebindOrphanReturns.setOnClickListener {
            val preview = KaoChangOperate.previewOrphanSupplementReturns()
            val expectedBindings = preview.bindings
            when (preview.failure) {
                OrphanSupplementReturnCoordinator.Failure.NO_TASTE -> {
                    val boundSources = KaoChangOperate.completedHeatReturnIntentForUi()
                        ?.targets
                        ?.map { it.positionSn }
                        ?.toSet()
                        .orEmpty()
                    val activeSupplementSources = KaoChangOperate.activeSupplementBatchTargetPositions(
                        setOf(
                            SupplementBatchType.LOW_HEAT_13_15,
                            SupplementBatchType.LOW_HEAT_16_18,
                            SupplementBatchType.HIGH_HEAT_25_33
                        )
                    )
                    val missingSourcePositions = KaoPanHelper.getKaoPanList()
                        .filter {
                            OrphanSupplementReturnPlanner.isRecognizedSource(it) &&
                                it.taste?.tasteCode.isNullOrBlank() &&
                                it.positionSn !in boundSources &&
                                it.positionSn !in activeSupplementSources
                        }
                        .map { it.positionSn }
                        .sorted()
                    val sourcePositionSn = missingSourcePositions.firstOrNull()
                    val tasteCodes = KaoPanHelper.getKaoPanBoxList()
                        .mapNotNull { it.tasteCode?.takeIf(String::isNotBlank) }
                        .distinct()
                        .sorted()
                    if (sourcePositionSn == null || tasteCodes.isEmpty()) {
                        ToastUtils.showShort("缺少可确认的源盘或当前烤肠箱口味")
                        return@setOnClickListener
                    }
                    var selectedTaste: String? = null
                    AlertDialog.Builder(requireContext())
                        .setTitle("确认补热源盘口味")
                        .setMessage(
                            "缺少口味的补热源盘=$missingSourcePositions\n" +
                                "本次为源盘 $sourcePositionSn 选择当前烤肠箱口味，保存后请重新预览。"
                        )
                        .setSingleChoiceItems(tasteCodes.toTypedArray(), -1) { _, which ->
                            selectedTaste = tasteCodes[which]
                        }
                        .setPositiveButton("确认口味") { _, _ ->
                            val tasteCode = selectedTaste
                            if (tasteCode == null) {
                                ToastUtils.showShort("请先选择口味")
                            } else if (KaoChangOperate.confirmOrphanSupplementSourceTaste(sourcePositionSn, tasteCode)) {
                                ToastUtils.showShort("源盘口味已保存，请重新预览回搬目标")
                                updateLowerPlatformUi()
                                refreshKaoPanGrid()
                            } else {
                                ToastUtils.showShort("源盘口味保存失败，现场状态或口味已变化")
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                OrphanSupplementReturnCoordinator.Failure.INSUFFICIENT_TARGETS ->
                    ToastUtils.showShort("当前前区安全空位不足，不能建立回搬目标")
                OrphanSupplementReturnCoordinator.Failure.UNSAFE_SOURCE ->
                    ToastUtils.showShort("补热源盘状态已变化，请刷新后核对")
                null -> {
                    if (expectedBindings.isEmpty()) {
                        ToastUtils.showShort("当前没有需要建立回搬目标的补热烤肠")
                        updateLowerPlatformUi()
                        return@setOnClickListener
                    }
                    val bindingPreview = expectedBindings.joinToString("\n") {
                        "${it.sourcePositionSn} -> ${it.returnPositionSn} / ${it.tasteCode}"
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle("确认按当前空位建立回搬目标")
                        .setMessage("请核对以下绑定，只保存元数据，不会执行搬盘：\n\n$bindingPreview")
                        .setPositiveButton("确认建立") { _, _ ->
                            lifecycleScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    KaoChangOperate.rebindOrphanSupplementReturnsToCurrentEmptyFront(
                                        expectedBindings = expectedBindings,
                                        reason = "维护人员确认按当前空位建立回搬目标"
                                    )
                                }
                                when (result.failure) {
                                    null -> ToastUtils.showShort("回搬目标已建立")
                                    OrphanSupplementReturnCoordinator.Failure.STALE_PREVIEW ->
                                        ToastUtils.showShort("盘位已变化，请重新预览后确认")
                                    OrphanSupplementReturnCoordinator.Failure.CONFLICT ->
                                        ToastUtils.showShort("现有补肠批次或回搬目标冲突，未建立新绑定")
                                    OrphanSupplementReturnCoordinator.Failure.INTENT_PERSIST_FAILED ->
                                        ToastUtils.showShort("回搬绑定落盘失败，未修改盘位")
                                    OrphanSupplementReturnCoordinator.Failure.PAN_PERSIST_FAILED ->
                                        ToastUtils.showLong("盘位落盘失败，回搬绑定已保留，请重启后核对待回补标记")
                                    else -> ToastUtils.showShort("现场状态已变化，请重新预览")
                                }
                                updateLowerPlatformUi()
                                refreshKaoPanGrid()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                else -> ToastUtils.showShort("当前不能建立回搬目标，请刷新后重试")
            }
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
                if (KaoChangOperate.isActionGroupRecoveryBlocked()) {
                    setMaintenanceActionButtonsEnabled(true)
                    ToastUtils.showShort("存在未收口的动作恢复记录，请先完成恢复后再重置烤盘")
                    resetPanCooldownJob = null
                    return@launch
                }
                KaoChangAlgorithm.resetKaoPan { current, total, _ ->
                    binding.tvCooldownHint.text = "正在丢弃烤肠（当前第 $current 根 / 共 $total 根）"
                }
                if (KaoChangOperate.isActionGroupRecoveryBlocked()) {
                    setMaintenanceActionButtonsEnabled(true)
                    ToastUtils.showShort("存在未收口的动作恢复记录，请先完成恢复后再重置烤盘")
                    resetPanCooldownJob = null
                    return@launch
                }
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                PanPhysicalStateDeltaReporter.reportPansChanged(
                    changedPans = KaoPanHelper.getKaoPanList(),
                    reason = "maintenance_pan_reset_after_discard:all",
                    source = "maintenance_pan_reset_all"
                )
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
    private fun refreshKaoPanGrid() {
        val recoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked()
        val pans = KaoPanUiReservationStateBuilder.apply(
            pans = KaoPanHelper.getKaoPanList(),
            activeIntent = KaoChangOperate.maintenanceKaoPanIntentForUi(),
            recoveryBlocked = recoveryBlocked
        )
        kaoPanAdapter.submitList(pans)
        kaoPanAdapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupGrid() {
        kaoPanAdapter = KaoPanAdapter { showPurchaseOrderDialog(it) }
        binding.rvKaopan.apply {
            layoutManager = GridLayoutManager(context, 11)
            adapter = kaoPanAdapter
        }
        refreshKaoPanGrid()
        startRefreshTimer()

        binding.btnCz.setOnClickListener {
            if (resetPanCooldownJob?.isActive == true) return@setOnClickListener
            showConfirmDiscardAndResetPanDialog()
        }

        binding.btnUpdate.setOnClickListener {
            refreshKaoPanGrid()
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
            if (!status.compareAndSet(true, false)) {
                manualRecoveryInProgress.set(false)
                ToastUtils.showShort("请等待当前维护操作完成")
                return@setOnClickListener
            }
            val operationToken = operationGate.begin(MaintenanceOperationKind.FAULT_RECOVERY)
            AdminOperationLogger.i(
                action = "恢复正常状态",
                page = AdminOperationLogger.PAGE_PAN,
                result = AdminOperationLogger.RESULT_START,
                detail = "说明=检查下位机连接并清除设备故障状态"
            )
            setFaultRecoveryConflictingControlsEnabled(false)
            binding.tvCooldownHint.text = "正在恢复设备状态"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val discardJournal = KaoChangOperate.pendingDiscardJournalForRecovery()
                    if (discardJournal != null) {
                        val physicalResult = when {
                            discardJournal.checkpoint == DiscardCheckpoint.PREPARED -> null
                            discardJournal.confirmedPhysicalResult != null -> discardJournal.confirmedPhysicalResult
                            else -> confirmDiscardPhysicalResult(discardJournal)
                        }
                        if (
                            discardJournal.checkpoint == DiscardCheckpoint.COMMAND_ISSUED &&
                            physicalResult == null
                        ) {
                            ToastUtils.showLong("已取消核对，丢弃残留和维护态保持不变")
                            return@launch
                        }
                        val closeout = withContext(Dispatchers.IO) {
                            KaoChangOperate.confirmDiscardJournalRecovery(
                                physicalResult = physicalResult,
                                reason = "维护页现场核对丢弃结果"
                            )
                        }
                        if (closeout.ok) {
                            refreshKaoPanGrid()
                            updateRuntimeStatusUi()
                            ToastUtils.showLong("${closeout.reason}；设备仍保持维护，请再次点击恢复正常状态")
                            AdminOperationLogger.w(
                                action = "核对丢弃残留",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_DONE,
                                detail = "工位=${closeout.positionSn}，烤盘数据清空=${if (closeout.panCleared) "是" else "否"}"
                            )
                        } else {
                            ToastUtils.showLong("丢弃残留未收口：${closeout.reason}；设备继续保持维护")
                            AdminOperationLogger.w(
                                action = "核对丢弃残留",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "工位=${closeout.positionSn}，原因=${closeout.reason}"
                            )
                        }
                        return@launch
                    }
                    val initialConfig = AppConfig.getAppConfig()
                    val initialRecoveredOnlineStatus = resolveManualRecoveredOnlineStatus(initialConfig)
                    if (!needsLocalFaultRecovery(initialConfig)) {
                        val canRetryCloudOnly = ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(
                            onlineStatus = initialConfig.onlineStatus,
                            targetOnlineStatus = initialRecoveredOnlineStatus,
                            errorStatus = initialConfig.errorStatus,
                            moveStatus = initialConfig.moveStatus,
                            isEnable = initialConfig.isEnable,
                            faultPauseActive = FaultHeatPauseManager.shouldSkipBakingCompletion(initialConfig)
                        )
                        if (!canRetryCloudOnly) {
                            ToastUtils.showShort("设备当前无待恢复故障")
                            AdminOperationLogger.w(
                                action = "恢复正常状态",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_REJECT,
                                detail = "拒绝原因=设备当前不处于本机已恢复、云端待确认状态，" +
                                    "设备营业状态=${onlineStatusText(initialConfig.onlineStatus)}，" +
                                    "机械动作状态=${initialConfig.moveStatus}，" +
                                    "烤肠算法开关=${isEnableText(initialConfig.isEnable)}"
                            )
                            return@launch
                        }
                        val cloudDecision = confirmManualRecoveryInCloud(initialRecoveredOnlineStatus)
                        if (cloudDecision.confirmed) {
                            ToastUtils.showLong("本机与后台均已恢复正常状态；请现场确认后手动开启烤制算法")
                            AdminOperationLogger.w(
                                action = "恢复正常状态",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_DONE,
                                detail = "本机状态已正常，仅补交云端恢复确认成功，" +
                                    "设备营业状态=${onlineStatusText(initialConfig.onlineStatus)}，" +
                                    "设备故障状态=${initialConfig.errorStatus}"
                            )
                        } else {
                            ToastUtils.showLong(
                                "本机状态已正常，但后台恢复仍未完成：${cloudDecision.reason}；请稍后重试"
                            )
                            AdminOperationLogger.w(
                                action = "恢复正常状态",
                                page = AdminOperationLogger.PAGE_PAN,
                                result = AdminOperationLogger.RESULT_FAIL,
                                detail = "本机状态已正常，云端补交恢复确认失败，原因=${cloudDecision.reason}"
                            )
                        }
                        return@launch
                    }
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
                        previousOnlineStatus = previousOnlineStatus,
                        previousErrorStatus = previousErrorStatus,
                        previousIsEnable = previousIsEnable,
                        recoveredOnlineStatus = recoveredOnlineStatus,
                        recoveryDecision = recoveryDecision
                    )
                } finally {
                    finishFaultRecoveryOperation(operationToken, "正在恢复设备状态")
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

        val actions = arrayOf("手动取肠", "仅清除本位数据")
        AlertDialog.Builder(requireContext())
            .setTitle("工位 ${kaoPan.positionSn}")
            .setItems(actions) { dialog, which ->
                when (actions[which]) {
                    "手动取肠" -> {
                        resetAdminIdleTimer("手动取肠弹窗确认")
                        showManualTakeSausageDialog(kaoPan)
                    }
                    "仅清除本位数据" -> {
                        resetAdminIdleTimer("仅清除本位数据弹窗确认")
                        showClearPanPositionDataDialog(kaoPan)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                resetAdminIdleTimer("烤盘位维护弹窗取消")
                dialog.dismiss()
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    resetAdminIdleTimer("烤盘位维护弹窗显示")
                }
            }
            .show()
    }

    private fun showManualTakeSausageDialog(kaoPan: KaoPan) {
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

    private fun showClearPanPositionDataDialog(kaoPan: KaoPan) {
        AlertDialog.Builder(requireContext())
            .setTitle("仅清除本位数据")
            .setMessage("确认仅清除工位 ${kaoPan.positionSn} 的本地烤肠数据？\n\n该操作不执行机械动作，不关闭烤肠算法。")
            .setPositiveButton("确认清除") { dialog, _ ->
                resetAdminIdleTimer("仅清除本位数据确认")
                dialog.dismiss()
                clearPanPositionDataOnly(kaoPan.positionSn)
            }
            .setNegativeButton("取消") { dialog, _ ->
                resetAdminIdleTimer("仅清除本位数据取消")
                dialog.dismiss()
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    resetAdminIdleTimer("仅清除本位数据弹窗显示")
                }
            }
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun clearPanPositionDataOnly(positionSn: Int) {
        if (!status.compareAndSet(true, false)) {
            ToastUtils.showShort("请等待当前操作完成")
            return
        }
        val adminBusy = beginAdminBusy("维护页仅清除烤盘位数据")
        setMaintenanceActionButtonsEnabled(false)
        binding.tvCooldownHint.text = "正在清除工位 $positionSn 数据"
        AdminOperationLogger.i(
            action = "仅清除烤盘位数据",
            page = AdminOperationLogger.PAGE_PAN,
            result = AdminOperationLogger.RESULT_START,
            detail = "烤盘位=$positionSn，说明=纯数据清除，不执行机械动作，不关闭烤肠算法"
        )

        lifecycleScope.launch {
            try {
                val initialDecision = buildManualPanPositionClearReadinessDecision(ignoreMoveStatus = true)
                if (!initialDecision.ok) {
                    rejectClearPanPositionData(positionSn, initialDecision.reason)
                    return@launch
                }

                val idleReady = waitUntilMachineIdleOrTimeout(
                    reason = "维护页仅清除烤盘位数据等待空闲",
                    waitingText = "请等待上位机空闲",
                    update = { binding.tvCooldownHint.text = it }
                )
                if (!idleReady) {
                    val busyDecision = buildManualPanPositionClearReadinessDecision()
                    rejectClearPanPositionData(positionSn, busyDecision.reason)
                    ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                    return@launch
                }

                val latestDecision = buildManualPanPositionClearReadinessDecision()
                if (!latestDecision.ok) {
                    rejectClearPanPositionData(positionSn, latestDecision.reason)
                    return@launch
                }

                val clearResult = withContext(Dispatchers.IO) {
                    val config = AppConfig.getAppConfig()
                    ManualPanPositionDataClearExecutor.execute(
                        positionSn = positionSn,
                        bakingTimeMinutes = config.bakingTime,
                        discardTimeHours = config.discardTime
                    )
                }

                if (!clearResult.cleared) {
                    AdminOperationLogger.w(
                        action = "仅清除烤盘位数据",
                        page = AdminOperationLogger.PAGE_PAN,
                        result = AdminOperationLogger.RESULT_REJECT,
                        detail = "烤盘位=$positionSn，拒绝原因=目标烤盘位不存在"
                    )
                    ToastUtils.showShort("未找到工位 $positionSn")
                    return@launch
                }

                refreshKaoPanGrid()
                binding.tvCooldownHint.text = ""
                AdminOperationLogger.i(
                    action = "仅清除烤盘位数据",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_DONE,
                    detail = "清除前=${clearResult.before?.toLogText()}，清除后=${clearResult.after?.toLogText()}，" +
                        "云端同步=SnapshotSyncPolicy.maybeUploadSnapshot"
                )
                ToastUtils.showShort("工位 $positionSn 数据已清除")
            } catch (e: Exception) {
                AdminOperationLogger.e(
                    action = "仅清除烤盘位数据",
                    page = AdminOperationLogger.PAGE_PAN,
                    result = AdminOperationLogger.RESULT_FAIL,
                    detail = "烤盘位=$positionSn，失败原因=${e.message ?: e.javaClass.simpleName}"
                )
                ToastUtils.showShort("清除失败，请查看日志")
            } finally {
                binding.tvCooldownHint.text = ""
                setMaintenanceActionButtonsEnabled(true)
                status.set(true)
                endAdminBusy(adminBusy)
            }
        }
    }

    private fun buildManualPanPositionClearReadinessDecision(
        ignoreMoveStatus: Boolean = false
    ): ManualPanPositionDataClearPolicy.ReadinessDecision {
        val config = AppConfig.getAppConfig()
        return ManualPanPositionDataClearPolicy.checkReadiness(
            ManualPanPositionDataClearPolicy.State(
                moveStatus = if (ignoreMoveStatus) 0 else config.moveStatus,
                onlineStatus = config.onlineStatus,
                hasProcessingOrder = OrderStateManager.hasProcessingOrder(),
                hasOrderTracking = SelfCleanManager.hasOrderTracking()
            )
        )
    }

    private fun rejectClearPanPositionData(positionSn: Int, reason: String) {
        AdminOperationLogger.w(
            action = "仅清除烤盘位数据",
            page = AdminOperationLogger.PAGE_PAN,
            result = AdminOperationLogger.RESULT_REJECT,
            detail = "烤盘位=$positionSn，拒绝原因=$reason"
        )
        ToastUtils.showShort("当前不允许清除：$reason")
    }

    /**
     * 每秒刷新一次烤盘网格与错误状态显示。
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        algorithmSwitchFallbackTickCount = 0
        remoteRuntimeStatusRefreshTickCount = 0
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1_000L)
                }
            }.collect {
                refreshKaoPanGrid()
                algorithmSwitchFallbackTickCount++
                if (algorithmSwitchFallbackTickCount >= ALGORITHM_SWITCH_FALLBACK_SYNC_INTERVAL_SECONDS) {
                    algorithmSwitchFallbackTickCount = 0
                    syncAlgorithmSwitchFromRuntimeState("5秒低频兜底同步")
                    syncInspectionSwitchFromRuntimeState("5秒低频兜底同步")
                }
                updateLowerPlatformUi()
                updateRuntimeStatusUi()
                remoteRuntimeStatusRefreshTickCount++
                if (remoteRuntimeStatusRefreshTickCount >= REMOTE_RUNTIME_STATUS_REFRESH_INTERVAL_SECONDS) {
                    remoteRuntimeStatusRefreshTickCount = 0
                    refreshRemoteRuntimeStatusForDisplay("10秒定时刷新")
                }
                binding.tvAppUpdateStatus.text = "更新：${AppUpdateManager.lastStatusText()}"
            }
        }
    }

    override fun onDestroyView() {
        operationGate.invalidateAll()
        manualRecoveryInProgress.set(false)
        status.set(true)
        algorithmCooldownJob?.cancel()
        isAlgorithmCoolingDown = false
        super.onDestroyView()
        MaintenanceUiRefreshBridge.unregister(maintenanceUiRefreshCallback)
        refreshJob?.cancel()
        _binding = null
    }
}
