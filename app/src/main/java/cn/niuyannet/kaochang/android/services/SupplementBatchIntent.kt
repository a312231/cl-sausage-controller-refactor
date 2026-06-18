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
    var completed: Boolean = false
)

data class SupplementBatchIntent(
    var batchId: String = "",
    var type: SupplementBatchType = SupplementBatchType.UNKNOWN,
    var targets: MutableList<SupplementBatchIntentTarget> = mutableListOf(),
    var failedActionName: String? = null,
    var failedTargetPanNo: Int? = null,
    var failureMessage: String? = null,
    var requiresManualConfirm: Boolean = false,
    var createdAtMs: Long = 0L,
    var updatedAtMs: Long = 0L
) {
    fun remainingTargets(): List<SupplementBatchIntentTarget> =
        targets.filter { !it.completed }

    fun isComplete(): Boolean =
        targets.isNotEmpty() && targets.all { it.completed }
}

interface SupplementBatchIntentStore {
    fun load(): SupplementBatchIntent?
    fun save(intent: SupplementBatchIntent)
    fun clear()
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
        if (intent.targets.isEmpty() || intent.isComplete()) {
            store.clear()
            return null
        }
        return intent
    }

    fun begin(
        targets: List<KaoChangOperate.SupplementTarget>,
        typeOverride: SupplementBatchType? = null
    ): SupplementBatchIntent? {
        val active = loadActive()
        if (active != null) {
            warnLogger(
                "【补肠批次恢复】已有未完成补肠批次，拒绝覆盖新批次：batchId=${active.batchId}，" +
                    "type=${typeText(active.type)}，remaining=${active.remainingTargets().map { it.positionSn }}"
            )
            return null
        }

        val normalizedTargets = targets
            .filter { it.kaoPan.positionSn in 1..33 }
            .map {
                SupplementBatchIntentTarget(
                    positionSn = it.kaoPan.positionSn,
                    tasteCode = it.tasteCode?.takeIf { code -> code.isNotBlank() }
                        ?: it.kaoPan.taste?.tasteCode?.takeIf { code -> code.isNotBlank() },
                    returnPositionSn = it.returnPositionSn?.takeIf { position -> position in 1..33 },
                    completed = false
                )
            }
            .distinctBy { it.positionSn }
            .sortedBy { it.positionSn }
            .toMutableList()
        if (normalizedTargets.isEmpty()) {
            return null
        }

        val now = clock()
        val intent = SupplementBatchIntent(
            batchId = "supplement:$now:${normalizedTargets.joinToString("-") { it.positionSn.toString() }}",
            type = typeOverride ?: inferType(normalizedTargets.map { it.positionSn }),
            targets = normalizedTargets,
            createdAtMs = now,
            updatedAtMs = now
        )
        store.save(intent)
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
            store.clear()
            infoLogger("【补肠批次恢复】补肠批次已全部完成，清除批次意图：batchId=$batchId")
        } else {
            store.save(intent)
            infoLogger(
                "【补肠批次恢复】补肠批次目标已完成：batchId=$batchId，targetPan=$targetPanNo，" +
                    "remaining=${intent.remainingTargets().map { it.positionSn }}"
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
        intent.requiresManualConfirm = requiresManualConfirm
        intent.updatedAtMs = clock()
        store.save(intent)
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
        intent.requiresManualConfirm = false
        intent.updatedAtMs = clock()
        store.save(intent)
        warnLogger(
            "【补肠批次恢复】人工已确认半路烤肠/机械位置安全，允许续补未完成批次：reason=$reason，" +
                "batchId=${intent.batchId}，targets=${intent.targets.map { it.positionSn }}，" +
                "remaining=${intent.remainingTargets().map { it.positionSn }}"
        )
        return intent
    }

    fun clear(reason: String) {
        val active = store.load()
        store.clear()
        if (active != null) {
            infoLogger(
                "【补肠批次恢复】已清除补肠批次意图：reason=$reason，batchId=${active.batchId}，" +
                    "type=${typeText(active.type)}"
            )
        }
    }

    companion object {
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
    }
}

internal fun KaoPan.applySupplementIntentTaste(tasteCode: String?) {
    val normalized = tasteCode?.takeIf { it.isNotBlank() } ?: return
    if (taste?.tasteCode.isNullOrBlank()) {
        taste = cn.niuyannet.kaochang.android.model.bean.Taste(normalized)
    }
}
