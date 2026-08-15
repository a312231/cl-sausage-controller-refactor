package cn.niuyannet.kaochang.android.services

import android.os.SystemClock
import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.RepeatedLogGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 智能烤肠算法
 * 检测烤盘状态、库存并自动管理烤肠烤制流程
 */
object KaoChangAlgorithm {
    data class SupplyRuntimeContext(
        val hasAutoSupplementSupplyBoxesAvailable: Boolean,
        val hasPhysicalSupplyBoxesAvailable: Boolean,
        val hasSellableSausage: Boolean,
        val hasActiveBatch: Boolean
    )

    private val TAG = "KaoChangAlgorithm"
    private const val LOG_ALGORITHM = "算法轮询"
    private const val LOG_DEVICE = "设备状态"
    private const val LOG_COMM = "通信状态"
    private const val SUPPLY_RECOVER_SOURCE = "runtime_state:supply_status_recovered"
    private const val SUPPLY_SOLD_OUT_SOURCE = "runtime_state:supply_status_sold_out"
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
    private var lastAvailableDecisionLogKey = ""
    private var lastAvailableUpdateLogKey = ""
    private var lastBusinessStartRecoveryLogKey = ""
    private var loggedManualRecoveryRequiredOnDisconnect = false
    private var loggedManualRecoveryRequiredAfterReconnect = false
    private var inspectionDisconnectSuppressedLogged = false
    private var actionGroupRecoveredAfterModbusConnect = false
    private var startupHeatingStateRestored = false
    private var lastPollStateSnapshot: String? = null
    private var lastPollStateName: String? = null
    private var lastAutoDispatchBlockSnapshot: String? = null
    private var lastFaultHeatDiagnosticLogKey: String? = null
    private val faultCloseoutLogGate = RepeatedLogGate(summaryIntervalMs = null)
    private var orphanDeliveryBusyRecoveryCandidate: String? = null
    private var lastOrphanDeliveryBusyRecoveryAttemptAtMs: Long = 0L
    private var lastStableHighOldLowHeatDiagnosticKey: String? = null
    private val businessCloseoutPendingRepository = BusinessCloseoutPendingRepository(
        SharedPreferenceBusinessCloseoutPendingStore()
    )
    private var businessCloseoutHeatingPausedInProcess = false
    private var lastBusinessCloseoutBlockKey = ""
    var isEnabled = false

