package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

enum class SupplementBatchType {
    LOW_INITIALIZATION_1_9,
    LOW_HEAT_13_15,
    LOW_HEAT_16_18,
    HIGH_INITIALIZATION_1_21,
    HIGH_HEAT_25_33,
    UNKNOWN
}

data class SupplementBatchIntentTarget(
    var positionSn: Int = 0,
    var tasteCode: String? = null,
    var returnPositionSn: Int? = null,
    var completed: Boolean = false,
    var skippedBySupplyDegrade: Boolean = false,
    var skipReason: String? = null,
    var cancelledByFaultTimeout: Boolean = false,
    var cancelReason: String? = null,
    var originBatchId: String? = null,
    var originBatchType: SupplementBatchType? = null
)

data class SupplementBatchIntent(
    var batchId: String = "",
    var type: SupplementBatchType = SupplementBatchType.UNKNOWN,
    var targets: MutableList<SupplementBatchIntentTarget> = mutableListOf(),
    var failedActionName: String? = null,
    var failedTargetPanNo: Int? = null,
    var failureMessage: String? = null,
    var failureAtMs: Long = 0L,
    var requiresManualConfirm: Boolean = false,
    var createdAtMs: Long = 0L,
    var updatedAtMs: Long = 0L
) {
    fun remainingTargets(): List<SupplementBatchIntentTarget> =
        targets.filter { !it.completed && !it.skippedBySupplyDegrade && !it.cancelledByFaultTimeout }

    fun isComplete(): Boolean =
        targets.isNotEmpty() && targets.all { it.completed || it.skippedBySupplyDegrade || it.cancelledByFaultTimeout }
}

interface SupplementBatchIntentStore {
    fun load(): SupplementBatchIntent?
    fun save(intent: SupplementBatchIntent)
    fun clear()

    fun saveDurable(intent: SupplementBatchIntent): Boolean {
        save(intent)
        return true
    }

    fun clearDurable(): Boolean {
        clear()
        return true
    }
}

class SharedPreferenceSupplementBatchIntentStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_SUPPLEMENT_BATCH_INTENT
) : SupplementBatchIntentStore {

    override fun load(): SupplementBatchIntent? {
        val raw = PreferenceUtils.getStringPreference(file, key, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw, SupplementBatchIntent::class.java)
        } catch (e: Exception) {
            LogUtils.w("【补肠批次恢复】读取本地补肠批次意图失败，已按无批次处理：raw=$raw", e)
            null
        }
    }

    override fun save(intent: SupplementBatchIntent) {
        PreferenceUtils.saveStringPreference(file, key, JSON.toJSONString(intent))
    }

    override fun clear() {
        PreferenceUtils.saveStringPreference(file, key, "")
    }

    override fun saveDurable(intent: SupplementBatchIntent): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(intent))

    override fun clearDurable(): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, "")

    companion object {
        private const val KEY_SUPPLEMENT_BATCH_INTENT = "sp_supplement_batch_intent_json"
    }
}

