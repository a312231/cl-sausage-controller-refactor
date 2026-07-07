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
    }

    @Test
    fun testPagePanMove_shouldNotTreatCompletedAsSuccessBeforeRunningObserved() {
        assertEquals(PanMove203Decision.WaitingForStart, PanMove203Policy.evaluate(2, hasObservedRunning = false))
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
    fun testPagePanMove_shouldEncodeSourceAndTargetIntoRegister202() {
        assertEquals(0x0102, TestPagePanMoveCommandPolicy.encodeCommand(sourcePosition = 1, targetPosition = 2))
        assertEquals(0x2120, TestPagePanMoveCommandPolicy.encodeCommand(sourcePosition = 33, targetPosition = 32))
    }

    @Test
    fun testPagePanMove_shouldValidateOneToThirtyThreeAndRejectSamePosition() {
        assertEquals(true, TestPagePanMoveCommandPolicy.validate(sourcePosition = 1, targetPosition = 33).ok)
        assertEquals("源烤盘位必须在 1~33 之间", TestPagePanMoveCommandPolicy.validate(0, 2).message)
        assertEquals("目标烤盘位必须在 1~33 之间", TestPagePanMoveCommandPolicy.validate(1, 34).message)
        assertEquals("源烤盘位和目标烤盘位不能相同", TestPagePanMoveCommandPolicy.validate(8, 8).message)
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
    fun moveStatusRecovery_shouldRejectIdleRecoveryWhenActionGroupIsBusy() {
        assertEquals(
            false,
            MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(
                statusReadings = listOf(0, 0),
                actionGroupIdle = false
            )
        )
        assertEquals(
            true,
            MoveStatusRecoveryPolicy.canRecoverIdleFromLegacy201(
                statusReadings = listOf(0, 0),
                actionGroupIdle = true
            )
        )
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
