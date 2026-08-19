package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetStartupCloseoutTest {
    @Test
    fun preparedAfterCrash_shouldReapplyGateWithoutClosingOrderOrLocalResources() {
        val fixture = Fixture(serverSuccess = true, phase = ResetCloseoutPhase.PREPARED)

        assertTrue(fixture.coordinator.resume())

        assertEquals(listOf("prepared"), fixture.events)
        assertEquals(ResetCloseoutPhase.PREPARED, fixture.store.load()!!.phase)
        assertFalse(fixture.finalized)
    }

    @Test
    fun resetWritten_shouldReleaseLocalResourcesBeforeHttpAndKeepGateOnHttpFailure() {
        val fixture = Fixture(serverSuccess = false)

        assertTrue(fixture.coordinator.resume())

        assertEquals(listOf("local", "http"), fixture.events)
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, fixture.store.load()!!.phase)
        assertFalse(fixture.finalized)
    }

    @Test
    fun sameProcessAfterRestartScheduleFailure_shouldKeepGateAndNotCloseResources() {
        val fixture = Fixture(serverSuccess = true, processBoundaryCrossed = false)

        assertFalse(fixture.coordinator.resume())

        assertTrue(fixture.events.isEmpty())
        assertEquals(ResetCloseoutPhase.RESET_WRITTEN, fixture.store.load()!!.phase)
    }

    @Test
    fun serverSuccess_shouldPersistTerminalAndWaitUntilTenSecondsBeforeUnblocking() {
        val fixture = Fixture(serverSuccess = true, nowMs = 4_000L)

        assertTrue(fixture.coordinator.resume())

        assertEquals(ResetCloseoutPhase.SERVER_CLOSED, fixture.store.load()!!.phase)
        assertEquals(7_000L, fixture.scheduledDelayMs)
        assertEquals("FAILED", fixture.terminalState)
        fixture.runScheduled()
        assertTrue(fixture.finalized)
        assertTrue(fixture.store.load() == null)
    }

    @Test
    fun allOut4Confirmed_shouldPersistFinishedTerminal() {
        val fixture = Fixture(serverSuccess = true, confirmedOut4 = 2)

        assertTrue(fixture.coordinator.resume())

        assertEquals("FINISHED", fixture.terminalState)
    }

    @Test
    fun serverClosedAfterCrash_shouldResumeFinalizationWithoutRepeatingHttp() {
        val fixture = Fixture(serverSuccess = true, phase = ResetCloseoutPhase.SERVER_CLOSED)

        assertTrue(fixture.coordinator.resume())

        assertTrue(fixture.events.isEmpty())
        fixture.runScheduled()
        assertEquals(listOf("terminal"), fixture.events)
        assertTrue(fixture.finalized)
    }

    private class Fixture(
        private val serverSuccess: Boolean,
        nowMs: Long = 10_000L,
        confirmedOut4: Int = 0,
        phase: ResetCloseoutPhase = ResetCloseoutPhase.RESET_WRITTEN,
        processBoundaryCrossed: Boolean = true
    ) {
        private var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        val events = mutableListOf<String>()
        var scheduledDelayMs = -1L
        var scheduled: (() -> Unit)? = null
        var terminalState = ""
        var finalized = false

        init {
            store.saveDurable(
                ResetCloseoutRecord(
                    resetId = "reset-1",
                    originProcessId = "old-process",
                    kind = ResetKind.NORMAL,
                    phase = phase,
                    orderNo = "ORDER-1",
                    items = listOf(ResetOrderItem(11L, 2, confirmedOut4)),
                    previousOnlineStatus = 1,
                    previousOrderBlockReason = "ORIGINAL",
                    resetWrittenAtMs = if (phase == ResetCloseoutPhase.PREPARED) null else 1_000L
                )
            )
        }

        val coordinator = ResetStartupCloseoutCoordinator(
            store = store,
            nowMs = { nowMs },
            processBoundaryCrossed = { processBoundaryCrossed },
            recoverPrepared = { events += "prepared"; true },
            closeLocalResources = { events += "local"; true },
            closeServerOrder = { _, callback -> events += "http"; callback(serverSuccess) },
            persistOrderTerminal = { _, finished ->
                events += "terminal"
                terminalState = if (finished) "FINISHED" else "FAILED"
                true
            },
            scheduleFinalize = { delayMs, action ->
                scheduledDelayMs = delayMs
                scheduled = action
            },
            finalizeAndUnblock = {
                finalized = true
                store.clearDurable()
            }
        )

        fun runScheduled() = requireNotNull(scheduled).invoke()
    }
}