class SupplementBatchIntentRepository(
    private val store: SupplementBatchIntentStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val infoLogger: (String) -> Unit = { LogUtils.i(it) },
    private val warnLogger: (String) -> Unit = { LogUtils.w(it) }
) {
    fun loadActive(): SupplementBatchIntent? {
        val intent = store.load() ?: return null
        if (migrateLegacyTargetOrigins(intent)) {
            saveDurableOrThrow(intent)
        }
        if (intent.targets.isEmpty() || shouldClearCompletedIntentOnLoad(intent)) {
            clearDurableOrThrow()
            return null
        }
        return intent
    }

    fun begin(
        targets: List<KaoChangOperate.SupplementTarget>,
        typeOverride: SupplementBatchType? = null
    ): SupplementBatchIntent? {
        val active = loadActive()
        val retainedReturnBindings = active?.retainedCompletedHeatReturnTargets().orEmpty()
        if (active != null && active.remainingTargets().isNotEmpty()) {
            warnLogger(
                "【补肠批次恢复】已有未完成补肠批次，拒绝覆盖新批次：batchId=${active.batchId}，" +
                    "type=${typeText(active.type)}，remaining=${active.remainingTargets().map { it.positionSn }}"
            )
            return null
        }

        val now = clock()
        val normalizedPositions = targets.map { it.kaoPan.positionSn }
            .filter { it in 1..33 }
            .distinct()
            .sorted()
        val normalizedType = typeOverride ?: inferType(normalizedPositions)
        val normalizedBatchId = "supplement:$now:${normalizedPositions.joinToString("-") }"
        val normalizedTargets = targets
            .filter { it.kaoPan.positionSn in 1..33 }
            .map {
                SupplementBatchIntentTarget(
                    positionSn = it.kaoPan.positionSn,
                    tasteCode = it.tasteCode?.takeIf { code -> code.isNotBlank() }
                        ?: it.kaoPan.taste?.tasteCode?.takeIf { code -> code.isNotBlank() },
                    returnPositionSn = it.returnPositionSn?.takeIf { position -> position in 1..33 },
                    completed = false,
                    originBatchId = normalizedBatchId,
                    originBatchType = normalizedType
                )
            }
            .distinctBy { it.positionSn }
            .sortedBy { it.positionSn }
            .toMutableList()
        if (normalizedTargets.isEmpty()) {
            return null
        }
        val duplicateRetainedSources = retainedReturnBindings
            .map { it.positionSn }
            .intersect(normalizedTargets.map { it.positionSn }.toSet())
        if (duplicateRetainedSources.isNotEmpty()) {
            warnLogger(
                "【补肠批次恢复】已有同源盘待回搬绑定，拒绝启动会覆盖绑定的新批次：" +
                    "sources=$duplicateRetainedSources，newTargets=${normalizedTargets.map { it.positionSn }}"
            )
            return null
        }

        val intent = SupplementBatchIntent(
            batchId = normalizedBatchId,
            type = normalizedType,
            targets = (retainedReturnBindings + normalizedTargets).toMutableList(),
            createdAtMs = now,
            updatedAtMs = now
        )
        if (retainedReturnBindings.isNotEmpty()) {
            infoLogger(
                "【补肠批次恢复】启动新批次时保留已完成补热回搬绑定：" +
                    "retainedSources=${retainedReturnBindings.map { it.positionSn }}，" +
                    "retainedReturns=${retainedReturnBindings.map { it.returnPositionSn }}，" +
                    "newType=${typeText(intent.type)}，newTargets=${normalizedTargets.map { it.positionSn }}"
            )
        }
        saveDurableOrThrow(intent)
        infoLogger(
            "【补肠批次恢复】已记录补肠批次意图：batchId=${intent.batchId}，" +
                "type=${typeText(intent.type)}，targets=${intent.targets.map { it.positionSn }}，" +
                "tastes=${intent.targets.map { it.tasteCode ?: "未知" }}"
        )
        return intent
    }

    fun markCompleted(batchId: String?, targetPanNo: Int) {
        if (batchId.isNullOrBlank()) {
            return
        }
        val intent = store.load() ?: return
        if (intent.batchId != batchId) {
            return
        }
        val target = intent.targets.firstOrNull { it.positionSn == targetPanNo } ?: return
        if (target.completed) {
            return
        }
        target.completed = true
        intent.updatedAtMs = clock()
        intent.failedActionName = null
        intent.failedTargetPanNo = null
        intent.failureMessage = null
        intent.requiresManualConfirm = false
        if (intent.isComplete()) {
            if (shouldRetainCompletedHeatIntentForReturn(intent)) {
                saveDurableOrThrow(intent)
                infoLogger(
                    "【补肠批次恢复】补热批次已全部上盘，保留回搬绑定直到源盘回搬完成：batchId=$batchId，" +
                        "returnPositions=${intent.targets.map { it.returnPositionSn }}"
                )
            } else {
                clearDurableOrThrow()
                infoLogger("【补肠批次恢复】补肠批次已全部完成，清除批次意图：batchId=$batchId")
            }
        } else {
            saveDurableOrThrow(intent)
            infoLogger(
                "【补肠批次恢复】补肠批次目标已完成：batchId=$batchId，targetPan=$targetPanNo，" +
                    "remaining=${intent.remainingTargets().map { it.positionSn }}"
            )
        }
    }

    fun markSkippedBySupplyDegrade(batchId: String?, targetPanNo: Int, reason: String) {
        if (batchId.isNullOrBlank()) {
            return
        }
        val intent = store.load() ?: return
        if (intent.batchId != batchId) {
            return
        }
        val target = intent.targets.firstOrNull { it.positionSn == targetPanNo } ?: return
        if (target.completed || target.skippedBySupplyDegrade) {
            return
        }
        target.skippedBySupplyDegrade = true
        target.skipReason = reason
        intent.updatedAtMs = clock()
        intent.failedActionName = null
        intent.failedTargetPanNo = null
        intent.failureMessage = null
        intent.requiresManualConfirm = false
        if (intent.isComplete()) {
            if (shouldRetainCompletedHeatIntentForReturn(intent)) {
                saveDurableOrThrow(intent)
                infoLogger(
                    "【补肠批次恢复】补热批次已无待补目标，保留已上盘烤肠回搬绑定：batchId=$batchId，" +
                        "returnPositions=${intent.targets.map { it.returnPositionSn }}"
                )
            } else {
                clearDurableOrThrow()
                infoLogger("【补肠批次恢复】补肠批次已完成或供给降级跳过，清除批次意图：batchId=$batchId")
            }
        } else {
            saveDurableOrThrow(intent)
            warnLogger(
                "【补肠批次恢复】补肠批次目标因供给降级跳过：batchId=$batchId，targetPan=$targetPanNo，" +
                    "reason=$reason，remaining=${intent.remainingTargets().map { it.positionSn }}"
            )
        }
    }

    fun markFailure(
        batchId: String?,
        targetPanNo: Int?,
        actionName: String,
        failureMessage: String,
        requiresManualConfirm: Boolean
    ) {
        if (batchId.isNullOrBlank()) {
            return
        }
        val intent = store.load() ?: return
        if (intent.batchId != batchId) {
            return
        }
        intent.failedActionName = actionName
        intent.failedTargetPanNo = targetPanNo
        intent.failureMessage = failureMessage
        if (requiresManualConfirm || intent.failureAtMs <= 0L) {
            intent.failureAtMs = clock()
        }
        intent.requiresManualConfirm = requiresManualConfirm
        intent.updatedAtMs = clock()
        saveDurableOrThrow(intent)
        warnLogger(
            "【补肠批次恢复】补肠批次失败，已保留待续补意图：batchId=$batchId，" +
                "type=${typeText(intent.type)}，targetPan=$targetPanNo，action=$actionName，" +
                "requiresManualConfirm=$requiresManualConfirm，reason=$failureMessage，" +
                "remaining=${intent.remainingTargets().map { it.positionSn }}"
        )
    }

    fun confirmManualSafety(reason: String): SupplementBatchIntent? {
        val intent = loadActive() ?: return null
        if (!intent.requiresManualConfirm) {
            return intent
        }
        val faultElapsedMs = if (intent.failureAtMs > 0L) {
            clock() - intent.failureAtMs
        } else {
            0L
        }
        val cancelledTargets = if (isHeatSupplementBatch(intent.type) &&
            faultElapsedMs > HEAT_FAULT_RESUME_WINDOW_MS
        ) {
            cancelRemainingTargetsByFaultTimeout(intent)
        } else {
            emptyList()
        }
        intent.requiresManualConfirm = false
        intent.updatedAtMs = clock()
        if (intent.isComplete()) {
            if (shouldRetainCompletedHeatIntentForReturn(intent)) {
                saveDurableOrThrow(intent)
            } else {
                clearDurableOrThrow()
            }
        } else {
            saveDurableOrThrow(intent)
        }
        if (cancelledTargets.isEmpty()) {
            warnLogger(
                "【补肠批次恢复】人工已确认半路烤肠/机械位置安全，允许续补未完成批次：reason=$reason，" +
                    "batchId=${intent.batchId}，targets=${intent.targets.map { it.positionSn }}，" +
                    "remaining=${intent.remainingTargets().map { it.positionSn }}，failureAtMs=${intent.failureAtMs}，" +
                    "faultElapsedMs=$faultElapsedMs"
            )
        } else {
            warnLogger(
                "【补肠批次恢复】人工确认时故障已超过10分钟，取消未完成补热目标并停止续补：" +
                    "reason=$reason，batchId=${intent.batchId}，cancelled=$cancelledTargets，" +
                    "remaining=${intent.remainingTargets().map { it.positionSn }}，failureAtMs=${intent.failureAtMs}，" +
                    "faultElapsedMs=$faultElapsedMs"
            )
        }
        return intent
    }

    fun shouldDelayHeatReturnForBatchCompleteness(
        types: Set<SupplementBatchType>,
        onFaultTimeoutCancelled: (SupplementBatchIntent) -> Unit = {}
    ): Boolean {
        val intent = loadActive() ?: return false
        if (intent.type !in types ||
            !isHeatSupplementBatch(intent.type) ||
            intent.remainingTargets().isEmpty() ||
            intent.failureAtMs <= 0L
        ) {
            return false
        }
        val faultElapsedMs = if (intent.failureAtMs > 0L) {
            clock() - intent.failureAtMs
        } else {
            0L
        }
        if (faultElapsedMs <= HEAT_FAULT_RESUME_WINDOW_MS) {
            return true
        }
        val cancelledTargets = cancelRemainingTargetsByFaultTimeout(intent)
        intent.updatedAtMs = clock()
        onFaultTimeoutCancelled(intent)
        if (intent.isComplete()) {
            if (shouldRetainCompletedHeatIntentForReturn(intent)) {
                saveDurableOrThrow(intent)
            } else {
                clearDurableOrThrow()
            }
        } else {
            saveDurableOrThrow(intent)
        }
        warnLogger(
            "【补肠批次恢复】补热批次故障已超过10分钟，停止继续阻塞成熟回搬：" +
                "batchId=${intent.batchId}，type=${typeText(intent.type)}，cancelled=$cancelledTargets，" +
                "remaining=${intent.remainingTargets().map { it.positionSn }}，faultElapsedMs=$faultElapsedMs"
        )
        return false
    }

    fun clearCompletedHeatReturnIfSourceCleared(
        types: Set<SupplementBatchType>,
        sourcePositionsWithSausage: Set<Int>,
        sourcePanStatuses: Map<Int, Int> = emptyMap(),
        reason: String
    ): Boolean {
        val intent = store.load() ?: return false
        val retainedTargetsForTypes = intent.retainedCompletedHeatReturnTargets()
            .filter { target -> target.resolvedOriginBatchType() in types }
        if (retainedTargetsForTypes.isEmpty()) {
            return false
        }
        val retainedTargetsToClear = retainedTargetsForTypes
            .filterNot { target ->
                val lowSourceReusedAsSellable =
                    target.resolvedOriginBatchType() in setOf(
                        SupplementBatchType.LOW_HEAT_13_15,
                        SupplementBatchType.LOW_HEAT_16_18
                    ) && sourcePanStatuses[target.positionSn] == KaoPan.STATUS_SELLABLE_KEEP_WARM
                target.positionSn in sourcePositionsWithSausage && !lowSourceReusedAsSellable
            }
        if (retainedTargetsToClear.isEmpty()) {
            return false
        }
        val retainedSourcePositions = retainedTargetsToClear
            .map { it.positionSn }
            .toSet()
        val remainingTargets = intent.targets
            .filterNot { target -> target.positionSn in retainedSourcePositions }
            .toMutableList()
        if (remainingTargets.isEmpty()) {
            clearDurableOrThrow()
        } else {
            saveDurableOrThrow(
                intent.copy(
                    targets = remainingTargets,
                    updatedAtMs = clock()
                )
            )
        }
        infoLogger(
            "【补肠批次恢复】补热批次源盘已全部回搬，清除回搬绑定：reason=$reason，" +
                "batchId=${intent.batchId}，type=${typeText(intent.type)}，sources=$retainedSourcePositions"
        )
        return true
    }

    fun clear(reason: String) {
        val active = store.load()
        clearDurableOrThrow()
        if (active != null) {
            infoLogger(
                "【补肠批次恢复】已清除补肠批次意图：reason=$reason，batchId=${active.batchId}，" +
                    "type=${typeText(active.type)}"
            )
        }
    }

    fun clearPendingTargetsRetainingCompletedHeatReturnBindings(reason: String): Boolean {
        val active = store.load() ?: return false
        val retainedReturnBindings = active.retainedCompletedHeatReturnTargets()
        if (retainedReturnBindings.isEmpty()) {
            clear(reason)
            return true
        }
        val removedTargets = active.targets
            .filterNot { target -> target in retainedReturnBindings }
            .map { it.positionSn }
        saveDurableOrThrow(
            active.copy(
                targets = retainedReturnBindings.toMutableList(),
                requiresManualConfirm = false,
                failedTargetPanNo = null,
                failedActionName = null,
                failureMessage = null,
                failureAtMs = 0L,
                updatedAtMs = clock()
            )
        )
        infoLogger(
            "【补肠批次恢复】清除未完成补肠目标但保留已完成补热回搬绑定：reason=$reason，" +
                "batchId=${active.batchId}，type=${typeText(active.type)}，" +
                "removedTargets=$removedTargets，retainedSources=${retainedReturnBindings.map { it.positionSn }}"
        )
        return removedTargets.isNotEmpty()
    }

    companion object {
        const val HEAT_FAULT_RESUME_WINDOW_MS: Long = 10 * 60 * 1000L

        fun inferType(positions: List<Int>): SupplementBatchType {
            val sorted = positions.sorted()
            return when {
                sorted.isNotEmpty() && sorted.all { it in 13..15 } -> SupplementBatchType.LOW_HEAT_13_15
                sorted.isNotEmpty() && sorted.all { it in 16..18 } -> SupplementBatchType.LOW_HEAT_16_18
                sorted.isNotEmpty() && sorted.all { it in 25..33 } -> SupplementBatchType.HIGH_HEAT_25_33
                sorted == (1..9).toList() -> SupplementBatchType.LOW_INITIALIZATION_1_9
                sorted.isNotEmpty() && sorted.all { it in 1..21 } -> SupplementBatchType.HIGH_INITIALIZATION_1_21
                else -> SupplementBatchType.UNKNOWN
            }
        }

        fun typeText(type: SupplementBatchType): String = when (type) {
            SupplementBatchType.LOW_INITIALIZATION_1_9 -> "低并发初始化1~9"
            SupplementBatchType.LOW_HEAT_13_15 -> "低并发补热13~15"
            SupplementBatchType.LOW_HEAT_16_18 -> "低并发补热16~18"
            SupplementBatchType.HIGH_INITIALIZATION_1_21 -> "高并发初始化1~21"
            SupplementBatchType.HIGH_HEAT_25_33 -> "高并发补热25~33"
            SupplementBatchType.UNKNOWN -> "未知补肠批次"
        }

        fun isHeatSupplementBatch(type: SupplementBatchType): Boolean =
            type == SupplementBatchType.LOW_HEAT_13_15 ||
                type == SupplementBatchType.LOW_HEAT_16_18 ||
                type == SupplementBatchType.HIGH_HEAT_25_33

        fun isInitializationBatch(type: SupplementBatchType): Boolean =
            type == SupplementBatchType.LOW_INITIALIZATION_1_9 ||
                type == SupplementBatchType.HIGH_INITIALIZATION_1_21

        fun heatTypeForSourcePosition(positionSn: Int): SupplementBatchType? = when (positionSn) {
            in 13..15 -> SupplementBatchType.LOW_HEAT_13_15
            in 16..18 -> SupplementBatchType.LOW_HEAT_16_18
            in 25..33 -> SupplementBatchType.HIGH_HEAT_25_33
            else -> null
        }

        fun positionMatchesType(positionSn: Int, type: SupplementBatchType): Boolean = when (type) {
            SupplementBatchType.LOW_HEAT_13_15 -> positionSn in 13..15
            SupplementBatchType.LOW_HEAT_16_18 -> positionSn in 16..18
            SupplementBatchType.HIGH_HEAT_25_33 -> positionSn in 25..33
            SupplementBatchType.LOW_INITIALIZATION_1_9 -> positionSn in 1..9
            SupplementBatchType.HIGH_INITIALIZATION_1_21 -> positionSn in 1..21
            SupplementBatchType.UNKNOWN -> false
        }
    }

    private fun cancelRemainingTargetsByFaultTimeout(intent: SupplementBatchIntent): List<Int> {
        val remaining = intent.remainingTargets()
        remaining.forEach { target ->
            target.cancelledByFaultTimeout = true
            target.cancelReason = "故障超过10分钟，取消未完成补热目标"
        }
        return remaining.map { it.positionSn }
    }

    private fun shouldClearCompletedIntentOnLoad(intent: SupplementBatchIntent): Boolean {
        return intent.isComplete() && !shouldRetainCompletedHeatIntentForReturn(intent)
    }

    private fun shouldRetainCompletedHeatIntentForReturn(intent: SupplementBatchIntent): Boolean {
        return intent.retainedCompletedHeatReturnTargets().isNotEmpty()
    }

    fun markReturned(
        originBatchId: String,
        originBatchType: SupplementBatchType,
        sourcePositionSn: Int,
        returnPositionSn: Int? = null
    ): Boolean = removeBinding(
        originBatchId = originBatchId,
        originBatchType = originBatchType,
        sourcePositionSn = sourcePositionSn,
        returnPositionSn = returnPositionSn
    )

    fun markSourceCleared(
        originBatchId: String,
        originBatchType: SupplementBatchType,
        sourcePositionSn: Int
    ): Boolean = removeBinding(originBatchId, originBatchType, sourcePositionSn, null)

    fun reopenSupplyDegradedTarget(batchId: String, targetPanNo: Int, reason: String): Boolean {
        val intent = store.load() ?: return false
        if (intent.batchId != batchId) {
            return false
        }
        val target = intent.targets.firstOrNull {
            it.positionSn == targetPanNo &&
                it.skippedBySupplyDegrade
        } ?: return false
        target.skippedBySupplyDegrade = false
        target.skipReason = null
        intent.updatedAtMs = clock()
        saveDurableOrThrow(intent)
        infoLogger(
            "【补肠批次恢复】供给恢复后重新开放原目标：reason=$reason，batchId=$batchId，targetPan=$targetPanNo"
        )
        return true
    }

    private fun migrateLegacyTargetOrigins(intent: SupplementBatchIntent): Boolean {
        var changed = false
        intent.targets.forEach { target ->
            val inferredType = target.originBatchType
                ?: legacyOriginBatchType(intent, target)
            if (target.originBatchType == null) {
                target.originBatchType = inferredType
                changed = true
            }
            if (target.originBatchId.isNullOrBlank()) {
                target.originBatchId =
                    "legacy:${intent.createdAtMs}:${inferredType.name}:${target.positionSn}"
                changed = true
            }
        }
        if (changed) {
            intent.updatedAtMs = clock()
            infoLogger(
                "【补肠批次恢复】已耐久迁移旧目标来源身份：containerBatchId=${intent.batchId}，" +
                    "targets=${intent.targets.map { "${it.positionSn}:${it.originBatchType}:${it.originBatchId}" }}"
            )
        }
        return changed
    }

    private fun legacyOriginBatchType(
        intent: SupplementBatchIntent,
        target: SupplementBatchIntentTarget
    ): SupplementBatchType {
        if (target.returnPositionSn != null) {
            return heatTypeForSourcePosition(target.positionSn) ?: SupplementBatchType.UNKNOWN
        }
        return intent.type.takeIf { positionMatchesType(target.positionSn, it) }
            ?: heatTypeForSourcePosition(target.positionSn)
            ?: SupplementBatchType.UNKNOWN
    }

    private fun removeBinding(
        originBatchId: String,
        originBatchType: SupplementBatchType,
        sourcePositionSn: Int,
        returnPositionSn: Int?
    ): Boolean {
        val intent = store.load() ?: return false
        val target = intent.targets.firstOrNull {
            it.completed &&
                it.originBatchId == originBatchId &&
                it.originBatchType == originBatchType &&
                it.positionSn == sourcePositionSn &&
                (returnPositionSn == null || it.returnPositionSn == returnPositionSn)
        } ?: return false
        val remaining = intent.targets.filterNot { it === target }.toMutableList()
        if (remaining.isEmpty()) {
            clearDurableOrThrow()
        } else {
            saveDurableOrThrow(intent.copy(targets = remaining, updatedAtMs = clock()))
        }
        return true
    }

    private fun saveDurableOrThrow(intent: SupplementBatchIntent) {
        check(store.saveDurable(intent)) { "关键补肠批次状态落盘失败" }
    }

    private fun clearDurableOrThrow() {
        check(store.clearDurable()) { "关键补肠批次状态清理落盘失败" }
    }
}

internal fun SupplementBatchIntent.retainedCompletedHeatReturnTargets(): List<SupplementBatchIntentTarget> {
    return targets.filter {
        it.completed &&
            !it.cancelledByFaultTimeout &&
            !it.skippedBySupplyDegrade &&
            it.returnPositionSn != null &&
            SupplementBatchIntentRepository.isHeatSupplementBatch(
                it.resolvedOriginBatchType() ?: SupplementBatchType.UNKNOWN
            )
    }
}

internal fun SupplementBatchIntentTarget.resolvedOriginBatchType(): SupplementBatchType? =
    originBatchType ?: SupplementBatchIntentRepository.heatTypeForSourcePosition(positionSn)

internal fun KaoPan.applySupplementIntentTaste(tasteCode: String?) {
    val normalized = tasteCode?.takeIf { it.isNotBlank() } ?: return
    if (taste?.tasteCode.isNullOrBlank()) {
        taste = cn.niuyannet.kaochang.android.model.bean.Taste(normalized)
    }
}
