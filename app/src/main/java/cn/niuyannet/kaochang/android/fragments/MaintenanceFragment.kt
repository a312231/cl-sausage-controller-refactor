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
    private var algorithmCooldownJob: Job? = null
    private var clearPanCooldownJob: Job? = null
    private var resetPanCooldownJob: Job? = null

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
        if (config.available != 0L) {
            LogUtils.i("【维护操作】$reason，同步清零二维码页预计烤制时间：available=${config.available}->0")
            config.available = 0L
            AppConfig.saveAppConfig(config)
            DataManagementAPI.uploadDeviceInfo()
        }
    }

    private fun setMaintenanceActionButtonsEnabled(enabled: Boolean) {
        binding.btnCz.isEnabled = enabled
        binding.btnClearAll.isEnabled = enabled
        binding.swKzAlgorithm.isEnabled = enabled
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
        binding.swKzAlgorithm.isChecked = KaoChangAlgorithm.isEnabled

        binding.btnClearAll.setOnClickListener {
            if (clearPanCooldownJob?.isActive == true) return@setOnClickListener
            LogUtils.d("【维护操作】点击清空烤盘，准备停止烤制算法并清空全部烤盘")

            val width = binding.btnClearAll.width
            binding.btnClearAll.minWidth = width
            binding.btnClearAll.maxWidth = width
            setMaintenanceActionButtonsEnabled(false)

            KaoChangAlgorithm.stopServer()
            lifecycleScope.launch {
                waitUntilMachineIdle("等待完成") { binding.btnClearAll.text = it }
                delay(10_000L)
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                clearAvailableAfterManualPanReset("点击清空烤盘")
                KaoChangAlgorithm.runServer()
            }

            clearPanCooldownJob = startCountdown(
                seconds = 15,
                onTick = { binding.btnClearAll.text = "冷却${String.format("%2d", it)}秒" },
                onFinish = {
                    setMaintenanceActionButtonsEnabled(true)
                    binding.btnClearAll.text = "清空烤盘"
                }
            )
        }

        binding.swKzAlgorithm.setOnCheckedChangeListener { buttonView, isChecked ->
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
            algorithmCooldownJob?.cancel()
            algorithmCooldownJob = lifecycleScope.launch {
                waitUntilMachineIdle("等待完成") { binding.tvCooldownHint.text = it }
                var remaining = 10
                while (remaining > 0) {
                    binding.tvCooldownHint.text = "冷却 ${String.format("%2d", remaining)} 秒"
                    delay(1_000L)
                    remaining--
                }
                binding.tvCooldownHint.text = "冷却完成"
                buttonView.isEnabled = true
                isAlgorithmCoolingDown = false
                binding.btnCz.isEnabled = !isChecked
                binding.btnClearAll.isEnabled = !isChecked
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
            LogUtils.d("【维护操作】点击丢弃所有烤肠并重置烤盘，准备停止烤制算法并重置烤盘状态")

            val width = binding.btnCz.width
            binding.btnCz.minWidth = width
            binding.btnCz.maxWidth = width
            setMaintenanceActionButtonsEnabled(false)

            KaoChangAlgorithm.stopServer()
            lifecycleScope.launch {
                waitUntilMachineIdle("请等待上位机动作完成") { binding.btnCz.text = it }
                KaoChangAlgorithm.resetKaoPan()
                delay(10_000L)
                KaoPanHelper.clearKaoPan()
                KaoPanHelper.init()
                clearAvailableAfterManualPanReset("点击丢弃所有烤肠并重置烤盘")
                KaoChangAlgorithm.runServer()
            }

            resetPanCooldownJob = startCountdown(
                seconds = 15,
                onTick = { binding.btnCz.text = "冷却${String.format("%2d", it)}秒" },
                onFinish = {
                    setMaintenanceActionButtonsEnabled(true)
                    binding.btnCz.text = "丢弃所有烤肠重置烤盘"
                }
            )
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
