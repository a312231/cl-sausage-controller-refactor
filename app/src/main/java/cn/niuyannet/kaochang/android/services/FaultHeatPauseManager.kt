package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.LinkedHashSet

/**
 * 故障停热计时管理器。
 *
 * 负责记录故障停热开始时间，并在恢复非 0 温度前补偿烤制计时。
 * 保温时间按 wall-clock（自然时间）继续计算，过保则进入待丢弃。
 */
object FaultHeatPauseManager {
    const val ALERT_CLOCK_REGRESSED = "CLOCK_REGRESSED"
    const val ALERT_ANOMALY_TOO_LONG = "ANOMALY_TOO_LONG"
    const val ALERT_BAKING_PAUSE_ABORT = "BAKING_PAUSE_ABORT"
    const val ALERT_KEEP_WARM_EXPIRED = "KEEP_WARM_EXPIRED"

    private const val LOG_TAG = "故障停热"
    private val mutex = Any()

    data class EndPauseResult(
        val active: Boolean,
        val pauseDurationMs: Long,
        val shiftedPositions: List<Int>,
        val holdingPositions: List<Int>,
        val pendingDiscardPositions: List<Int>,
        val alertCode: String?
    ) {
        companion object {
            val NotActive = EndPauseResult(
                active = false,
                pauseDurationMs = 0L,
                shiftedPositions = emptyList(),
                holdingPositions = emptyList(),
                pendingDiscardPositions = emptyList(),
                alertCode = null
            )
        }
    }

