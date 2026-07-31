package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

enum class DiscardCheckpoint {
    PREPARED,
    COMMAND_ISSUED
}

data class DiscardJournal(
    val owner: String,
    val positionSn: Int,
    val checkpoint: DiscardCheckpoint,
    val createdAtMs: Long,
    val mechanicalClaimState: MechanicalClaimState? = null,
    val mechanicalClaimRevision: Long = 0L
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
