package cn.niuyannet.kaochang.android.services

import android.util.Log
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
    var isEnabled = false //是否开启算法

    fun restoreRoastAlgorithmState() {
        isEnabled = AppConfig.getAppConfig().isEnable == 1
    }
    /**
     * 运行这个智能烤肠算法服务
     * 低并发和中并发，可相互切换
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
        Log.d(TAG, "智能烤肠算法服务启动开始工作")
        LogUtils.d(TAG, "算法准备0 运动状态：${config.moveStatus}")
        config.moveStatus = 0
        AppConfig.saveAppConfig(config)
        LogUtils.d(TAG, "算法准备1 运动状态：${config.moveStatus}")
        serviceStartTime = System.currentTimeMillis() // 记录服务启动时间
        LogUtils.d(TAG, "智能烤肠算法服务启动开始工作")
        startModbusStateMonitor()
        GlobalScope.launch(Dispatchers.IO) {
            while (isRunning) {
                //if (AppConfig.getAppConfig().moveStatus == 0) {
                pollKaoPanStatus()
                if (!isRunning) break // 延迟前再检测一次(是否点击清空烤盘),防止延迟结束状态就重置完毕
                LogUtils.d(TAG, "轮询完成 运动状态：${AppConfig.getAppConfig().moveStatus}")
                //}
                delay(10 * 1000)// 1秒空拍一次
            }
        }
    }
    /**
     * 检查烤盘是否有烤肠可售
     */
    private fun checkAvailable(config: AppConfigBean,kaoPanList: List<KaoPan>) {
        val list_1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList)

        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)


        val h_1_9 = list_1_9.filter { it.isHasSausage }.size // 烤肠数
        val k_1_9 = list_1_9.filter { it.isHasSausage && it.startTime > 0 }.size // 1-9在烤制数
        val h_13_18 = list_13_18.filter { it.isHasSausage }.size

        val h_1_21 = list_1_21.filter { it.isHasSausage }.size
        val k_1_21 = list_1_21.filter { it.isHasSausage && it.startTime > 0 }.size // 1-21在烤制数
        val h_25_33 = list_25_33.filter { it.isHasSausage }.size
        // 没有已烤好的烤肠，但已经在烤制中
        if (h_1_9 == 0 && h_13_18 > 0) {

            val now = System.currentTimeMillis()

            // 找到还在加热中的烤肠，计算剩余时间
            val minRemainHeatTime: Long? = list_13_18
                .asSequence() // 小优化
                .filter { it.isHasSausage && it.startTime > 0 }
                .map { kaoPan ->
                    val heatTime = now - kaoPan.startTime
                    kaoPan.bakingTime - heatTime
                }
                .filter { it > 0 }          // 只保留还没烤好的
                .maxOrNull()                // ✅ 关键：可空

            if (minRemainHeatTime != null) {
                // 有正在补肠
                config.available = minRemainHeatTime
            } else {
                // 没有在烤的烤肠
                config.available = 0L
            }

            AppConfig.saveAppConfig(config)
            // 同步更新云端状态
            NetApi.updateDeviceInfo()
        }
        else if (h_1_21 == 0 && h_25_33 > 0) {
            val now = System.currentTimeMillis()

            // 找到还在加热中的烤肠，计算剩余时间
            val minRemainHeatTime: Long? = list_25_33
                .asSequence()
                .filter { it.isHasSausage && it.startTime > 0 }
                .map { kaoPan ->
                    val heatTime = now - kaoPan.startTime
                    kaoPan.bakingTime - heatTime
                }
                .filter { it > 0 }          // 只保留还没烤好的
                .maxOrNull()                // ✅ 关键：可空

            if (minRemainHeatTime != null) {
                // 有正在补肠
                config.available = minRemainHeatTime
            } else {
                // 没有在烤的烤肠
                config.available = 0L
            }

            AppConfig.saveAppConfig(config)
            // 同步更新云端状态
            NetApi.updateDeviceInfo()
        }
        // 并发初始化烤制中
        else if (k_1_21 == list_1_21.size) {
            val now = System.currentTimeMillis()

            // 找到还在加热中的烤肠，计算剩余时间
            val minRemainHeatTime: Long? = list_1_21
                .asSequence()
                .filter { it.isHasSausage && it.startTime > 0 }
                .map { kaoPan ->
                    val heatTime = now - kaoPan.startTime
                    kaoPan.bakingTime - heatTime
                }
                .filter { it > 0 }          // 只保留还没烤好的
                .maxOrNull()                // ✅ 关键：可空

            if (minRemainHeatTime != null) {
                // 有正在补肠
                config.available = minRemainHeatTime
            } else {
                // 没有在烤的烤肠
                config.available = 0L
            }

            AppConfig.saveAppConfig(config)
            // 同步更新云端状态
            NetApi.updateDeviceInfo()
        }
        else if (k_1_9 == list_1_9.size) {

            val now = System.currentTimeMillis()

            // 找到还在加热中的烤肠，计算剩余时间
            val minRemainHeatTime: Long? = list_1_9
                .asSequence() // 小优化
                .filter { it.isHasSausage && it.startTime > 0 }
                .map { kaoPan ->
                    val heatTime = now - kaoPan.startTime
                    kaoPan.bakingTime - heatTime
                }
                .filter { it > 0 }          // 只保留还没烤好的
                .maxOrNull()                // ✅ 关键：可空

            if (minRemainHeatTime != null) {
                // 有正在补肠
                config.available = minRemainHeatTime
            } else {
                // 没有在烤的烤肠
                config.available = 0L
            }

            AppConfig.saveAppConfig(config)
            // 同步更新云端状态
            NetApi.updateDeviceInfo()
        }
        else if (config.available > 0){
            LogUtils.d("KaoChangAlgorithm", "检查烤盘是否有烤肠：${config.available}")
            config.apply {
                available = 0
            }
            AppConfig.saveAppConfig(config)
            //同步更新云端状态
            NetApi.updateDeviceInfo()

        }
    }
    /**
     * 轮询烤盘的状态，判断当前处于那个模式，低，高发模式
     */
    private suspend fun pollKaoPanStatus() {
        Log.d(TAG, "轮询烤盘状态")
        LogUtils.d("KaoChangAlgorithm", "轮询烤盘状态")
        // 获取烤盘配置
        val config = AppConfig.getAppConfig()
        //获取烤盘信息
        val kaoPanList=KaoPanHelper.getKaoPanList()
        //获取烤肠箱信息
        val kaoPanBoxList=KaoPanHelper.getKaoPanBoxList()
        var onlineStatus=config.onlineStatus //在线状态（0未启用 1运营中 2休息中 3维护中 4工作中（正在出烤肠））
        val isWithinBusinessHours=isWithinBusinessHours(getCurrentTime(), config.businessTime)// 检查是否在营业时间内
        if (!isWithinBusinessHours){
            if (onlineStatus != 2) {
                Log.d(TAG, "非营业时间，暂停烤制，不工作")
                //需要移除烤盘的所有数据，并且停止加热
                KaoChangOperate.pauseBusiness()
                //营业时间结束清除烤肠
                Log.d(TAG, "营业时间结束清除烤肠！")
                KaoChangOperate.endBusinessHours(config)
            }
            return
        }
        else{
            if (onlineStatus == 2) {
                KaoChangOperate.startBusinessHours(config)
                onlineStatus = AppConfig.getAppConfig().onlineStatus
            }
        }
        if (!(onlineStatus==1||onlineStatus==4)){
            //Log.d(TAG, "设备状态=$onlineStatus，不工作")
            LogUtils.d(TAG, "设备状态=$onlineStatus，不工作")
            return
        }
        if (!VMModbusHelper.connectStatus()){
            LogUtils.d("KaoChangAlgorithm", "modbus串口没有连接上")
            return
        }
        if (!isEnabled){
            Log.d(TAG, "算法未开启，不工作")
            //LogUtils.d(TAG, "算法未开启，不工作")
            return
        }
        LogUtils.d("KaoChangAlgorithm", "算法开始运行了-模式：${config.modeType}")
        LogUtils.d("KaoChangAlgorithm", "运动状态：${config.moveStatus}")

        checkAvailable(config, kaoPanList) // 检查烤盘是否有烤肠

        if (config.modeType==1){
            LowCurrentTrafficMode.lowTrafficMode(config,kaoPanList,kaoPanBoxList) // 低并发模式
        }else if (config.modeType==2){
            HighCurrentTrafficMode.highTrafficMode(config,list=kaoPanList, listBox = kaoPanBoxList) // 高并发模式
        }
    }
    /**
     * 用户下单取出烤肠到售卖台
     * 根据下单的口味和id信息
     * 1.查询保温直接最长的口味
     * 2.然后取出来
     */
    suspend fun purchase(tasteCode: String, productId: Int): KaoChangOperate.TakeSausageResult {
        // 获取烤盘信息
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val config = AppConfig.getAppConfig()
        // 查找保温时间最长的烤肠
//        val targetKaoPan = kaoPanList.filter {
//            // 如果指定了口味ID，则只查找对应口味的烤肠
//            it.isHasSausage&&it.taste.tasteCode==tasteCode&&it.taste.productId==productId
//        }.maxByOrNull { it.holdingTime }
        //新增控制条件，低并发在1-6区间找，高并发在1-21中找
        var targetKaoPan: KaoPan?=null
        if (config.modeType==1){
             //targetKaoPan =  TrafficModeHelper.getTrafficModePositions(1, 6, kaoPanList).filter {
            targetKaoPan =  TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList).filter {
                // 如果指定了口味ID，则只查找对应口味的烤肠
                it.isHasSausage&&it.taste.tasteCode==tasteCode&&it.taste.productId==productId&&it.holdingTime > 0
            }.minByOrNull { it.holdingTime }
        }else{
             targetKaoPan =  TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList).filter {
                // 如果指定了口味ID，则只查找对应口味的烤肠
                it.isHasSausage&&it.taste.tasteCode==tasteCode&&it.taste.productId==productId&&it.holdingTime > 0
            }.minByOrNull { it.holdingTime }
        }

        
        if (targetKaoPan != null) {
            // 取出烤肠
            return KaoChangOperate.takeSausageResult(targetKaoPan)
        } else {
            LogUtils.w(TAG, "没有找到可供售卖的烤肠")
        }
        return KaoChangOperate.TakeSausageResult.ERROR
    }
    /**
     * 重置烤盘
     */
    suspend fun resetKaoPan() {
       val kaoPanList = KaoPanHelper.getKaoPanList()
       //如果有烤肠就把烤肠废弃掉
       for (kaoPan in kaoPanList){
           if (kaoPan.isHasSausage){
               KaoChangOperate.discardSausage(kaoPan)
           }
       }
    }


    /**
     * 停止服务
     */
    fun stopServer() {
        isRunning = false
        modbusMonitorStarted = false
        Log.d(TAG, "智能烤肠算法服务停止")
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
                    rememberRecoverableOnlineStatus(config.onlineStatus)

                    if (modbusReportedUnavailable) {
                        if (modbusReconnectSinceMs == null) {
                            modbusReconnectSinceMs = now
                            LogUtils.d(TAG, "检测到modbus恢复，开始稳定性观察")
                        } else if (now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS) {
                            val restoredOnlineStatus = resolveRecoveredOnlineStatus(config)
                            config.errorStatus = 0
                            config.onlineStatus = restoredOnlineStatus
                            AppConfig.saveAppConfig(config)
                            KaoChangOperate.restoreHeatingStateFromCurrentPans("modbus恢复稳定")
                            rememberRecoverableOnlineStatus(restoredOnlineStatus)
                            modbusReportedUnavailable = false
                            modbusReconnectSinceMs = null
                            uploadDeviceStatus(
                                "modbus串口恢复稳定，已恢复设备可用状态：onlineStatus=$restoredOnlineStatus"
                            )
                        }
                    } else {
                        modbusReconnectSinceMs = null
                    }
                } else {
                    modbusReconnectSinceMs = null
                    if (modbusDisconnectSinceMs == null) {
                        modbusDisconnectSinceMs = now
                        LogUtils.w("检测到modbus串口断开，开始不可用状态观察")
                    }
                    if (lastReconnectAttemptMs == 0L || now - lastReconnectAttemptMs >= MODBUS_RECONNECT_ATTEMPT_INTERVAL_MS) {
                        lastReconnectAttemptMs = now
                        VMModbusHelper.connectModbus { status ->
                            LogUtils.d("modbus后台重连尝试完成：status=$status")
                        }
                    }

                    if (!modbusReportedUnavailable && now - modbusDisconnectSinceMs!! >= MODBUS_DISCONNECT_REPORT_DELAY_MS) {
                        rememberRecoverableOnlineStatus(config.onlineStatus)
                        config.errorStatus = 2
                        config.onlineStatus = 3
                        AppConfig.saveAppConfig(config)
                        modbusReportedUnavailable = true
                        uploadDeviceStatus("modbus串口断开超过${MODBUS_DISCONNECT_REPORT_DELAY_MS}ms，已上报维护中")
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
        LogUtils.w(logMessage)
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
            Log.e(TAG, "时间格式解析错误: $e")
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
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

    fun disableRoastAlgorithm() {
        isEnabled = false
        AppConfig.getAppConfig().isEnable = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

}
