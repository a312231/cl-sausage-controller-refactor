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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 记录是否处于冷却中
    // 烤制算法
    private var isCoolingDown_1 = false
    private var cooldownJob_1: Job? = null
    // 清空烤盘
    private var cooldownJob_2: Job? = null
    // 重置烤盘
    private var cooldownJob_3: Job? = null

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
    /**
     * 设置按钮点击事件
     */
    private fun setupButtons() {
        binding.swKzAlgorithm.isChecked = KaoChangAlgorithm.isEnabled
        binding.btnClearAll.setOnClickListener {
            //KaoPanHelper.resetKaoPan()
            //重置烤盘
            // 如果仍在冷却中，直接返回
            if (cooldownJob_2?.isActive == true) return@setOnClickListener
            // 锁宽
            val w = binding.btnClearAll.width
            binding.btnClearAll.minWidth = w
            binding.btnClearAll.maxWidth = w

            // 1️⃣ 禁用按钮，防止重复点击
            binding.btnCz.isEnabled = false
            binding.btnClearAll.isEnabled = false
            binding.swKzAlgorithm.isEnabled = false

            // 2️⃣ 启动冷却倒计时（15 秒）
            var remaining = 15

            KaoChangAlgorithm.stopServer()//停止烤肠服务

            lifecycleScope.launch(Dispatchers.IO) {
                while (AppConfig.getAppConfig().moveStatus != 0) {
                    binding.btnClearAll.text = "等待完成"
                    delay(2*1000)
                }
                // KaoChangAlgorithm.resetKaoPan()
                withContext(Dispatchers.Main) {
                    delay(10*1000)// 等待15s
                    KaoPanHelper.clearKaoPan()
                    KaoPanHelper.init()
                    KaoChangAlgorithm.runServer()//运行烤肠服务
                }
            }

            cooldownJob_2 = lifecycleScope.launch {
                while (AppConfig.getAppConfig().moveStatus != 0) {
                    binding.btnClearAll.text = "等待完成"
                    delay(2*1000)
                }
                while (remaining > 0) {
                    delay(1000)
                    remaining--
                    binding.btnClearAll.text = "冷却${String.format("%2d", remaining)}秒"
                }

                // 倒计时结束，恢复按钮状态
                binding.btnClearAll.isEnabled = true
                binding.btnCz.isEnabled = true
                binding.swKzAlgorithm.isEnabled = true
                binding.btnClearAll.text = "清空烤盘"
            }
        }
        binding.swKzAlgorithm.setOnCheckedChangeListener { buttonView, isChecked ->

            // 根据开关状态启用/禁用烤制算法
            if (isCoolingDown_1) {
                // 恢复上一个状态（避免UI错乱）
                buttonView.isChecked = !isChecked
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                KaoChangAlgorithm.enableRoastAlgorithm()
            } else {
                KaoChangAlgorithm.disableRoastAlgorithm()
            }
            // 启动10秒冷却
            isCoolingDown_1 = true
            buttonView.isEnabled = false
            // 启动倒计时协程
            cooldownJob_1?.cancel()
            cooldownJob_1 = lifecycleScope.launch(Dispatchers.Main) {
                while (AppConfig.getAppConfig().moveStatus != 0) {
                    binding.tvCooldownHint.text = " 等待完成 "
                    delay(2*1000)
                }
                var remaining = 10
                while (remaining > 0) {
                    binding.tvCooldownHint.text = "冷却 ${String.format("%2d", remaining)} 秒"
                    delay(1000)
                    remaining--
                }
                // 冷却结束
                binding.tvCooldownHint.text = "冷却 10 秒"
                buttonView.isEnabled = true
                isCoolingDown_1 = false

                binding.btnCz.isEnabled = !isChecked
                binding.btnClearAll.isEnabled = !isChecked
            }
        }
    }
    
    /**
     * 设置数据
     */
    @SuppressLint("NotifyDataSetChanged")
    @OptIn(DelicateCoroutinesApi::class)
    private fun setupGrid() {
        // 设置烤盘列表适配器
        kaoPanAdapter = KaoPanAdapter {
            //弹框取出烤肠
            showPurchaseOrderDialog(it)
        }
        binding.rvKaopan.apply {
            layoutManager = GridLayoutManager(context, 11)
            adapter = kaoPanAdapter
        }
        kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
        // 开始定时刷新
        startRefreshTimer()
        
        binding.btnCz.setOnClickListener {
            //重置烤盘
            // 如果仍在冷却中，直接返回
            if (cooldownJob_3?.isActive == true) return@setOnClickListener
            // 锁宽
            val w = binding.btnCz.width
            binding.btnCz.minWidth = w
            binding.btnCz.maxWidth = w
            // 如果在运动中，直接返回

            // 1️⃣ 禁用按钮，防止重复点击
            binding.btnCz.isEnabled = false
            binding.btnClearAll.isEnabled = false
            binding.swKzAlgorithm.isEnabled = false
            // 2️⃣ 启动冷却倒计时（15 秒）
            var remaining = 15
            //重置烤盘
            KaoChangAlgorithm.stopServer()//停止烤肠服务


            lifecycleScope.launch(Dispatchers.IO) {
                while (AppConfig.getAppConfig().moveStatus != 0) {
                    binding.btnCz.text = "请等待上位机动作完成"
                    delay(2*1000)
                }

                KaoChangAlgorithm.resetKaoPan()
                delay(10*1000)// 等待10s 确保旧烤肠服务终止
                withContext(Dispatchers.Main) {
                    KaoPanHelper.clearKaoPan()
                    KaoPanHelper.init()
                    KaoChangAlgorithm.runServer()//运行烤肠服务
                }
            }

            cooldownJob_3 = lifecycleScope.launch {
                while (AppConfig.getAppConfig().moveStatus != 0) {
                    binding.btnCz.text = "请等待上位机动作完成"
                    delay(2*1000)
                }
                while (remaining > 0) {
                    delay(1000)
                    remaining--
                    binding.btnCz.text = "      冷却${String.format("%2d", remaining)}秒      "
                }

                // 倒计时结束，恢复按钮状态
                binding.btnCz.isEnabled = true
                binding.btnClearAll.isEnabled = true
                binding.swKzAlgorithm.isEnabled = true
                binding.btnCz.text = "丢弃所有烤肠重置烤盘"
            }
        }
        binding.btnUpdate.setOnClickListener {
            kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
            kaoPanAdapter.notifyDataSetChanged()
        }
        binding.btnLogs.setOnClickListener {
           // LogUtils.d("标签", "这是一条调试日志")
            val intent = Intent(requireContext(), LogViewActivity::class.java)
            startActivity(intent)
        }
//        binding.btnShijue.setOnClickListener {
//            SauceDetectionLauncher.launch(requireContext())
//        }
        binding.btnErroStatus.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val config = AppConfig.getAppConfig()
                config.errorStatus = 0
                config.onlineStatus = if (isWithinBusinessHours(getCurrentTime(), config.businessTime)) 1 else 2
                AppConfig.saveAppConfig(config)
                DataManagementAPI.uploadDeviceInfo()
            }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().time)
    }

    /**
     * 根据当前设备配置的营业时间判断“恢复正常”后应该回到运营中还是休息中。
     * 后台当前时间串既可能是 HH:mm，也可能是 HH:mm:ss，这里统一兼容两种格式。
     */
    // 根据当前设备配置的营业时间判断，点击“恢复正常状态”后应该回到运营中还是休息中。
    // 后台当前时间串可能是 HH:mm，也可能是 HH:mm:ss，这里统一兼容两种格式。
    private fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        try {
            if (businessTime == null) return false

            val current = parseBusinessTime(currentTimeStr) ?: return false
            val calendar = Calendar.getInstance()
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1

            if ("everyday".equals(businessTime.mode, ignoreCase = true) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2) {
                val start = parseBusinessTime(businessTime.defaultTime[0]) ?: return false
                val end = parseBusinessTime(businessTime.defaultTime[1]) ?: return false
                return isTimeInRange(current, start, end)
            } else if ("custom".equals(businessTime.mode, ignoreCase = true) && businessTime.customTimes != null) {
                for (timeItem in businessTime.customTimes) {
                    if (timeItem.day == dayOfWeek && timeItem.enabled && timeItem.timeRange != null && timeItem.timeRange.size >= 2) {
                        val start = parseBusinessTime(timeItem.timeRange[0]) ?: return false
                        val end = parseBusinessTime(timeItem.timeRange[1]) ?: return false
                        return isTimeInRange(current, start, end)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e("恢复设备状态时解析营业时间失败：${e.message}")
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
     * 弹出对话框，点击确认取烤肠
     */
    private fun showPurchaseOrderDialog(kaoPan: KaoPan) {
        if (!kaoPan.isHasSausage){
            ToastUtils.showShort("没有烤肠")
            return
        }
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("确认")
            .setMessage("是否要取出烤肠?")
            .setPositiveButton("确定") { dialog, _ ->
                // 判断
                if (status.get()) {
                    status.set(false)
                    //
                    lifecycleScope.launch(Dispatchers.IO) {
                        var appState = AppConfig.getAppConfig().moveStatus
                        // 运动状态 为0是说明机械臂闲置
                        while (appState != 0) {
                            delay(2000)
                            appState = AppConfig.getAppConfig().moveStatus
                            LogUtils.d("KaoChangAlgorithm", "取出烤肠 等待上位机闲置 $appState")
                        }
                        AppConfig.getAppConfig().moveStatus = 1
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())
                        // 调用取烤肠的算法
                        KaoChangOperate.takeSausage(kaoPan)
                        AppConfig.getAppConfig().moveStatus = 0
                        AppConfig.saveAppConfig(AppConfig.getAppConfig())

                        lifecycleScope.launch(Dispatchers.Main) {
                            dialog.dismiss()
                            status.set(true)
                        }
                    }
                } else ToastUtils.showShort("请等待当前操作完成")
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    /**
     * 启动2秒定时刷新
     */
    private fun startRefreshTimer() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            flow {
                while (true) {
                    emit(Unit)
                    delay(1000)
                }
            }.collect {
                kaoPanAdapter.submitList(KaoPanHelper.getKaoPanList())
                kaoPanAdapter.notifyDataSetChanged()
                binding.tvErroStatus.apply {
                    text = if (AppConfig.getAppConfig().errorStatus == 0) "状态：正常" else "状态：错误"
                    setTextColor(resources.getColor(if (AppConfig.getAppConfig().errorStatus == 0) android.R.color.holo_green_dark else android.R.color.holo_red_dark))
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
