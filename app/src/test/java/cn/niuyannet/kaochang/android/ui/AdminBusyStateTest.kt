package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminBusyStateTest {
    @Test
    fun busyTokenKeepsAdminPageBusyUntilFinished() {
        var nowMs = 1_000L
        val state = AdminBusyState(staleMs = 10 * 60_000L) { nowMs }

        val token = state.begin("清空烤盘")

        assertTrue(state.isBusy())
        assertEquals(1, state.activeCount())
        state.finish(token)
        assertFalse(state.isBusy())
        assertEquals(0, state.activeCount())
    }

    @Test
    fun staleBusyTokenIsPrunedAfterConfiguredTimeout() {
        var nowMs = 1_000L
        val state = AdminBusyState(staleMs = 10 * 60_000L) { nowMs }
        state.begin("拍照识别")
        nowMs += 10 * 60_000L + 1
        val staleReasons = mutableListOf<String>()

        val busy = state.isBusy { token, _ ->
            staleReasons += token.reason
        }

        assertFalse(busy)
        assertEquals(listOf("拍照识别"), staleReasons)
        assertEquals(0, state.activeCount())
    }
}
