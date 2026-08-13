package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import java.util.concurrent.CancellationException

enum class PanMoveCheckpoint {
    PREPARED,
    COMMAND_INTENT,
    COMMAND_ISSUED,
    OBSERVED_RUNNING
}

data class PanMoveJournal(
    val moveId: String,
    val sourcePositionSn: Int,
    val targetPositionSn: Int,
    val originBatchId: String? = null,
    val originBatchType: SupplementBatchType? = null,
    val targetHoldingTime: Long? = null,
    val checkpoint: PanMoveCheckpoint,
    val createdAtMs: Long,
    val mechanicalClaimState: MechanicalClaimState? = null,
    val mechanicalClaimRevision: Long = 0L
)

interface PanMoveJournalStore {
    fun load(): PanMoveJournal?
    fun saveDurable(journal: PanMoveJournal): Boolean
    fun clearDurable(): Boolean
}

class SharedPreferencePanMoveJournalStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_PAN_MOVE_JOURNAL,
    private val readRaw: () -> String? = { PreferenceUtils.getStringPreference(file, key, null) },
    private val decodeRaw: (String) -> PanMoveJournal = { raw -> JSON.parseObject(raw, PanMoveJournal::class.java) }
) : PanMoveJournalStore {
    override fun load(): PanMoveJournal? = readRaw()
        ?.takeIf { it.isNotBlank() }
        ?.let { raw ->
            val parsed = try {
                decodeRaw(raw).takeIf(::isValid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (_: Exception) {
                null
            }
            parsed ?: corruptJournal()
        }

    private fun isValid(journal: PanMoveJournal): Boolean =
        journal.moveId.isNotBlank() &&
            journal.sourcePositionSn > 0 &&
            journal.targetPositionSn > 0 &&
            journal.checkpoint.name.isNotBlank()

    private fun corruptJournal() = PanMoveJournal(
        moveId = "corrupt-journal",
        sourcePositionSn = 0,
        targetPositionSn = 0,
        checkpoint = PanMoveCheckpoint.COMMAND_INTENT,
        createdAtMs = 0L
    )

    override fun saveDurable(journal: PanMoveJournal): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(journal))

    override fun clearDurable(): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, "")

    companion object {
        private const val KEY_PAN_MOVE_JOURNAL = "sp_pan_move_journal_json"
    }
}

enum class PanMoveRecoveryDecision {
    WAIT_FOR_RUNNING,
    REQUIRE_MANUAL_CONFIRMATION,
    FINALIZE_SUCCESS,
    CLEAR_SETTLED_JOURNAL
}

object PanMoveRecoveryPolicy {
    fun decide(
        journal: PanMoveJournal,
        moveResult: Int,
        panAlreadyApplied: Boolean,
        bindingAlreadyCleared: Boolean
    ): PanMoveRecoveryDecision {
        if (panAlreadyApplied && bindingAlreadyCleared) {
            return PanMoveRecoveryDecision.CLEAR_SETTLED_JOURNAL
        }
        if (moveResult == 2) {
            return if (journal.checkpoint == PanMoveCheckpoint.OBSERVED_RUNNING) {
                PanMoveRecoveryDecision.FINALIZE_SUCCESS
            } else {
                PanMoveRecoveryDecision.REQUIRE_MANUAL_CONFIRMATION
            }
        }
        return when (journal.checkpoint) {
            PanMoveCheckpoint.PREPARED,
            PanMoveCheckpoint.COMMAND_INTENT,
            PanMoveCheckpoint.COMMAND_ISSUED -> PanMoveRecoveryDecision.WAIT_FOR_RUNNING
            PanMoveCheckpoint.OBSERVED_RUNNING -> PanMoveRecoveryDecision.REQUIRE_MANUAL_CONFIRMATION
        }
    }
}
