package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 智能烤肠算法
 * 检测烤盘状态、库存并自动管理烤肠烤制流程
 */
object KaoChangAlgorithm {
    private val TAG = "KaoChangAlgorithm"
    private const val LOG_ALGORITHM = "算法轮询"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_COMM = "通信状态"
    private const val MODBUS_MONITOR_INTERVAL_MS = 1_000L
    private const val MODBUS_DISCONNECT_REPORT_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_STABLE_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS = 5_000L
    private var isRunning = false
    private var modbusMonitorStarted = false
    private var serviceStartTime: Long = 0
    private var modbusDisconnectSinceMs: Long? = null
    private var modbusReconnectSinceMs: Long? = null
    private var lastReconnectAttemptMs: Long = 0L
    private var modbusReportedUnavailable = false
    private var lastRecoverableOnlineStatus = 1
    private var loggedManualRecoveryRequiredOnDisconnect = false
    private var loggedManualRecoveryRequiredAfterReconnect = false
    private var lastPollStateSnapshot: String? = null
    var isEnabled = false

    private fun logD(category: String, message: String) = LogUtils.d(TAG, "[$category] $message")
    private fun logW(category: String, message: String) = LogUtils.w(TAG, "[$category] $message")

    private fun modeText(value: Int): String = when (value) {
        1 -> "1(低并发)"
        2 -> "2(高并发)"
        else -> "$value(未知)"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0(无过渡态)"
        1 -> "1(低转高过渡)"
        2 -> "2(高转低过渡)"
        else -> "$value(未知)"
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0(未启用/离线)"
        1 -> "1(运营中)"
        2 -> "2(休息中)"
        3 -> "3(维护中)"
        4 -> "4(工作中/正在出餐)"
        else -> "$value(未知)"
    }

    private fun moveStatusText(value: Int): String = when (value) {
        0 -> "0(机械臂空闲)"
        1 -> "1(机械臂运动中)"
        else -> "$value(未知)"
    }

    private fun errorStatusText(value: Int): String = when (value) {
        0 -> "0(正常)"
        1 -> "1(识别/流程错误)"
        2 -> "2(Modbus断连错误)"
        else -> "$value(未知)"
    }

    fun restoreRoastAlgorithmState() {
        isEnabled = AppConfig.getAppConfig().isEnable == 1
    }

