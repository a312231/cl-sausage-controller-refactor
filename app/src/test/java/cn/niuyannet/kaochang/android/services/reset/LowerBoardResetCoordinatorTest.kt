package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerBoardResetCoordinatorTest {

    @Test
    fun successfulNormalReset_shouldPersistBlockWrite999AndScheduleOneRestartWithoutChangingState() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = true)

        val result = fixture.coordinator.execute(
            kind = ResetKind.NORMAL,
            currentOnlineStatus = 1,
            currentOrderBlockReason = "LOWER_PLATFORM_RECOVERY_REQUIRED",
            currentHomeQrHidden = 1,
            currentScanBlockStatus = 1
        )

        assertTrue(result.success)
        assertEquals(1, fixture.writeCalls)
        assertEquals(1, fixture.restartCalls)
        assertEquals(listOf("prepare", "server", "write", "restart"), fixture.events)
        assertEquals(1, fixture.blockedOnlineStatus)
        assertEquals("SYSTEM_RESET_IN_PROGRESS", fixture.blockedReason)
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, fixture.store.load()!!.phase)
        assertEquals("LOWER_PLATFORM_RECOVERY_REQUIRED", fixture.store.load()!!.previousOrderBlockReason)
        assertEquals(1, fixture.store.load()!!.previousHomeQrHidden)
        assertEquals(1, fixture.store.load()!!.previousScanBlockStatus)
    }

    @Test
    fun failed999_shouldKeepPreparedGateForSafeRetryAndNeverRestart() {
        val fixture = Fixture(writeSucceeded = false, restartScheduled = true)

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 3, "ORIGINAL", 1, 1)

        assertFalse(result.success)
        assertEquals(0, fixture.restartCalls)
        assertEquals(ResetCloseoutPhase.PREPARED, fixture.store.load()!!.phase)
    }

    @Test
    fun failedEmergency999_shouldRemainInMaintenanceWithAlgorithmDisabledByCaller() {
        val fixture = Fixture(writeSucceeded = false, restartScheduled = true)

        val result = fixture.coordinator.execute(ResetKind.EMERGENCY, 1, "", 0, 0)

        assertFalse(result.success)
        assertEquals(ResetCloseoutPhase.PREPARED, fixture.store.load()!!.phase)
    }

    @Test
    fun atomicTemporaryBlockFailure_shouldNotLeavePartialPreparedEvidence() {
        val fixture = Fixture(
            writeSucceeded = true,
            restartScheduled = true,
            blockSucceeded = false
        )

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 1, "", 0, 0)

        assertFalse(result.success)
        assertEquals(0, fixture.writeCalls)
        assertNull(fixture.store.load())
    }

    @Test
    fun serverBlockNotConfirmed_shouldKeepPreparedGateAndNeverWrite999() {
        val fixture = Fixture(
            writeSucceeded = true,
            restartScheduled = true,
            serverBlockConfirmed = false
        )

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 1, "", 0, 0)

        assertFalse(result.success)
        assertEquals(0, fixture.writeCalls)
        assertEquals(listOf("prepare", "server"), fixture.events)
        assertEquals(ResetCloseoutPhase.PREPARED, fixture.store.load()!!.phase)
    }

    @Test
    fun restartScheduleFailure_shouldKeepResetWrittenAndGateBlocked() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = false)

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 1, "", 0, 0)

        assertFalse(result.success)
        assertTrue(result.resetWritten)
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, fixture.store.load()!!.phase)
    }

    @Test
    fun resetWrittenButEvidencePersistFailed_shouldKeepMechanicalGateAndNeverPretendWriteFailed() {
        val fixture = Fixture(
            writeSucceeded = true,
            restartScheduled = true,
            persistWrittenSucceeded = false
        )

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 1, "", 0, 0)

        assertFalse(result.success)
        assertTrue(result.resetWritten)
        assertEquals(ResetCloseoutPhase.PREPARED, fixture.store.load()!!.phase)
    }

    @Test
    fun preparedAfterProcessCrash_shouldAllowIdempotent999Retry() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = true)
        fixture.store.saveDurable(
            ResetCloseoutRecord(
                resetId = "old-reset",
                originProcessId = "old-process",
                kind = ResetKind.NORMAL,
                phase = ResetCloseoutPhase.PREPARED,
                previousOnlineStatus = 1,
                previousOrderBlockReason = "ORIGINAL",
                previousHomeQrHidden = 1,
                previousScanBlockStatus = 1
            )
        )

        val result = fixture.coordinator.execute(ResetKind.NORMAL, 1, "SYSTEM_RESET_IN_PROGRESS", 1, 1)

        assertTrue(result.success)
        assertEquals(1, fixture.writeCalls)
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, fixture.store.load()!!.phase)
        assertEquals("ORIGINAL", fixture.store.load()!!.previousOrderBlockReason)
    }

    @Test
    fun resetPreparation_shouldPreserveActiveOrderEvidenceForRefund() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = true)
        fixture.store.saveDurable(
            ResetCloseoutRecord(
                phase = ResetCloseoutPhase.ORDER_ACTIVE,
                orderNo = "ORDER-1",
                items = listOf(ResetOrderItem(11L, 2, 1)),
                previousOnlineStatus = 4
            )
        )

        assertTrue(fixture.coordinator.execute(ResetKind.NORMAL, 4, "", 0, 0).success)

        assertEquals("ORDER-1", fixture.store.load()!!.orderNo)
        assertEquals(listOf(ResetOrderItem(11L, 2, 1)), fixture.store.load()!!.items)
    }

    @Test
    fun out4ConfirmedWhileWaitingFor999_shouldUseLatestEvidenceWhenPersistingResetWritten() {
        val fixture = Fixture(
            writeSucceeded = true,
            restartScheduled = true,
            confirmOut4BeforeWrite = true
        )
        fixture.store.saveDurable(
            ResetCloseoutRecord(
                phase = ResetCloseoutPhase.ORDER_ACTIVE,
                orderNo = "ORDER-1",
                items = listOf(ResetOrderItem(11L, 1, 0)),
                previousOnlineStatus = 4
            )
        )

        assertTrue(fixture.coordinator.execute(ResetKind.NORMAL, 4, "", 0, 0).success)

        val written = fixture.store.load()!!
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, written.phase)
        assertEquals(1, written.items.single().confirmedOut4)
    }

    @Test
    fun emergencyPreparedRetryViaNormalButton_shouldNeverDowngradeResetKind() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = true)
        fixture.store.saveDurable(
            ResetCloseoutRecord(
                resetId = "emergency-reset",
                originProcessId = "old-process",
                kind = ResetKind.EMERGENCY,
                phase = ResetCloseoutPhase.PREPARED,
                previousOnlineStatus = 1
            )
        )

        assertTrue(fixture.coordinator.execute(ResetKind.NORMAL, 3, "SYSTEM_RESET_IN_PROGRESS", 1, 1).success)

        assertEquals(ResetKind.EMERGENCY, fixture.appliedKind)
        assertEquals(ResetKind.EMERGENCY, fixture.store.load()!!.kind)
    }

    @Test
    fun normalPreparedRetryViaEmergencyButton_shouldUpgradeResetKind() {
        val fixture = Fixture(writeSucceeded = true, restartScheduled = true)
        fixture.store.saveDurable(
            ResetCloseoutRecord(
                resetId = "normal-reset",
                originProcessId = "old-process",
                kind = ResetKind.NORMAL,
                phase = ResetCloseoutPhase.PREPARED,
                previousOnlineStatus = 1
            )
        )

        assertTrue(fixture.coordinator.execute(ResetKind.EMERGENCY, 1, "SYSTEM_RESET_IN_PROGRESS", 1, 1).success)

        assertEquals(ResetKind.EMERGENCY, fixture.appliedKind)
        assertEquals(ResetKind.EMERGENCY, fixture.store.load()!!.kind)
    }

    private class Fixture(
        writeSucceeded: Boolean,
        restartScheduled: Boolean,
        blockSucceeded: Boolean = true,
        serverBlockConfirmed: Boolean = true,
        persistWrittenSucceeded: Boolean = true,
        confirmOut4BeforeWrite: Boolean = false
    ) {
        private var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        var writeCalls = 0
        var restartCalls = 0
        var blockedOnlineStatus = -1
        var blockedReason = ""
        var appliedKind: ResetKind? = null
        val events = mutableListOf<String>()

        val coordinator = LowerBoardResetCoordinator(
            store = store,
            newResetId = { "reset-1" },
            nowMs = { 1_000L },
            currentProcessId = { "process-1" },
            prepareAndApplyTemporaryBlock = { prepared, reason ->
                events += "prepare"
                blockedOnlineStatus = prepared.previousOnlineStatus
                blockedReason = reason
                appliedKind = prepared.kind
                blockSucceeded && store.saveDurable(prepared)
            },
            confirmServerBlock = { events += "server"; serverBlockConfirmed },
            writeResetAndPersistResult = { prepared, writtenAtMs ->
                events += "write"
                writeCalls++
                if (confirmOut4BeforeWrite) {
                    store.confirmOut4Durable("ORDER-1", 11L)
                }
                val latest = store.load()
                when {
                    !writeSucceeded -> ResetWriteResult.WRITE_FAILED
                    latest?.phase != ResetCloseoutPhase.PREPARED ||
                        latest.resetId != prepared.resetId ||
                        latest.kind != prepared.kind -> ResetWriteResult.PERSIST_FAILED
                    persistWrittenSucceeded && store.saveDurable(
                        latest.copy(
                            phase = ResetCloseoutPhase.RESET_WRITTEN,
                            resetWrittenAtMs = writtenAtMs
                        )
                    ) -> ResetWriteResult.SUCCESS
                    else -> ResetWriteResult.PERSIST_FAILED
                }
            },
            scheduleColdRestart = { events += "restart"; restartCalls++; restartScheduled }
        )
    }
}
