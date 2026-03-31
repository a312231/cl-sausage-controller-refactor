package cn.niuyannet.kaochang.android.fragments

import android.annotation.SuppressLint
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
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.ui.LogViewActivity
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MaintenanceFragment : Fragment() {
    private var _binding: FragmentMaintenanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var kaoPanAdapter: KaoPanAdapter
    private var refreshJob: Job? = null

    private val status = AtomicBoolean(true)

    private var isAlgorithmCoolingDown = false
    private var ignoreAlgorithmSwitchChange = false
    private var algorithmCooldownJob: Job? = null
    private var clearPanCooldownJob: Job? = null
    private var resetPanCooldownJob: Job? = null

    companion object {
        private const val MAINTENANCE_ACTION_COOLDOWN_SECONDS = 15
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
        setupGrid()
        setupButtons()
    }

    private fun clearAvailableAfterManualPanReset(reason: String) {
        val config = AppConfig.getAppConfig()
        if (config.available != 0L || config.availableCountdownLatched != 0) {
            LogUtils.i("【维护操作】$reason，同步清零二维码页预计烤制时间：available=${config.available}->0")
            config.available = 0L
            config.availableCountdownLatched = 0
            AppConfig.saveAppConfig(config)
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

    private fun disableAlgorithmForMaintenance(reason: String) {
        if (KaoChangAlgorithm.isEnabled || AppConfig.getAppConfig().isEnable == 1) {
            LogUtils.i("【维护操作】$reason，立即关闭烤制算法并保持关闭，防止继续自动补肠")
            KaoChangAlgorithm.disableRoastAlgorithm()
        } else {
            LogUtils.i("【维护操作】$reason，烤制算法当前已关闭")
        }
        setAlgorithmSwitchCheckedSilently(false)
    }

    private fun finishMaintenanceAction() {
        setAlgorithmSwitchCheckedSilently(false)
        binding.tvCooldownHint.text = ""
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

    private suspend fun waitUntilMachineIdle(waitingText: String, update: (String) -> Unit) {
        while (AppConfig.getAppConfig().moveStatus != 0) {
            update(waitingText)
            delay(2_000L)
        }
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
        setAlgorithmSwitchCheckedSilently(KaoChangAlgorithm.isEnabled)

        binding.btnClearAll.setOnClickListener {
            if (clearPanCooldownJob?.isActive == true) return@setOnClickListener

            LogUtils.d("【维护操作】点击清空烤盘，准备立即关闭烤制算法并清空全部烤盘")
            setMaintenanceActionButtonsEnabled(false)
            binding.tvCooldownHint.text = "正在清空烤盘"
            disableAlgorithmForMaintenance("点击清空烤盘")

            clearPanCooldownJob = lifecycleScope.launch {
                waitUntilMachineIdle("等待机械动作完成后清空烤盘") {
                    binding.tvCooldownHint.text = it
                }
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                clearAvailableAfterManualPanReset("点击清空烤盘")
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
            if (ignoreAlgorithmSwitchChange) {
                return@setOnCheckedChangeListener
            }
            if (isAlgorithmCoolingDown) {
                buttonView.isChecked = !isChecked
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                KaoChangAlgorithm.enableRoastAlgorithm()
            } else {
                KaoChangAlgorithm.disableRoastAlgorithm()
            }

            isAlgorithmCoolingDown = true
            buttonView.isEnabled = false
            setMaintenanceButtonsEnabled(false)
            algorithmCooldownJob?.cancel()
            algorithmCooldownJob = lifecycleScope.launch {
                waitUntilMachineIdle("等待机械动作完成") { binding.tvCooldownHint.text = it }
                var remaining = 10
                while (remaining > 0) {
                    binding.tvCooldownHint.text = "冷却 ${String.format("%2d", remaining)} 秒"
                    delay(1_000L)
                    remaining--
                }
                binding.tvCooldownHint.text = ""
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
                waitUntilMachineIdle("等待机械动作完成后重置烤盘") {
                    binding.tvCooldownHint.text = it
                }
                KaoChangAlgorithm.resetKaoPan { current, total, _ ->
                    binding.tvCooldownHint.text = "正在丢弃烤肠（当前第 $current 根 / 共 $total 根）"
                }
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                clearAvailableAfterManualPanReset("点击丢弃所有烤肠并重置烤盘")
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
            lifecycleScope.launch {
                val config = AppConfig.getAppConfig()
                val targetOnlineStatus =
                    if (isWithinBusinessHours(getCurrentTime(), config.businessTime)) 1 else 2
                LogUtils.d(
                    "【维护操作】点击恢复正常状态，errorStatus: ${config.errorStatus}->0, " +
                        "onlineStatus: ${config.onlineStatus}->$targetOnlineStatus"
                )
                config.errorStatus = 0
                config.onlineStatus = targetOnlineStatus
                AppConfig.saveAppConfig(config)
                DataManagementAPI.uploadDeviceInfo()
            }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().time)
    }

    /**
     * 根据当前营业时间配置判断“恢复正常状态”后应该回到运营中还是休息中。
     * 兼容 HH:mm 和 HH:mm:ss 两种时间格式。
     */
    private fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        try {
            if (businessTime == null) return false

            val current = parseBusinessTime(currentTimeStr) ?: return false
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                7
            } else {
                calendar.get(Calendar.DAY_OF_WEEK) - 1
            }

            if ("everyday".equals(businessTime.mode, ignoreCase = true) &&
                businessTime.defaultTime != null &&
                businessTime.defaultTime.size >= 2
            ) {
                val start = parseBusinessTime(businessTime.defaultTime[0]) ?: return false
                val end = parseBusinessTime(businessTime.defaultTime[1]) ?: return false
                return isTimeInRange(current, start, end)
            }

            if ("custom".equals(businessTime.mode, ignoreCase = true) &&
                businessTime.customTimes != null
            ) {
                for (timeItem in businessTime.customTimes) {
                    if (timeItem.day == dayOfWeek &&
                        timeItem.enabled &&
                        timeItem.timeRange != null &&
                        timeItem.timeRange.size >= 2
                    ) {
                        val start = parseBusinessTime(timeItem.timeRange[0]) ?: return false
                        val end = parseBusinessTime(timeItem.timeRange[1]) ?: return false
                        return isTimeInRange(current, start, end)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e("【维护操作】恢复设备状态时解析营业时间失败：${e.message}")
        }
        return false
    }

    private fun parseBusinessTime(value: String): Date? {
        val formats = listOf("HH:mm:ss", "HH:mm")
        for (pattern in formats) {
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(value)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun isTimeInRange(current: Date, start: Date, end: Date): Boolean {
        return if (start.before(end) || start == end) {
            (current.after(start) || current == start) && (current.before(end) || current == end)
        } else {
            (current.after(start) || current == start) || (current.before(end) || current == end)
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

        AlertDialog.Builder(requireContext())
            .setTitle("确认")
            .setMessage("是否要取出烤肠？")
            .setPositiveButton("确定") { dialog, _ ->
                if (!status.get()) {
                    ToastUtils.showShort("请等待当前操作完成")
                    return@setPositiveButton
                }
                status.set(false)
                val tasteLabel = kaoPan.taste.productName?.takeIf { it.isNotBlank() }
                    ?: kaoPan.taste.tasteName?.takeIf { it.isNotBlank() }
                    ?: "未知口味"
                LogUtils.d("【维护操作】手动取肠确认：准备从烤盘${kaoPan.positionSn}取出烤肠($tasteLabel)")

                lifecycleScope.launch {
                    waitUntilMachineIdle("请等待上位机空闲") { }
                    try {
                        AppConfig.getAppConfig().moveStatus = 1
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
                        KaoChangOperate.takeSausage(kaoPan)
                    } finally {
                        AppConfig.getAppConfig().moveStatus = 0
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
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
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1_000L)
                }
            }.collect {
                kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
                kaoPanAdapter.notifyDataSetChanged()
                binding.tvErroStatus.apply {
                    val isNormal = AppConfig.getAppConfig().errorStatus == 0
                    text = if (isNormal) "状态：正常" else "状态：错误"
                    setTextColor(
                        resources.getColor(
                            if (isNormal) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                        )
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}
