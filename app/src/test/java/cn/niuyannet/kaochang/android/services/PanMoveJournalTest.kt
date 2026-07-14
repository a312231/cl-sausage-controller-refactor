package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanMoveJournalTest {

    @Test
    fun completedRegister_withoutObservedRunning_requiresManualConfirmation() {
        val journal = journal(PanMoveCheckpoint.COMMAND_ISSUED)

        assertEquals(
            PanMoveRecoveryDecision.REQUIRE_MANUAL_CONFIRMATION,
            PanMoveRecoveryPolicy.decide(journal, moveResult = 2, panAlreadyApplied = false, bindingAlreadyCleared = false)
        )
    }

    @Test
    fun completedRegister_afterObservedRunning_canFinalize() {
        val journal = journal(PanMoveCheckpoint.OBSERVED_RUNNING)

        assertEquals(
            PanMoveRecoveryDecision.FINALIZE_SUCCESS,
            PanMoveRecoveryPolicy.decide(journal, moveResult = 2, panAlreadyApplied = false, bindingAlreadyCleared = false)
        )
    }

    @Test
    fun alreadySettledMove_onlyClearsJournal() {
        val journal = journal(PanMoveCheckpoint.OBSERVED_RUNNING)

        assertEquals(
            PanMoveRecoveryDecision.CLEAR_SETTLED_JOURNAL,
            PanMoveRecoveryPolicy.decide(journal, moveResult = 2, panAlreadyApplied = true, bindingAlreadyCleared = true)
        )
    }

    @Test
    fun alreadySettledMove_clearsJournalEvenWhenResultRegisterResetToIdle() {
        val journal = journal(PanMoveCheckpoint.OBSERVED_RUNNING)

        assertEquals(
            PanMoveRecoveryDecision.CLEAR_SETTLED_JOURNAL,
            PanMoveRecoveryPolicy.decide(journal, moveResult = 0, panAlreadyApplied = true, bindingAlreadyCleared = true)
        )
    }

    @Test
    fun storeFailure_doesNotAdvanceCheckpoint() {
        val store = MemoryPanMoveJournalStore(saveAllowed = false)
        val prepared = journal(PanMoveCheckpoint.PREPARED)

        assertFalse(store.saveDurable(prepared.copy(checkpoint = PanMoveCheckpoint.COMMAND_INTENT)))
        assertEquals(PanMoveCheckpoint.PREPARED, store.journal?.checkpoint)
    }

    @Test
    fun storeSuccess_advancesCheckpointDurably() {
        val store = MemoryPanMoveJournalStore(saveAllowed = true)
        val prepared = journal(PanMoveCheckpoint.PREPARED)
        store.journal = prepared

        assertTrue(store.saveDurable(prepared.copy(checkpoint = PanMoveCheckpoint.COMMAND_INTENT)))
        assertEquals(PanMoveCheckpoint.COMMAND_INTENT, store.journal?.checkpoint)
    }

    private fun journal(checkpoint: PanMoveCheckpoint) = PanMoveJournal(
        moveId = "move-1",
        sourcePositionSn = 13,
        targetPositionSn = 1,
        originBatchId = "low-1",
        originBatchType = SupplementBatchType.LOW_HEAT_13_15,
        targetHoldingTime = 1234L,
        checkpoint = checkpoint,
        createdAtMs = 1000L
    )

    private class MemoryPanMoveJournalStore(
        private val saveAllowed: Boolean
    ) : PanMoveJournalStore {
        var journal: PanMoveJournal? = PanMoveJournal(
            moveId = "move-1",
            sourcePositionSn = 13,
            targetPositionSn = 1,
            checkpoint = PanMoveCheckpoint.PREPARED,
            createdAtMs = 1000L
        )

        override fun load(): PanMoveJournal? = journal

        override fun saveDurable(journal: PanMoveJournal): Boolean {
            if (!saveAllowed) return false
            this.journal = journal
            return true
        }

        override fun clearDurable(): Boolean {
            journal = null
            return true
        }
    }
}
