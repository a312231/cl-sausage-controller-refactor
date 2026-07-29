package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalLogOutboxTest {

    @Test
    fun enqueue_shouldKeepIndependentFailuresWithoutCoalescing() {
        val storage = CriticalLogOutbox.InMemoryStorage()
        val outbox = CriticalLogOutbox(storage)

        assertTrue(outbox.enqueue(entry("id-1", "同一动作失败", 1_000L), nowMs = 1_000L))
        assertTrue(outbox.enqueue(entry("id-2", "同一动作失败", 1_001L), nowMs = 1_001L))

        assertEquals(listOf("id-1", "id-2"), outbox.peekBatch(nowMs = 1_001L, limit = 10).map { it.id })
    }

    @Test
    fun newInstance_shouldRecoverPersistedEntriesAndRemoveOnlyAcknowledgedEntry() {
        val storage = CriticalLogOutbox.InMemoryStorage()
        CriticalLogOutbox(storage).apply {
            enqueue(entry("id-1", "失败1", 1_000L), nowMs = 1_000L)
            enqueue(entry("id-2", "失败2", 1_001L), nowMs = 1_001L)
        }

        val restarted = CriticalLogOutbox(storage)
        assertEquals(listOf("id-1", "id-2"), restarted.peekBatch(1_002L, 10).map { it.id })

        restarted.remove("id-1", nowMs = 1_003L)

        assertEquals(listOf("id-2"), restarted.peekBatch(1_003L, 10).map { it.id })
    }

    @Test
    fun enqueue_shouldPruneExpiredEntries() {
        val storage = CriticalLogOutbox.InMemoryStorage()
        val outbox = CriticalLogOutbox(storage, maxAgeMs = 1_000L)

        outbox.enqueue(entry("expired", "旧失败", 1_000L), nowMs = 1_000L)
        outbox.enqueue(entry("fresh", "新失败", 2_001L), nowMs = 2_001L)

        assertEquals(listOf("fresh"), outbox.peekBatch(2_001L, 10).map { it.id })
    }

    @Test
    fun peekBatch_shouldKeepEntriesWhenWallClockMovesBackward() {
        val storage = CriticalLogOutbox.InMemoryStorage()
        val outbox = CriticalLogOutbox(storage, maxAgeMs = 1_000L)

        outbox.enqueue(entry("future-after-clock-change", "关键失败", 10_000L), nowMs = 10_000L)

        assertEquals(
            listOf("future-after-clock-change"),
            outbox.peekBatch(nowMs = 5_000L, limit = 10).map { it.id }
        )
    }

    @Test
    fun enqueue_shouldKeepNewestEntriesWithinCountAndByteBounds() {
        val countStorage = CriticalLogOutbox.InMemoryStorage()
        val countBounded = CriticalLogOutbox(countStorage, maxCount = 2, maxBytes = 10_000)
        countBounded.enqueue(entry("id-1", "a", 1L), 1L)
        countBounded.enqueue(entry("id-2", "b", 2L), 2L)
        countBounded.enqueue(entry("id-3", "c", 3L), 3L)
        assertEquals(listOf("id-2", "id-3"), countBounded.peekBatch(3L, 10).map { it.id })

        val byteStorage = CriticalLogOutbox.InMemoryStorage()
        val sample = entry("id-1", "x".repeat(80), 1L)
        val byteBounded = CriticalLogOutbox(
            byteStorage,
            maxCount = 10,
            maxBytes = sample.storageBytes() + 8
        )
        assertTrue(byteBounded.enqueue(sample, 1L))
        assertTrue(byteBounded.enqueue(entry("id-2", "y".repeat(80), 2L), 2L))
        assertEquals(listOf("id-2"), byteBounded.peekBatch(2L, 10).map { it.id })
        assertFalse(byteBounded.enqueue(entry("oversized", "z".repeat(1_000), 3L), 3L))
        assertEquals(listOf("id-2"), byteBounded.peekBatch(3L, 10).map { it.id })
    }

    private fun entry(id: String, content: String, nowMs: Long) = CriticalLogEntry(
        id = id,
        level = "E",
        tag = "KaoChang",
        content = content,
        reportedAt = nowMs,
        enqueuedAt = nowMs
    )
}
