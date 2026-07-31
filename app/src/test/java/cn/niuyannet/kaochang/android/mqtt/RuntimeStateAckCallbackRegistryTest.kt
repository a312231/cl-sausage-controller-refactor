package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateAckCallbackRegistryTest {

    @Test
    fun newerAck_shouldNotSuppressOlderTimeout() {
        val registry = RuntimeStateAckCallbackRegistry()
        val completed = mutableListOf<SendServerHelper.RuntimeStateServerAck>()
        val older = registry.createListener(completed::add)
        val newer = registry.createListener(completed::add)

        assertTrue(registry.register(100L, 1, 0, older))
        assertTrue(registry.register(101L, 3, 2, newer))
        assertTrue(registry.complete(101L, ack(101L)))
        assertTrue(registry.timeout(100L))

        assertEquals(listOf(101L, 100L), completed.map { it.runtimeSeq })
        assertEquals("ack_timeout", completed.last().reason)
        assertFalse(completed.last().received)
    }

    @Test
    fun sameSequenceReregister_shouldNotReplaceOriginalWaiter() {
        val registry = RuntimeStateAckCallbackRegistry()
        var originalCompletions = 0
        var replacementCompletions = 0
        val original = registry.createListener { originalCompletions++ }
        val replacement = registry.createListener { replacementCompletions++ }

        assertTrue(registry.register(100L, 1, 0, original))
        assertFalse(registry.register(100L, 1, 0, replacement))
        assertTrue(registry.timeout(100L))

        assertEquals(1, originalCompletions)
        assertEquals(0, replacementCompletions)
    }

    @Test
    fun ackAndTimeout_shouldCompleteOnlyOnce() {
        val registry = RuntimeStateAckCallbackRegistry()
        var completions = 0
        val listener = registry.createListener { completions++ }

        assertTrue(registry.register(100L, 1, 0, listener))
        assertTrue(registry.complete(100L, ack(100L)))
        assertFalse(registry.timeout(100L))

        assertEquals(1, completions)
    }

    @Test
    fun cancel_shouldRemoveWaiterWithoutCallback() {
        val registry = RuntimeStateAckCallbackRegistry()
        var completions = 0
        val listener = registry.createListener { completions++ }

        assertTrue(registry.register(100L, 1, 0, listener))
        assertTrue(registry.cancel(listener))
        assertFalse(registry.complete(100L, ack(100L)))
        assertFalse(registry.timeout(100L))

        assertEquals(0, completions)
    }

    private fun ack(runtimeSeq: Long) = SendServerHelper.RuntimeStateServerAck(
        runtimeSeq = runtimeSeq,
        received = true,
        applied = true,
        reason = "applied",
        onlineStatus = 1,
        errorStatus = 0
    )
}
