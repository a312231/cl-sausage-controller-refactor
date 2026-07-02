package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanPhysicalStateBaselineStoreTest {

    @Test
    fun currentBaseline_shouldReturnNullWhenNoConfirmedSnapshotExists() {
        val storage = PanPhysicalStateBaselineStore.InMemoryStorage()

        PanPhysicalStateBaselineStore.withStorageForTest(storage) {
            assertNull(PanPhysicalStateBaselineStore.currentBaseline())
        }
    }

    @Test
    fun recordConfirmedSnapshot_shouldPersistSequenceHashAndServerPanDeltaSeq() {
        val storage = PanPhysicalStateBaselineStore.InMemoryStorage()

        PanPhysicalStateBaselineStore.withStorageForTest(storage) {
            PanPhysicalStateBaselineStore.recordConfirmedSnapshot(1234L, "hash-1234", 7L)

            val baseline = PanPhysicalStateBaselineStore.currentBaseline()
            assertEquals(1234L, baseline?.sequence)
            assertEquals("hash-1234", baseline?.snapshotHash)
            assertEquals(7L, baseline?.panDeltaSeq)
        }
    }

    @Test
    fun currentBaseline_shouldIgnoreLegacyStateWithoutServerPanDeltaSeq() {
        val storage = PanPhysicalStateBaselineStore.InMemoryStorage()

        PanPhysicalStateBaselineStore.withStorageForTest(storage) {
            storage.putLong("key_confirmed_snapshot_sequence", 1234L)
            storage.putString("key_confirmed_snapshot_hash", "hash-1234")

            assertNull(PanPhysicalStateBaselineStore.currentBaseline())
        }
    }
}
