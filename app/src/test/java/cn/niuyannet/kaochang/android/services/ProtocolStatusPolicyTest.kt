package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolStatusPolicyTest {

    @Test
    fun panMove203_shouldRequireRunningOrClearedCommandBeforeCompleted() {
        assertEquals(PanMove203Decision.WaitingForStart, PanMove203Policy.evaluate(0, hasObservedRunning = false))
        assertEquals(PanMove203Decision.Running, PanMove203Policy.evaluate(1, hasObservedRunning = false))
        assertEquals(PanMove203Decision.WaitingForStart, PanMove203Policy.evaluate(2, hasObservedRunning = false))
        assertEquals(PanMove203Decision.Success, PanMove203Policy.evaluate(2, hasObservedRunning = true))
        assertEquals(
            PanMove203Decision.Success,
            PanMove203Policy.evaluate(2, hasObservedRunning = false, commandRegisterValue = 0)
        )
        assertEquals(
            PanMove203Decision.WaitingForStart,
            PanMove203Policy.evaluate(2, hasObservedRunning = false, commandRegisterValue = 0x0102)
        )
    }

    @Test
    fun panMove203_shouldFailWhenRunningFallsBackToIdleOrReturnsFourFError() {
        assertEquals(PanMove203Decision.Failed, PanMove203Policy.evaluate(0, hasObservedRunning = true))
        assertEquals(PanMove203Decision.Failed, PanMove203Policy.evaluate(0x00F8, hasObservedRunning = false))
        assertEquals(PanMove203Decision.Failed, PanMove203Policy.evaluate(0x00FB, hasObservedRunning = true))
    }

    @Test
    fun panMove203_shouldPollFastEnoughToObserveShortRunningWindow() {
        assertEquals(500L, PanMove203Policy.pollDelayMs)
        assertEquals(2_000L, PanMove203Policy.waitOtherActionDelayMs)
    }
}
