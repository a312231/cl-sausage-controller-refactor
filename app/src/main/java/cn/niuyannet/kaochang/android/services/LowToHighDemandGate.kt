package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON

/**
 * 低并发转高并发的需求来源门禁。
 *
 * 过保丢弃代表“没人买”，因此这类空位不能作为低转高的需求证据。
 * 非需求空位状态必须耐久保存，重启后不能把人工清盘、供给不足或故障造成的空位
 * 误判为售卖需求。真实观察到盘位重新有肠时才解除对应 blocked。
 */
data class LowToHighDemandState(
    val blockedFrontPositions: MutableSet<Int> = linkedSetOf()
)

enum class LowToHighDemandLoadStatus { EMPTY, PRESENT, CORRUPT }

data class LowToHighDemandLoadResult(
    val status: LowToHighDemandLoadStatus,
    val state: LowToHighDemandState? = null
)

interface LowToHighDemandStateStore {
    fun load(): LowToHighDemandLoadResult
    fun saveDurable(state: LowToHighDemandState): Boolean
    fun clearDurable(): Boolean
}

class SharedPreferenceLowToHighDemandStateStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_LOW_TO_HIGH_DEMAND_GATE_STATE
) : LowToHighDemandStateStore {
    override fun load(): LowToHighDemandLoadResult = decode(
        PreferenceUtils.getStringPreference(file, key, null)
    )

    override fun saveDurable(state: LowToHighDemandState): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(state))

    override fun clearDurable(): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, "")

    companion object {
        const val KEY_LOW_TO_HIGH_DEMAND_GATE_STATE = "sp_low_to_high_demand_gate_state_json"

        fun decode(raw: String?): LowToHighDemandLoadResult {
            if (raw.isNullOrBlank()) {
                return LowToHighDemandLoadResult(LowToHighDemandLoadStatus.EMPTY)
            }
            return try {
                val objectValue = JSON.parseObject(raw)
                val values = objectValue.getJSONArray("blockedFrontPositions")
                    ?.mapNotNull { (it as? Number)?.toInt() }
                    ?.filter { it in 1..21 }
                    ?.toMutableSet()
                    ?: mutableSetOf()
                LowToHighDemandLoadResult(
                    LowToHighDemandLoadStatus.PRESENT,
                    LowToHighDemandState(values)
                )
            } catch (e: Exception) {
                LowToHighDemandLoadResult(LowToHighDemandLoadStatus.CORRUPT)
            }
        }
    }
}

class LowToHighDemandStateRepository(
    private val store: LowToHighDemandStateStore,
    private val infoLogger: (String) -> Unit = { }
) {
    @Synchronized
    fun recordNonDemandEmpty(positionSn: Int, reason: String): Boolean {
        if (positionSn !in 1..21) return false
        val state = loadState()
        val changed = state.blockedFrontPositions.add(positionSn)
        if (changed) {
            check(store.saveDurable(state)) {
                "低转高门禁持久化失败：operation=recordNonDemandEmpty，position=$positionSn，reason=$reason"
            }
            infoLogger("记录非需求空位：position=$positionSn，reason=$reason")
        }
        return changed
    }

    @Synchronized
    fun clearPosition(positionSn: Int, reason: String): Boolean {
        if (positionSn !in 1..21) return false
        val state = loadState()
        val changed = state.blockedFrontPositions.remove(positionSn)
        if (changed) {
            check(store.saveDurable(state)) {
                "低转高门禁持久化失败：operation=clearPosition，position=$positionSn，reason=$reason"
            }
            infoLogger("清除非需求空位：position=$positionSn，reason=$reason")
        }
        return changed
    }

    @Synchronized
    fun blockedPositions(): Set<Int> = loadState().blockedFrontPositions.toSet()

    @Synchronized
    fun clearAll(reason: String): Boolean {
        val hadBlocked = loadState().blockedFrontPositions.isNotEmpty()
        check(store.clearDurable()) {
            "低转高门禁持久化失败：operation=clearAll，reason=$reason"
        }
        if (hadBlocked) infoLogger("清空非需求空位：reason=$reason")
        return hadBlocked
    }

    private fun loadState(): LowToHighDemandState {
        val result = store.load()
        return when (result.status) {
            LowToHighDemandLoadStatus.EMPTY -> LowToHighDemandState()
            LowToHighDemandLoadStatus.PRESENT -> result.state ?: LowToHighDemandState()
            LowToHighDemandLoadStatus.CORRUPT -> LowToHighDemandState((1..21).toMutableSet())
        }
    }
}

object LowToHighDemandGate {
    private const val TAG = "LowToHighDemandGate"
    private const val LOW_MODE = 1
    private const val HIGH_MODE = 2
    private const val NO_TRANSITION = 0
    private const val LOW_TO_HIGH_TRANSITION = 1
    private const val HIGH_TO_LOW_TRANSITION = 2
    private val trackedFrontPositions = 1..21
    private val lowHeatPositions = 13..18
    private var stateStore: LowToHighDemandStateStore = SharedPreferenceLowToHighDemandStateStore()
    private var stateRepository = LowToHighDemandStateRepository(stateStore)
    private val defaultInfoLogger: (String) -> Unit = { message -> LogUtils.i(TAG, message) }
    private var infoLogger: (String) -> Unit = defaultInfoLogger

    data class ExpiredDiscardCloseoutResult(
        val changedModeState: Boolean,
        val blockedFrontPositionsChanged: Boolean,
        val blockedFrontPositions: Set<Int>
    )

    @Synchronized
    fun recordExpiredDiscard(positionSn: Int): Boolean {
        return recordNonDemandEmpty(positionSn, "expired_discard")
    }

