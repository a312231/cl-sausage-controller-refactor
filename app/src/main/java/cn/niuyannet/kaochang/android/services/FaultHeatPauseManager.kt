package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import java.util.LinkedHashSet
import java.util.UUID

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
    @Volatile
    private var reportPhysicalPansChanged: (List<Int>, String, String) -> Unit = { changedPositions, reason, source ->
        PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
            changedPanPositions = changedPositions,
            reason = reason,
            source = source
        )
    }

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

    class RecoveryJournal {
        var journalId: String = ""
        var pauseStartedAtMs: Long = 0L
        var preparedAtMs: Long = 0L
        var pauseDurationMs: Long = 0L
        var targetStartTimes: MutableMap<Int, Long> = linkedMapOf()
        var pendingDiscardPositions: MutableList<Int> = mutableListOf()
        var alertCode: String? = null
        var alertReason: String? = null
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
            AppConfig.saveAppConfigDurable(config)
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
            var config = AppConfig.getAppConfig()
            if (!config.faultHeatRecoveryJournalJson.isNullOrBlank()) {
                replayPendingRecoveryJournalLocked(config, reason)
                config = AppConfig.getAppConfig()
            }
            if (config.faultHeatPauseActive != 1 || config.faultHeatPauseStartedAtMs <= 0L) {
                return EndPauseResult.NotActive
            }

            val nowMs = System.currentTimeMillis()
            val pans = KaoPanHelper.getKaoPanList()
            val normalizedPositions = normalizeStaleBakingHoldingTimes(pans, config)
            if (normalizedPositions.isNotEmpty()) {
                LogUtils.w(
                    "【$LOG_TAG】故障停热恢复前自愈烤制盘旧保温时间：" +
                        "positions=$normalizedPositions，reason=$reason"
                )
            }
            val result = calculatePauseAdjustment(
                pans = pans,
                config = config,
                nowMs = nowMs,
                pauseStartedAtMs = config.faultHeatPauseStartedAtMs
            )
            val journal = buildRecoveryJournal(pans, config, result, nowMs, reason)
            config.faultHeatRecoveryJournalJson = JSON.toJSONString(journal)
            AppConfig.saveAppConfigDurable(config)
            applyRecoveryJournal(pans, config, journal)
            KaoPanHelper.saveKaoPanListDurable(pans)
            config.faultHeatPauseActive = 0
            config.faultHeatStopConfirmed = 0
            config.faultHeatPauseStartedAtMs = 0L
            config.faultHeatPauseReason = null
            config.faultHeatPauseSource = null
            config.faultHeatRecoveryJournalJson = null
            AppConfig.saveAppConfigDurable(config)
            LogUtils.w(
                "【$LOG_TAG】结束故障停热暂停：" +
                    "reason=$reason，pauseDurationMs=${result.pauseDurationMs}，" +
                    "顺延烤盘=${result.shiftedPositions}，保温未顺延烤盘=${result.holdingPositions}，" +
                    "待丢弃烤盘=${result.pendingDiscardPositions}，alertCode=${result.alertCode ?: "无"}"
            )
            return result
        }
    }

    /** 在任何恢复正温写入前持久化恢复目标，避免进程崩溃留下物理已升温但计时未补偿的状态。 */
    fun prepareRecoveryJournalIfNeeded(reason: String): Boolean {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.faultHeatPauseActive != 1 || config.faultHeatPauseStartedAtMs <= 0L ||
                !config.faultHeatRecoveryJournalJson.isNullOrBlank()) {
                return false
            }
            val pans = KaoPanHelper.getKaoPanList()
            val nowMs = System.currentTimeMillis()
            val result = calculatePauseAdjustment(pans, config, nowMs, config.faultHeatPauseStartedAtMs)
            config.faultHeatRecoveryJournalJson = JSON.toJSONString(
                buildRecoveryJournal(pans, config, result, nowMs, reason)
            )
            AppConfig.saveAppConfigDurable(config)
            return true
        }
    }

    fun replayPendingRecoveryJournalIfNeeded(reason: String): Boolean {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.faultHeatRecoveryJournalJson.isNullOrBlank()) {
                return false
            }
            return replayPendingRecoveryJournalLocked(config, reason)
        }
    }

    private fun replayPendingRecoveryJournalLocked(config: AppConfigBean, reason: String): Boolean {
        val raw = config.faultHeatRecoveryJournalJson ?: return false
        val journal = try {
            JSON.parseObject(raw, RecoveryJournal::class.java)
        } catch (throwable: Throwable) {
            LogUtils.e("【$LOG_TAG】恢复 journal 损坏，保持停热暂停等待人工处理：reason=$reason，error=${throwable.message}")
            return false
        }
        if (journal.journalId.isBlank() || journal.preparedAtMs <= 0L || journal.pauseStartedAtMs <= 0L) {
            LogUtils.e("【$LOG_TAG】恢复 journal 字段非法，保持停热暂停等待人工处理：reason=$reason")
            return false
        }
        val pans = KaoPanHelper.getKaoPanList()
        applyRecoveryJournal(pans, config, journal)
        KaoPanHelper.saveKaoPanListDurable(pans)
        if (config.faultHeatPauseActive == 1) {
            config.faultHeatPauseStartedAtMs = maxOf(config.faultHeatPauseStartedAtMs, journal.preparedAtMs)
        }
        config.faultHeatRecoveryJournalJson = null
        AppConfig.saveAppConfigDurable(config)
        LogUtils.w("【$LOG_TAG】已幂等重放未完成恢复 journal：journalId=${journal.journalId}，reason=$reason")
        return true
    }

    internal fun buildRecoveryJournal(
        pans: List<KaoPan>,
        config: AppConfigBean,
        result: EndPauseResult,
        preparedAtMs: Long,
        reason: String
    ): RecoveryJournal = RecoveryJournal().apply {
        journalId = UUID.randomUUID().toString()
        pauseStartedAtMs = config.faultHeatPauseStartedAtMs
        this.preparedAtMs = preparedAtMs
        pauseDurationMs = result.pauseDurationMs
        result.shiftedPositions.forEach { position ->
            pans.firstOrNull { it.positionSn == position }?.let { pan ->
                targetStartTimes[position] = pan.startTime + result.pauseDurationMs
            }
        }
        pendingDiscardPositions.addAll((config.pendingDiscardPositions + result.pendingDiscardPositions).distinct().sorted())
        alertCode = result.alertCode ?: if (result.pendingDiscardPositions.isNotEmpty()) ALERT_BAKING_PAUSE_ABORT else null
        alertReason = if (result.pendingDiscardPositions.isNotEmpty()) {
            "故障停热恢复触发食品安全待丢弃：reason=$reason，pauseDurationMs=${result.pauseDurationMs}，positions=${result.pendingDiscardPositions}"
        } else {
            null
        }
    }

    internal fun applyRecoveryJournal(pans: List<KaoPan>, config: AppConfigBean, journal: RecoveryJournal) {
        journal.targetStartTimes.forEach { (position, targetStartTime) ->
            pans.firstOrNull { it.positionSn == position }?.startTime = targetStartTime
        }
        addPendingDiscardPositions(config, journal.pendingDiscardPositions)
        if (!journal.alertCode.isNullOrBlank()) {
            config.foodSafetyAlertCode = journal.alertCode
            config.foodSafetyAlertReason = journal.alertReason
        }
    }

    fun previewFaultHeatPauseEnd(reason: String): EndPauseResult {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.faultHeatPauseActive != 1 || config.faultHeatPauseStartedAtMs <= 0L) {
                return EndPauseResult.NotActive
            }
            val pans = KaoPanHelper.getKaoPanList()
            val normalizedPositions = normalizeStaleBakingHoldingTimes(pans, config)
            if (normalizedPositions.isNotEmpty()) {
                KaoPanHelper.saveKaoPanList(pans)
                AppConfig.saveAppConfig(config)
                LogUtils.w(
                    "【$LOG_TAG】故障停热恢复预判前自愈烤制盘旧保温时间：" +
                        "positions=$normalizedPositions，reason=$reason"
                )
            }
            return calculatePauseAdjustment(
                pans = pans,
                config = config,
                nowMs = System.currentTimeMillis(),
                pauseStartedAtMs = config.faultHeatPauseStartedAtMs
            )
        }
    }

    fun endFaultHeatPauseAndSyncIfNeeded(reason: String): EndPauseResult {
        val result = endFaultHeatPauseAndAdjustIfNeeded(reason)
        syncRecoveredPhysicalState(result, reason)
        return result
    }

    internal fun syncRecoveredPhysicalState(result: EndPauseResult, reason: String) {
        if (!result.active) {
            return
        }
        val changedPositions = (result.shiftedPositions + result.pendingDiscardPositions)
            .distinct()
            .sorted()
        if (changedPositions.isEmpty()) {
            return
        }
        val syncReason = "fault_heat_pause_recovered:$reason"
        reportPhysicalPansChanged.invoke(
            changedPositions,
            syncReason,
            "fault_heat_pause_recovered"
        )
    }

    fun shouldSkipBakingCompletion(): Boolean {
        synchronized(mutex) {
            return shouldSkipBakingCompletion(AppConfig.getAppConfig())
        }
    }

    internal fun shouldSkipBakingCompletion(config: AppConfigBean): Boolean {
        return config.faultHeatPauseActive == 1 && config.faultHeatPauseStartedAtMs > 0L
    }

    internal fun isStaleBakingHoldingTime(pan: KaoPan): Boolean {
        return pan.isHasSausage &&
            pan.startTime > 0L &&
            pan.holdingTime > 0L &&
            pan.startTime > pan.holdingTime
    }

    internal fun normalizeStaleBakingHoldingTimes(
        pans: List<KaoPan>,
        config: AppConfigBean? = null
    ): List<Int> {
        synchronized(mutex) {
            val normalizedPositions = mutableListOf<Int>()
            pans.forEach { pan ->
                if (isStaleBakingHoldingTime(pan)) {
                    pan.holdingTime = 0L
                    pan.status = 1
                    normalizedPositions += pan.positionSn
                }
            }
            if (normalizedPositions.isNotEmpty() && config != null) {
                config.pendingDiscardPositions.removeAll(normalizedPositions.toSet())
                if (config.pendingDiscardPositions.isEmpty()) {
                    config.foodSafetyAlertCode = null
                    config.foodSafetyAlertReason = null
                }
            }
            return normalizedPositions.sorted()
        }
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
            config.faultHeatStopConfirmed = 0
            config.faultHeatPauseStartedAtMs = 0L
            config.faultHeatPauseReason = null
            config.faultHeatPauseSource = null
            config.faultHeatRecoveryJournalJson = null
            config.pendingDiscardPositions.clear()
            config.foodSafetyAlertCode = null
            config.foodSafetyAlertReason = null
            AppConfig.saveAppConfigDurable(config)
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

    internal fun withSyncDependenciesForTest(
        reportPhysicalPansChanged: (List<Int>, String, String) -> Unit,
        block: () -> Unit
    ) {
        val previousReporter = this.reportPhysicalPansChanged
        this.reportPhysicalPansChanged = reportPhysicalPansChanged
        try {
            block()
        } finally {
            this.reportPhysicalPansChanged = previousReporter
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
        val result = calculatePauseAdjustment(pans, config, nowMs, pauseStartedAtMs)
        val shiftedPositions = result.shiftedPositions.toSet()
        pans.filter { it.positionSn in shiftedPositions }.forEach { pan ->
            pan.startTime += result.pauseDurationMs
        }

        if (result.pendingDiscardPositions.isNotEmpty()) {
            if (emitLogs) {
                pans.filter { it.positionSn in result.pendingDiscardPositions }.forEach { pan ->
                    val alertCode = when {
                        result.alertCode != null -> result.alertCode
                        isHoldingPan(pan) -> ALERT_KEEP_WARM_EXPIRED
                        else -> ALERT_BAKING_PAUSE_ABORT
                    }
                    logPendingDiscardPan(pan, alertCode, result.pauseDurationMs, reason)
                }
            }
            addPendingDiscardPositions(config, result.pendingDiscardPositions)
            val effectiveAlertCode = result.alertCode ?: ALERT_BAKING_PAUSE_ABORT
            config.foodSafetyAlertCode = effectiveAlertCode
            config.foodSafetyAlertReason = "故障停热恢复触发食品安全待丢弃：reason=$reason，pauseDurationMs=${result.pauseDurationMs}，positions=${result.pendingDiscardPositions}"
        }

        return result
    }

    private fun calculatePauseAdjustment(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        pauseStartedAtMs: Long
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
                    } else {
                        shiftedPositions.add(pan.positionSn)
                    }
                }
                isHoldingPan(pan) -> {
                    holdingPositions.add(pan.positionSn)
                    if (pan.closeTime > 0L && nowMs - pan.holdingTime > pan.closeTime) {
                        pendingPositions.add(pan.positionSn)
                    }
                }
            }
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
