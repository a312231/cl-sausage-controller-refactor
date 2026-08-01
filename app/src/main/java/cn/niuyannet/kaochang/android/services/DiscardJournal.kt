package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.ConsumableEvent
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

enum class DiscardCheckpoint {
    PREPARED,
    COMMAND_ISSUED
}

enum class DiscardPhysicalResult {
    DISCARDED,
    NOT_DISCARDED
}

data class DiscardJournalRecoveryDecision(
    val canClose: Boolean,
    val clearPan: Boolean,
    val reason: String
)

object DiscardJournalRecoveryPolicy {
    fun resolveSource(journal: DiscardJournal, pendingDiscardPositions: Set<Int>): String? =
        journal.source ?: if (journal.positionSn in pendingDiscardPositions) "过保到期" else null

    fun evaluate(
        journal: DiscardJournal?,
        onlineStatus: Int,
        isEnable: Int,
        moveStatus: Int,
        status201Readings: List<Int>,
        actionGroupResultStates: List<Int>?,
        physicalResult: DiscardPhysicalResult?
    ): DiscardJournalRecoveryDecision {
        val current = journal
            ?: return DiscardJournalRecoveryDecision(false, false, "没有待收口的丢弃记录")
        if (current.positionSn !in 1..33) {
            return DiscardJournalRecoveryDecision(false, false, "丢弃记录已损坏，禁止自动释放")
        }
        if (onlineStatus != 3 || isEnable != 0) {
            return DiscardJournalRecoveryDecision(false, false, "仅允许在维护态且算法关闭时收口")
        }
        if (moveStatus != 4) {
            return DiscardJournalRecoveryDecision(false, false, "丢弃机械状态已变化")
        }
        if (!MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(status201Readings)) {
            return DiscardJournalRecoveryDecision(false, false, "201 未连续确认空闲")
        }
        if (actionGroupResultStates == null) {
            return DiscardJournalRecoveryDecision(false, false, "300/400 动作组结果读取未完成")
        }
        val unsafeActionGroupResult = actionGroupResultStates.firstOrNull {
            it != AtomicActionCodes.IDLE && it != AtomicActionCodes.COMPLETED
        }
        if (unsafeActionGroupResult != null) {
            return DiscardJournalRecoveryDecision(
                false,
                false,
                "300/400 动作组结果不可安全恢复：$unsafeActionGroupResult"
            )
        }
        if (current.checkpoint == DiscardCheckpoint.PREPARED) {
            return DiscardJournalRecoveryDecision(true, false, "命令尚未下发，可安全释放")
        }
        val confirmed = physicalResult
            ?: return DiscardJournalRecoveryDecision(false, false, "需要现场确认目标烤盘实际结果")
        return DiscardJournalRecoveryDecision(
            canClose = true,
            clearPan = confirmed == DiscardPhysicalResult.DISCARDED,
            reason = "现场结果已确认"
        )
    }
}

data class DiscardJournal(
    val owner: String,
    val positionSn: Int,
    val checkpoint: DiscardCheckpoint,
    val createdAtMs: Long,
    val mechanicalClaimState: MechanicalClaimState? = null,
    val mechanicalClaimRevision: Long = 0L,
    val confirmedPhysicalResult: DiscardPhysicalResult? = null,
    val source: String? = null,
    val tasteLabel: String? = null,
    val wasteConsumableEvent: ConsumableEvent? = null
)

interface DiscardJournalStore {
    fun load(): DiscardJournal?
    fun saveDurable(journal: DiscardJournal): Boolean
    fun clearDurable(): Boolean
}

class SharedPreferenceDiscardJournalStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_DISCARD_JOURNAL
) : DiscardJournalStore {
    override fun load(): DiscardJournal? = PreferenceUtils
        .getStringPreference(file, key, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
            val parsed = runCatching { JSON.parseObject(raw, DiscardJournal::class.java) }.getOrNull()
            parsed?.takeIf(::isValid)
                ?: corruptJournal()
        }

    private fun isValid(journal: DiscardJournal): Boolean =
        journal.owner.isNotBlank() &&
            journal.positionSn > 0 &&
            runCatching { journal.checkpoint.name }.isSuccess

    private fun corruptJournal() = DiscardJournal(
        owner = "corrupt-journal",
        positionSn = 0,
        checkpoint = DiscardCheckpoint.COMMAND_ISSUED,
        createdAtMs = 0L
    )

    override fun saveDurable(journal: DiscardJournal): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(journal))

    override fun clearDurable(): Boolean = PreferenceUtils.saveStringPreferenceDurable(file, key, "")

    companion object {
        private const val KEY_DISCARD_JOURNAL = "sp_discard_journal_json"
    }
}
