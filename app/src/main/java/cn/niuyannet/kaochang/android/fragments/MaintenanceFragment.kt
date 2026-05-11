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
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.MaintenanceModbusRecoveryConnector
import cn.niuyannet.kaochang.android.services.BusinessTimeWindowHelper
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.TemperatureControlPolicy
import cn.niuyannet.kaochang.android.ui.LogViewActivity
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
        0 -> "0（未启用）"
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
        MaintenanceUiRefreshBridge.register(maintenanceUiRefreshCallback)
        setupGrid()
        setupButtons()
        syncAlgorithmSwitchFromRuntimeState("维护页初始化", forceLog = true)
        syncInspectionSwitchFromRuntimeState("维护页初始化", forceLog = true)
    }

    private fun clearRuntimeStateAfterManualPanReset(reason: String) {
        val config = AppConfig.getAppConfig()
        val oldAvailable = config.available
        val oldAvailableCountdownLatched = config.availableCountdownLatched
        val oldTransitionMode = config.transitionMode
        val needReset =
            oldAvailable != 0L ||
                oldAvailableCountdownLatched != 0 ||
                oldTransitionMode != 0
        if (!needReset) {
            LogUtils.d(
                "【维护操作】$reason，运行态已完成必要收口，保持原并发模式：" +
                    "available=0，availableCountdownLatched=0，" +
                    "modeType（并发模式）=${modeText(config.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}"
            )
            return
        }

        config.available = 0L
        config.availableCountdownLatched = 0
        config.transitionMode = 0
        AppConfig.saveAppConfig(config)
        LogUtils.i(
            "【维护操作】$reason，已重置运行态：" +
                "available（二维码页预计时间）=$oldAvailable->0，" +
                "availableCountdownLatched（二维码页预计时间锁存标记）=$oldAvailableCountdownLatched->0，" +
                "modeType（并发模式）保持=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}"
        )
        val runtimePublishOk = SendServerHelper.publishServiceUpdateStatus()
        val availablePublishOk = SendServerHelper.publishAvailableState("maintenance:$reason")
        if (!runtimePublishOk || !availablePublishOk) {
            LogUtils.w(
                "【维护操作】$reason，MQTT 主上报失败，回退 HTTP 补偿：" +
                    "runtimePublishOk=$runtimePublishOk，availablePublishOk=$availablePublishOk"
            )
            DataManagementAPI.uploadDeviceInfo()
        }
    }

    private fun setMaintenanceActionButtonsEnabled(enabled: Boolean) {
        binding.btnCz.isEnabled = enabled
        binding.btnClearAll.isEnabled = enabled
        binding.swKzAlgorithm.isEnabled = enabled
    }

    private fun setMaintenanceButtonsEnabled(enabled: Boolean) {
        binding.btnCz.isEnabled = enabled
        binding.btnClearAll.isEnabled = enabled
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
        val status201 = withContext(Dispatchers.IO) {
            if (!VMModbusHelper.connectStatus()) {
                -1
            } else {
                VMModbusHelper.readHoldingRegisters(
                    KaoChangOperate.modbus_address,
                    KaoChangOperate.action_address_status
                )
            }
        }
        if (status201 == 0) {
            val oldMoveStatus = config.moveStatus
            config.moveStatus = 0
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【维护操作】检测到本地机械状态与下位机空闲状态不一致，已自动收口：" +
                    "reason=$reason，moveStatus（机械动作状态）=$oldMoveStatus->0，status201（下位机动作状态）=0"
            )
            return true
        }
        return false
    }

    private suspend fun evaluateManualRecoveryHeating(
        targetOnlineStatus: Int
    ): TemperatureControlPolicy.ManualRecoveryHeatingDecision {
        return withContext(Dispatchers.IO) {
            if (!VMModbusHelper.connectStatus()) {
                return@withContext TemperatureControlPolicy.decideManualRecoveryHeating(
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

            TemperatureControlPolicy.decideManualRecoveryHeating(
                modbusConnected = true,
                actionGroupReady = actionGroupReady,
                legacyActionStatus201 = legacyActionStatus201,
                moveStatus = currentConfig.moveStatus,
                panMoveResult203 = panMoveResult203,
                targetOnlineStatus = targetOnlineStatus
            )
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
        binding.btnClearAll.setOnClickListener {
            if (clearPanCooldownJob?.isActive == true) return@setOnClickListener

            LogUtils.d("【维护操作】点击清空烤盘，准备立即关闭烤制算法并清空全部烤盘")
            setMaintenanceActionButtonsEnabled(false)
            binding.tvCooldownHint.text = "正在清空烤盘"
            disableAlgorithmForMaintenance("点击清空烤盘")

            clearPanCooldownJob = lifecycleScope.launch {
                val idleReady = waitUntilMachineIdleOrTimeout(
                    reason = "维护页点击清空烤盘等待空闲",
                    waitingText = "等待机械动作完成后清空烤盘",
                    update = {
                        binding.tvCooldownHint.text = it
                    }
                )
                if (!idleReady) {
                    LogUtils.w("【维护操作】点击清空烤盘等待机械空闲超时，已取消本次操作")
                    binding.tvCooldownHint.text = ""
                    setMaintenanceActionButtonsEnabled(true)
                    ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                    clearPanCooldownJob = null
                    return@launch
                }
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                KaoChangOperate.clearPendingSupplementBatchIntent("维护页点击清空烤盘")
                KaoChangOperate.stopAllHeatingIfNoSausage("维护页点击清空烤盘", force = true)
                clearRuntimeStateAfterManualPanReset("点击清空烤盘")
                LogUtils.i("【维护操作】清空烤盘完成，烤制算法保持关闭，进入15秒防重复操作冷却")
                finishMaintenanceAction()
                clearPanCooldownJob = startMaintenanceCooldown(target = "clear") {
                    setMaintenanceActionButtonsEnabled(true)
                    binding.btnClearAll.text = "清空烤盘"
                    binding.tvCooldownHint.text = ""
                    clearPanCooldownJob = null
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
                LogUtils.i(
                    "【维护操作】手动切换烤肠算法完成：" +
                        "requestedChecked=$enable，" +
                        "isEnable（烤肠算法开关）=$beforeIsEnable->${afterConfig.isEnable}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(afterConfig.onlineStatus)}，" +
                        "modeType（并发模式）=${modeText(afterConfig.modeType)}，" +
                        "transitionMode（并发切换过渡态）=${transitionText(afterConfig.transitionMode)}"
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
                LogUtils.w(
                    "【维护操作】烤肠算法开关处于冷却中，拒绝本次切换：" +
                        "requestedChecked=$isChecked，" +
                        "isEnable（烤肠算法开关）=${currentConfig.isEnable}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}，" +
                        "modeType（并发模式）=${modeText(currentConfig.modeType)}，" +
                        "transitionMode（并发切换过渡态）=${transitionText(currentConfig.transitionMode)}"
                )
                setAlgorithmSwitchCheckedSilently(!isChecked)
                return@setOnCheckedChangeListener
            }

            LogUtils.i(
                "【维护操作】收到手动切换烤肠算法请求：" +
                    "requestedChecked=$isChecked，" +
                    "isEnable（烤肠算法开关）=${currentConfig.isEnable}，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}，" +
                    "modeType（并发模式）=${modeText(currentConfig.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${transitionText(currentConfig.transitionMode)}"
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
                        LogUtils.w(
                            "【维护操作】手动开启烤肠算法前同步云端状态失败，拒绝本次开启：" +
                                "onlineStatus（设备营业状态）=${onlineStatusText(latestConfig.onlineStatus)}，" +
                                "isEnable（烤肠算法开关）=${latestConfig.isEnable}，" +
                                "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(latestConfig.restStatusSource)}"
                        )
                        syncAlgorithmSwitchFromRuntimeState("开启算法前同步失败后回滚UI", forceLog = true)
                        ToastUtils.showShort("同步云端设备状态失败，请稍后重试")
                        restoreSwitchControls()
                        return@runOnUiThread
                    }

                    if (latestConfig.onlineStatus in setOf(0, 2, 3) || latestConfig.inspectionMode == 1 || latestConfig.errorStatus != 0) {
                        LogUtils.w(
                            "【维护操作】手动开启烤肠算法前已同步到云端最新状态，本次拒绝开启：" +
                                "onlineStatus（设备营业状态）=${onlineStatusText(latestConfig.onlineStatus)}，" +
                                "inspectionMode（检修模式）=${inspectionModeText(latestConfig.inspectionMode)}，" +
                                "errorStatus（设备故障状态）=${latestConfig.errorStatus}，" +
                                "isEnable（烤肠算法开关）=${latestConfig.isEnable}，" +
                                "restStatusSource（休息中来源）=${AppConfig.restStatusSourceText(latestConfig.restStatusSource)}"
                        )
                        syncAlgorithmSwitchFromRuntimeState("开启算法前云端状态不允许后回滚UI", forceLog = true)
                        val tip = when {
                            latestConfig.inspectionMode == 1 -> "设备当前处于检修模式，请先退出检修模式再开启算法"
                            latestConfig.errorStatus != 0 -> "设备当前存在故障，请先恢复设备状态再开启算法"
                            latestConfig.onlineStatus == 0 -> "后台设备状态为未启用，请先启用后再开启算法"
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
                LogUtils.i(
                    "【检修模式】已进入检修模式：" +
                        "inspectionMode（检修模式）=${inspectionModeText(config.inspectionMode)}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                        "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}"
                )
            } else {
                val snapshot = AppConfig.getInspectionSnapshot()
                config.inspectionMode = 0
                AppConfig.saveAppConfig(config)
                LogUtils.i(
                    "【检修模式】已退出检修模式：" +
                        "inspectionMode（检修模式）=${inspectionModeText(config.inspectionMode)}，" +
                        "snapshotFound=${snapshot != null}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                        "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}"
                )
                AppConfig.clearInspectionSnapshot()
            }
            val runtimePublishOk = SendServerHelper.publishServiceUpdateStatus()
            if (!runtimePublishOk) {
                LogUtils.w("【检修模式】检修模式状态 MQTT 主上报失败，回退 HTTP 补偿")
                DataManagementAPI.uploadDeviceInfo()
            }
            syncInspectionSwitchFromRuntimeState("本地手动切换检修模式完成", forceLog = true)
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

            LogUtils.d("【维护操作】点击丢弃所有烤肠并重置烤盘，准备立即关闭烤制算法并重置烤盘状态")
            setMaintenanceActionButtonsEnabled(false)
            binding.tvCooldownHint.text = "正在丢弃并重置烤盘"
            disableAlgorithmForMaintenance("点击丢弃所有烤肠并重置烤盘")

            resetPanCooldownJob = lifecycleScope.launch {
                val idleReady = waitUntilMachineIdleOrTimeout(
                    reason = "维护页点击重置烤盘等待空闲",
                    waitingText = "等待机械动作完成后重置烤盘",
                    update = { binding.tvCooldownHint.text = it }
                )
                if (!idleReady) {
                    LogUtils.w("【维护操作】点击重置烤盘等待机械空闲超时，已取消本次操作")
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
                KaoChangOperate.clearPendingSupplementBatchIntent("维护页点击丢弃所有烤肠并重置烤盘")
                KaoChangOperate.stopAllHeatingIfNoSausage("维护页点击丢弃所有烤肠并重置烤盘", force = true)
                clearRuntimeStateAfterManualPanReset("点击丢弃所有烤肠并重置烤盘")
                LogUtils.i("【维护操作】重置烤盘完成，烤制算法保持关闭，进入15秒防重复操作冷却")
                finishMaintenanceAction()
                resetPanCooldownJob = startMaintenanceCooldown(target = "reset") {
                    setMaintenanceActionButtonsEnabled(true)
                    binding.btnCz.text = "丢弃所有烤肠重置烤盘"
                    binding.tvCooldownHint.text = ""
                    resetPanCooldownJob = null
                }
            }
        }

        binding.btnUpdate.setOnClickListener {
            kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
            kaoPanAdapter.notifyDataSetChanged()
        }

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(requireContext(), LogViewActivity::class.java))
        }

        binding.btnErroStatus.setOnClickListener {
            if (!manualRecoveryInProgress.compareAndSet(false, true)) {
                ToastUtils.showShort("正在恢复设备状态，请稍候")
                return@setOnClickListener
            }
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
                        LogUtils.w(
                            "【维护操作】拒绝恢复正常状态：下位机串口仍未连接，" +
                                "modbusAddress（Modbus串口地址）=${latestConfig.modbusAddress}，" +
                                "onlineStatus（设备营业状态）=${onlineStatusText(latestConfig.onlineStatus)}，" +
                                "errorStatus（设备故障状态）=${latestConfig.errorStatus}，" +
                                "moveStatus（机械动作状态）=${latestConfig.moveStatus}"
                        )
                        return@launch
                    }
                    val config = AppConfig.getAppConfig()
                    val previousOnlineStatus = config.onlineStatus
                    val previousErrorStatus = config.errorStatus
                    val previousScanBlockStatus = config.scanBlockStatus
                    val previousIsEnable = config.isEnable
                    val recoveredOnlineStatus = resolveManualRecoveredOnlineStatus(config)
                    config.errorStatus = 0
                    config.onlineStatus = recoveredOnlineStatus
                    config.scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
                    config.isEnable = 0
                    withContext(Dispatchers.IO) {
                        AppConfig.saveAppConfig(config)
                        KaoChangOperate.stopHeatingForFault("维护页恢复正常状态后保持算法关闭，等待现场手动开启算法")
                    }
                    val runtimePublishOk = withContext(Dispatchers.IO) {
                        SendServerHelper.publishServiceUpdateStatus()
                    }
                    if (!runtimePublishOk) {
                        LogUtils.w(
                            "【维护操作】恢复正常状态 MQTT 上报失败，回退 HTTP 补偿：" +
                                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                                "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(config.scanBlockStatus)}，" +
                                "errorStatus（设备故障状态）=${config.errorStatus}"
                        )
                        withContext(Dispatchers.IO) {
                            DataManagementAPI.uploadDeviceInfo()
                        }
                    }
                    ToastUtils.showLong("设备已恢复正常状态；请现场确认后手动开启烤制算法")
                    LogUtils.w(
                        "【维护操作】上位机已完成恢复正常状态：" +
                            "onlineStatus（设备营业状态）=${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(config.onlineStatus)}，" +
                            "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(previousScanBlockStatus)}->${scanBlockStatusText(config.scanBlockStatus)}，" +
                            "errorStatus（设备故障状态）=$previousErrorStatus->${config.errorStatus}（正常），" +
                            "isEnable（烤肠算法开关）=${isEnableText(previousIsEnable)}->${isEnableText(config.isEnable)}，" +
                            "恢复入口=上位机维护页，后续动作=现场手动开启烤制算法"
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

    private fun scanBlockStatusText(value: Int): String = when (value) {
        AppConfigBean.SCAN_BLOCK_STATUS_NORMAL -> "0（不屏蔽）"
        AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> "1（屏蔽）"
        else -> "$value（未知）"
    }

    /**
     * 手动从烤盘取出一根烤肠，仅用于维护页人工操作。
     */
    private fun showPurchaseOrderDialog(kaoPan: KaoPan) {
        if (!kaoPan.isHasSausage) {
            ToastUtils.showShort("没有烤肠")
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("是否要取出烤肠？")
            .setPositiveButton("确定") { dialog, _ ->
                if (!status.compareAndSet(true, false)) {
                    ToastUtils.showShort("请等待当前操作完成")
                    return@setPositiveButton
                }
                val tasteLabel = kaoPan.taste.productName?.takeIf { it.isNotBlank() }
                    ?: kaoPan.taste.tasteName?.takeIf { it.isNotBlank() }
                    ?: "未知口味"
                LogUtils.d("【维护操作】手动取肠确认：准备从烤盘${kaoPan.positionSn}取出烤肠($tasteLabel)")

                lifecycleScope.launch {
                    try {
                        val modbusConnected = withContext(Dispatchers.IO) {
                            MaintenanceModbusRecoveryConnector.ensureConnected()
                        }
                        if (!modbusConnected) {
                            ToastUtils.showShort("下位机未连接，请先恢复通信")
                            LogUtils.w(
                                "【维护操作】拒绝手动取肠：下位机当前未连接，" +
                                    "modbusAddress（Modbus串口地址）=${AppConfig.getAppConfig().modbusAddress}"
                            )
                            return@launch
                        }
                        val idleReady = waitUntilMachineIdleOrTimeout(
                            reason = "维护页手动取肠等待空闲",
                            waitingText = "请等待上位机空闲"
                        )
                        if (!idleReady) {
                            LogUtils.w(
                                "【维护操作】手动取肠等待机械空闲超时：" +
                                    "moveStatus（机械动作状态）=${AppConfig.getAppConfig().moveStatus}"
                            )
                            ToastUtils.showShort("设备当前机械状态未归零，请先检查下位机连接或恢复状态")
                            return@launch
                        }
                        val result = withContext(Dispatchers.IO) {
                            KaoChangOperate.takeSausage(kaoPan)
                        }
                        if (!result) {
                            ToastUtils.showShort("手动取肠失败，请查看日志")
                        }
                    } finally {
                        dialog.dismiss()
                        status.set(true)
                    }
                }
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
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
