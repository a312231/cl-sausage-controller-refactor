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

    @Test
    fun legacy201_shouldRequireRunningBeforeIdleMeansCompleted() {
        assertEquals(Legacy201Decision.WaitingForStart, Legacy201Policy.evaluateAfterCommand(0, hasObservedRunning = false))
        assertEquals(Legacy201Decision.Running, Legacy201Policy.evaluateAfterCommand(1, hasObservedRunning = false))
        assertEquals(Legacy201Decision.Success, Legacy201Policy.evaluateAfterCommand(0, hasObservedRunning = true))
        assertEquals(Legacy201Decision.BusyOrError, Legacy201Policy.evaluateAfterCommand(2, hasObservedRunning = true))
    }

    @Test
    fun moveStatusRecovery_shouldRequireTwoIdle201Readings() {
        assertEquals(false, MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(listOf(0)))
        assertEquals(true, MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(listOf(0, 0)))
        assertEquals(false, MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(listOf(0, 1)))
        assertEquals(false, MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(listOf(-1, 0)))
    }

    @Test
    fun zone3SelfClean_shouldNotMarkPendingWhenFourFNoOpSuppressed() {
        assertEquals(
            false,
            Zone3SelfCleanPolicy.shouldMarkPending(
                zone3Dirty = 1,
                zone3CleanPending = 0,
                zone3CleanSuppressedFor4F = 1,
                zone3HasSausage = false
            )
        )
        assertEquals(
            true,
            Zone3SelfCleanPolicy.shouldMarkPending(
                zone3Dirty = 1,
                zone3CleanPending = 0,
                zone3CleanSuppressedFor4F = 0,
                zone3HasSausage = false
            )
        )
    }

    @Test
    fun selfCleanNoOp_shouldBeRecognizedByStableMessage() {
        assertEquals(true, FourFSelfCleanNoOpPolicy.isNoOp("4f_self_clean_no_op"))
        assertEquals(false, FourFSelfCleanNoOpPolicy.isNoOp("201=2"))
    }
}
