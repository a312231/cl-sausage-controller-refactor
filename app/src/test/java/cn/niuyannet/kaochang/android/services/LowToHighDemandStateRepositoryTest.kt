package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LowToHighDemandStateRepositoryTest {
    @Test
    fun saveAndReload_shouldKeepBlockedPositionsAcrossRepositoryInstances() {
        val store = MemoryLowToHighDemandStateStore()

        LowToHighDemandStateRepository(store).recordNonDemandEmpty(5, "initialization_gap")

        assertEquals(setOf(5), LowToHighDemandStateRepository(store).blockedPositions())
    }

    @Test
    fun clearPosition_shouldRemoveOnlyFilledPosition() {
        val store = MemoryLowToHighDemandStateStore()
        val repository = LowToHighDemandStateRepository(store)
        repository.recordNonDemandEmpty(5, "supply_gap")
        repository.recordNonDemandEmpty(6, "action_failure")

        repository.clearPosition(5, "补肠成功写入")

        assertEquals(setOf(6), repository.blockedPositions())
    }

    @Test
    fun blockedPositions_shouldFailClosedWhenStoredJsonIsCorrupt() {
        val repository = LowToHighDemandStateRepository(
            MemoryLowToHighDemandStateStore(loadStatus = LowToHighDemandLoadStatus.CORRUPT)
        )

        assertEquals((1..21).toSet(), repository.blockedPositions())
    }

    @Test
    fun decode_shouldReturnCorruptForInvalidNonBlankJson() {
        val result = SharedPreferenceLowToHighDemandStateStore.decode("{broken")

        assertEquals(LowToHighDemandLoadStatus.CORRUPT, result.status)
        assertNull(result.state)
    }

    @Test
    fun recordNonDemandEmpty_shouldThrowWhenDurableSaveFails() {
        val store = FailingLowToHighDemandStateStore(emptySet(), saveSucceeds = false)
        val repository = LowToHighDemandStateRepository(store)

        val failure = assertDurableFailure {
            repository.recordNonDemandEmpty(5, "initialization_gap")
        }

        assertTrue(failure.message.orEmpty().contains("recordNonDemandEmpty"))
        assertEquals(emptySet<Int>(), repository.blockedPositions())
    }

    @Test
    fun clearPosition_shouldThrowAndKeepOldStateWhenDurableSaveFails() {
        val store = FailingLowToHighDemandStateStore(setOf(5, 6), saveSucceeds = false)
        val repository = LowToHighDemandStateRepository(store)

        val failure = assertDurableFailure {
            repository.clearPosition(5, "补肠成功写入")
        }

        assertTrue(failure.message.orEmpty().contains("clearPosition"))
        assertEquals(setOf(5, 6), repository.blockedPositions())
    }

    @Test
    fun clearAll_shouldThrowAndKeepOldStateWhenDurableClearFails() {
        val store = FailingLowToHighDemandStateStore(setOf(5, 6), clearSucceeds = false)
        val repository = LowToHighDemandStateRepository(store)

        val failure = assertDurableFailure {
            repository.clearAll("人工重置")
        }

        assertTrue(failure.message.orEmpty().contains("clearAll"))
        assertEquals(setOf(5, 6), repository.blockedPositions())
    }

    private fun assertDurableFailure(block: () -> Unit): IllegalStateException {
        return try {
            block()
            fail("durable=false 时必须抛出异常")
            error("unreachable")
        } catch (e: IllegalStateException) {
            e
        }
    }

    private class MemoryLowToHighDemandStateStore(
        private var loadStatus: LowToHighDemandLoadStatus = LowToHighDemandLoadStatus.EMPTY
    ) : LowToHighDemandStateStore {
        private var state: LowToHighDemandState? = null

        override fun load(): LowToHighDemandLoadResult = when (loadStatus) {
            LowToHighDemandLoadStatus.CORRUPT -> LowToHighDemandLoadResult(LowToHighDemandLoadStatus.CORRUPT)
            LowToHighDemandLoadStatus.EMPTY -> LowToHighDemandLoadResult(LowToHighDemandLoadStatus.EMPTY)
            LowToHighDemandLoadStatus.PRESENT -> LowToHighDemandLoadResult(
                LowToHighDemandLoadStatus.PRESENT,
                state?.copy(blockedFrontPositions = state!!.blockedFrontPositions.toMutableSet())
            )
        }

        override fun saveDurable(state: LowToHighDemandState): Boolean {
            this.state = state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
            loadStatus = LowToHighDemandLoadStatus.PRESENT
            return true
        }

        override fun clearDurable(): Boolean {
            state = LowToHighDemandState()
            loadStatus = LowToHighDemandLoadStatus.PRESENT
            return true
        }
    }

    private class FailingLowToHighDemandStateStore(
        initialPositions: Set<Int>,
        private val saveSucceeds: Boolean = true,
        private val clearSucceeds: Boolean = true
    ) : LowToHighDemandStateStore {
        private var state = LowToHighDemandState(initialPositions.toMutableSet())

        override fun load(): LowToHighDemandLoadResult = LowToHighDemandLoadResult(
            LowToHighDemandLoadStatus.PRESENT,
            state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
        )

        override fun saveDurable(state: LowToHighDemandState): Boolean {
            if (!saveSucceeds) return false
            this.state = state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
            return true
        }

        override fun clearDurable(): Boolean {
            if (!clearSucceeds) return false
            state = LowToHighDemandState()
            return true
        }
    }
}
