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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private data class HeatBatch(
        val name: String,
        val pans: List<KaoPan>
    )

    private val TAG = "KaoChangAlgorithm"
    private const val LOG_ALGORITHM = "算法轮询"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_COMM = "通信状态"
    private const val MODBUS_MONITOR_INTERVAL_MS = 1_000L
    private const val MODBUS_DISCONNECT_REPORT_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_STABLE_DELAY_MS = 5_000L
    private const val MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS = 5_000L
    /**
     * 算法对象内部的后台作用域。
     *
     * 只用于承接算法轮询和 Modbus 状态监控这类应用级后台任务，
     * 避免继续使用 GlobalScope 造成难以控制的悬挂协程。
     */
    private val algorithmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        algorithmScope.launch {
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

    private fun isHeatingPan(kaoPan: KaoPan): Boolean {
        return kaoPan.isHasSausage && kaoPan.startTime > 0L && kaoPan.holdingTime <= 0L
    }

    private fun isHoldingPan(kaoPan: KaoPan): Boolean {
        return kaoPan.isHasSausage && kaoPan.holdingTime > 0L
    }

    private fun calcPanRemainHeatTime(kaoPan: KaoPan, now: Long): Long? {
        if (!isHeatingPan(kaoPan)) {
            return null
        }
        val heatTime = now - kaoPan.startTime
        val remain = kaoPan.bakingTime - heatTime
        return remain.takeIf { it > 0L }
    }

    private fun calcBatchRemainHeatTime(list: List<KaoPan>, now: Long): Long? {
        return list
            .asSequence()
            .mapNotNull { calcPanRemainHeatTime(it, now) }
            .maxOrNull()
    }

    private fun buildSupplementHeatBatches(
        isLowOrTransition: Boolean,
        heatArea: List<KaoPan>
    ): List<HeatBatch> {
        return if (isLowOrTransition) {
            listOf(
                HeatBatch("13~15补肠批次", heatArea.filter { it.positionSn in 13..15 }),
                HeatBatch("16~18补肠批次", heatArea.filter { it.positionSn in 16..18 })
            )
        } else {
            listOf(HeatBatch("25~33补肠批次", heatArea))
        }
    }

    /**
     * 计算二维码页显示的“预计烤制时间”。
     * 展示规则：
     * 1. 前区还有保温烤肠时，不展示预计烤制时间；
     * 2. 前区没有可售烤肠时：
     *    - 若当前开盘批次仍在前区加热，则显示这一批最后一根烤好的剩余时间；
     *    - 若只剩补肠批次在补热区加热，则显示“最早能恢复可售”的那一批剩余时间；
     * 3. 当前批次全部进入保温后才一次性开放；
     * 4. 小程序可售开关仍通过 availableCountdownLatched 控制。
     */
    private fun checkAvailable(config: AppConfigBean, kaoPanList: List<KaoPan>) {
        val list_1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList)
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)

        val isLowOrTransition = config.modeType == 1 || config.transitionMode == 1
        val sellArea = if (isLowOrTransition) list_1_9 else list_1_21
        val heatArea = if (isLowOrTransition) list_13_18 else list_25_33
        val trackedArea = sellArea + heatArea
        val trackedPans = trackedArea.filter { it.isHasSausage }
        val sellableHoldingPans = sellArea.filter { isHoldingPan(it) }
        val now = System.currentTimeMillis()

        if (trackedPans.isEmpty()) {
            updateAvailableState(config, 0L, 0, "当前批次相关烤盘已全部清空")
            return
        }

        if (sellableHoldingPans.isNotEmpty()) {
            updateAvailableState(config, 0L, 1, "前区仍有保温烤肠，不展示预计时间")
            return
        }

        val openingBatchRemainHeatTime = calcBatchRemainHeatTime(sellArea, now)
        if (openingBatchRemainHeatTime != null) {
            updateAvailableState(
                config,
                openingBatchRemainHeatTime,
                0,
                "前区暂无可售烤肠，显示当前开盘批次最后一根预计完成时间"
            )
            return
        }

        val nextSupplementBatch = buildSupplementHeatBatches(isLowOrTransition, heatArea)
            .mapNotNull { batch ->
                calcBatchRemainHeatTime(batch.pans, now)?.let { remain ->
                    batch.name to remain
                }
            }
            .minByOrNull { it.second }

        if (nextSupplementBatch != null) {
            updateAvailableState(
                config,
                nextSupplementBatch.second,
                0,
                "前区已售空，显示${nextSupplementBatch.first}恢复可售前的预计时间"
            )
            return
        }

        updateAvailableState(config, 0L, 0, "前区无保温烤肠，当前也没有正在加热的待开放批次")
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

        when {
            config.transitionMode != 0 -> {
                HighCurrentTrafficMode.highTrafficMode(config, list = kaoPanList, listBox = kaoPanBoxList)
            }

            config.modeType == 1 -> {
                LowCurrentTrafficMode.lowTrafficMode(config, kaoPanList, kaoPanBoxList)
            }

            config.modeType == 2 -> {
                HighCurrentTrafficMode.highTrafficMode(config, list = kaoPanList, listBox = kaoPanBoxList)
            }
        }
    }

    /**
     * 用户下单后，从可售烤盘中选择一根烤肠出餐。
     * 当前策略：
     * 1. 低并发及低转高过渡态仅从 1~9 号前区选取；
     * 2. 高并发从 1~21 号可售区选取；
     * 3. 按 holdingTime 最小值优先，即“最早进入保温的先卖”。
     */
    suspend fun purchase(tasteCode: String, productId: Int): KaoChangOperate.TakeSausageResult {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val config = AppConfig.getAppConfig()
        val targetKaoPan: KaoPan? = if (config.modeType == 1 || config.transitionMode == 1) {
            TrafficModeHelper.selectSellCandidate(
                TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList),
                tasteCode,
                productId
            )
        } else {
            TrafficModeHelper.selectSellCandidate(
                TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList),
                tasteCode,
                productId
            )
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
    suspend fun resetKaoPan(progressCallback: ((current: Int, total: Int, kaoPan: KaoPan) -> Unit)? = null) {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val targetPans = kaoPanList.filter { it.isHasSausage }
        val targetPositions = targetPans.map { it.positionSn }
        LogUtils.i("【维护操作】管理员触发批量丢弃重置烤盘，待处理烤盘=$targetPositions")
        targetPans.forEachIndexed { index, kaoPan ->
            progressCallback?.invoke(index + 1, targetPans.size, kaoPan)
            KaoChangOperate.discardSausage(
                kaoPan,
                source = "管理员指定",
                detail = "维护页批量重置烤盘"
            )
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
        algorithmScope.launch {
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
