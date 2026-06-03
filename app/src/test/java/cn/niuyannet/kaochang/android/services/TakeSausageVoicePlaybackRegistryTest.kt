package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeSausageVoicePlaybackRegistryTest {

    @Test
    fun beginReplacesPreviousToken() {
        val registry = TakeSausageVoicePlaybackRegistry()

        val first = registry.begin()
        val second = registry.begin()

        assertFalse(registry.isActive(first))
        assertTrue(registry.isActive(second))
    }

    @Test
    fun staleCallbackCannotFinishCurrentPlayback() {
        val registry = TakeSausageVoicePlaybackRegistry()

        val first = registry.begin()
        val second = registry.begin()

        assertFalse(registry.finishIfActive(first))
        assertTrue(registry.isActive(second))
    }

    @Test
    fun staleTimeoutCannotFinishCurrentPlayback() {
        val registry = TakeSausageVoicePlaybackRegistry()

        val oldTimeoutToken = registry.begin()
        val currentToken = registry.begin()

        assertFalse(registry.finishIfActive(oldTimeoutToken))
        assertTrue(registry.isActive(currentToken))
    }

    @Test
    fun activeCallbackFinishesPlayback() {
        val registry = TakeSausageVoicePlaybackRegistry()

        val token = registry.begin()

        assertTrue(registry.finishIfActive(token))
        assertFalse(registry.hasActive())
    }

    @Test
    fun clearActiveRemovesCurrentPlayback() {
        val registry = TakeSausageVoicePlaybackRegistry()

        registry.begin()
        registry.clearActive()

        assertFalse(registry.hasActive())
    }
}
