package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LowToHighDemandGateTest {

    @Before
    fun setUp() {
        LowToHighDemandGate.replaceInfoLoggerForTest { }
    }

    @After
    fun tearDown() {
        LowToHighDemandGate.clearAll("test cleanup")
        LowToHighDemandGate.resetInfoLoggerForTest()
    }

    @Test
    fun effectiveEmptyFrontPositions_shouldExcludeExpiredDiscardPositions() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)

        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertEquals(
            setOf(1, 2, 3, 4, 7),
            LowToHighDemandGate.effectiveEmptyFrontPositions(frontPans)
        )
    }

    @Test
    fun effectiveEmptyFrontPositions_shouldIntersectBlockedPositionsWithCurrentEmptyPans() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)

        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 6, 7))

        assertEquals(
            setOf(1, 2, 3, 4, 7),
            LowToHighDemandGate.effectiveEmptyFrontPositions(frontPans)
        )
        assertEquals(setOf(6), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun clearPosition_shouldAllowReopenedPositionToCountForDemandAgain() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.clearPosition(5, "supplement succeeded")

        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertEquals(
            setOf(1, 2, 3, 4, 5, 6, 7),
            LowToHighDemandGate.effectiveEmptyFrontPositions(frontPans)
        )
    }

    @Test
    fun clearPositions_shouldClearMaintenanceTargetsOnly() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)
        LowToHighDemandGate.clearPositions(listOf(5, 10), "maintenance targets")

        assertEquals(setOf(6), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun clearAll_shouldClearBatchResetState() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)
        LowToHighDemandGate.clearAll("batch reset")

        assertEquals(emptySet<Int>(), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenSevenEmptyPositionsIncludeExpiredDiscardSlots() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)

        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatEmptyCount = 0
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldAllowWhenSevenEmptyPositionsComeFromRealDemand() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertTrue(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatEmptyCount = 0
            )
        )
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldRecordFrontPositionAndCollapseTransition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 1
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 5)

        assertEquals(1, config.modeType)
        assertEquals(0, config.transitionMode)
        assertEquals(true, result.changedModeState)
        assertEquals(setOf(5), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldRecordFrontPositionWithoutModeChangeWhenAlreadyLowMode() {
        val config = AppConfigBean().apply {
            modeType = 1
            transitionMode = 0
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 5)

        assertEquals(1, config.modeType)
        assertEquals(0, config.transitionMode)
        assertEquals(false, result.changedModeState)
        assertEquals(true, result.blockedFrontPositionsChanged)
        assertEquals(setOf(5), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldCollapseHighToLowTransitionAndRecordFrontPosition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 2
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 5)

        assertEquals(1, config.modeType)
        assertEquals(0, config.transitionMode)
        assertEquals(true, result.changedModeState)
        assertEquals(true, result.blockedFrontPositionsChanged)
        assertEquals(setOf(5), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldCollapseHighToLowTransitionWithoutBlockingNonFrontPosition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 2
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 10)

        assertEquals(1, config.modeType)
        assertEquals(0, config.transitionMode)
        assertEquals(true, result.changedModeState)
        assertEquals(emptySet<Int>(), LowToHighDemandGate.blockedFrontPositions())
    }

    private fun frontPans(emptyPositions: Set<Int>): List<KaoPan> =
        (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position !in emptyPositions
            }
        }
}
