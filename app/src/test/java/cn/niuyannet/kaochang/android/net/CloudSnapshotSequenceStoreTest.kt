package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSnapshotSequenceStoreTest {

    @Test
    fun nextSequence_shouldUseCurrentTimeWhenNoPreviousSequenceExists() {
        val storage = CloudSnapshotSequenceStore.InMemoryStorage()

        CloudSnapshotSequenceStore.withStorageAndClockForTest(storage, FixedClock(1778159017108L)) {
            assertEquals(1778159017108L, CloudSnapshotSequenceStore.nextSequence())
        }
    }

    @Test
    fun nextSequence_shouldIncrementPreviousWhenPreviousIsGreaterThanCurrentTime() {
        val storage = CloudSnapshotSequenceStore.InMemoryStorage()

        CloudSnapshotSequenceStore.withStorageAndClockForTest(storage, FixedClock(1000L)) {
            assertEquals(1000L, CloudSnapshotSequenceStore.nextSequence())
            assertEquals(1001L, CloudSnapshotSequenceStore.nextSequence())
            assertEquals(1001L, CloudSnapshotSequenceStore.currentSequence())
        }
    }

    @Test
    fun nextSequence_shouldRemainMonotonicWhenClockMovesBackward() {
        val storage = CloudSnapshotSequenceStore.InMemoryStorage()
        val clock = MutableClock(2000L)

        CloudSnapshotSequenceStore.withStorageAndClockForTest(storage, clock) {
            assertEquals(2000L, CloudSnapshotSequenceStore.nextSequence())
            clock.nowMillis = 1500L
            assertEquals(2001L, CloudSnapshotSequenceStore.nextSequence())
        }
    }

    private class FixedClock(private val nowMillis: Long) : CloudSnapshotSequenceStore.Clock {
        override fun nowMillis(): Long = nowMillis
    }

    private class MutableClock(var nowMillis: Long) : CloudSnapshotSequenceStore.Clock {
        override fun nowMillis(): Long = nowMillis
    }
}
