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
        LowToHighDemandGate.replaceStateStoreForTest(MemoryLowToHighDemandStateStore())
        LowToHighDemandGate.replaceInfoLoggerForTest { }
    }

    @After
    fun tearDown() {
        LowToHighDemandGate.clearAll("test cleanup")
        LowToHighDemandGate.resetInfoLoggerForTest()
        LowToHighDemandGate.resetStateStoreForTest()
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
    fun effectiveEmptyFrontPositions_shouldExcludePersistedInitializationAndSupplyGaps() {
        LowToHighDemandGate.recordNonDemandEmpty(5, "initialization_gap")
        LowToHighDemandGate.recordNonDemandEmpty(6, "supply_gap")

        assertEquals(
            setOf(1, 2, 3, 4, 7, 8, 9),
            LowToHighDemandGate.effectiveEmptyFrontPositions(frontPans(emptyPositions = (1..9).toSet()))
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldStillCountSaleEmptyPositionsThatHaveReturnBindings() {
        assertTrue(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans(emptyPositions = (1..8).toSet()),
                lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenEightEmptyPositionsAreOnlyNonDemandGaps() {
        (1..8).forEach { LowToHighDemandGate.recordNonDemandEmpty(it, "manual_clear") }

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans(emptyPositions = (1..8).toSet()),
                lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
            )
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
    fun canTriggerLowToHigh_shouldRejectWhenEightEmptyPositionsIncludeExpiredDiscardSlots() {
        LowToHighDemandGate.recordExpiredDiscard(5)
        LowToHighDemandGate.recordExpiredDiscard(6)

        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldAllowWhenEightEmptyPositionsComeFromRealDemand() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertTrue(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenExactlySevenEmptyPositionsAreNotGreaterThanSeven() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenOnly13To15IsBaking() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(
                    activeBakingPositions = (13..15).toSet(),
                    keepWarmPositions = (16..18).toSet()
                )
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenOnly16To18IsBaking() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(
                    activeBakingPositions = (16..18).toSet(),
                    keepWarmPositions = (13..15).toSet()
                )
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldRejectWhenLowHeatAreaIsKeepWarmInsteadOfBaking() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertFalse(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(activeBakingPositions = emptySet(), keepWarmPositions = (13..18).toSet())
            )
        )
    }

    @Test
    fun canTriggerLowToHigh_shouldAllowWhenLowHeatAreaIsWaitingReturnKeepWarm() {
        val frontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertTrue(
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = frontPans,
                lowHeatPans = lowHeatPans(
                    activeBakingPositions = emptySet(),
                    waitingReturnPositions = (13..18).toSet()
                )
            )
        )
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldRecordFrontPositionAndEnterHighToLowTransition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 0
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 5)

        assertEquals(2, config.modeType)
        assertEquals(2, config.transitionMode)
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

        assertEquals(2, config.modeType)
        assertEquals(2, config.transitionMode)
        assertEquals(false, result.changedModeState)
        assertEquals(true, result.blockedFrontPositionsChanged)
        assertEquals(setOf(5), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun markExpiredDiscardAndFoldToLow_shouldTrackHighFrontDiscardPosition() {
        val config = AppConfigBean().apply {
            modeType = 2
            transitionMode = 0
        }

        val result = LowToHighDemandGate.markExpiredDiscardAndFoldToLow(config, positionSn = 10)

        assertEquals(2, config.modeType)
        assertEquals(2, config.transitionMode)
        assertEquals(true, result.changedModeState)
        assertEquals(setOf(10), LowToHighDemandGate.blockedFrontPositions())
    }

    @Test
    fun blockedEmptyFrontPositions_shouldKeepHighFrontBlocksWhenCheckingLowFrontOnly() {
        LowToHighDemandGate.recordExpiredDiscard(10)

        val lowFrontPans = frontPans(emptyPositions = setOf(1, 2, 3, 4, 5, 6, 7))

        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), LowToHighDemandGate.effectiveEmptyFrontPositions(lowFrontPans))
        assertEquals(setOf(10), LowToHighDemandGate.blockedFrontPositions())
        assertEquals(
            setOf(10),
            LowToHighDemandGate.blockedEmptyFrontPositions(
                (1..21).map { position ->
                    KaoPan().apply {
                        positionSn = position
                        isHasSausage = position != 10
                    }
                }
            )
        )
    }

    private fun frontPans(emptyPositions: Set<Int>): List<KaoPan> =
        (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position !in emptyPositions
            }
        }

    private fun lowHeatPans(
        activeBakingPositions: Set<Int>,
        keepWarmPositions: Set<Int> = emptySet(),
        waitingReturnPositions: Set<Int> = emptySet()
    ): List<KaoPan> =
        (13..18).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position in activeBakingPositions ||
                    position in keepWarmPositions ||
                    position in waitingReturnPositions
                startTime = if (position in activeBakingPositions) 1_000L else 0L
                holdingTime = if (position in keepWarmPositions || position in waitingReturnPositions) 2_000L else 0L
                status = when (position) {
                    in keepWarmPositions -> 2
                    in waitingReturnPositions -> 3
                    in activeBakingPositions -> 1
                    else -> 0
                }
            }
        }

    private class MemoryLowToHighDemandStateStore : LowToHighDemandStateStore {
        private var state = LowToHighDemandState()

        override fun load(): LowToHighDemandLoadResult = LowToHighDemandLoadResult(
            LowToHighDemandLoadStatus.PRESENT,
            state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
        )

        override fun saveDurable(state: LowToHighDemandState): Boolean {
            this.state = state.copy(blockedFrontPositions = state.blockedFrontPositions.toMutableSet())
            return true
        }

        override fun clearDurable(): Boolean {
            state = LowToHighDemandState()
            return true
        }
    }
}
