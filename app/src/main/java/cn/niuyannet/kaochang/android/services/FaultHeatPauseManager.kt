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
    const val ALERT_RECOVERY_JOURNAL_INVALID = "RECOVERY_JOURNAL_INVALID"

    private const val LOG_TAG = "故障停热"
    private const val RECOVERY_JOURNAL_SCHEMA_VERSION = 2
    private const val RECOVERY_JOURNAL_SEMANTICS = "ABSOLUTE_START_TIME"
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
        val alertCode: String?,
        val targetStartTimes: Map<Int, Long> = emptyMap(),
        val failClosed: Boolean = false
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
        var schemaVersion: Int? = null
        var semantics: String? = null
        var journalId: String? = null
        var pauseStartedAtMs: Long = 0L
        var preparedAtMs: Long = 0L
        var pauseDurationMs: Long = 0L
        var affectedPositions: MutableList<Int>? = mutableListOf()
        var targetStartTimes: MutableMap<Int, Long>? = linkedMapOf()
        var pendingDiscardPositions: MutableList<Int>? = mutableListOf()
        var coveredUntilMs: Long? = null
        var alertCode: String? = null
        var alertReason: String? = null
    }

    data class RecoveryJournalReplayResult(
        val applied: Boolean,
        val legacy: Boolean = false,
        val journalId: String? = null,
        val coveredUntilMs: Long? = null,
        val error: String? = null
    )

    enum class RecoveryJournalPrepareStatus {
        READY,
        NO_PAUSE,
        BLOCKED
    }

    data class RecoveryJournalPrepareResult(
        val status: RecoveryJournalPrepareStatus,
        val error: String? = null
    )

    private data class DecodedRecoveryJournal(
        val journal: NormalizedRecoveryJournal? = null,
        val legacy: Boolean = false,
        val journalId: String? = null,
        val error: String? = null
    )

    private data class NormalizedRecoveryJournal(
        val legacy: Boolean,
        val journalId: String,
        val pauseStartedAtMs: Long,
        val preparedAtMs: Long,
        val coveredUntilMs: Long,
        val targetStartTimes: Map<Int, Long>,
        val pendingDiscardPositions: List<Int>,
        val alertCode: String?,
        val alertReason: String?
    )

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
                if (!replayPendingRecoveryJournalLocked(config, reason)) {
                    return failClosedResult(ALERT_RECOVERY_JOURNAL_INVALID)
                }
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
            if (result.failClosed) {
                LogUtils.e(
                    "【$LOG_TAG】故障停热恢复计算异常，保持暂停等待人工处理：" +
                        "reason=$reason，alertCode=${result.alertCode}"
                )
                return result
            }
            val journal = buildRecoveryJournal(config, result, nowMs, reason)
            config.faultHeatRecoveryJournalJson = JSON.toJSONString(journal)
            AppConfig.saveAppConfigDurable(config)
            if (!applyRecoveryJournal(pans, config, journal)) {
                LogUtils.e("【$LOG_TAG】新建恢复 journal 校验失败，保持暂停等待人工处理：reason=$reason")
                return failClosedResult(ALERT_RECOVERY_JOURNAL_INVALID)
            }
            KaoPanHelper.saveKaoPanListDurable(pans)
            config.faultHeatPauseActive = 0
            config.faultHeatStopConfirmed = 0
            config.faultHeatPauseStartedAtMs = 0L
            config.faultHeatPauseReason = null
            config.faultHeatPauseSource = null
            config.faultHeatRecoveryJournalJson = null
            if (
                config.pendingDiscardPositions.isEmpty() &&
                config.foodSafetyAlertCode == ALERT_ANOMALY_TOO_LONG
            ) {
                config.foodSafetyAlertCode = null
                config.foodSafetyAlertReason = null
            }
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
    fun prepareRecoveryJournalIfNeeded(reason: String): RecoveryJournalPrepareResult {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            val pans = KaoPanHelper.getKaoPanList()
            val nowMs = System.currentTimeMillis()
            val existingJournal = config.faultHeatRecoveryJournalJson
            if (!existingJournal.isNullOrBlank()) {
                if (config.faultHeatPauseActive != 1 || config.faultHeatPauseStartedAtMs <= 0L) {
                    return RecoveryJournalPrepareResult(
                        status = RecoveryJournalPrepareStatus.BLOCKED,
                        error = "已有恢复journal但故障停热暂停状态非法"
                    )
                }
                return inspectRecoveryJournalForPreparation(existingJournal, pans, nowMs)
            }
            if (config.faultHeatPauseActive != 1) {
                return if (config.faultHeatPauseStartedAtMs == 0L) {
                    RecoveryJournalPrepareResult(RecoveryJournalPrepareStatus.NO_PAUSE)
                } else {
                    RecoveryJournalPrepareResult(
                        status = RecoveryJournalPrepareStatus.BLOCKED,
                        error = "故障停热暂停状态与起点不一致"
                    )
                }
            }
            if (config.faultHeatPauseStartedAtMs <= 0L) {
                return RecoveryJournalPrepareResult(
                    status = RecoveryJournalPrepareStatus.BLOCKED,
                    error = "故障停热暂停起点非法"
                )
            }
            val result = calculatePauseAdjustment(pans, config, nowMs, config.faultHeatPauseStartedAtMs)
            if (result.failClosed) {
                LogUtils.e(
                    "【$LOG_TAG】拒绝准备异常恢复 journal，保持停热暂停：" +
                        "reason=$reason，alertCode=${result.alertCode}"
                )
                return RecoveryJournalPrepareResult(
                    status = RecoveryJournalPrepareStatus.BLOCKED,
                    error = result.alertCode ?: "恢复预判失败"
                )
            }
            config.faultHeatRecoveryJournalJson = JSON.toJSONString(
                buildRecoveryJournal(config, result, nowMs, reason)
            )
            return try {
                AppConfig.saveAppConfigDurable(config)
                RecoveryJournalPrepareResult(RecoveryJournalPrepareStatus.READY)
            } catch (throwable: Throwable) {
                config.faultHeatRecoveryJournalJson = null
                RecoveryJournalPrepareResult(
                    status = RecoveryJournalPrepareStatus.BLOCKED,
                    error = "恢复journal持久化失败:${throwable.message}"
                )
            }
        }
    }

    internal fun shouldBlockRecoveryTemperatureWrite(
        preview: EndPauseResult,
        preparation: RecoveryJournalPrepareResult
    ): Boolean = preview.failClosed || preparation.status == RecoveryJournalPrepareStatus.BLOCKED

    internal fun shouldCompensateFailedRecoveryFinalize(
        result: EndPauseResult,
        preparation: RecoveryJournalPrepareResult
    ): Boolean = result.failClosed ||
        (preparation.status == RecoveryJournalPrepareStatus.READY && !result.active)

    internal fun inspectRecoveryJournalForPreparation(
        raw: String,
        pans: List<KaoPan>,
        nowMs: Long
    ): RecoveryJournalPrepareResult {
        val decoded = decodeRecoveryJournal(raw, pans, nowMs)
        return if (decoded.journal != null) {
            RecoveryJournalPrepareResult(RecoveryJournalPrepareStatus.READY)
        } else {
            RecoveryJournalPrepareResult(
                status = RecoveryJournalPrepareStatus.BLOCKED,
                error = decoded.error ?: "已有恢复journal非法"
            )
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
        val pans = KaoPanHelper.getKaoPanList()
        val result = replayRecoveryJournalState(
            raw = raw,
            pans = pans,
            config = config,
            nowMs = System.currentTimeMillis()
        )
        if (!result.applied) {
            LogUtils.e(
                "【$LOG_TAG】恢复 journal 非法，保持停热暂停等待人工处理：" +
                    "reason=$reason，error=${result.error}"
            )
            return false
        }
        KaoPanHelper.saveKaoPanListDurable(pans)
        config.faultHeatRecoveryJournalJson = null
        AppConfig.saveAppConfigDurable(config)
        LogUtils.w("【$LOG_TAG】已幂等重放未完成恢复 journal：journalId=${result.journalId}，reason=$reason")
        return true
    }

    internal fun buildRecoveryJournal(
        config: AppConfigBean,
        result: EndPauseResult,
        preparedAtMs: Long,
        reason: String
    ): RecoveryJournal = RecoveryJournal().apply {
        schemaVersion = RECOVERY_JOURNAL_SCHEMA_VERSION
        semantics = RECOVERY_JOURNAL_SEMANTICS
        journalId = UUID.randomUUID().toString()
        pauseStartedAtMs = config.faultHeatPauseStartedAtMs
        this.preparedAtMs = preparedAtMs
        coveredUntilMs = preparedAtMs
        pauseDurationMs = result.pauseDurationMs
        affectedPositions = result.targetStartTimes.keys.sorted().toMutableList()
        targetStartTimes = result.targetStartTimes.toMap(LinkedHashMap())
        pendingDiscardPositions =
            (config.pendingDiscardPositions + result.pendingDiscardPositions).distinct().sorted().toMutableList()
        alertCode = result.alertCode ?: if (result.pendingDiscardPositions.isNotEmpty()) ALERT_BAKING_PAUSE_ABORT else null
        alertReason = if (result.pendingDiscardPositions.isNotEmpty()) {
            "故障停热恢复触发食品安全待丢弃：reason=$reason，pauseDurationMs=${result.pauseDurationMs}，positions=${result.pendingDiscardPositions}"
        } else {
            null
        }
    }

    internal fun applyRecoveryJournal(
        pans: List<KaoPan>,
        config: AppConfigBean,
        journal: RecoveryJournal
    ): Boolean {
        val normalized = normalizeRecoveryJournalObject(
            journal = journal,
            legacy = journal.schemaVersion == null,
            pans = pans,
            nowMs = journal.coveredUntilMs ?: journal.preparedAtMs
        ).getOrNull() ?: return false
        applyNormalizedRecoveryJournal(pans, config, normalized)
        return true
    }

    internal fun replayRecoveryJournalState(
        raw: String,
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long
    ): RecoveryJournalReplayResult {
        val decoded = decodeRecoveryJournal(raw, pans, nowMs)
        val normalized = decoded.journal ?: return RecoveryJournalReplayResult(
            applied = false,
            legacy = decoded.legacy,
            journalId = decoded.journalId,
            error = decoded.error
        )
        applyNormalizedRecoveryJournal(pans, config, normalized)
        return RecoveryJournalReplayResult(
            applied = true,
            legacy = normalized.legacy,
            journalId = normalized.journalId,
            coveredUntilMs = normalized.coveredUntilMs
        )
    }

    private fun decodeRecoveryJournal(
        raw: String,
        pans: List<KaoPan>,
        nowMs: Long
    ): DecodedRecoveryJournal {
        val root = try {
            JSON.parseObject(raw)
        } catch (throwable: Throwable) {
            return DecodedRecoveryJournal(error = "JSON损坏:${throwable.message}")
        } ?: return DecodedRecoveryJournal(error = "JSON为空")
        val legacy = !root.containsKey("schemaVersion")
        val requiredFields = if (legacy) {
            listOf(
                "journalId",
                "pauseStartedAtMs",
                "preparedAtMs",
                "pauseDurationMs",
                "targetStartTimes",
                "pendingDiscardPositions"
            )
        } else {
            listOf(
                "schemaVersion",
                "semantics",
                "journalId",
                "pauseStartedAtMs",
                "preparedAtMs",
                "pauseDurationMs",
                "affectedPositions",
                "targetStartTimes",
                "pendingDiscardPositions",
                "coveredUntilMs"
            )
        }
        val missing = requiredFields.filterNot(root::containsKey)
        if (missing.isNotEmpty()) {
            return DecodedRecoveryJournal(legacy = legacy, error = "缺少字段:$missing")
        }
        if (requiredFields.any { root[it] == null }) {
            return DecodedRecoveryJournal(legacy = legacy, error = "必填字段为null")
        }
        val journal = try {
            JSON.parseObject(raw, RecoveryJournal::class.java)
        } catch (throwable: Throwable) {
            return DecodedRecoveryJournal(legacy = legacy, error = "字段解析失败:${throwable.message}")
        }
        val normalizedResult = normalizeRecoveryJournalObject(journal, legacy, pans, nowMs)
        val normalized = normalizedResult.getOrElse { throwable ->
            return DecodedRecoveryJournal(
                legacy = legacy,
                journalId = journal.journalId?.takeIf(String::isNotBlank),
                error = throwable.message ?: "journal字段非法"
            )
        }
        return DecodedRecoveryJournal(
            journal = normalized,
            legacy = normalized.legacy,
            journalId = normalized.journalId
        )
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
        if (clearDiscardPositionStateAndAlertIfFinished(positionSn)) {
            publishDiscardPositionCleared(positionSn)
        }
    }

    internal fun clearDiscardPositionStateAndAlertIfFinished(positionSn: Int): Boolean {
        synchronized(mutex) {
            val config = AppConfig.getAppConfig()
            if (config.pendingDiscardPositions.remove(positionSn)) {
                normalizePendingDiscardPositions(config)
                if (config.pendingDiscardPositions.isEmpty()) {
                    config.foodSafetyAlertCode = null
                    config.foodSafetyAlertReason = null
                }
                AppConfig.saveAppConfigDurable(config)
                LogUtils.i(
                    "【$LOG_TAG】已清除待丢弃烤盘位：positionSn=$positionSn，" +
                        "remaining=${config.pendingDiscardPositions}"
                )
                return true
            }
            return false
        }
    }

    internal fun publishDiscardPositionCleared(positionSn: Int) {
        SendServerHelper.publishServiceUpdateStatus("runtime_state:pending_discard_cleared")
        LogUtils.i("【$LOG_TAG】待丢弃烤盘位清除状态已发布：positionSn=$positionSn")
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
        result.targetStartTimes.forEach { (position, targetStartTime) ->
            pans.firstOrNull { it.positionSn == position }?.startTime = targetStartTime
        }

        if (result.pendingDiscardPositions.isNotEmpty()) {
            if (emitLogs) {
                pans.filter { it.positionSn in result.pendingDiscardPositions }.forEach { pan ->
                    val alertCode = when {
                        result.alertCode != null -> result.alertCode
                        isHoldingPan(pan) -> ALERT_KEEP_WARM_EXPIRED
                        else -> ALERT_BAKING_PAUSE_ABORT
                    }
                    logPendingDiscardPan(
                        pan = pan,
                        alertCode = alertCode,
                        pauseDurationMs = actualPauseOverlapMs(pan, nowMs, pauseStartedAtMs),
                        reason = reason
                    )
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
        val targetStartTimes = linkedMapOf<Int, Long>()
        val hasAnySausage = TemperatureControlPolicy.hasAnySausageInPhysicalPans(pans)
        val globalAlertCode = when {
            nowMs < pauseStartedAtMs -> ALERT_CLOCK_REGRESSED
            hasAnySausage && pauseDurationMs > normalizedFaultPauseAnomalyMs(config) -> ALERT_ANOMALY_TOO_LONG
            else -> null
        }
        val hasFutureBakingStart = pans.any { isBakingPan(it) && it.startTime > nowMs }
        val failClosed = globalAlertCode != null || hasFutureBakingStart
        val alertCode = globalAlertCode ?: if (hasFutureBakingStart) ALERT_CLOCK_REGRESSED else null
        pans.forEach { pan ->
            when {
                isBakingPan(pan) -> {
                    val overlapMs = actualPauseOverlapMs(pan, nowMs, pauseStartedAtMs)
                    if (failClosed || overlapMs > normalizedFaultBakingPauseAbortMs(config)) {
                        pendingPositions.add(pan.positionSn)
                        if (!failClosed) {
                            targetStartTimes[pan.positionSn] = pan.startTime
                        }
                    } else {
                        shiftedPositions.add(pan.positionSn)
                        targetStartTimes[pan.positionSn] = pan.startTime + overlapMs
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
            alertCode = alertCode,
            targetStartTimes = targetStartTimes.toSortedMap(),
            failClosed = failClosed
        )
    }

    private fun actualPauseOverlapMs(pan: KaoPan, nowMs: Long, pauseStartedAtMs: Long): Long =
        (nowMs - maxOf(pauseStartedAtMs, pan.startTime)).coerceAtLeast(0L)

    private fun normalizeRecoveryJournalObject(
        journal: RecoveryJournal,
        legacy: Boolean,
        pans: List<KaoPan>,
        nowMs: Long
    ): Result<NormalizedRecoveryJournal> = runCatching {
        if (!legacy) {
            require(journal.schemaVersion == RECOVERY_JOURNAL_SCHEMA_VERSION) { "不支持的schemaVersion" }
            require(journal.semantics == RECOVERY_JOURNAL_SEMANTICS) { "不支持的semantics" }
        }
        val journalId = journal.journalId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("journalId为空")
        require(journal.pauseStartedAtMs > 0L) { "pauseStartedAtMs非法" }
        require(journal.preparedAtMs >= journal.pauseStartedAtMs) { "preparedAtMs非法" }
        require(journal.pauseDurationMs >= 0L) { "pauseDurationMs非法" }
        val coveredUntilMs = if (legacy) journal.preparedAtMs else journal.coveredUntilMs
        require(coveredUntilMs != null && coveredUntilMs >= journal.pauseStartedAtMs) { "coveredUntilMs非法" }
        require(coveredUntilMs <= journal.preparedAtMs) { "coveredUntilMs晚于preparedAtMs" }
        require(nowMs >= journal.preparedAtMs && nowMs >= coveredUntilMs) { "journal时间晚于当前时间" }

        val targets = normalizeTargetStartTimes(journal.targetStartTimes)
        val affectedPositions = if (legacy) {
            targets.keys.sorted()
        } else {
            normalizePositionList(journal.affectedPositions, "affectedPositions")
        }
        val pendingDiscardPositions =
            normalizePositionList(journal.pendingDiscardPositions, "pendingDiscardPositions")
        if (!legacy) {
            require(affectedPositions == targets.keys.sorted()) { "affectedPositions与targets不一致" }
        }
        val bakingPositions = pans.filter(::isBakingPan).map { it.positionSn }.sorted()
        if (legacy) {
            val pendingBakingPositions = pendingDiscardPositions.filter { it in bakingPositions }
            require((targets.keys + pendingBakingPositions).distinct().sorted() == bakingPositions) {
                "legacy journal未完整覆盖当前烤制盘"
            }
        } else {
            require(targets.keys.sorted() == bakingPositions) { "targets未完整覆盖当前烤制盘" }
        }
        targets.forEach { (position, targetStartTime) ->
            val pan = pans.firstOrNull { it.positionSn == position }
                ?: throw IllegalArgumentException("目标盘位不存在:$position")
            require(isBakingPan(pan)) { "目标盘位不是烤制盘:$position" }
            require(targetStartTime in pan.startTime..nowMs) { "目标时间不在合法范围:$position" }
            require(targetStartTime <= coveredUntilMs) { "目标时间晚于coveredUntilMs:$position" }
        }

        NormalizedRecoveryJournal(
            legacy = legacy,
            journalId = journalId,
            pauseStartedAtMs = journal.pauseStartedAtMs,
            preparedAtMs = journal.preparedAtMs,
            coveredUntilMs = coveredUntilMs,
            targetStartTimes = targets,
            pendingDiscardPositions = pendingDiscardPositions,
            alertCode = journal.alertCode,
            alertReason = journal.alertReason
        )
    }

    private fun normalizeTargetStartTimes(raw: MutableMap<Int, Long>?): Map<Int, Long> {
        require(raw != null) { "targetStartTimes为null" }
        val normalized = linkedMapOf<Int, Long>()
        (raw as Map<*, *>).forEach { (rawPosition, rawTarget) ->
            val position = (rawPosition as? Number)?.toInt()
                ?: rawPosition?.toString()?.toIntOrNull()
                ?: throw IllegalArgumentException("target盘位非法")
            val target = (rawTarget as? Number)?.toLong()
                ?: throw IllegalArgumentException("target时间非法:$position")
            require(position in 1..33) { "target盘位越界:$position" }
            require(target > 0L) { "target时间非法:$position" }
            require(normalized.put(position, target) == null) { "target盘位重复:$position" }
        }
        return normalized.toSortedMap()
    }

    private fun normalizePositionList(raw: MutableList<Int>?, fieldName: String): List<Int> {
        require(raw != null) { "$fieldName 为null" }
        val positions = (raw as List<*>).map { value ->
            (value as? Number)?.toInt() ?: throw IllegalArgumentException("$fieldName 含非法值")
        }
        require(positions.all { it in 1..33 }) { "$fieldName 含越界盘位" }
        require(positions.size == positions.distinct().size) { "$fieldName 含重复盘位" }
        return positions.sorted()
    }

    private fun applyNormalizedRecoveryJournal(
        pans: List<KaoPan>,
        config: AppConfigBean,
        journal: NormalizedRecoveryJournal
    ) {
        journal.targetStartTimes.forEach { (position, targetStartTime) ->
            pans.first { it.positionSn == position }.startTime = targetStartTime
        }
        addPendingDiscardPositions(config, journal.pendingDiscardPositions)
        if (!journal.alertCode.isNullOrBlank()) {
            config.foodSafetyAlertCode = journal.alertCode
            config.foodSafetyAlertReason = journal.alertReason
        }
        if (config.faultHeatPauseActive == 1) {
            config.faultHeatPauseStartedAtMs =
                maxOf(config.faultHeatPauseStartedAtMs, journal.coveredUntilMs)
        }
    }

    private fun failClosedResult(alertCode: String): EndPauseResult = EndPauseResult(
        active = true,
        pauseDurationMs = 0L,
        shiftedPositions = emptyList(),
        holdingPositions = emptyList(),
        pendingDiscardPositions = emptyList(),
        alertCode = alertCode,
        failClosed = true
    )

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