    /**
     * 启动烤肠算法主循环。
     * 当前只保留状态变化日志，避免每轮轮询刷屏。
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun runServer() {
        if (isRunning) return
        restoreRoastAlgorithmState()
        isRunning = true
        val config = AppConfig.getAppConfig()
        rememberRecoverableOnlineStatus(config.onlineStatus)
        if (config.onlineStatus == 3 && config.errorStatus == 2 && !VMModbusHelper.connectStatus()) {
            modbusReportedUnavailable = true
        }
        logD(LOG_ALGORITHM, "算法准备0：moveStatus=${config.moveStatus}")
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
        logD(LOG_ALGORITHM, "算法准备1：moveStatus=${config.moveStatus}")
        serviceStartTime = System.currentTimeMillis()
        logD(LOG_ALGORITHM, "智能烤肠算法服务启动")
        startModbusStateMonitor()
        GlobalScope.launch(Dispatchers.IO) {
            while (isRunning) {
                pollKaoPanStatus()
                if (!isRunning) break
                delay(10 * 1000)
            }
        }
    }

    private fun updateAvailableState(
        config: AppConfigBean,
        newAvailable: Long,
        newLatched: Int,
        reason: String
    ) {
        if (config.available == newAvailable && config.availableCountdownLatched == newLatched) {
            return
        }
        logD(
            LOG_ALGORITHM,
            "更新二维码预计时间：reason=$reason, available=${config.available}->$newAvailable, " +
                "availableCountdownLatched=${config.availableCountdownLatched}->$newLatched"
        )
        config.available = newAvailable
        config.availableCountdownLatched = newLatched
        AppConfig.saveAppConfig(config)
        NetApi.updateDeviceInfo()
    }

    private fun calcMinRemainHeatTime(list: List<KaoPan>): Long? {
        val now = System.currentTimeMillis()
        return list
            .asSequence()
            .filter { it.isHasSausage && it.startTime > 0 }
            .map { kaoPan ->
                val heatTime = now - kaoPan.startTime
                kaoPan.bakingTime - heatTime
            }
            .filter { it > 0 }
            .minOrNull()
    }

    /**
     * 计算二维码页显示的“预计烤制时间”。
     * 展示规则：
     * 1. 只显示当前这一批“第一根烤好还要多久”；
     * 2. 第一根一旦变为可售，倒计时立即清零，并锁定为“不继续滚动显示第二根/第三根”；
     * 3. 只有当前这一批相关烤盘全部清空后，才允许下一批重新显示倒计时。
     */
    private fun checkAvailable(config: AppConfigBean, kaoPanList: List<KaoPan>) {
        val list_1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList)
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)

        val isLowOrTransition = config.modeType == 1 || config.transitionMode == 1
        val sellArea = if (isLowOrTransition) list_1_9 else list_1_21
        val heatArea = if (isLowOrTransition) list_13_18 else list_25_33

        val sellableCount = sellArea.count { it.isHasSausage && it.holdingTime > 0 }
        val totalTrackedCount = sellArea.count { it.isHasSausage } + heatArea.count { it.isHasSausage }
        val trackedHeatingArea = (sellArea + heatArea).filter { it.isHasSausage && it.startTime > 0 }

        if (totalTrackedCount == 0) {
            updateAvailableState(config, 0L, 0, "当前批次相关烤盘已全部清空")
            return
        }

        if (sellableCount > 0) {
            updateAvailableState(config, 0L, 1, "当前批次第一根已可售")
            return
        }

        if (config.availableCountdownLatched == 1) {
            updateAvailableState(config, 0L, 1, "当前批次第一根已展示完成，不继续滚动到下一根")
            return
        }

        if (trackedHeatingArea.isNotEmpty()) {
            updateAvailableState(
                config,
                calcMinRemainHeatTime(trackedHeatingArea) ?: 0L,
                0,
                "当前无可售烤肠，显示相关区域最近一根预计完成时间"
            )
        } else {
            updateAvailableState(config, 0L, 0, "当前没有需要展示的第一根预计时间")
        }
    }

    /**
     * 轮询烤盘和设备状态，决定当前调度模式。
     */
    private suspend fun pollKaoPanStatus() {
        val config = AppConfig.getAppConfig()
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        var onlineStatus = config.onlineStatus
        val isWithinBusinessHours = isWithinBusinessHours(getCurrentTime(), config.businessTime)
        if (!isWithinBusinessHours) {
            if (onlineStatus != 2) {
                logD(LOG_ALGORITHM, "当前不在营业时间内，暂停算法调度并执行停业收尾")
                KaoChangOperate.pauseBusiness()
                KaoChangOperate.endBusinessHours(config)
            }
            return
        } else {
            if (onlineStatus == 2) {
                KaoChangOperate.startBusinessHours(config)
                onlineStatus = AppConfig.getAppConfig().onlineStatus
            }
        }

        if (!(onlineStatus == 1 || onlineStatus == 4)) {
            logD(LOG_DEVICE, "设备当前处于${onlineStatusText(onlineStatus)}，跳过算法调度")
            return
        }
        if (!VMModbusHelper.connectStatus()) {
            logD(LOG_COMM, "modbus 串口未连接，跳过算法调度")
            return
        }
        if (!isEnabled) {
            return
        }

        val pollStateSnapshot =
            "mode=${config.modeType},transition=${config.transitionMode},online=$onlineStatus,move=${config.moveStatus},error=${config.errorStatus}"
        if (pollStateSnapshot != lastPollStateSnapshot) {
            logD(
                LOG_ALGORITHM,
                "算法状态变化：modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，onlineStatus=${onlineStatusText(onlineStatus)}，moveStatus=${moveStatusText(config.moveStatus)}，errorStatus=${errorStatusText(config.errorStatus)}"
            )
            lastPollStateSnapshot = pollStateSnapshot
        }

        checkAvailable(config, kaoPanList)

        if (config.modeType == 1 || config.transitionMode == 1) {
            LowCurrentTrafficMode.lowTrafficMode(config, kaoPanList, kaoPanBoxList)
        } else if (config.modeType == 2) {
            HighCurrentTrafficMode.highTrafficMode(config, list = kaoPanList, listBox = kaoPanBoxList)
        }
    }

    /**
     * 用户下单后，从可售烤盘中选择一根烤肠出餐。
     * 当前策略：
     * 1. 低并发及低转高过渡态仅从 1~9 号前区选取；
     * 2. 高并发从 1~21 号可售区选取；
     * 3. 按 holdingTime 最大值优先，即“最早进入保温的先卖”。
     */
    suspend fun purchase(tasteCode: String, productId: Int): KaoChangOperate.TakeSausageResult {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val config = AppConfig.getAppConfig()
        val targetKaoPan: KaoPan? = if (config.modeType == 1 || config.transitionMode == 1) {
            TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList).filter {
                it.isHasSausage &&
                    it.taste.tasteCode == tasteCode &&
                    it.taste.productId == productId &&
                    it.holdingTime > 0
            }.maxByOrNull { it.holdingTime }
        } else {
            TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList).filter {
                it.isHasSausage &&
                    it.taste.tasteCode == tasteCode &&
                    it.taste.productId == productId &&
                    it.holdingTime > 0
            }.maxByOrNull { it.holdingTime }
        }

        if (targetKaoPan != null) {
            logD(
                LOG_ALGORITHM,
                "收到出货订单：口味=$tasteCode，商品=$productId，已调度托盘 ID=${targetKaoPan.id}，烤盘位=${targetKaoPan.positionSn}，holdingTime=${targetKaoPan.holdingTime}"
            )
            return KaoChangOperate.takeSausageResult(targetKaoPan)
        } else {
            logW(LOG_DEVICE, "收到出货订单失败：当前没有找到可售烤肠 [口味=$tasteCode, 商品=$productId]")
        }
        return KaoChangOperate.TakeSausageResult.ERROR
    }

    /**
     * 维护页批量重置烤盘。
     * 所有有肠烤盘都会按“管理员指定丢弃”处理。
     */
    suspend fun resetKaoPan() {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val targetPositions = kaoPanList.filter { it.isHasSausage }.map { it.positionSn }
        LogUtils.i("【维护操作】管理员触发批量丢弃重置烤盘，待处理烤盘=$targetPositions")
        for (kaoPan in kaoPanList) {
            if (kaoPan.isHasSausage) {
                KaoChangOperate.discardSausage(
                    kaoPan,
                    source = "管理员指定",
                    detail = "维护页批量重置烤盘"
                )
            }
        }
    }

    /**
     * 停止服务
     */
    fun stopServer() {
        isRunning = false
        modbusMonitorStarted = false
        logD(LOG_ALGORITHM, "智能烤肠算法服务停止")
    }

    private fun startModbusStateMonitor() {
        if (modbusMonitorStarted) {
            return
        }
        modbusMonitorStarted = true
        GlobalScope.launch(Dispatchers.IO) {
            while (isRunning) {
                val now = System.currentTimeMillis()
                val connected = VMModbusHelper.connectStatus()
                val config = AppConfig.getAppConfig()

                if (connected) {
                    modbusDisconnectSinceMs = null
                    lastReconnectAttemptMs = 0L
                    loggedManualRecoveryRequiredOnDisconnect = false
                    rememberRecoverableOnlineStatus(config.onlineStatus)

                    if (modbusReportedUnavailable) {
                        if (modbusReconnectSinceMs == null) {
                            modbusReconnectSinceMs = now
                            logD(LOG_COMM, "检测到modbus恢复，开始稳定性观察")
                        } else if (now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS) {
                            val restoredOnlineStatus = resolveRecoveredOnlineStatus(config)
                            config.errorStatus = 0
                            config.onlineStatus = restoredOnlineStatus
                            AppConfig.saveAppConfig(config)
                            KaoChangOperate.restoreHeatingStateFromCurrentPans("modbus恢复稳定")
                            rememberRecoverableOnlineStatus(restoredOnlineStatus)
                            modbusReportedUnavailable = false
                            modbusReconnectSinceMs = null
                            loggedManualRecoveryRequiredAfterReconnect = false
                            uploadDeviceStatus(
                                "modbus串口恢复稳定，自动恢复原因=断连类错误，已恢复设备可用状态：onlineStatus=$restoredOnlineStatus"
                            )
                        }
                    } else {
                        modbusReconnectSinceMs = null
                        if (config.onlineStatus == 3 && config.errorStatus != 0 && !loggedManualRecoveryRequiredAfterReconnect) {
                            loggedManualRecoveryRequiredAfterReconnect = true
                            logW(
                                LOG_COMM,
                                "检测到modbus已恢复，但当前仍处于维护中(errorStatus=${config.errorStatus})；" +
                                    "不可自动恢复原因=当前错误并非本次断连监控触发，需人工恢复"
                            )
                        } else if (!(config.onlineStatus == 3 && config.errorStatus != 0)) {
                            loggedManualRecoveryRequiredAfterReconnect = false
                        }
                    }
                } else {
                    modbusReconnectSinceMs = null
                    loggedManualRecoveryRequiredAfterReconnect = false
                    if (modbusDisconnectSinceMs == null) {
                        modbusDisconnectSinceMs = now
                        logW(LOG_COMM, "检测到modbus串口断开，开始不可用状态观察")
                    }
                    if (lastReconnectAttemptMs == 0L || now - lastReconnectAttemptMs >= MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS) {
                        lastReconnectAttemptMs = now
                        VMModbusHelper.connectModbus { status ->
                            logD(LOG_COMM, "modbus后台重连尝试完成：status=$status")
                        }
                    }

                    if (!modbusReportedUnavailable && now - modbusDisconnectSinceMs!! >= MODBUS_DISCONNECT_REPORT_DELAY_MS) {
                        if (config.onlineStatus in 0..2) {
                            rememberRecoverableOnlineStatus(config.onlineStatus)
                            config.errorStatus = 2
                            config.onlineStatus = 3
                            AppConfig.saveAppConfig(config)
                            modbusReportedUnavailable = true
                            loggedManualRecoveryRequiredOnDisconnect = false
                            uploadDeviceStatus("modbus串口断开超过${MODBUS_DISCONNECT_REPORT_DELAY_MS}ms，自动恢复原因=断连类错误，已上报维护中")
                        } else if (!loggedManualRecoveryRequiredOnDisconnect) {
                            loggedManualRecoveryRequiredOnDisconnect = true
                            logW(
                                LOG_COMM,
                                "modbus串口断开，但设备当前已处于维护中(errorStatus=${config.errorStatus})；" +
                                    "不可自动恢复原因=当前错误并非从运营/休息状态切入的断连故障，保留人工恢复"
                            )
                        }
                    }
                }

                delay(MODBUS_MONITOR_INTERVAL_MS)
            }
            modbusMonitorStarted = false
        }
    }

    private fun rememberRecoverableOnlineStatus(onlineStatus: Int) {
        if (onlineStatus in 0..2) {
            lastRecoverableOnlineStatus = onlineStatus
        }
    }

    private fun resolveRecoveredOnlineStatus(config: AppConfigBean): Int {
        if (config.status != 1) {
            return 0
        }
        if (lastRecoverableOnlineStatus == 0 || lastRecoverableOnlineStatus == 2) {
            return lastRecoverableOnlineStatus
        }
        return if (isWithinBusinessHours(getCurrentTime(), config.businessTime)) 1 else 2
    }

    private fun uploadDeviceStatus(logMessage: String) {
        logW(LOG_DEVICE, logMessage)
        SendServerHelper.publishServiceUpdateStatus()
        NetApi.updateDeviceInfo()
    }
    /**
     * 判断当前时间是否在营业时间内
     * @param currentTimeStr 当前时间字符串，格式为"HH:mm"
     * @param businessTime 营业时间配置
     * @return 是否在营业时间内
     */
    private fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        try {
            if (businessTime == null) return false
            
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val current = format.parse(currentTimeStr)
            
            // 获取当前是星期几（1-7，对应周一到周日）
            val calendar = Calendar.getInstance()
            // Calendar.DAY_OF_WEEK 返回 1-7，对应周日到周六，需要转换为 1-7 对应周一到周日
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1
            
            // 根据营业时间模式判断
            if ("everyday".equals(businessTime.mode, ignoreCase = true) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2) {
                // 默认模式：所有天使用相同的时间范围
                val start = format.parse(businessTime.defaultTime[0])
                val end = format.parse(businessTime.defaultTime[1])
                
                return isTimeInRange(current, start, end)
            } else if ("custom".equals(businessTime.mode, ignoreCase = true) && businessTime.customTimes != null) {
                // 自定义模式：每天可以有不同的时间范围
                for (timeItem in businessTime.customTimes) {
                    // 检查是否是当前日期且已启用
                    if (timeItem.day == dayOfWeek && timeItem.enabled && timeItem.timeRange != null && timeItem.timeRange.size >= 2) {
                        val start = format.parse(timeItem.timeRange[0])
                        val end = format.parse(timeItem.timeRange[1])
                        
                        return isTimeInRange(current, start, end)
                    }
                }
            }
            
            // 如果没有匹配的时间范围，则不在营业时间内
            return false
            
        } catch (e: Exception) {
            logW(LOG_ALGORITHM, "营业时间解析失败：$e")
            return false
        }
    }
    
    /**
     * 判断时间是否在范围内，处理跨天的情况
     */
    private fun isTimeInRange(current: Date, start: Date, end: Date): Boolean {
        // 判断是否跨天营业
        return if (start.before(end) || start == end) {
            // 不跨天：当前时间在开始和结束时间之间
            (current.after(start) || current == start) && (current.before(end) || current == end)
        } else {
            // 跨天：当前时间在开始时间之后或结束时间之前
            (current.after(start) || current == start) || (current.before(end) || current == end)
        }
    }
 

    /**
     * 获取当前时间
     * @return 当前时间字符串，格式为"HH:mm"
     */
    private fun getCurrentTime(): String {
        val currentTime = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(currentTime.time)
    }

    fun enableRoastAlgorithm() {
        KaoChangOperate.actionDebug()
        isEnabled = true
        AppConfig.getAppConfig().isEnable = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        logD(LOG_ALGORITHM, "维护操作：已开启烤制算法，modeType=${modeText(AppConfig.getAppConfig().modeType)}")
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

    fun disableRoastAlgorithm() {
        isEnabled = false
        AppConfig.getAppConfig().isEnable = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        logD(LOG_ALGORITHM, "维护操作：已关闭烤制算法")
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

}
