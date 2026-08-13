package cn.niuyannet.kaochang.scheduler

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementBatchHeatPositionStrategyTest {

    private val strategy = LegacyFixedBatchHeatPositionStrategy()

    @Test
    fun strategy_shouldExposeStableVersionedIdAndDerivedCounts() {
        assertEquals(StrategyId("legacy-fixed-batch-heat-position-v1"), strategy.id)
        assertEquals(0, LowHeatBatchPlan(LowHeatAction.NONE).requestedCount)
        assertEquals(3, LowHeatBatchPlan(LowHeatAction.START_13_15).requestedCount)
        assertEquals(3, LowHeatBatchPlan(LowHeatAction.START_16_18).requestedCount)
        assertEquals(0, LowHeatBatchPlan(LowHeatAction.STOP_ZONE_2).requestedCount)
        assertEquals(0, HighHeatBatchPlan(HighHeatAction.NONE).requestedCount)
        assertEquals(9, HighHeatBatchPlan(HighHeatAction.START_25_33).requestedCount)
        assertTrue(HighHeatBatchPlan(HighHeatAction.START_25_33).shouldStart)
    }

    @Test
    fun lowHeat_shouldMatchLegacyForPhysicalRangeAndIntWraparound() {
        for (front in 0..9) {
            for (first in 0..3) {
                for (second in 0..3) {
                    assertEquals(
                        legacyLowHeat(front, first, second),
                        strategy.planLowHeat(LowHeatBatchInput(front, first, second)).action
                    )
                }
            }
        }

        val edgeCases = listOf(
            Triple(-1, 3, 3),
            Triple(10, 3, 3),
            Triple(0, Int.MIN_VALUE, Int.MIN_VALUE + 6),
            Triple(0, Int.MIN_VALUE, Int.MIN_VALUE),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        )
        edgeCases.forEach { (front, first, second) ->
            assertEquals(
                legacyLowHeat(front, first, second),
                strategy.planLowHeat(LowHeatBatchInput(front, first, second)).action
            )
        }
        assertEquals(
            LowHeatAction.STOP_ZONE_2,
            strategy.planLowHeat(LowHeatBatchInput(0, Int.MIN_VALUE, Int.MIN_VALUE + 6)).action
        )
        assertEquals(
            LowHeatAction.NONE,
            strategy.planLowHeat(LowHeatBatchInput(0, Int.MIN_VALUE, Int.MIN_VALUE)).action
        )
    }

    @Test
    fun highHeatSteady_shouldMatchLegacyThresholdForPhysicalRange() {
        for (front in 0..21) {
            for (heat in 0..9) {
                val expected = if (front >= 9 && heat == 9) {
                    HighHeatAction.START_25_33
                } else {
                    HighHeatAction.NONE
                }
                assertEquals(
                    expected,
                    strategy.planHighHeatSteady(HighHeatSteadyInput(front, heat)).action
                )
            }
        }
    }

    @Test
    fun positions_shouldPreserveCandidateOrderAndAllowPartialPlan() {
        val plan = strategy.planPositions(
            SupplementPositionInput(
                requestedCount = 9,
                targetCandidates = listOf(25, 26, 27, 28),
                returnCandidates = listOf(3, 1, 8)
            )
        )

        assertEquals(
            listOf(
                SupplementPositionPair(25, 3),
                SupplementPositionPair(26, 1),
                SupplementPositionPair(27, 8)
            ),
            plan.pairs
        )
    }

    @Test
    fun positions_shouldFailClosedForInvalidSnapshots() {
        val invalidInputs = listOf(
            SupplementPositionInput(0, listOf(13), listOf(1)),
            SupplementPositionInput(-1, listOf(13), listOf(1)),
            SupplementPositionInput(3, listOf(13, 13, 14), listOf(1, 2, 3)),
            SupplementPositionInput(3, listOf(13, 14, 15), listOf(1, 1, 2)),
            SupplementPositionInput(3, listOf(0, 14, 15), listOf(1, 2, 3)),
            SupplementPositionInput(3, listOf(13, 14, 15), listOf(-1, 2, 3))
        )

        invalidInputs.forEach { input ->
            assertTrue(strategy.planPositions(input).pairs.isEmpty())
        }
    }

    @Test
    fun positions_shouldDefensivelyCopyInputAndExposeUnmodifiableOutput() {
        val targets = mutableListOf(13, 14, 15)
        val returns = mutableListOf(1, 2, 3)
        val input = SupplementPositionInput(3, targets, returns)
        targets[0] = 99
        returns[0] = 99

        val plan = strategy.planPositions(input)
        assertEquals(SupplementPositionPair(13, 1), plan.pairs.first())
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (input.targetCandidates as MutableList<Int>).add(16)
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.pairs as MutableList<SupplementPositionPair>).clear()
        }
    }

    @Test
    fun positions_shouldMatchIndependentOracleForTenThousandFixedSeedCases() {
        val random = Random(0x1C4BA7C1L)
        repeat(10_000) {
            val requestedCount = random.nextInt(15) - 2
            val targets = MutableList(random.nextInt(13)) { random.nextInt(40) - 3 }
            val returns = MutableList(random.nextInt(13)) { random.nextInt(30) - 3 }
            val expected = if (
                requestedCount <= 0 ||
                targets.any { it <= 0 } || returns.any { it <= 0 } ||
                targets.toSet().size != targets.size || returns.toSet().size != returns.size
            ) {
                emptyList()
            } else {
                val count = minOf(requestedCount, targets.size, returns.size)
                (0 until count).map { index ->
                    SupplementPositionPair(targets[index], returns[index])
                }
            }

            assertEquals(
                expected,
                strategy.planPositions(
                    SupplementPositionInput(requestedCount, targets, returns)
                ).pairs
            )
        }
    }

    private fun legacyLowHeat(front: Int, first: Int, second: Int): LowHeatAction {
        val combined = first + second
        if (front >= 3) {
            if (first == 3 && second == 3) {
                return LowHeatAction.START_13_15
            } else if (first == 3 && front >= 6 && second == 0) {
                return LowHeatAction.START_13_15
            } else if (second == 3 && front >= 6 && first == 0) {
                return LowHeatAction.START_16_18
            }
        } else if (combined == 6) {
            return LowHeatAction.STOP_ZONE_2
        }
        return LowHeatAction.NONE
    }
}
