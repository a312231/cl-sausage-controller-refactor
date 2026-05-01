package cn.niuyannet.kaochang.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSnapshotSequenceStoreTest {

    @Test
    fun nextSequence_shouldStartFromOneWhenNoPreviousSequenceExists() {
        val storage = CloudSnapshotSequenceStore.InMemoryStorage()

        CloudSnapshotSequenceStore.withStorageForTest(storage) {
            assertEquals(1L, CloudSnapshotSequenceStore.nextSequence())
        }
    }

    @Test
    fun nextSequence_shouldPersistAndIncrementSequence() {
        val storage = CloudSnapshotSequenceStore.InMemoryStorage()

        CloudSnapshotSequenceStore.withStorageForTest(storage) {
            assertEquals(1L, CloudSnapshotSequenceStore.nextSequence())
            assertEquals(2L, CloudSnapshotSequenceStore.nextSequence())
            assertEquals(2L, CloudSnapshotSequenceStore.currentSequence())
        }
    }
}
