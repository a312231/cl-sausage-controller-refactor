package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighToLowTimerStateTest {

    @Test
    fun disarmDurably_shouldWriteOnceAndSkipUnchangedState() {
        val store = MemoryHighToLowTimerStateStore(
            HighToLowTimerState(armed = true, anchorAtMs = 100L)
        )
        val repository = HighToLowTimerStateRepository(store)

        assertTrue(repository.disarmDurably())
        assertTrue(repository.disarmDurably())

        assertEquals(1, store.saveCount)
        assertEquals(HighToLowTimerState(), store.state)
    }

    @Test
    fun disarmDurably_shouldKeepPreviousStateWhenDurableWriteFails() {
        val initial = HighToLowTimerState(armed = true, anchorAtMs = 100L)
        val store = MemoryHighToLowTimerStateStore(initial, saveSucceeds = false)
        val repository = HighToLowTimerStateRepository(store)

        assertFalse(repository.disarmDurably())

        assertEquals(initial, store.state)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun armDurably_shouldRejectInvalidTimeWithoutWriting() {
        val store = MemoryHighToLowTimerStateStore()
        val repository = HighToLowTimerStateRepository(store)

        assertFalse(repository.armDurably(0L))

        assertEquals(0, store.saveCount)
        assertEquals(HighToLowTimerState(), store.state)
    }

    @Test
    fun armDurably_shouldWriteOnceAndSkipUnchangedState() {
        val store = MemoryHighToLowTimerStateStore()
        val repository = HighToLowTimerStateRepository(store)

        assertTrue(repository.armDurably(100L))
        assertTrue(repository.armDurably(100L))

        assertEquals(1, store.saveCount)
        assertEquals(HighToLowTimerState(armed = true, anchorAtMs = 100L), store.state)
    }

    @Test
    fun armDurably_shouldKeepDisarmedStateWhenDurableWriteFails() {
        val store = MemoryHighToLowTimerStateStore(saveSucceeds = false)
        val repository = HighToLowTimerStateRepository(store)

        assertFalse(repository.armDurably(100L))

        assertEquals(1, store.saveCount)
        assertEquals(HighToLowTimerState(), store.state)
    }

    @Test
    fun decode_shouldFailClosedForCorruptOrInvalidArmedState() {
        assertEquals(
            HighToLowTimerState(armed = true, anchorAtMs = 100L),
            SharedPreferenceHighToLowTimerStateStore.decode("{\"armed\":true,\"anchorAtMs\":100}")
        )
        assertEquals(
            HighToLowTimerState(),
            SharedPreferenceHighToLowTimerStateStore.decode("not-json")
        )
        assertEquals(
            HighToLowTimerState(),
            SharedPreferenceHighToLowTimerStateStore.decode("{\"armed\":true,\"anchorAtMs\":0}")
        )
    }

    private class MemoryHighToLowTimerStateStore(
        initial: HighToLowTimerState = HighToLowTimerState(),
        private val saveSucceeds: Boolean = true
    ) : HighToLowTimerStateStore {
        var state: HighToLowTimerState = initial
            private set
        var saveCount: Int = 0
            private set

        override fun load(): HighToLowTimerState = state

        override fun saveDurable(state: HighToLowTimerState): Boolean {
            saveCount += 1
            if (saveSucceeds) {
                this.state = state
            }
            return saveSucceeds
        }
    }
}
