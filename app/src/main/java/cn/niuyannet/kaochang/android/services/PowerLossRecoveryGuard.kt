package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils

object PowerLossRecoveryGuard {
    private const val LOG_TAG = "断电恢复"
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private var recoveredInProcess = false

    data class RecoveryResult(
        val adjusted: Boolean,
        val normalizedPositions: List<Int>,
        val pauseResult: FaultHeatPauseManager.EndPauseResult
    )

    fun recoverBeforeRuntimeStart(source: String): RecoveryResult {
        if (recoveredInProcess) {
            LogUtils.d("【$LOG_TAG】冷启动停热恢复闸门已执行，跳过重复执行：source=$source")
            return RecoveryResult(
                adjusted = false,
                normalizedPositions = emptyList(),
                pauseResult = FaultHeatPauseManager.EndPauseResult.NotActive
            )
        }
        recoveredInProcess = true

        FaultHeatPauseManager.replayPendingRecoveryJournalIfNeeded("冷启动:$source")
        val config = AppConfig.getAppConfig()
        val pans = KaoPanHelper.getKaoPanList()
        val result = recoverPansAfterColdStart(
            pans = pans,
            config = config,
            nowMs = System.currentTimeMillis(),
            reason = "App冷启动检测到运行心跳中断：source=$source"
        )
        KaoPanHelper.saveKaoPanListDurable(pans)
        AppConfig.saveAppConfigDurable(config)
        logRecoveryResult(source, result, config)
        if (result.pauseResult.pendingDiscardPositions.isNotEmpty() || result.pauseResult.alertCode != null) {
            publishRecoveryState()
        }
        return result
    }

    fun recoverPansAfterColdStart(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        reason: String,
        emitLogs: Boolean = true
    ): RecoveryResult {
        val lastAliveAtMs = config.lastRuntimeAliveAtMs
        if (lastAliveAtMs <= 0L) {
            config.lastRuntimeAliveAtMs = nowMs
            return RecoveryResult(
                adjusted = false,
                normalizedPositions = emptyList(),
                pauseResult = FaultHeatPauseManager.EndPauseResult.NotActive
            )
        }

        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            config.lastRuntimeAliveAtMs = nowMs
            return RecoveryResult(
                adjusted = false,
                normalizedPositions = emptyList(),
                pauseResult = FaultHeatPauseManager.EndPauseResult.NotActive
            )
        }

        val normalizedPositions = FaultHeatPauseManager.normalizeStaleBakingHoldingTimes(pans, config)
        val pauseResult = FaultHeatPauseManager.adjustPansForPause(
            pans = pans,
            config = config,
            nowMs = nowMs,
            pauseStartedAtMs = lastAliveAtMs,
            reason = reason,
            emitLogs = emitLogs
        )
        config.lastRuntimeAliveAtMs = nowMs
        return RecoveryResult(
            adjusted = true,
            normalizedPositions = normalizedPositions,
            pauseResult = pauseResult
        )
    }

    fun markAliveIfDue(config: AppConfigBean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastAliveAtMs = config.lastRuntimeAliveAtMs
        if (lastAliveAtMs > 0L && nowMs - lastAliveAtMs < HEARTBEAT_INTERVAL_MS) {
            return false
        }
        config.lastRuntimeAliveAtMs = nowMs
        return true
    }

    fun markAliveIfDueAndPersist(nowMs: Long = System.currentTimeMillis()) {
        val config = AppConfig.getAppConfig()
        if (markAliveIfDue(config, nowMs)) {
            AppConfig.saveAppConfig(config)
        }
    }

    private fun logRecoveryResult(source: String, result: RecoveryResult, config: AppConfigBean) {
        val pauseResult = result.pauseResult
        if (!result.adjusted) {
            if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
                LogUtils.i(
                    "【$LOG_TAG】检测到持久化故障停热暂停，冷启动不重复顺延烤制计时：" +
                        "source=$source，faultHeatPauseStartedAtMs=${config.faultHeatPauseStartedAtMs}，" +
                        "lastRuntimeAliveAtMs=${config.lastRuntimeAliveAtMs}"
                )
                return
            }
            LogUtils.i(
                "【$LOG_TAG】未发现历史运行心跳，初始化本机心跳：" +
                    "source=$source，lastRuntimeAliveAtMs=${config.lastRuntimeAliveAtMs}"
            )
            return
        }
        LogUtils.w(
            "【$LOG_TAG】冷启动停热缺口已按故障停热策略处理：" +
                "source=$source，pauseDurationMs=${pauseResult.pauseDurationMs}，" +
                "顺延烤盘=${pauseResult.shiftedPositions}，" +
                "保温未顺延烤盘=${pauseResult.holdingPositions}，" +
                "待丢弃烤盘=${pauseResult.pendingDiscardPositions}，" +
                "恢复前旧保温时间自愈烤盘=${result.normalizedPositions}，" +
                "alertCode=${pauseResult.alertCode ?: "无"}"
        )
    }

    private fun publishRecoveryState() {
        val runtimeOk = SendServerHelper.publishServiceUpdateStatus("runtime_state:power_loss_recovery")
        val availableOk = SendServerHelper.publishAvailableState("power_loss_recovery")
        if (!runtimeOk) {
            LogUtils.w("【$LOG_TAG】断电恢复运行态 MQTT 发送队列拒绝，回退 HTTP 补偿")
            NetApi.updateDeviceInfo()
        }
        if (!availableOk) {
            LogUtils.w("【$LOG_TAG】断电恢复首页可售态 MQTT 发送队列拒绝")
        }
    }
}
