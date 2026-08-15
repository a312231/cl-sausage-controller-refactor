package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCloseoutPendingTest {

    @Test
    fun shouldRefreshTemperatureAfterDiscard_shouldSkipOnlyBusinessCloseout() {
        assertFalse(shouldRefreshTemperatureAfterDiscard(businessCloseout = true))
        assertTrue(shouldRefreshTemperatureAfterDiscard(businessCloseout = false))
    }

    @Test
    fun repository_shouldWriteOnlyWhenPendingStateChanges() {
        val store = MemoryBusinessCloseoutPendingStore()
        val repository = BusinessCloseoutPendingRepository(store)

        assertTrue(repository.requestDurably())
        assertTrue(repository.requestDurably())
        assertTrue(repository.isPending())
        assertTrue(repository.clearDurably())
        assertTrue(repository.clearDurably())
        assertFalse(repository.isPending())

        assertTrue(store.savedValues == listOf("1", ""))
    }

    @Test
    fun repository_shouldFailClosedForUnknownNonBlankValueAndFailedWrite() {
        val corrupted = BusinessCloseoutPendingRepository(
            MemoryBusinessCloseoutPendingStore(initialRaw = "unknown")
        )
        assertTrue(corrupted.isPending())

        val failedStore = MemoryBusinessCloseoutPendingStore(saveSucceeds = false)
        val failed = BusinessCloseoutPendingRepository(failedStore)
        assertFalse(failed.requestDurably())
        assertFalse(failed.isPending())
    }

    @Test
    fun shouldCaptureBusinessCloseout_shouldOnlyCaptureAutomaticBusinessCloseCases() {
        assertTrue(shouldCaptureBusinessCloseout(false, 1, 0))
        assertTrue(shouldCaptureBusinessCloseout(false, 4, 0))
        assertTrue(shouldCaptureBusinessCloseout(false, 3, 2))

        assertFalse(shouldCaptureBusinessCloseout(true, 1, 0))
        assertFalse(shouldCaptureBusinessCloseout(false, 3, 0))
        assertFalse(shouldCaptureBusinessCloseout(false, 0, 0))
    }

    @Test
    fun canRunBusinessCloseoutDiscard_shouldRequireSafeIdleWithoutRecoveryEvidence() {
        assertTrue(
            canRunBusinessCloseoutDiscard(
                onlineStatus = 2,
                automaticRestSource = true,
                errorStatus = 0,
                inspectionMode = 0,
                modbusConnected = true,
                moveStatus = 0,
                orderPending = false,
                recoveryBlocked = false,
                emergencyResetPending = false,
                lowerPlatformBlocked = false
            )
        )
        assertFalse(canRunBusinessCloseoutDiscard(4, true, 0, 0, true, 2, true, false, false, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 2, 0, true, 0, false, false, false, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 0, 1, true, 0, false, false, false, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 0, 0, false, 0, false, false, false, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 0, 0, true, 0, false, true, false, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 0, 0, true, 0, false, false, true, false))
        assertFalse(canRunBusinessCloseoutDiscard(2, true, 0, 0, true, 0, false, false, false, true))
    }

    @Test
    fun canFinalizeBusinessCloseout_shouldRequireAllPhysicalAndRecoveryEvidenceClear() {
        assertTrue(canFinalizeBusinessCloseout(false, false, 0, false, false, false, false))
        assertFalse(canFinalizeBusinessCloseout(true, false, 0, false, false, false, false))
        assertFalse(canFinalizeBusinessCloseout(false, true, 0, false, false, false, false))
        assertFalse(canFinalizeBusinessCloseout(false, false, 4, false, false, false, false))
        assertFalse(canFinalizeBusinessCloseout(false, false, 0, true, false, false, false))
        assertFalse(canFinalizeBusinessCloseout(false, false, 0, false, true, false, false))
        assertFalse(canFinalizeBusinessCloseout(false, false, 0, false, false, true, false))
        assertFalse(canFinalizeBusinessCloseout(false, false, 0, false, false, false, true))
    }

    @Test
    fun canIssueBusinessCloseoutDiscardCommand_shouldKeepSafetyChecksWithoutAlgorithmSwitch() {
        assertTrue(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(4, true, 0, 0, 0, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, false, 0, 0, 0, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, true, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 2, 0, 0, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 1, 0, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 1, false, false, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, false, true, false))
        assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, false, false, true))
    }

    private class MemoryBusinessCloseoutPendingStore(
        initialRaw: String? = null,
        private val saveSucceeds: Boolean = true
    ) : BusinessCloseoutPendingStore {
        private var raw: String? = initialRaw
        val savedValues = mutableListOf<String>()

        override fun loadRaw(): String? = raw

        override fun saveRawDurably(raw: String): Boolean {
            savedValues += raw
            if (saveSucceeds) {
                this.raw = raw
            }
            return saveSucceeds
        }
    }
}