    @Synchronized
    fun recordNonDemandEmpty(positionSn: Int, reason: String): Boolean {
        if (positionSn !in trackedFrontPositions) return false
        val changed = stateRepository.recordNonDemandEmpty(positionSn, reason)
        if (changed) {
            logInfo("【低转高门禁】记录blocked位置：position=$positionSn，reason=$reason")
        }
        return changed
    }

    @Synchronized
    fun clearPosition(positionSn: Int, reason: String): Boolean {
        val removed = stateRepository.clearPosition(positionSn, reason)
        if (removed) {
            logInfo("【低转高门禁】清除blocked位置：position=$positionSn，reason=$reason，remaining=${stateRepository.blockedPositions()}")
        }
        return removed
    }

    @Synchronized
    fun clearPositions(positionSnList: Iterable<Int>, reason: String): Boolean {
        val removedPositions = positionSnList.filter { stateRepository.clearPosition(it, reason) }
        if (removedPositions.isNotEmpty()) {
            logInfo("【低转高门禁】批量清除blocked位置：removed=$removedPositions，reason=$reason，remaining=${stateRepository.blockedPositions()}")
        }
        return removedPositions.isNotEmpty()
    }

    @Synchronized
    fun clearAll(reason: String): Boolean {
        val removed = stateRepository.clearAll(reason)
        if (removed) logInfo("【低转高门禁】清空blocked位置：reason=$reason")
        return removed
    }

    @Synchronized
    internal fun replaceInfoLoggerForTest(logger: (String) -> Unit) {
        infoLogger = logger
    }

    @Synchronized
    internal fun resetInfoLoggerForTest() {
        infoLogger = defaultInfoLogger
    }

    @Synchronized
    internal fun replaceStateStoreForTest(store: LowToHighDemandStateStore) {
        stateStore = store
        stateRepository = LowToHighDemandStateRepository(store)
    }

    @Synchronized
    internal fun resetStateStoreForTest() {
        stateStore = SharedPreferenceLowToHighDemandStateStore()
        stateRepository = LowToHighDemandStateRepository(stateStore)
    }

    private fun logInfo(message: String) {
        infoLogger(message)
    }

    @Synchronized
    fun blockedFrontPositions(): Set<Int> = stateRepository.blockedPositions()

    @Synchronized
    fun effectiveEmptyFrontPositions(frontPans: List<KaoPan>): Set<Int> {
        pruneObservedFilledPositions(frontPans)
        val blockedPositions = stateRepository.blockedPositions()
        val currentEmptyPositions = frontPans
            .filter { it.positionSn in trackedFrontPositions && !it.isHasSausage }
            .map { it.positionSn }
            .toSet()
        return currentEmptyPositions - blockedPositions
    }

    @Synchronized
    fun blockedEmptyFrontPositions(frontPans: List<KaoPan>): Set<Int> {
        pruneObservedFilledPositions(frontPans)
        val blockedPositions = stateRepository.blockedPositions()
        return frontPans
            .filter { it.positionSn in trackedFrontPositions && !it.isHasSausage }
            .map { it.positionSn }
            .filter { it in blockedPositions }
            .toSet()
    }

    @Synchronized
    fun canTriggerLowToHigh(
        frontPans: List<KaoPan>,
        lowHeatPans: List<KaoPan>
    ): Boolean {
        return effectiveEmptyFrontPositions(frontPans).size > 7 &&
            hasActiveLowHeatBatch(lowHeatPans)
    }

    @Synchronized
    fun markExpiredDiscardAndFoldToLow(
        config: AppConfigBean,
        positionSn: Int
    ): ExpiredDiscardCloseoutResult {
        val blockedChanged = recordExpiredDiscard(positionSn)
        val shouldDrainHighConcurrency =
            config.modeType == HIGH_MODE ||
                config.transitionMode == LOW_TO_HIGH_TRANSITION ||
                config.transitionMode == HIGH_TO_LOW_TRANSITION
        val targetModeType = if (shouldDrainHighConcurrency) HIGH_MODE else LOW_MODE
        val targetTransitionMode = if (shouldDrainHighConcurrency) HIGH_TO_LOW_TRANSITION else NO_TRANSITION
        val changedModeState =
            config.modeType != targetModeType || config.transitionMode != targetTransitionMode
        config.modeType = targetModeType
        config.transitionMode = targetTransitionMode
        return ExpiredDiscardCloseoutResult(
            changedModeState = changedModeState,
            blockedFrontPositionsChanged = blockedChanged,
            blockedFrontPositions = stateRepository.blockedPositions()
        )
    }

    private fun pruneObservedFilledPositions(frontPans: List<KaoPan>) {
        val observedPositions = frontPans
            .filter { it.positionSn in trackedFrontPositions }
            .map { it.positionSn }
            .toSet()
        if (observedPositions.isEmpty()) {
            return
        }
        val blockedPositions = stateRepository.blockedPositions()
        val currentEmptyPositions = frontPans
            .filter { it.positionSn in observedPositions && !it.isHasSausage }
            .map { it.positionSn }
            .toSet()
        observedPositions
            .filter { it in blockedPositions && it !in currentEmptyPositions }
            .forEach { stateRepository.clearPosition(it, "观察到盘位已有烤肠") }
    }

    private fun hasActiveLowHeatBatch(lowHeatPans: List<KaoPan>): Boolean {
        val byPosition = lowHeatPans
            .filter { it.positionSn in lowHeatPositions }
            .associateBy { it.positionSn }
        return lowHeatPositions.all { position ->
            val pan = byPosition[position] ?: return@all false
            pan.isHasSausage &&
                (
                    pan.startTime > 0L && pan.status != KaoPan.STATUS_SELLABLE_KEEP_WARM ||
                        pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                    )
        }
    }
}
