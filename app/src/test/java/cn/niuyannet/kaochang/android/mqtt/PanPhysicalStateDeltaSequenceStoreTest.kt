package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class PanPhysicalStateDeltaSequenceStoreTest {

    @Test
    fun nextSequence_shouldStartFromOneAfterZeroServerSeqBaseline() {
        val storage = PanPhysicalStateDeltaSequenceStore.InMemoryStorage()

        PanPhysicalStateDeltaSequenceStore.withStorageForTest(storage) {
            PanPhysicalStateDeltaSequenceStore.resetToServerSequence(0L)

            assertEquals(1L, PanPhysicalStateDeltaSequenceStore.nextSequence())
            assertEquals(1L, PanPhysicalStateDeltaSequenceStore.currentSequence())
        }
    }

    @Test
    fun nextSequence_shouldContinueFromConfirmedServerSeq() {
        val storage = PanPhysicalStateDeltaSequenceStore.InMemoryStorage()

        PanPhysicalStateDeltaSequenceStore.withStorageForTest(storage) {
            PanPhysicalStateDeltaSequenceStore.resetToServerSequence(7L)

            assertEquals(8L, PanPhysicalStateDeltaSequenceStore.nextSequence())
            assertEquals(9L, PanPhysicalStateDeltaSequenceStore.nextSequence())
            assertEquals(9L, PanPhysicalStateDeltaSequenceStore.currentSequence())
        }
    }

    @Test
    fun resetToServerSequence_shouldAllowHttpBaselineToRecoverAfterLocalPublishGap() {
        val storage = PanPhysicalStateDeltaSequenceStore.InMemoryStorage()

        PanPhysicalStateDeltaSequenceStore.withStorageForTest(storage) {
            PanPhysicalStateDeltaSequenceStore.resetToServerSequence(7L)
            assertEquals(8L, PanPhysicalStateDeltaSequenceStore.nextSequence())

            PanPhysicalStateDeltaSequenceStore.resetToServerSequence(7L)
            assertEquals(8L, PanPhysicalStateDeltaSequenceStore.nextSequence())
        }
    }
}