    private fun logD(category: String, message: String) = LogUtils.d(TAG, "[$category] $message")
    private fun logW(category: String, message: String) = LogUtils.w(TAG, "[$category] $message")
    private fun logI(category: String, message: String) = LogUtils.i(TAG, "[$category] $message")
    private fun logE(category: String, message: String) = LogUtils.e(TAG, "[$category] $message")

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
        0 -> "0(待投运)"
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
        2 -> "2(通信/硬件级故障)"
        else -> "$value(未知)"
    }

    private fun restStatusSourceText(value: String?): String = AppConfig.restStatusSourceText(value)

    private fun supplyStatusText(value: Int): String = when (value) {
        AppConfigBean.SUPPLY_STATUS_NORMAL -> "0(正常)"
        AppConfigBean.SUPPLY_STATUS_SOLD_OUT -> "1(售罄/待补货)"
        else -> "$value(未知)"
    }

    private fun isEnableText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    private fun businessTimeText(value: BusinessTime?): String {
        if (value == null) {
            return "null"
        }
        val defaultRange = value.defaultTime?.joinToString(prefix = "[", postfix = "]") ?: "null"
        val customRange = value.customTimes
            ?.joinToString(prefix = "[", postfix = "]") { item ->
                "day=${item.day},enabled=${item.enabled},range=${item.timeRange}"
            }
            ?: "null"
        return "mode=${value.mode ?: "null"},defaultTime=$defaultRange,customTimes=$customRange"
    }

    private fun faultHeatDiagnosticText(
        config: AppConfigBean,
        currentTime: String,
        isWithinBusinessHours: Boolean
    ): String =
        "currentTime=$currentTime，" +
            "isWithinBusinessHours=$isWithinBusinessHours，" +
            "businessTime=${businessTimeText(config.businessTime)}，" +
            "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
            "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}，" +
            "errorCode=${config.errorCode ?: "null"}，" +
            "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
            "moveStatus（机械动作状态）=${moveStatusText(config.moveStatus)}，" +
            "isRunning（算法轮询是否运行）=$isRunning，" +
            "faultHeatPauseActive（故障停热暂停）=${config.faultHeatPauseActive}"

    private fun logFaultHeatDiagnosticOnce(
        stage: String,
        config: AppConfigBean,
        currentTime: String,
        isWithinBusinessHours: Boolean
    ) {
        val logKey = listOf(
            stage,
            isWithinBusinessHours,
            businessTimeText(config.businessTime),
            config.onlineStatus,
            config.errorStatus,
            config.errorCode ?: "null",
            config.isEnable,
            config.moveStatus,
            isRunning,
            config.faultHeatPauseActive
        ).joinToString("|")
        if (lastFaultHeatDiagnosticLogKey == logKey) {
            return
        }
        lastFaultHeatDiagnosticLogKey = logKey
        logW(
            LOG_DEVICE,
            "【故障停热诊断】stage=$stage，" +
                faultHeatDiagnosticText(config, currentTime, isWithinBusinessHours)
        )
    }

    private fun residualDrainText(value: Int): String = when (value) {
        0 -> "0(无高并发残留)"
        1 -> "1(高并发残留消化中)"
        else -> "$value(未知)"
    }

    private fun logBusinessStartSkipOnce(source: String, reason: String, config: AppConfigBean) {
        val logKey = listOf(
            source,
            reason,
            config.onlineStatus,
            config.errorStatus,
            config.moveStatus,
            config.isEnable,
            AppConfig.normalizeRestStatusSource(config.restStatusSource)
        ).joinToString("|")
        if (lastBusinessStartRecoveryLogKey == logKey) {
            return
        }
        lastBusinessStartRecoveryLogKey = logKey
        logD(
            LOG_DEVICE,
            "跳过营业开始自动恢复：source=$source，原因=$reason，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
                "restStatusSource（休息中来源）=${restStatusSourceText(config.restStatusSource)}，" +
                "moveStatus（机械动作状态）=${moveStatusText(config.moveStatus)}，" +
                "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}"
        )
    }

    fun requestBusinessCloseout(source: String): Boolean {
        if (businessCloseoutPendingRepository.isPending()) {
            return true
        }
        if (!businessCloseoutPendingRepository.requestDurably()) {
            logE(LOG_DEVICE, "营业结束请求落盘失败，保持当前状态等待下轮重试：source=$source")
            return false
        }
        businessCloseoutHeatingPausedInProcess = false
        logI(LOG_DEVICE, "营业结束请求已耐久保存：source=$source")
        return true
    }

    fun finalizeBusinessCloseoutAfterManualPanClear(): Boolean {
        if (!businessCloseoutPendingRepository.isPending()) {
            return true
        }
        if (KaoPanHelper.getKaoPanList().any { it.isHasSausage }) {
            return false
        }
        val config = AppConfig.getAppConfig()
        val previousModeType = config.modeType
        val previousTransitionMode = config.transitionMode
        val previousResidualDrain = config.highConcurrencyResidualDrain
        foldBusinessCloseoutAfterManualPanClear(config)
        val committed = runCatching {
            AppConfig.saveAppConfigDurable(config)
            true
        }.getOrElse { error ->
            config.modeType = previousModeType
            config.transitionMode = previousTransitionMode
            config.highConcurrencyResidualDrain = previousResidualDrain
            logE(LOG_DEVICE, "人工清盘后的营业收口低并发提交失败，保留 pending：${error.message}")
            false
        }
        if (!committed) {
            return false
        }
        if (!businessCloseoutPendingRepository.clearDurably()) {
            logE(LOG_DEVICE, "人工清盘已提交低并发，但营业结束 pending 清理失败，等待人工重试")
            return false
        }
        businessCloseoutHeatingPausedInProcess = false
        lastBusinessCloseoutBlockKey = ""
        logI(LOG_DEVICE, "人工清盘已完成营业收口：保持维护态并提交低并发")
        return true
    }

    fun recoverBusinessStartIfNeeded(source: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (businessCloseoutPendingRepository.isPending()) {
            logBusinessStartSkipOnce(source, "营业结束收口尚未完成", config)
            return false
        }
        val businessTime = config.businessTime ?: return false
        if (!isWithinBusinessHours(getCurrentTime(), businessTime)) {
            lastBusinessStartRecoveryLogKey = ""
            return false
        }
        return when (config.onlineStatus) {
            2 -> {
                if (AppConfig.normalizeRestStatusSource(config.restStatusSource) != AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS) {
                    logBusinessStartSkipOnce(source, "休息中来源不是系统自动营业结束", config)
                    return false
                }
                val previousOnlineStatus = config.onlineStatus
                val previousIsEnable = config.isEnable
                KaoChangOperate.startBusinessHours(config, source)
                val currentConfig = AppConfig.getAppConfig()
                val changed = previousOnlineStatus != currentConfig.onlineStatus || previousIsEnable != currentConfig.isEnable
                if (changed) {
                    lastBusinessStartRecoveryLogKey = ""
                }
                changed
            }
            1 -> {
                lastBusinessStartRecoveryLogKey = ""
                false
            }
            0 -> {
                logBusinessStartSkipOnce(source, "待投运", config)
                false
            }
            3 -> {
                logBusinessStartSkipOnce(source, "维护中", config)
                false
            }
            4 -> {
                logBusinessStartSkipOnce(source, "正在出餐", config)
                false
            }
            else -> {
                logBusinessStartSkipOnce(source, "未知状态", config)
                false
            }
        }
    }

    fun restoreRoastAlgorithmState() {
        isEnabled = AppConfig.getAppConfig().isEnable == 1
    }

    fun isModbusAutoRecoverableFault(errorCode: String?): Boolean {
        return errorCode == FaultCode.MODBUS_DISCONNECT.name
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
        if (
            config.onlineStatus == 3 &&
            config.errorStatus == 2 &&
            isModbusAutoRecoverableFault(config.errorCode) &&
            !VMModbusHelper.connectStatus()
        ) {
            modbusReportedUnavailable = true
        }
        logD(LOG_ALGORITHM, "算法准备：保留moveStatus=${config.moveStatus}，等待统一动作恢复确认")
        serviceStartTime = System.currentTimeMillis()
        startupHeatingStateRestored = false
        logD(LOG_ALGORITHM, "智能烤肠算法服务启动")
        startModbusStateMonitor()
        algorithmScope.launch {
            while (isRunning) {
                // ponytail: 心跳必须先于 pollKaoPanStatus，且不得受 onlineStatus 门控；
                // 休息/维护中也要续心跳，否则重启会把带电休息误算成断电停热缺口导致误丢盘。
                PowerLossRecoveryGuard.markAliveIfDueAndPersist()
                pollKaoPanStatus()
                if (!isRunning) break
                // 每轮 poll 后只在可售/库存投影变化或兜底到期时同步完整快照。
                SnapshotSyncPolicy.maybeUploadSnapshot(reason = "poll_loop")
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
        val oldDisplayText = buildAvailableDisplayText(config.available)
        val newDisplayText = buildAvailableDisplayText(newAvailable)
        val logKey = listOf(reason, newLatched, newDisplayText).joinToString("|")
        if (lastAvailableUpdateLogKey != logKey) {
            lastAvailableUpdateLogKey = logKey
            logD(
                LOG_ALGORITHM,
                "更新二维码预计时间：reason=$reason，" +
                    "available=${config.available}->$newAvailable，" +
                    "availableCountdownLatched=${config.availableCountdownLatched}->$newLatched，" +
                    "displayText=$oldDisplayText->$newDisplayText"
            )
        }
        config.available = newAvailable
        config.availableCountdownLatched = newLatched
        AppConfig.saveAppConfig(config)
        HomeUiRefreshBridge.requestRefresh("available_state_changed")
        val mqttOk = SendServerHelper.publishAvailableState("algorithm:$reason")
        if (!mqttOk) {
            logW(
                LOG_ALGORITHM,
                "二维码预计时间 MQTT 发送队列拒绝，回退 HTTP 补偿：" +
                    "reason=$reason，available=$newAvailable，availableCountdownLatched=$newLatched"
            )
            NetApi.updateDeviceInfo()
        }
    }

    private fun buildAvailableDisplayText(available: Long): String {
        if (available <= 0L) {
            return "扫码下单 纯肉烤肠"
        }
        return if (available >= 60_000L) {
            "预计烤制 ${kotlin.math.ceil(available / 60000.0).toInt()} 分钟"
        } else {
            "预计烤制 ${kotlin.math.ceil(available / 1000.0).toInt()} 秒"
        }
    }

    private fun isHeatingPan(kaoPan: KaoPan): Boolean {
        return kaoPan.isHasSausage && kaoPan.startTime > 0L && kaoPan.holdingTime <= 0L
    }

    private fun isHoldingPan(kaoPan: KaoPan): Boolean {
        return KaoPanHelper.isSellable(kaoPan, KaoPanHelper.pendingDiscardPositionsOrEmpty())
    }

    private fun buildAvailableDecisionContext(
        config: AppConfigBean,
        sellArea: List<KaoPan>,
        heatArea: List<KaoPan>
    ): String {
        val frontOccupiedCount = sellArea.count { it.isHasSausage }
        val frontHoldingCount = sellArea.count { isHoldingPan(it) }
        val frontHeatingCount = sellArea.count { isHeatingPan(it) }
        val heatAreaHeatingCount = heatArea.count { isHeatingPan(it) }
        return "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
            "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}，" +
            "modeType（并发模式）=${modeText(config.modeType)}，" +
            "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
            "frontOccupiedCount（前区有肠数）=$frontOccupiedCount，" +
            "frontHoldingCount（前区保温数）=$frontHoldingCount，" +
            "frontHeatingCount（前区加热数）=$frontHeatingCount，" +
            "heatAreaHeatingCount（补热区加热数）=$heatAreaHeatingCount，" +
            "available（二维码页预计时间）=${config.available}，" +
            "availableCountdownLatched（二维码页预计时间锁存标记）=${config.availableCountdownLatched}"
    }

    private fun logAvailableDecision(
        reason: String,
        config: AppConfigBean,
        sellArea: List<KaoPan>,
        heatArea: List<KaoPan>
    ) {
        val frontOccupiedCount = sellArea.count { it.isHasSausage }
        val frontHoldingCount = sellArea.count { isHoldingPan(it) }
        val frontHeatingCount = sellArea.count { isHeatingPan(it) }
        val heatAreaHeatingCount = heatArea.count { isHeatingPan(it) }
        val logKey =
            listOf(
                reason,
                config.onlineStatus,
                config.isEnable,
                config.modeType,
                config.transitionMode,
                config.highConcurrencyResidualDrain,
                frontOccupiedCount,
                frontHoldingCount,
                frontHeatingCount,
                heatAreaHeatingCount,
                config.availableCountdownLatched
            ).joinToString("|")
        if (lastAvailableDecisionLogKey == logKey) {
            return
        }
        lastAvailableDecisionLogKey = logKey
        logD(
            LOG_ALGORITHM,
            "二维码预计时间判定：reason=$reason，${buildAvailableDecisionContext(config, sellArea, heatArea)}"
        )
    }
    /**
     * 计算二维码页显示的“预计烤制时间”。
     * 展示规则：
     * 1. 前区还有保温烤肠时，不展示预计烤制时间；
     * 2. 前区没有可售烤肠时：
     *    - 若当前开盘批次仍在前区加热，则显示“最早能恢复可售”的开盘批次剩余时间；
     *    - 若只剩补肠批次在补热区加热，则显示“最早能恢复可售”的那一批剩余时间；
     * 3. 只要前区出现至少一根可售保温烤肠，就打开 availableCountdownLatched（二维码页预计时间锁存标记）；
     * 4. 实际可售数量仍由服务端按烤盘、口味、商品和订单占用计算。
     */
    private fun checkAvailable(config: AppConfigBean, kaoPanList: List<KaoPan>) {
        val sellArea = findAvailableProjectionSellArea(config, kaoPanList)
        val heatArea = findAvailableProjectionHeatArea(config, kaoPanList)
        val trackedArea = findAvailableProjectionTrackedArea(config, kaoPanList)
        val trackedPans = trackedArea.filter { it.isHasSausage }
        val sellableHoldingPans = sellArea.filter { isHoldingPan(it) }
        val now = System.currentTimeMillis()

        if (trackedPans.isEmpty()) {
            logAvailableDecision("当前批次相关烤盘已全部清空", config, sellArea, heatArea)
            updateAvailableState(config, 0L, 0, "当前批次相关烤盘已全部清空")
            return
        }

        if (sellableHoldingPans.isNotEmpty()) {
            logAvailableDecision("前区仍有保温烤肠，不展示预计时间", config, sellArea, heatArea)
            updateAvailableState(config, 0L, 1, "前区仍有保温烤肠，不展示预计时间")
            return
        }

        val nextAvailableBatch = findNextAvailableHeatBatch(
            config = config,
            kaoPanList = kaoPanList,
            now = now
        )
        if (nextAvailableBatch != null) {
            logAvailableDecision(
                "前区暂无可售烤肠，显示${nextAvailableBatch.name}恢复可售前的预计时间：remainingMs=${nextAvailableBatch.remainHeatTimeMs}",
                config,
                sellArea,
                heatArea
            )
            updateAvailableState(
                config,
                nextAvailableBatch.remainHeatTimeMs,
                0,
                "前区暂无可售烤肠，显示${nextAvailableBatch.name}恢复可售前的预计时间"
            )
            return
        }

        logAvailableDecision("前区无保温烤肠，当前也没有正在加热的待开放批次", config, sellArea, heatArea)
        updateAvailableState(config, 0L, 0, "前区无保温烤肠，当前也没有正在加热的待开放批次")
    }

    private fun getSellArea(config: AppConfigBean, kaoPanList: List<KaoPan>): List<KaoPan> {
        return findAvailableProjectionSellArea(config, kaoPanList)
    }

    private fun getTrackedArea(config: AppConfigBean, kaoPanList: List<KaoPan>): List<KaoPan> {
        return findAvailableProjectionTrackedArea(config, kaoPanList)
    }

    private fun evaluateSupplyRuntimeContext(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        kaoPanBoxList: List<cn.niuyannet.kaochang.android.model.bean.KaoPanBox>
    ): SupplyRuntimeContext {
        val sellArea = getSellArea(config, kaoPanList)
        val trackedArea = getTrackedArea(config, kaoPanList)
        val hasAutoSupplementSupplyBoxesAvailable = kaoPanBoxList.any {
            KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it)
        }
        val hasPhysicalSupplyBoxesAvailable = kaoPanBoxList.any {
            KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(it)
        }
        val hasSellableSausage = sellArea.any { isHoldingPan(it) }
        val hasActiveBatch =
            trackedArea.any { it.isHasGrilling } ||
                trackedArea.any { it.isHasSausage && !isHoldingPan(it) } ||
                config.moveStatus != 0 ||
                config.onlineStatus == 4 ||
                config.transitionMode != 0
        return SupplyRuntimeContext(
            hasAutoSupplementSupplyBoxesAvailable = hasAutoSupplementSupplyBoxesAvailable,
            hasPhysicalSupplyBoxesAvailable = hasPhysicalSupplyBoxesAvailable,
            hasSellableSausage = hasSellableSausage,
            hasActiveBatch = hasActiveBatch
        )
    }

    private fun publishRuntimeStateOrFallback(source: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus(source)
        if (!mqttOk) {
            logW(LOG_DEVICE, "运行态 MQTT 发送队列拒绝，回退 HTTP 补偿：source=$source")
            NetApi.updateDeviceInfo()
        }
    }

    private fun stopHeatingForSoldOutState(config: AppConfigBean) {
        KaoChangOperate.stopHeating(1)
        if (config.modeType == 1 || config.transitionMode == 1) {
            KaoChangOperate.stopHeating(2)
            return
        }
        KaoChangOperate.stopHeating(2)
        KaoChangOperate.stopHeating(3)
    }

    private fun selfHealHighToLowEmptyResidual(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        kaoPanBoxList: List<cn.niuyannet.kaochang.android.model.bean.KaoPanBox>
    ): Boolean {
        val hasSupplyBoxesAvailable = kaoPanBoxList.any { KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it) }
        val decision = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = config.transitionMode,
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            moveStatus = config.moveStatus,
            hasSupplyBoxesAvailable = hasSupplyBoxesAvailable,
            kaoPanList = kaoPanList
        )
        if (!decision.canClear) {
            return false
        }

        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val oldSupplyStatus = config.supplyStatus
        config.modeType = 1
        config.transitionMode = 0
        AppConfig.saveAppConfig(config)
        logW(
            LOG_ALGORITHM,
            "【并发切换】高转低空残留自愈：清理残留过渡态并回落低并发，" +
                "modeType（并发模式）=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "箱空=${!decision.hasSupplyBoxesAvailable}，前区无保温可售肠=${!decision.hasFrontSellableSausage}，" +
                "高并发区域10~21/25~33空闲=${decision.highConcurrencyAreaIdle}，" +
                "moveStatus（机械动作状态）=${moveStatusText(config.moveStatus)}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "supplyStatus（补货状态）清理前=${supplyStatusText(oldSupplyStatus)}，随后重新计算"
        )
        publishRuntimeStateOrFallback("runtime_state:high_to_low_empty_residual_healed")
        return true
    }

    private fun diagnoseStableHighSuspiciousOldLowHeatSources(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ) {
        val oldLowHeatPans = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        val suspiciousPositions = collectStableHighSuspiciousOldLowHeatSources(
            modeType = config.modeType,
            transitionMode = config.transitionMode,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = activeIntent
        )
        val logKey = if (suspiciousPositions.isEmpty()) {
            "none"
        } else {
            "positions=${suspiciousPositions.joinToString(",")}|batchId=${activeIntent?.batchId.orEmpty()}"
        }
        if (lastStableHighOldLowHeatDiagnosticKey == logKey) {
            return
        }
        lastStableHighOldLowHeatDiagnosticKey = logKey
        if (suspiciousPositions.isEmpty()) {
            return
        }
        logW(
            LOG_ALGORITHM,
            "【并发切换诊断】高并发稳态检测到强疑似旧低补热源仍为可售保温：" +
                "positions=$suspiciousPositions，batchId=${activeIntent?.batchId}，" +
                "type=${activeIntent?.type?.let { SupplementBatchIntentRepository.typeText(it) }}，" +
                "处理策略=仅告警并同步运行态，不自动改盘，避免误伤高并发正常前区可售"
        )
        publishRuntimeStateOrFallback("runtime_state:stable_high_suspected_old_low_heat_legacy")
    }

    private fun refreshSupplyStatus(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        kaoPanBoxList: List<cn.niuyannet.kaochang.android.model.bean.KaoPanBox>
    ): SupplyRuntimeContext {
        val context = evaluateSupplyRuntimeContext(config, kaoPanList, kaoPanBoxList)
        val targetSupplyStatus =
            if (!context.hasPhysicalSupplyBoxesAvailable && !context.hasSellableSausage && !context.hasActiveBatch) {
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT
            } else {
                AppConfigBean.SUPPLY_STATUS_NORMAL
            }
        if (config.supplyStatus == targetSupplyStatus) {
            return context
        }

        val previousSupplyStatus = config.supplyStatus
        config.supplyStatus = targetSupplyStatus
        AppConfig.saveAppConfig(config)
        if (targetSupplyStatus == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
            stopHeatingForSoldOutState(config)
            logW(
                LOG_DEVICE,
                "补货状态已切换为售罄：supplyStatus（补货状态）=${supplyStatusText(previousSupplyStatus)}->${supplyStatusText(config.supplyStatus)}，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "原因=烤肠箱已空且当前无可售烤肠、无活动批次"
            )
            publishRuntimeStateOrFallback(SUPPLY_SOLD_OUT_SOURCE)
        } else {
            logI(
                LOG_DEVICE,
                "补货状态已恢复正常：supplyStatus（补货状态）=${supplyStatusText(previousSupplyStatus)}->${supplyStatusText(config.supplyStatus)}，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "原因=检测到烤肠箱已补货或仍有可继续售卖/处理的批次"
            )
            publishRuntimeStateOrFallback(SUPPLY_RECOVER_SOURCE)
        }
        HomeUiRefreshBridge.requestRefresh("supply_status_changed")
        return context
    }

    fun hasBlockingActiveBatchForAlgorithmDisable(): Boolean {
        val config = AppConfig.getAppConfig()
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val context = evaluateSupplyRuntimeContext(config, kaoPanList, KaoPanHelper.getKaoPanBoxList())
        return context.hasActiveBatch
    }

    private fun canEnableAlgorithm(config: AppConfigBean, onlineStatus: Int = config.onlineStatus): Boolean {
        return onlineStatus in listOf(1, 4) &&
            config.inspectionMode == AppConfigBean.INSPECTION_MODE_NORMAL &&
            config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL
    }

    private fun shouldFreezeByInspection(config: AppConfigBean): Boolean {
        return config.inspectionMode == AppConfigBean.INSPECTION_MODE_ACTIVE
    }

    private fun refreshHighConcurrencyResidualDrain(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        source: String
    ): Boolean {
        val previousResidualDrain = config.highConcurrencyResidualDrain
        if (config.transitionMode == 1) {
            if (previousResidualDrain == 0) {
                return false
            }
            config.highConcurrencyResidualDrain = 0
            AppConfig.saveAppConfig(config)
            logI(
                LOG_ALGORITHM,
                "低转高过渡进行中，清理内部高并发残留消化标记：" +
                    "source=$source，highConcurrencyResidualDrain（高并发残留消化态）=${residualDrainText(previousResidualDrain)}->${residualDrainText(config.highConcurrencyResidualDrain)}，" +
                    "modeType（并发模式）=${modeText(config.modeType)}，transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}"
            )
            publishRuntimeStateOrFallback("runtime_state:low_to_high_residual_drain_cleared")
            return true
        }
        val targetResidualDrain = HighConcurrencyResidualDrainEvaluator.evaluate(
            modeType = config.modeType,
            kaoPanList = kaoPanList,
            includeLowHeatOverlapArea = config.highConcurrencyResidualDrain == 1
        )
        if (previousResidualDrain == targetResidualDrain) {
            return false
        }
        config.highConcurrencyResidualDrain = targetResidualDrain
        AppConfig.saveAppConfig(config)
        logI(
            LOG_ALGORITHM,
            "内部高并发残留收口标记已更新：source=$source，" +
                "highConcurrencyResidualDrain（高并发残留消化态）=${residualDrainText(previousResidualDrain)}->${residualDrainText(targetResidualDrain)}，" +
                "modeType（并发模式）=${modeText(config.modeType)}，transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}"
        )
        publishRuntimeStateOrFallback("runtime_state:high_concurrency_residual_drain_changed")
        return true
    }

    private fun stopHeatingAndDispatchForFault(
        reason: String,
        forceStopHeating: Boolean = false
    ) {
        KaoChangOperate.withFaultHeatTransitionLock {
            val currentConfig = AppConfig.getAppConfig()
            if (!forceStopHeating && currentConfig.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL) {
                logD(LOG_DEVICE, "跳过已过期的故障停热收口：reason=$reason，当前故障已恢复")
                return@withFaultHeatTransitionLock
            }
            val currentTime = getCurrentTime()
            val isWithinBusinessHours = isWithinBusinessHours(currentTime, currentConfig.businessTime)
            val previousOnlineStatus = currentConfig.onlineStatus
            val previousIsEnable = currentConfig.isEnable
            if (currentConfig.onlineStatus != 3) {
                currentConfig.onlineStatus = 3
            }
            currentConfig.isEnable = 0
            isEnabled = false
            AppConfig.saveAppConfig(currentConfig)
            if (
                forceStopHeating ||
                !FaultHeatPauseManager.shouldSkipBakingCompletion(currentConfig) ||
                currentConfig.faultHeatStopConfirmed != 1
            ) {
                KaoChangOperate.stopHeatingForFault(reason)
            }
            val faultCloseoutState = listOf(
                reason,
                currentConfig.onlineStatus,
                currentConfig.isEnable,
                currentConfig.errorStatus,
                currentConfig.errorCode ?: "null",
                currentConfig.moveStatus,
                currentConfig.faultHeatPauseActive,
                currentConfig.faultHeatStopConfirmed
            ).joinToString("|")
            if (faultCloseoutLogGate.evaluate(faultCloseoutState, SystemClock.elapsedRealtime())?.stateChanged == true) {
                logW(
                    LOG_DEVICE,
                    "设备故障态收口：reason=$reason，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(currentConfig.onlineStatus)}，" +
                        "isEnable（烤肠算法开关）=${isEnableText(previousIsEnable)}->${isEnableText(currentConfig.isEnable)}，" +
                        "errorStatus（设备故障状态）=${errorStatusText(currentConfig.errorStatus)}，" +
                        faultHeatDiagnosticText(currentConfig, currentTime, isWithinBusinessHours)
                )
            }
            HomeUiRefreshBridge.requestRefresh("algorithm_fault:$reason")
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
        if (config.businessTime == null) {
            logW(LOG_ALGORITHM, "【营业时间】营业时间配置为空（businessTime=null），算法按非营业时间处理，设备将不会运营。请检查后台是否已配置营业时间")
        }
        normalizeSupplementSourceKeepWarmBeforeEarlyReturns(config, kaoPanList)
        val currentTime = getCurrentTime()
        val isWithinBusinessHours = isWithinBusinessHours(currentTime, config.businessTime)
        captureBusinessCloseoutBeforeEarlyReturns(config, isWithinBusinessHours)

        if (config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL) {
            lastFaultHeatDiagnosticLogKey = null
            faultCloseoutLogGate.reset()
        }

        if (config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL) {
            logFaultHeatDiagnosticOnce("error_status_before_fault_stop", config, currentTime, isWithinBusinessHours)
            stopHeatingAndDispatchForFault("算法轮询检测到设备故障")
            SendServerHelper.publishServiceUpdateStatus()
            return
        }
        if (processBusinessCloseoutPendingIfAny()) {
            return
        }
        if (
            onlineStatus == 3 &&
            (
                !FaultHeatPauseManager.shouldSkipBakingCompletion(config) ||
                    config.faultHeatStopConfirmed != 1
            )
        ) {
            KaoChangOperate.stopHeatingForFault("维护态检测到故障停热暂停或三区停热确认缺失，写0")
            return
        }

        if (!isWithinBusinessHours) {
            logFaultHeatDiagnosticOnce("not_within_business_hours_before_return", config, currentTime, isWithinBusinessHours)
            logD(LOG_ALGORITHM, "当前不在营业时间内，设备状态=${onlineStatusText(onlineStatus)}，跳过普通算法调度")
            return
        }

        if (onlineStatus == 2) {
            recoverBusinessStartIfNeeded("pollKaoPanStatus")
            onlineStatus = AppConfig.getAppConfig().onlineStatus
        }

        if (!(onlineStatus == 1 || onlineStatus == 4)) {
            logD(LOG_DEVICE, "设备当前处于${onlineStatusText(onlineStatus)}，跳过算法调度")
            return
        }
        if (shouldFreezeByInspection(config)) {
            logD(
                LOG_DEVICE,
                "设备当前处于检修模式，已冻结自动调度：" +
                    "inspectionMode=${config.inspectionMode}，onlineStatus=${onlineStatusText(onlineStatus)}，" +
                    "isEnable=${isEnableText(config.isEnable)}"
            )
            return
        }
        if (!VMModbusHelper.connectStatus()) {
            logD(LOG_COMM, "modbus 串口未连接，跳过算法调度")
            return
        }
        if (KaoChangOperate.isParallelSupplementPausedForRuntimeRecovery() &&
            !KaoChangOperate.resumeParallelSupplementForRuntimeRecovery("算法轮询优先恢复双 lane")
        ) {
            logD(LOG_ALGORITHM, "双 lane 运行态延迟续作未完成，跳过本轮其他机械调度")
            return
        }
        val actionGroupRecoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked()
        if (actionGroupRecoveryBlocked) {
            refreshActiveBakingTimeBeforeMaturityCheck(config, kaoPanList)
            runCatching {
                val closeout = KaoChangOperate.prepareLowInitializationMaturityCloseout(
                    config = config,
                    pans = kaoPanList,
                    nowMs = System.currentTimeMillis(),
                    recoveryBlocked = true
                )
                if (closeout != null) {
                    val beforeSellable = if (closeout.requiresPanSettlement) {
                        KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
                    } else {
                        null
                    }
                    if (closeout.requiresPanSettlement) {
                        check(settleMaturedLowFrontBeforeRecoveryReturn(config, kaoPanList, closeout)) {
                            "低并发残缺初始化成熟盘状态收口失败"
                        }
                    }
                    KaoPanHelper.saveKaoPanListDurable(KaoPanHelper.getKaoPanList())
                    KaoChangOperate.completeLowInitializationMaturityCloseout(closeout)
                    if (beforeSellable != null) {
                        runCatching {
                            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                                beforeSellable,
                                KaoPanHelper.getKaoPanList(),
                                "恢复门禁阻塞期间一区成熟转保温"
                            )
                        }.onFailure { error ->
                            logW(LOG_ALGORITHM, "一区成熟收口已耐久完成，可售状态等待快照补偿：${error.message}")
                        }
                    }
                }
            }.onFailure { error ->
                logW(LOG_ALGORITHM, "低并发残缺初始化成熟收口失败，保持恢复门禁：${error.message}")
            }
            logD(LOG_ALGORITHM, "动作恢复尚未收口，跳过本轮机械调度")
            return
        }
        if (kaoPanBoxList.isEmpty()) {
            logW(LOG_ALGORITHM, "本地烤肠箱缓存为空，跳过本轮自动调度并触发库存补拉，避免首次安装时使用空库存运行")
            DataManagementAPI.refreshStockData(logPrefix = "算法轮询-stock-missing")
            return
        }
        if (
            config.isEnable == 1 &&
            isEnabled &&
            TemperatureControlPolicy.shouldRestoreHeatingAfterStartup(startupHeatingStateRestored, modbusConnected = true)
        ) {
            val restoreSucceeded =
                KaoChangOperate.applyConfiguredTemperatureByPanState("App启动后首次检测到Modbus已连接，按当前烤盘状态恢复温度")
            startupHeatingStateRestored = restoreSucceeded
        }
        KaoChangOperate.stopAllHeatingIfNoSausage("全盘无肠兜底检测")

        selfHealHighToLowEmptyResidual(config, kaoPanList, kaoPanBoxList)
        refreshHighConcurrencyResidualDrain(config, kaoPanList, "pollKaoPanStatus")
        diagnoseStableHighSuspiciousOldLowHeatSources(config, kaoPanList)
        val supplyContext = refreshSupplyStatus(config, kaoPanList, kaoPanBoxList)
        val autoDispatchDecision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            moveStatus = config.moveStatus,
            lowerPlatformStatus = config.lowerPlatformStatus,
            lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName,
            orderPending = SupplementPauseController.shouldPauseForOrder()
        )
        val schedulingEnabled =
            isEnabled && config.isEnable == 1 && canEnableAlgorithm(config, onlineStatus) && autoDispatchDecision.canStart

        refreshActiveBakingTimeBeforeMaturityCheck(config, kaoPanList)
        settleOldLowHeatSourcesBeforePendingDiscard(config, kaoPanList)
        if (config.pendingDiscardPositions.isNotEmpty()) {
            val recovery = if (shouldAttemptOrphanDeliveryBusyRecovery(config)) {
                KaoChangOperate.recoverOrphanDeliveryBusyForPendingDiscard()
            } else {
                null
            }
            if (recovery?.recovered == true) {
                publishRuntimeStateOrFallback("orphan_delivery_busy_recovered_for_pending_discard")
                SendServerHelper.publishAvailableState("orphan_delivery_busy_recovered_for_pending_discard")
            }
            OverKeepWarmDiscardScanner.scanAndDiscard(1..33, "pending_discard_priority")
            return
        }
        resetOrphanDeliveryBusyRecoveryAttempt()
        settleMaturedZonesBeforeDispatch(config, kaoPanList)
        normalizeOldLowHeatSourcesBeforeAvailabilityProjection(config, kaoPanList)

        val pollStateSnapshot =
            "mode=${config.modeType},transition=${config.transitionMode},residual=${config.highConcurrencyResidualDrain},online=$onlineStatus,move=${config.moveStatus},error=${config.errorStatus},supply=${config.supplyStatus},homeQrHidden=${config.homeQrHidden},inspection=${config.inspectionMode},isEnable=${config.isEnable},enable=$schedulingEnabled"
        if (pollStateSnapshot != lastPollStateSnapshot) {
            val currentStateName = AlgorithmStateLogFormatter.stateName(
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                highConcurrencyResidualDrain = config.highConcurrencyResidualDrain,
                schedulingEnabled = schedulingEnabled
            )
            LogUtils.i(
                TAG,
                AlgorithmStateLogFormatter.buildChangeLog(
                    previousStateName = lastPollStateName,
                    modeType = config.modeType,
                    transitionMode = config.transitionMode,
                    highConcurrencyResidualDrain = config.highConcurrencyResidualDrain,
                    onlineStatus = onlineStatus,
                    supplyStatus = config.supplyStatus,
                    homeQrHidden = config.homeQrHidden,
                    inspectionMode = config.inspectionMode,
                    moveStatus = config.moveStatus,
                    errorStatus = config.errorStatus,
                    isEnable = config.isEnable,
                    schedulingEnabled = schedulingEnabled
                )
            )
            lastPollStateSnapshot = pollStateSnapshot
            lastPollStateName = currentStateName
        }

        checkAvailable(config, kaoPanList)
        if (config.supplyStatus == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
            return
        }
        if (isEnabled && config.isEnable == 1 && !autoDispatchDecision.canStart) {
            val blockSnapshot =
                "online=$onlineStatus,move=${config.moveStatus},error=${config.errorStatus},inspection=${config.inspectionMode},reason=${autoDispatchDecision.reason}"
            if (blockSnapshot != lastAutoDispatchBlockSnapshot) {
                logI(
                    LOG_ALGORITHM,
                    "【调度保护】${autoDispatchDecision.reason}"
                )
                lastAutoDispatchBlockSnapshot = blockSnapshot
            }
            return
        }
        lastAutoDispatchBlockSnapshot = null

        when {
            config.transitionMode != 0 -> {
                HighCurrentTrafficMode.highTrafficMode(
                    config,
                    list = kaoPanList,
                    listBox = kaoPanBoxList,
                    boxSupplyAvailable = supplyContext.hasAutoSupplementSupplyBoxesAvailable,
                    schedulingEnabled = schedulingEnabled
                )
            }

            config.modeType == 1 -> {
                if (config.highConcurrencyResidualDrain == 1) {
                    logI(
                        LOG_ALGORITHM,
                        "【调度保护】低并发内部高并发残留收口中，暂停新的低并发补热批次：" +
                            "highConcurrencyResidualDrain=${residualDrainText(config.highConcurrencyResidualDrain)}"
                    )
                    HighCurrentTrafficMode.drainHighConcurrencyResidual(
                        list = kaoPanList,
                        listBox = kaoPanBoxList,
                        schedulingEnabled = schedulingEnabled,
                        reason = "后台/人工切低并发后的高并发残留内部收口"
                    )
                } else {
                    LowCurrentTrafficMode.lowTrafficMode(
                        config,
                        kaoPanList,
                        kaoPanBoxList,
                        boxSupplyAvailable = supplyContext.hasAutoSupplementSupplyBoxesAvailable,
                        schedulingEnabled = schedulingEnabled
                    )
                }
            }

            config.modeType == 2 -> {
                HighCurrentTrafficMode.highTrafficMode(
                    config,
                    list = kaoPanList,
                    listBox = kaoPanBoxList,
                    boxSupplyAvailable = supplyContext.hasAutoSupplementSupplyBoxesAvailable,
                    schedulingEnabled = schedulingEnabled
                )
            }
        }
    }

    private fun captureBusinessCloseoutBeforeEarlyReturns(
        config: AppConfigBean,
        isWithinBusinessHours: Boolean
    ) {
        if (
            shouldCaptureBusinessCloseout(
                isWithinBusinessHours = isWithinBusinessHours,
                onlineStatus = config.onlineStatus,
                errorStatus = config.errorStatus
            )
        ) {
            requestBusinessCloseout("算法轮询检测到营业结束")
        }
    }

    private fun logBusinessCloseoutBlocked(reason: String, config: AppConfigBean) {
        val key = listOf(
            reason,
            config.onlineStatus,
            config.errorStatus,
            config.inspectionMode,
            config.isEnable,
            config.moveStatus,
            SupplementPauseController.shouldPauseForOrder()
        ).joinToString("|")
        if (key == lastBusinessCloseoutBlockKey) {
            return
        }
        lastBusinessCloseoutBlockKey = key
        logD(
            LOG_DEVICE,
            "营业结束收口暂缓：reason=$reason，onlineStatus=${onlineStatusText(config.onlineStatus)}，" +
                "errorStatus=${errorStatusText(config.errorStatus)}，moveStatus=${moveStatusText(config.moveStatus)}，" +
                "inspectionMode=${config.inspectionMode}，isEnable=${isEnableText(config.isEnable)}"
        )
    }

    private fun ensureBusinessCloseoutRestGate(): Boolean {
        var restStateChanged = false
        var lockedBlockReason: String? = null
        val restGateReady = KaoChangOperate.withFaultHeatTransitionLock {
            val lockedConfig = AppConfig.getAppConfig()
            if (SupplementPauseController.shouldPauseForOrder() || lockedConfig.onlineStatus == 4) {
                lockedBlockReason = "订单尚未结束"
                false
            } else if (lockedConfig.onlineStatus == 1) {
                val previousOnlineStatus = lockedConfig.onlineStatus
                val previousRestStatusSource = lockedConfig.restStatusSource
                lockedConfig.onlineStatus = 2
                lockedConfig.restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
                runCatching {
                    AppConfig.saveAppConfigDurable(lockedConfig)
                    restStateChanged = true
                    true
                }.getOrElse { error ->
                    lockedConfig.onlineStatus = previousOnlineStatus
                    lockedConfig.restStatusSource = previousRestStatusSource
                    lockedBlockReason = "营业结束休息态落盘失败：${error.message}"
                    false
                }
            } else {
                true
            }
        }
        if (!restGateReady) {
            val latest = AppConfig.getAppConfig()
            logBusinessCloseoutBlocked(lockedBlockReason ?: "休息态提交被拒绝", latest)
            return false
        }
        if (restStateChanged) {
            HomeUiRefreshBridge.requestRefresh("business_closeout:rest_gate")
            SendServerHelper.publishServiceUpdateStatus()
        }
        val latest = AppConfig.getAppConfig()
        if (
            latest.onlineStatus != 2 ||
            AppConfig.normalizeRestStatusSource(latest.restStatusSource) !=
            AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        ) {
            logBusinessCloseoutBlocked("当前状态不允许自动营业收口", latest)
            return false
        }
        return true
    }

    private fun currentBusinessCloseoutDiscardGate(config: AppConfigBean): Boolean {
        val lowerPlatformBlocked = AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
            config.lowerPlatformStatus,
            config.lowerPlatformIsolationActionName
        )
        return canRunBusinessCloseoutDiscard(
            onlineStatus = config.onlineStatus,
            automaticRestSource = AppConfig.normalizeRestStatusSource(config.restStatusSource) ==
                AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            modbusConnected = VMModbusHelper.connectStatus(),
            moveStatus = config.moveStatus,
            orderPending = SupplementPauseController.shouldPauseForOrder(),
            recoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked(),
            emergencyResetPending = KaoChangOperate.hasPendingEmergencyResetRecovery(),
            lowerPlatformBlocked = lowerPlatformBlocked
        )
    }

    private suspend fun processBusinessCloseoutPendingIfAny(): Boolean {
        if (!businessCloseoutPendingRepository.isPending()) {
            return false
        }
        if (!ensureBusinessCloseoutRestGate()) {
            return true
        }
        val latest = AppConfig.getAppConfig()
        if (!currentBusinessCloseoutDiscardGate(latest)) {
            logBusinessCloseoutBlocked("订单、故障、检修、通信或机械恢复门禁未清", latest)
            return true
        }
        if (!businessCloseoutHeatingPausedInProcess) {
            if (!KaoChangOperate.pauseBusiness()) {
                logBusinessCloseoutBlocked("三区停热未全部确认成功", AppConfig.getAppConfig())
                return true
            }
            businessCloseoutHeatingPausedInProcess = true
        }
        if (finalizeBusinessCloseoutIfAllClear()) {
            return true
        }

        KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
            types = setOf(
                SupplementBatchType.LOW_HEAT_13_15,
                SupplementBatchType.LOW_HEAT_16_18,
                SupplementBatchType.HIGH_HEAT_25_33
            ),
            sourcePositionsWithSausage = KaoPanHelper.getKaoPanList()
                .filter { it.positionSn in 13..18 || it.positionSn in 25..33 }
                .filter { it.isHasSausage }
                .map { it.positionSn }
                .toSet(),
            reason = "营业结束收口清理已空补热源盘 binding"
        )
        KaoChangOperate.clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(
            "营业结束停止未完成补肠目标"
        )
        val remainingSupplementTargets = KaoChangOperate.maintenanceKaoPanIntentForUi()
            ?.remainingTargets()
            .orEmpty()
        if (remainingSupplementTargets.isNotEmpty()) {
            logBusinessCloseoutBlocked("仍有待人工确认或未收口的补肠目标", AppConfig.getAppConfig())
            return true
        }

        val targetPans = KaoPanHelper.getKaoPanList()
            .filter { it.isHasSausage }
            .sortedBy { it.positionSn }
        for (kaoPan in targetPans) {
            val beforeDiscard = AppConfig.getAppConfig()
            if (!currentBusinessCloseoutDiscardGate(beforeDiscard)) {
                logBusinessCloseoutBlocked("下一盘丢弃前门禁已变化", beforeDiscard)
                return true
            }
            val discarded = try {
                KaoChangOperate.discardSausage(
                    kaoPan = kaoPan,
                    source = BUSINESS_CLOSEOUT_DISCARD_SOURCE,
                    detail = "营业结束耐久收口",
                    automatic = false,
                    businessCloseout = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logE(
                    LOG_DEVICE,
                    "营业结束丢弃异常，保留 pending 并停止本轮：pan=${kaoPan.positionSn}，" +
                        "error=${error.message ?: error.javaClass.simpleName}"
                )
                return true
            }
            if (!discarded) {
                logBusinessCloseoutBlocked("烤盘${kaoPan.positionSn}丢弃未确认成功，停止本轮", AppConfig.getAppConfig())
                return true
            }
        }
        finalizeBusinessCloseoutIfAllClear()
        return true
    }

    private fun finalizeBusinessCloseoutIfAllClear(): Boolean {
        var config = AppConfig.getAppConfig()
        if (
            config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL ||
            config.onlineStatus != 2 ||
            AppConfig.normalizeRestStatusSource(config.restStatusSource) !=
            AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
        ) {
            return false
        }
        val pans = KaoPanHelper.getKaoPanList()
        if (pans.any { it.isHasSausage }) {
            return false
        }
        if (
            !canFinalizeBusinessCloseout(
                hasSausage = false,
                hasPendingDiscard = config.pendingDiscardPositions.isNotEmpty(),
                moveStatus = config.moveStatus,
                orderPending = SupplementPauseController.shouldPauseForOrder(),
                recoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked(),
                emergencyResetPending = KaoChangOperate.hasPendingEmergencyResetRecovery(),
                hasSupplementEvidence = false
            )
        ) {
            return false
        }
        KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
            types = setOf(
                SupplementBatchType.LOW_HEAT_13_15,
                SupplementBatchType.LOW_HEAT_16_18,
                SupplementBatchType.HIGH_HEAT_25_33
            ),
            sourcePositionsWithSausage = emptySet(),
            reason = "营业结束全盘为空后收口补热 binding"
        )
        KaoChangOperate.clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(
            "营业结束全盘为空后收口补肠 intent"
        )
        config = AppConfig.getAppConfig()
        val hasSupplementEvidence = KaoChangOperate.maintenanceKaoPanIntentForUi() != null
        if (
            !canFinalizeBusinessCloseout(
                hasSausage = pans.any { it.isHasSausage },
                hasPendingDiscard = config.pendingDiscardPositions.isNotEmpty(),
                moveStatus = config.moveStatus,
                orderPending = SupplementPauseController.shouldPauseForOrder(),
                recoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked(),
                emergencyResetPending = KaoChangOperate.hasPendingEmergencyResetRecovery(),
                hasSupplementEvidence = hasSupplementEvidence
            )
        ) {
            return false
        }

        val previousModeType = config.modeType
        val previousTransitionMode = config.transitionMode
        val previousResidualDrain = config.highConcurrencyResidualDrain
        val previousOnlineStatus = config.onlineStatus
        val previousRestStatusSource = config.restStatusSource
        val previousIsEnable = config.isEnable
        val configAlreadyCommitted = config.modeType == 1 &&
            config.transitionMode == 0 &&
            config.highConcurrencyResidualDrain == 0 &&
            config.onlineStatus == 2 &&
            AppConfig.normalizeRestStatusSource(config.restStatusSource) ==
            AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS &&
            config.isEnable == 0
        if (!configAlreadyCommitted) {
            config.modeType = 1
            config.transitionMode = 0
            config.highConcurrencyResidualDrain = 0
            config.onlineStatus = 2
            config.restStatusSource = AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS
            config.isEnable = 0
            val committed = runCatching {
                AppConfig.saveAppConfigDurable(config)
                true
            }.getOrElse { error ->
                config.modeType = previousModeType
                config.transitionMode = previousTransitionMode
                config.highConcurrencyResidualDrain = previousResidualDrain
                config.onlineStatus = previousOnlineStatus
                config.restStatusSource = previousRestStatusSource
                config.isEnable = previousIsEnable
                logE(LOG_DEVICE, "营业结束低并发提交失败，保留 pending：${error.message}")
                false
            }
            if (!committed) {
                return false
            }
        }
        isEnabled = false
        if (!businessCloseoutPendingRepository.clearDurably()) {
            logE(LOG_DEVICE, "低并发已提交但营业结束 pending 清理失败，等待下轮幂等收口")
            return false
        }
        businessCloseoutHeatingPausedInProcess = false
        lastBusinessCloseoutBlockKey = ""
        HomeUiRefreshBridge.requestRefresh("business_closeout:low_committed")
        SendServerHelper.publishServiceUpdateStatus()
        logI(LOG_DEVICE, "营业结束全部烤肠已确认丢弃，已提交低并发休息态并清除 pending")
        return true
    }

    private fun shouldAttemptOrphanDeliveryBusyRecovery(config: AppConfigBean): Boolean {
        if (config.moveStatus != 2) {
            resetOrphanDeliveryBusyRecoveryAttempt()
            return false
        }
        val candidate = "pending=${config.pendingDiscardPositions.sorted()};online=${config.onlineStatus};" +
            "enable=${config.isEnable};error=${config.errorStatus};inspection=${config.inspectionMode}"
        if (candidate != orphanDeliveryBusyRecoveryCandidate) {
            orphanDeliveryBusyRecoveryCandidate = candidate
            lastOrphanDeliveryBusyRecoveryAttemptAtMs = 0L
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastOrphanDeliveryBusyRecoveryAttemptAtMs < 60_000L) return false
        lastOrphanDeliveryBusyRecoveryAttemptAtMs = nowMs
        return true
    }

    private fun resetOrphanDeliveryBusyRecoveryAttempt() {
        orphanDeliveryBusyRecoveryCandidate = null
        lastOrphanDeliveryBusyRecoveryAttemptAtMs = 0L
    }

    private fun settleMaturedZonesBeforeDispatch(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Boolean {
        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val settlements = MaturedZoneKeepWarmSettler.settleMaturedZones(
            pans = kaoPanList,
            config = config,
            nowMs = System.currentTimeMillis()
        ) { area ->
            KaoChangOperate.stopHeating(area)
            KaoChangOperate.keepWarm(area)
        }
        if (settlements.isEmpty()) {
            return false
        }

        logI(
            LOG_ALGORITHM,
            "【加热控制】机械调度前整区成熟收口：${
                settlements.joinToString("；") { settlement ->
                    "${settlement.area}区转保温，positions=${settlement.positions}"
                }
            }"
        )
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                changedPanPositions = settlements.flatMap { it.positions },
                reason = "机械门禁前整区成熟转保温",
                source = "matured_zone_keep_warm"
            )
        }.onFailure { error ->
            logW(LOG_ALGORITHM, "整区成熟已完成，物理增量上报异常，等待快照补偿：${error.message}")
        }
        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "机械门禁前整区成熟转保温"
        )
        return true
    }

    private fun settleMaturedLowFrontBeforeRecoveryReturn(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        closeout: LowInitializationMaturityCloseoutPolicy.Closeout
    ): Boolean {
        val applyKeepWarm: (Int) -> Boolean = { area ->
            val result = KaoChangOperate.keepWarmForMaturityCloseout(area)
            if (!result.isSuccess) {
                logW(
                    LOG_ALGORITHM,
                    "一区成熟收口保温写入失败，保持批次证据等待重试：" +
                        "status=${result.status}，message=${result.message ?: "无详情"}"
                )
            }
            result.isSuccess
        }
        val settlement = if (closeout.preparedBeforeEvaluation) {
            MaturedZoneKeepWarmSettler.settlePreparedZoneOne(
                pans = kaoPanList,
                config = config,
                nowMs = System.currentTimeMillis(),
                preparedPositions = closeout.maturedPositions,
                applyKeepWarm = applyKeepWarm
            )
        } else {
            MaturedZoneKeepWarmSettler.settleMaturedZoneOne(
                pans = kaoPanList,
                config = config,
                nowMs = System.currentTimeMillis(),
                applyKeepWarm = applyKeepWarm
            )
        } ?: return false
        logI(
            LOG_ALGORITHM,
            "【加热控制】恢复门禁阻塞期间一区成熟收口：positions=${settlement.positions}"
        )
        return true
    }

    private fun normalizeSupplementSourceKeepWarmBeforeEarlyReturns(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Boolean {
        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val normalizedPositions = mutableListOf<Int>()
        val lowSourcePans = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val highSourcePans = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)

        if (
            config.modeType == 1 &&
            config.transitionMode == 0 &&
            config.highConcurrencyResidualDrain != 1
        ) {
            normalizedPositions += LowHeatSupplementResumePlanner.normalizeLegacySellableKeepWarmSources(lowSourcePans)
        } else if (config.modeType == 2 && config.transitionMode == 1) {
            normalizedPositions += normalizeOldLowHeatWaitingSourcesForReturn(lowSourcePans)
        }
        normalizedPositions += HighHeatSupplementResumePlanner.normalizeLegacySellableKeepWarmSources(highSourcePans)

        val distinctPositions = normalizedPositions.distinct().sorted()
        if (distinctPositions.isEmpty()) {
            return false
        }
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        logW(
            LOG_ALGORITHM,
            "【烤盘状态自愈】调度早退前纠偏补热源区历史原地保温可售状态为等待回搬不可售：" +
                "positions=$distinctPositions，mode=${config.modeType}，transition=${config.transitionMode}，residual=${config.highConcurrencyResidualDrain}"
        )
        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "调度早退前补热源区历史可售状态纠偏"
        )
        return true
    }

    private fun settleOldLowHeatSourcesBeforePendingDiscard(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Boolean {
        if (!(config.modeType == 2 && config.transitionMode == 1 && config.pendingDiscardPositions.isNotEmpty())) {
            return false
        }
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return false
        }
        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val nowMs = System.currentTimeMillis()
        val oldLowHeatPans = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val normalizedPositions = normalizeOldLowHeatWaitingSourcesForReturn(oldLowHeatPans)
        val settledPositions = mutableListOf<Int>()

        listOf(13..15, 16..18).forEach { range ->
            val occupiedPans = oldLowHeatPans.filter { it.positionSn in range && it.isHasSausage }
            if (occupiedPans.isEmpty()) {
                return@forEach
            }
            val allReady = occupiedPans.all { pan ->
                when {
                    pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM &&
                        pan.startTime <= 0L &&
                        pan.holdingTime > 0L -> true

                    pan.startTime > 0L &&
                        pan.bakingTime > 0L &&
                        FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs) >= pan.bakingTime -> true

                    else -> false
                }
            }
            if (!allReady) {
                return@forEach
            }
            occupiedPans
                .filter { it.startTime > 0L }
                .forEach { pan ->
                    pan.startTime = 0L
                    pan.holdingTime = nowMs
                    pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                    settledPositions += pan.positionSn
                }
        }

        val changedPositions = (normalizedPositions + settledPositions).distinct().sorted()
        if (changedPositions.isEmpty()) {
            return false
        }
        if (settledPositions.isNotEmpty()) {
            KaoChangOperate.stopHeating(2)
            KaoChangOperate.keepWarm(2)
        }
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                changedPanPositions = changedPositions,
                reason = "低转高待丢弃优先前旧低补热源收口",
                source = "low_to_high_old_heat_settled"
            )
        }.onFailure { error ->
            logW(LOG_ALGORITHM, "旧低补热源收口物理增量上报异常，等待快照补偿：${error.message}")
        }
        logW(
            LOG_ALGORITHM,
            "【并发切换】低转高待丢弃优先前收口旧低补热源：13~18 已改为等待回搬保温不可售，" +
                "positions=$changedPositions，pendingDiscard=${config.pendingDiscardPositions}"
        )
        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "低转高待丢弃优先前旧低补热源收口"
        )
        return true
    }

    private fun normalizeOldLowHeatSourcesBeforeAvailabilityProjection(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Boolean {
        if (!(config.modeType == 2 && config.transitionMode == 1)) {
            return false
        }
        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val oldLowHeatPans = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val normalizedPositions = normalizeOldLowHeatWaitingSourcesForReturn(oldLowHeatPans)
        if (normalizedPositions.isEmpty()) {
            return false
        }
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                changedPanPositions = normalizedPositions,
                reason = "低转高可售投影前旧低补热源纠偏",
                source = "low_to_high_old_heat_normalized"
            )
        }.onFailure { error ->
            logW(LOG_ALGORITHM, "旧低补热源纠偏物理增量上报异常，等待快照补偿：${error.message}")
        }
        logW(
            LOG_ALGORITHM,
            "【并发切换】低转高可售投影前纠偏旧低补热源：13~18 历史原地保温可售已改为等待回搬不可售，positions=$normalizedPositions"
        )
        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "低转高可售投影前旧低补热源纠偏"
        )
        return true
    }

    private fun refreshActiveBakingTimeBeforeMaturityCheck(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Int {
        val targetBakingTimeMs = config.bakingTime.coerceAtLeast(0).toLong() * 60L * 1000L
        val changedPositions = kaoPanList
            .filter { pan ->
                pan.isHasSausage && pan.status == 1 && pan.startTime > 0L &&
                    pan.bakingTime != targetBakingTimeMs
            }
            .map { it.positionSn }
        val correctedCount = KaoPanHelper.refreshActiveBakingTime(kaoPanList, config.bakingTime)
        if (correctedCount <= 0) {
            return 0
        }
        KaoPanHelper.saveKaoPanList(kaoPanList)
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                changedPanPositions = changedPositions,
                reason = "烤制中烤盘按当前配置回刷烤制时长",
                source = "active_baking_time_refreshed"
            )
        }.onFailure { error ->
            logW(LOG_ALGORITHM, "烤制时长回刷物理增量上报异常，等待快照补偿：${error.message}")
        }
        logW(
            LOG_ALGORITHM,
            "【烤制时间同步】调度轮询发现烤制中烤盘仍使用旧烤制时长，已按当前配置回刷：" +
                "bakingTime=${config.bakingTime}分钟，correctedCount=$correctedCount"
        )
        return correctedCount
    }

    /**
     * 用户下单后，从可售烤盘中选择一根烤肠出餐。
     * 当前策略：
     * 1. 低并发稳态且无残留时仅从 1~9 号前区选取；
     * 2. 高并发、自然过渡态或内部高并发残留收口中从 1~21 号可售区选取；
     * 3. 按 holdingTime 最小值优先，即“最早进入保温的先卖”。
     */
    suspend fun purchase(
        tasteCode: String,
        productId: Int,
        orderContext: ActionGroupOrderContext? = null,
        deliveryIndex: Int? = null,
        deliveryTotal: Int? = null
    ): KaoChangOperate.TakeSausageResult {
        repeat(2) { attempt ->
            val kaoPanList = KaoPanHelper.getKaoPanList()
            val config = AppConfig.getAppConfig()
            val sellEnd = TrafficModeHelper.purchaseSellEnd(
                config.modeType,
                config.transitionMode,
                config.highConcurrencyResidualDrain
            )
            val targetKaoPan = TrafficModeHelper.selectSellCandidate(
                TrafficModeHelper.getTrafficModePositions(1, sellEnd, kaoPanList),
                tasteCode,
                productId
            )
            if (targetKaoPan == null) {
                logW(
                    LOG_DEVICE,
                    "收到出货订单失败：当前没有找到可售烤肠 [口味=$tasteCode, 商品=$productId]，" +
                        "scan=${attempt + 1}/2"
                )
                return@repeat
            }
            logD(
                LOG_ALGORITHM,
                "收到出货订单：口味=$tasteCode，商品=$productId，已调度托盘 ID=${targetKaoPan.id}，" +
                    "烤盘位=${targetKaoPan.positionSn}，holdingTime=${targetKaoPan.holdingTime}，scan=${attempt + 1}/2"
            )
            when (val result = KaoChangOperate.takeSausageResult(targetKaoPan, orderContext, deliveryIndex, deliveryTotal)) {
                KaoChangOperate.TakeSausageResult.NO_SELLABLE_STOCK -> {
                    if (attempt == 0) return@repeat
                }
                else -> return result
            }
        }
        return KaoChangOperate.TakeSausageResult.NO_SELLABLE_STOCK
    }

    /**
     * 维护页批量重置烤盘。
     * 所有有肠烤盘都会按“管理员指定丢弃”处理。
     */
    suspend fun resetKaoPan(progressCallback: ((current: Int, total: Int, kaoPan: KaoPan) -> Unit)? = null) {
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val targetPans = kaoPanList.filter { it.isHasSausage }
        val targetPositions = targetPans.map { it.positionSn }
        targetPositions.filter { it in 1..21 }.forEach { positionSn ->
            LowToHighDemandGate.recordNonDemandEmpty(positionSn, "manual_clear")
        }
        LogUtils.i("【维护操作】管理员触发批量丢弃重置烤盘，待处理烤盘=$targetPositions")
        targetPans.forEachIndexed { index, kaoPan ->
            progressCallback?.invoke(index + 1, targetPans.size, kaoPan)
            KaoChangOperate.discardSausage(
                kaoPan,
                source = "管理员指定",
                detail = "维护页批量重置烤盘",
                automatic = false
            )
        }
        FaultHeatPauseManager.clearFaultHeatPauseSilently("维护页批量重置烤盘完成")
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
        KaoChangOperate.invalidateFaultHeatStopConfirmation("Modbus监控启动")
        algorithmScope.launch {
            while (isRunning) {
                val now = System.currentTimeMillis()
                val connected = VMModbusHelper.connectStatus()
                val config = AppConfig.getAppConfig()

                if (!connected) {
                    KaoChangOperate.invalidateFaultHeatStopConfirmation("Modbus断开")
                }

                if (shouldFreezeByInspection(config)) {
                    modbusDisconnectSinceMs = null
                    modbusReconnectSinceMs = null
                    lastReconnectAttemptMs = 0L
                    loggedManualRecoveryRequiredOnDisconnect = false
                    loggedManualRecoveryRequiredAfterReconnect = false
                    if (!connected && !inspectionDisconnectSuppressedLogged) {
                        inspectionDisconnectSuppressedLogged = true
                        logD(LOG_COMM, "检修模式下检测到 Modbus 断连，已抑制自动切维护和刷屏错误日志")
                    } else if (connected && inspectionDisconnectSuppressedLogged) {
                        inspectionDisconnectSuppressedLogged = false
                        logD(LOG_COMM, "检修模式下检测到 Modbus 已恢复连接，继续保持日志抑制")
                    }
                    delay(MODBUS_MONITOR_INTERVAL_MS)
                    continue
                }
                inspectionDisconnectSuppressedLogged = false

                if (connected) {
                    modbusDisconnectSinceMs = null
                    lastReconnectAttemptMs = 0L
                    loggedManualRecoveryRequiredOnDisconnect = false
                    rememberRecoverableOnlineStatus(config.onlineStatus)
                    if (!actionGroupRecoveredAfterModbusConnect) {
                        actionGroupRecoveredAfterModbusConnect = true
                        val recovered = KaoChangOperate.recoverActionGroupStateAfterModbusReady("Modbus 已连接/重连后的动作组恢复检查")
                        if (!recovered) {
                            modbusReportedUnavailable = false
                            modbusReconnectSinceMs = null
                            logW(LOG_COMM, "300/400 动作组恢复检查未通过，已保持或切入维护态，等待人工确认")
                            delay(MODBUS_MONITOR_INTERVAL_MS)
                            continue
                        }
                    }

                    if (modbusReportedUnavailable) {
                        if (!isModbusAutoRecoverableFault(config.errorCode)) {
                            modbusReportedUnavailable = false
                            modbusReconnectSinceMs = null
                            if (config.onlineStatus == 3 && config.errorStatus != 0 && !loggedManualRecoveryRequiredAfterReconnect) {
                                loggedManualRecoveryRequiredAfterReconnect = true
                                logW(
                                    LOG_COMM,
                                    "检测到modbus已恢复，但当前故障来源不允许自动恢复：" +
                                        "errorCode=${config.errorCode ?: "null"}，errorStatus=${config.errorStatus}，需人工恢复"
                                )
                            }
                        } else if (modbusReconnectSinceMs == null) {
                            modbusReconnectSinceMs = now
                            logI(LOG_COMM, "【Modbus】检测到下位机重新连接，开始稳定性观察 | port=${AppConfig.getAppConfig().modbusAddress}")
                        } else if (now - modbusReconnectSinceMs!! >= MODBUS_RECONNECT_STABLE_DELAY_MS) {
                            var restoredOnlineStatus = config.onlineStatus
                            var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
                            val temperatureRestoreSucceeded = KaoChangOperate.withFaultHeatTransitionLock {
                                if (
                                    config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL ||
                                    !isModbusAutoRecoverableFault(config.errorCode) ||
                                    !VMModbusHelper.connectStatus()
                                ) {
                                    false
                                } else {
                                    val restoreOutcome = KaoChangOperate.restoreHeatingStateFromCurrentPansLocked("modbus恢复稳定")
                                    pauseEndResult = restoreOutcome.pauseEndResult
                                    val restored = restoreOutcome.succeeded
                                    if (restored) {
                                        restoredOnlineStatus = resolveRecoveredOnlineStatus(config)
                                        config.errorStatus = 0
                                        config.errorCode = null
                                        config.errorCategory = null
                                        config.errorReason = null
                                        config.onlineStatus = restoredOnlineStatus
                                        AppConfig.saveAppConfig(config)
                                    }
                                    restored
                                }
                            }
                            KaoChangOperate.syncRecoveredFaultHeatPhysicalState(pauseEndResult, "modbus恢复稳定")
                            if (!temperatureRestoreSucceeded) {
                                logW(
                                    LOG_COMM,
                                    "【Modbus】连接已稳定但温控恢复未全部成功，保持维护和计时暂停，等待下轮重试 | " +
                                        "port=${AppConfig.getAppConfig().modbusAddress}"
                                )
                            } else {
                                HomeUiRefreshBridge.requestRefresh("modbus_auto_recovery:restored")
                                rememberRecoverableOnlineStatus(restoredOnlineStatus)
                                modbusReportedUnavailable = false
                                modbusReconnectSinceMs = null
                                loggedManualRecoveryRequiredAfterReconnect = false
                                logI(LOG_COMM, "【Modbus】下位机稳定连接且温控恢复确认，自动恢复正常状态 | port=${AppConfig.getAppConfig().modbusAddress}, onlineStatus=$restoredOnlineStatus, errorStatus=0")
                                uploadDeviceStatus(
                                    "modbus串口恢复稳定且温控恢复确认，自动恢复原因=断连类错误，已恢复设备可用状态：onlineStatus=$restoredOnlineStatus"
                                )
                            }
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
                    actionGroupRecoveredAfterModbusConnect = false
                    KaoChangOperate.invalidateActionGroupRecoveryVerification("Modbus断开")
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
                            config.errorCode = FaultCode.MODBUS_DISCONNECT.name
                            config.errorCategory = FaultCode.MODBUS_DISCONNECT.category.display
                            config.errorReason = "modbus 串口断开超过 ${MODBUS_DISCONNECT_REPORT_DELAY_MS}ms"
                            stopHeatingAndDispatchForFault("modbus串口断开超过${MODBUS_DISCONNECT_REPORT_DELAY_MS}ms")
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
    }
    /**
     * 判断当前时间是否在营业时间内
     * @param currentTimeStr 当前时间字符串，格式为"HH:mm:ss"，兼容旧格式"HH:mm"
     * @param businessTime 营业时间配置
     * @return 是否在营业时间内
     */
    private fun isWithinBusinessHours(currentTimeStr: String, businessTime: BusinessTime?): Boolean {
        return try {
            BusinessTimeWindowHelper.isWithinBusinessHours(currentTimeStr, businessTime)
        } catch (e: Exception) {
            logW(LOG_ALGORITHM, "营业时间解析失败：$e")
            false
        }
    }
 

    /**
     * 获取当前时间
     * @return 当前时间字符串，格式为"HH:mm:ss"
     */
    private fun getCurrentTime(): String {
        return BusinessTimeWindowHelper.currentTimeString()
    }

    fun enableRoastAlgorithm(
        reason: String = "维护页手动开启算法",
        syncToServer: Boolean = true
    ): Boolean {
        var enabledConfig: AppConfigBean? = null
        var wasRunning = false
        var pauseEndResult: FaultHeatPauseManager.EndPauseResult? = null
        val enabled = KaoChangOperate.withFaultHeatTransitionLock {
            val config = AppConfig.getAppConfig()
            val enabledLocked = if (!canEnableAlgorithm(config)) {
                logW(
                    LOG_ALGORITHM,
                    "拒绝开启烤肠算法：reason=$reason，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                        "inspectionMode（检修模式）=${config.inspectionMode}，" +
                        "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}，" +
                        "isEnable（烤肠算法开关）=${isEnableText(config.isEnable)}"
                )
                false
            } else if (!VMModbusHelper.connectStatus()) {
                logW(
                    LOG_ALGORITHM,
                    "拒绝开启烤肠算法：下位机未连接，暂不恢复温度：" +
                        "reason=$reason，onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}"
                )
                false
            } else if (KaoChangOperate.isActionGroupRecoveryBlocked() &&
                !KaoChangOperate.isParallelSupplementPausedForRuntimeRecovery()
            ) {
                logW(LOG_ALGORITHM, "拒绝开启烤肠算法：动作恢复尚未收口，reason=$reason")
                false
            } else {
                KaoChangOperate.actionDebug()
                val restoreOutcome = KaoChangOperate.restoreHeatingStateFromCurrentPansLocked("算法开启后恢复温度：$reason")
                pauseEndResult = restoreOutcome.pauseEndResult
                val restored = restoreOutcome.succeeded
                val latestConfig = AppConfig.getAppConfig()
                if (!restored) {
                    logW(
                        LOG_ALGORITHM,
                        "拒绝开启烤肠算法：温控恢复未全部成功，保持计时暂停：reason=$reason"
                    )
                    false
                } else if (!canEnableAlgorithm(latestConfig) || !VMModbusHelper.connectStatus()) {
                    KaoChangOperate.stopHeatingForFault("算法开启期间设备状态变化，撤销温控恢复：$reason")
                    logW(
                        LOG_ALGORITHM,
                        "拒绝开启烤肠算法：温控恢复后设备条件已变化，重新停热并保持暂停：reason=$reason"
                    )
                    false
                } else {
                    wasRunning = isRunning
                    isEnabled = true
                    latestConfig.isEnable = 1
                    AppConfig.saveAppConfig(latestConfig)
                    enabledConfig = latestConfig
                    true
                }
            }
            if (!enabledLocked) {
                isEnabled = false
                val disabledConfig = AppConfig.getAppConfig()
                if (disabledConfig.isEnable != 0) {
                    disabledConfig.isEnable = 0
                    AppConfig.saveAppConfig(disabledConfig)
                }
            }
            enabledLocked
        }
        KaoChangOperate.syncRecoveredFaultHeatPhysicalState(pauseEndResult, "算法开启后恢复温度：$reason")
        if (!enabled) {
            if (syncToServer) {
                SendServerHelper.publishServiceUpdateStatus()
            }
            return false
        }
        val config = requireNotNull(enabledConfig)
        // scanAndDiscard 会先做可售阻断；若机械暂忙，下一轮 pollKaoPanStatus 顶层 pending 优先扫描继续兜底。
        algorithmScope.launch {
            OverKeepWarmDiscardScanner.scanAndDiscard(1..33, "fault_recovery")
        }
        if (!wasRunning) {
            logW(
                LOG_ALGORITHM,
                "手动开启烤肠算法时检测到轮询未运行，立即补启动算法服务：" +
                    "reason=$reason，onlineStatus=${onlineStatusText(config.onlineStatus)}，" +
                    "modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}"
            )
            runServer()
        }
        logD(
            LOG_ALGORITHM,
            "算法开关已开启：reason=$reason，" +
                "isRunning（算法轮询是否运行）=$wasRunning->${isRunning}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "modeType（并发模式）=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
                "bakingTime（烤制时长，分钟）=${config.bakingTime}"
        )
        if (syncToServer) {
            SendServerHelper.publishServiceUpdateStatus()
        }
        return true
    }

    fun disableRoastAlgorithm(
        reason: String = "维护页手动关闭算法",
        syncToServer: Boolean = true
    ): Boolean {
        val config = KaoChangOperate.withFaultHeatTransitionLock {
            isEnabled = false
            AppConfig.getAppConfig().also {
                it.isEnable = 0
                AppConfig.saveAppConfig(it)
            }
        }
        logD(
            LOG_ALGORITHM,
            "算法开关已关闭：reason=$reason，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                "modeType（并发模式）=${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(config.transitionMode)}，" +
                "收口语义=停止自动补肠/停止开新批次/停止新的并发切换，但当前烤制/搬盘/出餐/转保温继续自然完成"
        )
        if (syncToServer) {
            SendServerHelper.publishServiceUpdateStatus()
        }
        return true
    }

}