    fun beginFaultHeatPauseIfNeeded(reason: String, source: String) {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.faultHeatPauseActive == 1 && config.faultHeatPauseStartedAtMs > 0L) {
                LogUtils.i(
                    "【$LOG_TAG】故障停热暂停已存在，不重复覆盖开始时间：" +
                        "source=$source，reason=$reason，startedAt=${config.faultHeatPauseStartedAtMs}"
                )
                return
            }
            config.faultHeatPauseActive = 1
            config.faultHeatPauseStartedAtMs = System.currentTimeMillis()
            config.faultHeatPauseReason = reason
            config.faultHeatPauseSource = source
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【$LOG_TAG】开始故障停热暂停：" +
                    "source=$source，reason=$reason，startedAt=${config.faultHeatPauseStartedAtMs}，" +
                    "onlineStatus（设备营业状态）=${config.onlineStatus}，" +
                    "errorStatus（设备故障状态）=${config.errorStatus}，" +
                    "isEnable（烤肠算法开关）=${config.isEnable}，" +
                    "moveStatus（机械动作状态）=${config.moveStatus}"
            )
        }
    }

    fun endFaultHeatPauseAndAdjustIfNeeded(reason: String): EndPauseResult {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.faultHeatPauseActive != 1 || config.faultHeatPauseStartedAtMs <= 0L) {
                return EndPauseResult.NotActive
            }

            val nowMs = System.currentTimeMillis()
            val pans = KaoPanHelper.getKaoPanList()
            val result = adjustPansForPause(
                pans = pans,
                config = config,
                nowMs = nowMs,
                pauseStartedAtMs = config.faultHeatPauseStartedAtMs,
                reason = reason
            )
            KaoPanHelper.saveKaoPanList(pans)
            config.faultHeatPauseActive = 0
            config.faultHeatPauseStartedAtMs = 0L
            config.faultHeatPauseReason = null
            config.faultHeatPauseSource = null
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【$LOG_TAG】结束故障停热暂停：" +
                    "reason=$reason，pauseDurationMs=${result.pauseDurationMs}，" +
                    "顺延烤盘=${result.shiftedPositions}，保温未顺延烤盘=${result.holdingPositions}，" +
                    "待丢弃烤盘=${result.pendingDiscardPositions}，alertCode=${result.alertCode ?: "无"}"
            )
            return result
        }
    }

    fun shouldSkipBakingCompletion(): Boolean {
        synchronized(mutex) {
            return shouldSkipBakingCompletion(AppConfig.getAppConfig())
        }
    }

    internal fun shouldSkipBakingCompletion(config: AppConfigBean): Boolean {
        return config.faultHeatPauseActive == 1 && config.faultHeatPauseStartedAtMs > 0L
    }

    fun effectiveBakingElapsedMs(
        pan: KaoPan,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        synchronized(mutex) {
            return effectiveBakingElapsedMs(pan, AppConfig.getAppConfig(), nowMs)
        }
    }

    internal fun effectiveBakingElapsedMs(
        pan: KaoPan,
        config: AppConfigBean,
        nowMs: Long
    ): Long {
        val effectiveNowMs = if (shouldSkipBakingCompletion(config)) {
            config.faultHeatPauseStartedAtMs
        } else {
            nowMs
        }
        return (effectiveNowMs - pan.startTime).coerceAtLeast(0L)
    }

    fun clearFaultHeatPauseSilently(reason: String) {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            config.faultHeatPauseActive = 0
            config.faultHeatPauseStartedAtMs = 0L
            config.faultHeatPauseReason = null
            config.faultHeatPauseSource = null
            config.pendingDiscardPositions.clear()
            config.foodSafetyAlertCode = null
            config.foodSafetyAlertReason = null
            AppConfig.saveAppConfig(config)
            LogUtils.i("【$LOG_TAG】已静默清除故障停热与待丢弃状态：reason=$reason")
        }
    }

    fun clearDiscardPositionAndAlertIfFinished(positionSn: Int) {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.pendingDiscardPositions.remove(positionSn)) {
                normalizePendingDiscardPositions(config)
                if (config.pendingDiscardPositions.isEmpty()) {
                    config.foodSafetyAlertCode = null
                    config.foodSafetyAlertReason = null
                }
                AppConfig.saveAppConfig(config)
                SendServerHelper.publishServiceUpdateStatus("runtime_state:pending_discard_cleared")
                LogUtils.i(
                    "【$LOG_TAG】已清除待丢弃烤盘位：positionSn=$positionSn，" +
                        "remaining=${config.pendingDiscardPositions}"
                )
            }
        }
    }

    fun markPendingDiscardPositions(
        positions: Collection<Int>,
        alertCode: String,
        alertReason: String
    ): Boolean {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            val before = config.pendingDiscardPositions.toList()
            addPendingDiscardPositions(config, positions)
            if (config.pendingDiscardPositions == before) {
                return false
            }
            config.foodSafetyAlertCode = alertCode
            config.foodSafetyAlertReason = alertReason
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                "【$LOG_TAG】写入待丢弃烤盘列表：alertCode=$alertCode，positions=${positions.sorted()}，" +
                    "pendingDiscardPositions=${config.pendingDiscardPositions}，reason=$alertReason"
            )
            return true
        }
    }

    internal fun adjustPansForPause(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        pauseStartedAtMs: Long,
        reason: String,
        emitLogs: Boolean = true
    ): EndPauseResult {
        val pauseDurationMs = nowMs - pauseStartedAtMs
        val shiftedPositions = mutableListOf<Int>()
        val holdingPositions = mutableListOf<Int>()
        val pendingPositions = mutableListOf<Int>()
        val alertCode = when {
            nowMs < pauseStartedAtMs -> ALERT_CLOCK_REGRESSED
            pauseDurationMs > normalizedFaultPauseAnomalyMs(config) -> ALERT_ANOMALY_TOO_LONG
            else -> null
        }

        pans.forEach { pan ->
            when {
                isBakingPan(pan) -> {
                    if (alertCode != null || pauseDurationMs > normalizedFaultBakingPauseAbortMs(config)) {
                        pendingPositions.add(pan.positionSn)
                        if (emitLogs) {
                            logPendingDiscardPan(
                                pan = pan,
                                alertCode = alertCode ?: ALERT_BAKING_PAUSE_ABORT,
                                pauseDurationMs = pauseDurationMs,
                                reason = reason
                            )
                        }
                    } else {
                        pan.startTime += pauseDurationMs
                        shiftedPositions.add(pan.positionSn)
                    }
                }
                isHoldingPan(pan) -> {
                    holdingPositions.add(pan.positionSn)
                    if (pan.closeTime > 0L && nowMs - pan.holdingTime > pan.closeTime) {
                        pendingPositions.add(pan.positionSn)
                        if (emitLogs) {
                            logPendingDiscardPan(
                                pan = pan,
                                alertCode = alertCode ?: ALERT_KEEP_WARM_EXPIRED,
                                pauseDurationMs = pauseDurationMs,
                                reason = reason
                            )
                        }
                    }
                }
            }
        }

        if (pendingPositions.isNotEmpty()) {
            addPendingDiscardPositions(config, pendingPositions)
            val effectiveAlertCode = alertCode ?: ALERT_BAKING_PAUSE_ABORT
            config.foodSafetyAlertCode = effectiveAlertCode
            config.foodSafetyAlertReason = "故障停热恢复触发食品安全待丢弃：reason=$reason，pauseDurationMs=$pauseDurationMs，positions=${pendingPositions.sorted()}"
        }

        return EndPauseResult(
            active = true,
            pauseDurationMs = pauseDurationMs,
            shiftedPositions = shiftedPositions.sorted(),
            holdingPositions = holdingPositions.sorted(),
            pendingDiscardPositions = pendingPositions.distinct().sorted(),
            alertCode = alertCode
        )
    }

    fun addPendingDiscardPositions(config: AppConfigBean, positions: Collection<Int>) {
        val merged = LinkedHashSet<Int>()
        merged.addAll(config.pendingDiscardPositions.filter { it in 1..33 })
        merged.addAll(positions.filter { it in 1..33 })
        config.pendingDiscardPositions.clear()
        config.pendingDiscardPositions.addAll(merged.sorted())
    }

    fun normalizePendingDiscardPositions(config: AppConfigBean) {
        val normalized = config.pendingDiscardPositions
            .filter { it in 1..33 }
            .distinct()
            .sorted()
        config.pendingDiscardPositions.clear()
        config.pendingDiscardPositions.addAll(normalized)
    }

    fun normalizedFaultBakingPauseAbortMs(config: AppConfigBean): Long =
        config.faultBakingPauseAbortMs.takeIf { it > 0L } ?: 2L * 60L * 60L * 1000L

    fun normalizedFaultPauseAnomalyMs(config: AppConfigBean): Long =
        config.faultPauseAnomalyMs.takeIf { it > 0L } ?: 4L * 60L * 60L * 1000L

    fun normalizedHttpFallbackMinIntervalMs(config: AppConfigBean): Long =
        config.httpFallbackMinIntervalMs.takeIf { it > 0L } ?: 30_000L

    private fun isBakingPan(pan: KaoPan): Boolean =
        pan.isHasSausage && pan.startTime > 0L && pan.holdingTime <= 0L

    private fun isHoldingPan(pan: KaoPan): Boolean =
        pan.isHasSausage && pan.holdingTime > 0L

    private fun logPendingDiscardPan(
        pan: KaoPan,
        alertCode: String,
        pauseDurationMs: Long,
        reason: String
    ) {
        LogUtils.w(
            "【$LOG_TAG】写入待丢弃烤盘：" +
                "positionSn=${pan.positionSn}，alertCode=$alertCode，reason=$reason，" +
                "startTime=${pan.startTime}，holdingTime=${pan.holdingTime}，" +
                "closeTime=${pan.closeTime}，pauseDurationMs=$pauseDurationMs"
        )
    }
}
