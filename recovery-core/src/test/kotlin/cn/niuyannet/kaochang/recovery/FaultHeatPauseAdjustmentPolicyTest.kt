package cn.niuyannet.kaochang.recovery

import java.lang.reflect.Modifier
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaultHeatPauseAdjustmentPolicyTest {

    @Test
    fun normalPause_shiftsOnlyActualBakingOverlapAndExpiresHoldingStrictlyAfterCloseTime() {
        val input = input(
            nowMs = 10_000L,
            pauseStartedAtMs = 4_000L,
            bakingPauseAbortMs = 6_000L,
            pans = listOf(
                pan(3, startTimeMs = 2_000L),
                pan(1, startTimeMs = 7_000L),
                pan(4, startTimeMs = 1_000L, holdingTimeMs = 8_000L, closeTimeMs = 2_000L),
                pan(2, startTimeMs = 1_000L, holdingTimeMs = 7_999L, closeTimeMs = 2_000L),
                pan(5, hasSausage = false)
            )
        )

        val result = FaultHeatPauseAdjustmentPolicy.calculate(input)

        assertEquals(6_000L, result.pauseDurationMs)
        assertEquals(listOf(1, 3), result.shiftedPositions)
        assertEquals(listOf(2, 4), result.holdingPositions)
        assertEquals(listOf(2), result.pendingDiscardPositions)
        assertEquals(mapOf(1 to 10_000L, 3 to 8_000L), result.targetStartTimes)
        assertNull(result.alert)
        assertTrue(!result.failClosed)
    }

    @Test
    fun nonPositiveCloseTime_neverExpiresHolding() {
        val result = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                pans = listOf(
                    pan(1, holdingTimeMs = 1L, closeTimeMs = 0L),
                    pan(2, holdingTimeMs = 1L, closeTimeMs = -1L)
                )
            )
        )

        assertEquals(listOf(1, 2), result.holdingPositions)
        assertTrue(result.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun holdingExpiry_preservesNativeLongSubtractionWraparound() {
        val result = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                nowMs = Long.MIN_VALUE,
                pauseStartedAtMs = Long.MIN_VALUE,
                pans = listOf(
                    pan(1, holdingTimeMs = 1L, closeTimeMs = 1L),
                    pan(2, holdingTimeMs = Long.MAX_VALUE, closeTimeMs = 1L)
                )
            )
        )

        assertEquals(listOf(1, 2), result.holdingPositions)
        assertEquals(listOf(1), result.pendingDiscardPositions)
    }

    @Test
    fun resultNonSyntheticConstructors_arePrivateAtJvmBoundary() {
        val constructors = FaultHeatPauseAdjustment::class.java.declaredConstructors
            .filterNot { it.isSynthetic }

        assertTrue(constructors.isNotEmpty())
        assertTrue(constructors.all { Modifier.isPrivate(it.modifiers) })
    }

    @Test
    fun thresholds_areStrictAndGlobalFailuresDiscardEveryBakingPanWithoutTargets() {
        val exactlyAbort = FaultHeatPauseAdjustmentPolicy.calculate(
            input(nowMs = 10_000L, pauseStartedAtMs = 4_000L, bakingPauseAbortMs = 6_000L)
        )
        assertEquals(listOf(1), exactlyAbort.shiftedPositions)

        val overAbort = FaultHeatPauseAdjustmentPolicy.calculate(
            input(nowMs = 10_001L, pauseStartedAtMs = 4_000L, bakingPauseAbortMs = 6_000L)
        )
        assertEquals(listOf(1), overAbort.pendingDiscardPositions)
        assertEquals(mapOf(1 to 1_000L), overAbort.targetStartTimes)
        assertTrue(!overAbort.failClosed)

        val exactlyAnomaly = FaultHeatPauseAdjustmentPolicy.calculate(
            input(nowMs = 14_000L, pauseStartedAtMs = 4_000L, pauseAnomalyMs = 10_000L)
        )
        assertNull(exactlyAnomaly.alert)

        val overAnomaly = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                nowMs = 14_001L,
                pauseStartedAtMs = 4_000L,
                pauseAnomalyMs = 10_000L,
                pans = listOf(pan(33, startTimeMs = 1_000L), pan(34, startTimeMs = 2_000L))
            )
        )
        assertEquals(FaultHeatPauseAlert.ANOMALY_TOO_LONG, overAnomaly.alert)
        assertTrue(overAnomaly.failClosed)
        assertEquals(listOf(33, 34), overAnomaly.pendingDiscardPositions)
        assertTrue(overAnomaly.targetStartTimes.isEmpty())

        val outsidePhysicalRangeOnly = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                nowMs = 14_001L,
                pauseStartedAtMs = 4_000L,
                pauseAnomalyMs = 10_000L,
                pans = listOf(pan(34, startTimeMs = 2_000L))
            )
        )
        assertNull(outsidePhysicalRangeOnly.alert)
    }

    @Test
    fun clockRegressionOrFutureBakingStart_failsClosedWithClockAlert() {
        val regressed = FaultHeatPauseAdjustmentPolicy.calculate(
            input(nowMs = 3_999L, pauseStartedAtMs = 4_000L)
        )
        assertEquals(FaultHeatPauseAlert.CLOCK_REGRESSED, regressed.alert)
        assertTrue(regressed.failClosed)

        val futureBaking = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                nowMs = 10_000L,
                pauseStartedAtMs = 4_000L,
                pans = listOf(
                    pan(2, startTimeMs = 10_001L),
                    pan(1, startTimeMs = 2_000L),
                    pan(3, startTimeMs = 1_000L, holdingTimeMs = 9_000L, closeTimeMs = 500L)
                )
            )
        )
        assertEquals(FaultHeatPauseAlert.CLOCK_REGRESSED, futureBaking.alert)
        assertTrue(futureBaking.failClosed)
        assertEquals(listOf(1, 2, 3), futureBaking.pendingDiscardPositions)
        assertEquals(listOf(3), futureBaking.holdingPositions)
        assertTrue(futureBaking.targetStartTimes.isEmpty())
    }

    @Test
    fun inputAndOutputCollections_areDefensiveAndUnmodifiable() {
        val mutablePans = mutableListOf(pan(1, startTimeMs = 1_000L))
        val input = input(pans = mutablePans)
        mutablePans.clear()

        val result = FaultHeatPauseAdjustmentPolicy.calculate(input)

        assertEquals(listOf(1), result.shiftedPositions)
        expectUnsupported { (input.pans as MutableList<FaultHeatPanSnapshot>).clear() }
        expectUnsupported {
            (result.panDecisions as MutableList<FaultHeatPanAdjustmentDecision>).clear()
        }
        expectUnsupported { (result.shiftedPositions as MutableList<Int>).clear() }
        expectUnsupported { (result.targetStartTimes as MutableMap<Int, Long>).clear() }
    }

    @Test
    fun duplicatePositions_preserveListsAndUseLastTargetForMap() {
        val result = FaultHeatPauseAdjustmentPolicy.calculate(
            input(
                nowMs = 10_000L,
                pauseStartedAtMs = 5_000L,
                bakingPauseAbortMs = 4_000L,
                pans = listOf(
                    pan(7, startTimeMs = 1_000L),
                    pan(7, startTimeMs = 8_000L),
                    pan(8, startTimeMs = 1_000L, holdingTimeMs = 1_000L, closeTimeMs = 1L),
                    pan(8, startTimeMs = 1_000L, holdingTimeMs = 1_000L, closeTimeMs = 1L)
                )
            )
        )

        assertEquals(listOf(7), result.shiftedPositions)
        assertEquals(listOf(8, 8), result.holdingPositions)
        assertEquals(listOf(7, 8), result.pendingDiscardPositions)
        assertEquals(mapOf(7 to 10_000L), result.targetStartTimes)
        assertEquals(listOf(0, 1, 2, 3), result.panDecisions.map { it.inputIndex })
    }

    @Test
    fun representativeLongOverflowCases_matchFrozenCurrentFormula() {
        listOf(
            input(nowMs = Long.MIN_VALUE, pauseStartedAtMs = 1L, pans = listOf(pan(1, startTimeMs = 1L))),
            input(nowMs = Long.MIN_VALUE, pauseStartedAtMs = Long.MAX_VALUE, pans = listOf(pan(1, startTimeMs = Long.MAX_VALUE))),
            input(nowMs = Long.MAX_VALUE, pauseStartedAtMs = Long.MIN_VALUE, pans = listOf(pan(1, startTimeMs = Long.MAX_VALUE - 1))),
            input(nowMs = Long.MAX_VALUE, pauseStartedAtMs = 1L, pans = listOf(pan(1, startTimeMs = Long.MAX_VALUE - 2)))
        ).forEach { case ->
            assertEquivalent(case)
        }
    }

    @Test
    fun fixedSeedTenThousandCases_matchIndependentFrozenHeadFormula() {
        val random = Random(0x2C2FA017L)
        repeat(10_000) {
            val nowMs = random.nextLong()
            val pauseStartedAtMs = random.nextLong()
            val pans = List(random.nextInt(38)) { index ->
                FaultHeatPanSnapshot(
                    positionSn = when (random.nextInt(8)) {
                        0 -> random.nextInt(5) - 2
                        1 -> 34 + random.nextInt(4)
                        else -> if (index > 0 && random.nextInt(8) == 0) index else 1 + random.nextInt(33)
                    },
                    hasSausage = random.nextBoolean(),
                    startTimeMs = random.nextLong(),
                    holdingTimeMs = random.nextLong(),
                    closeTimeMs = random.nextLong()
                )
            }
            assertEquivalent(
                FaultHeatPauseAdjustmentInput(
                    nowMs = nowMs,
                    pauseStartedAtMs = pauseStartedAtMs,
                    bakingPauseAbortMs = 1L + random.nextInt(1_000_000),
                    pauseAnomalyMs = 1L + random.nextInt(1_000_000),
                    pans = pans
                )
            )
        }
    }

    private fun assertEquivalent(input: FaultHeatPauseAdjustmentInput) {
        val expected = frozenHeadOracle(input)
        val actual = FaultHeatPauseAdjustmentPolicy.calculate(input)

        assertEquals(expected.pauseDurationMs, actual.pauseDurationMs)
        assertEquals(expected.shiftedPositions, actual.shiftedPositions)
        assertEquals(expected.holdingPositions, actual.holdingPositions)
        assertEquals(expected.pendingDiscardPositions, actual.pendingDiscardPositions)
        assertEquals(expected.targetStartTimes, actual.targetStartTimes)
        assertEquals(expected.alert, actual.alert)
        assertEquals(expected.failClosed, actual.failClosed)
        assertEquals(expected.decisions, actual.panDecisions.map { decision ->
            OracleDecision(
                inputIndex = decision.inputIndex,
                positionSn = decision.positionSn,
                action = decision.action.name,
                decisionPauseOverlapMs = decision.decisionPauseOverlapMs,
                targetStartTimeMs = decision.targetStartTimeMs
            )
        })
    }

    private fun frozenHeadOracle(input: FaultHeatPauseAdjustmentInput): OracleResult {
        val pauseDurationMs = input.nowMs - input.pauseStartedAtMs
        val hasAnySausage = input.pans.any { it.positionSn in 1..33 && it.hasSausage }
        val globalAlert = when {
            input.nowMs < input.pauseStartedAtMs -> FaultHeatPauseAlert.CLOCK_REGRESSED
            hasAnySausage && pauseDurationMs > input.pauseAnomalyMs ->
                FaultHeatPauseAlert.ANOMALY_TOO_LONG
            else -> null
        }
        val futureBaking = input.pans.any {
            isBaking(it) && it.startTimeMs > input.nowMs
        }
        val failClosed = globalAlert != null || futureBaking
        val alert = globalAlert ?: if (futureBaking) FaultHeatPauseAlert.CLOCK_REGRESSED else null
        val shifted = mutableListOf<Int>()
        val holding = mutableListOf<Int>()
        val pending = mutableListOf<Int>()
        val targets = linkedMapOf<Int, Long>()
        val decisions = input.pans.mapIndexed { index, pan ->
            val overlap = (input.nowMs - maxOf(input.pauseStartedAtMs, pan.startTimeMs))
                .coerceAtLeast(0L)
            when {
                isBaking(pan) && (failClosed || overlap > input.bakingPauseAbortMs) -> {
                    pending += pan.positionSn
                    val target: Long? = if (failClosed) null else pan.startTimeMs
                    if (target != null) targets[pan.positionSn] = target
                    OracleDecision(index, pan.positionSn, "DISCARD_BAKING", overlap, target)
                }
                isBaking(pan) -> {
                    shifted += pan.positionSn
                    val target = pan.startTimeMs + overlap
                    targets[pan.positionSn] = target
                    OracleDecision(index, pan.positionSn, "SHIFT_BAKING", overlap, target)
                }
                isHolding(pan) && pan.closeTimeMs > 0L &&
                    input.nowMs - pan.holdingTimeMs > pan.closeTimeMs -> {
                    holding += pan.positionSn
                    pending += pan.positionSn
                    OracleDecision(index, pan.positionSn, "DISCARD_HOLDING", overlap, null)
                }
                isHolding(pan) -> {
                    holding += pan.positionSn
                    OracleDecision(index, pan.positionSn, "KEEP_HOLDING", overlap, null)
                }
                else -> OracleDecision(index, pan.positionSn, "IGNORE", overlap, null)
            }
        }
        return OracleResult(
            pauseDurationMs = pauseDurationMs,
            shiftedPositions = shifted.sorted(),
            holdingPositions = holding.sorted(),
            pendingDiscardPositions = pending.distinct().sorted(),
            targetStartTimes = targets.toSortedMap(),
            alert = alert,
            failClosed = failClosed,
            decisions = decisions
        )
    }

    private fun input(
        nowMs: Long = 10_000L,
        pauseStartedAtMs: Long = 4_000L,
        bakingPauseAbortMs: Long = 10_000L,
        pauseAnomalyMs: Long = Long.MAX_VALUE,
        pans: List<FaultHeatPanSnapshot> = listOf(pan(1, startTimeMs = 1_000L))
    ) = FaultHeatPauseAdjustmentInput(
        nowMs = nowMs,
        pauseStartedAtMs = pauseStartedAtMs,
        bakingPauseAbortMs = bakingPauseAbortMs,
        pauseAnomalyMs = pauseAnomalyMs,
        pans = pans
    )

    private fun pan(
        positionSn: Int,
        hasSausage: Boolean = true,
        startTimeMs: Long = 0L,
        holdingTimeMs: Long = 0L,
        closeTimeMs: Long = 0L
    ) = FaultHeatPanSnapshot(positionSn, hasSausage, startTimeMs, holdingTimeMs, closeTimeMs)

    private fun isBaking(pan: FaultHeatPanSnapshot): Boolean =
        pan.hasSausage && pan.startTimeMs > 0L && pan.holdingTimeMs <= 0L

    private fun isHolding(pan: FaultHeatPanSnapshot): Boolean =
        pan.hasSausage && pan.holdingTimeMs > 0L

    private fun expectUnsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }

    private data class OracleDecision(
        val inputIndex: Int,
        val positionSn: Int,
        val action: String,
        val decisionPauseOverlapMs: Long,
        val targetStartTimeMs: Long?
    )

    private data class OracleResult(
        val pauseDurationMs: Long,
        val shiftedPositions: List<Int>,
        val holdingPositions: List<Int>,
        val pendingDiscardPositions: List<Int>,
        val targetStartTimes: Map<Int, Long>,
        val alert: FaultHeatPauseAlert?,
        val failClosed: Boolean,
        val decisions: List<OracleDecision>
    )
}
