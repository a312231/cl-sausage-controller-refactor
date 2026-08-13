package cn.niuyannet.kaochang.scheduler

import java.lang.reflect.Modifier
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficDemandTransitionStrategyTest {

    private val strategy = LegacyDemandTimeoutTransitionStrategy()

    @Test
    fun strategy_shouldExposeStableVersionedId() {
        assertEquals(StrategyId("legacy-demand-timeout-transition-v1"), strategy.id)
    }

    @Test
    fun lowToHigh_shouldMatchLegacyAcrossPhysicalInputDomain() {
        for (emptyCount in 0..9) {
            for (hasActiveBatch in listOf(false, true)) {
                val expected = if (emptyCount > 7 && hasActiveBatch) {
                    TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND
                } else {
                    TrafficTransitionOutcome.NONE
                }

                assertEquals(
                    expected,
                    strategy.planLowToHigh(
                        LowToHighDemandInput(emptyCount, hasActiveBatch)
                    )
                )
            }
        }
    }

    @Test
    fun lowToHigh_shouldFailClosedOutsidePhysicalInputDomain() {
        listOf(-1, 10, 21, 22, Int.MIN_VALUE, Int.MAX_VALUE).forEach { emptyCount ->
            assertEquals(
                TrafficTransitionOutcome.NONE,
                strategy.planLowToHigh(LowToHighDemandInput(emptyCount, true))
            )
        }
    }

    @Test
    fun highToLow_shouldUseStrictBeforeCloseWindow() {
        val thresholdMs = 30 * 60 * 1000L
        listOf(-1L, 0L, thresholdMs, thresholdMs + 1L).forEach { remainingMs ->
            assertEquals(
                TrafficTransitionOutcome.NONE,
                strategy.planHighToLow(highInput(remainingUntilCloseMs = remainingMs))
            )
        }
        assertEquals(
            TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE,
            strategy.planHighToLow(highInput(remainingUntilCloseMs = thresholdMs - 1L))
        )
    }

    @Test
    fun highToLow_shouldRequireAnchorAndUseStrictInactivityThreshold() {
        val thresholdMs = 40 * 60 * 1000L
        assertEquals(
            TrafficTransitionOutcome.NONE,
            strategy.planHighToLow(highInput(lastHighSupplementTimeMs = null))
        )
        listOf(-1L, 0L, thresholdMs - 1L, thresholdMs).forEach { elapsedMs ->
            assertEquals(
                TrafficTransitionOutcome.NONE,
                strategy.planHighToLow(
                    highInput(
                        nowMs = 1_000L + elapsedMs,
                        lastHighSupplementTimeMs = 1_000L
                    )
                )
            )
        }
        assertEquals(
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT,
            strategy.planHighToLow(
                highInput(
                    nowMs = 1_000L + thresholdMs + 1L,
                    lastHighSupplementTimeMs = 1_000L
                )
            )
        )
    }

    @Test
    fun highToLow_shouldGiveBeforeClosePriorityOverTimeout() {
        assertEquals(
            TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE,
            strategy.planHighToLow(
                highInput(
                    remainingUntilCloseMs = 1L,
                    nowMs = 10_000_000L,
                    lastHighSupplementTimeMs = 1L
                )
            )
        )
    }

    @Test
    fun highToLow_shouldIgnoreNonPositiveInactivityMinutes() {
        listOf(Int.MIN_VALUE, -1, 0).forEach { minutes ->
            assertEquals(
                TrafficTransitionOutcome.NONE,
                strategy.planHighToLow(
                    highInput(
                        nowMs = Long.MAX_VALUE,
                        lastHighSupplementTimeMs = 1L,
                        inactivityThresholdMinutes = minutes
                    )
                )
            )
        }
    }

    @Test
    fun highToLow_shouldPreserveLegacyIntThresholdWraparound() {
        val thresholdMinutes = Int.MAX_VALUE
        val last = 1_000L
        listOf(
            -1L to TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT,
            -60_000L to TrafficTransitionOutcome.NONE,
            -60_001L to TrafficTransitionOutcome.NONE
        ).forEach { (elapsed, expected) ->
            assertEquals(
                expected,
                strategy.planHighToLow(
                    highInput(
                        nowMs = last + elapsed,
                        lastHighSupplementTimeMs = last,
                        inactivityThresholdMinutes = thresholdMinutes
                    )
                )
            )
        }

        assertEquals(
            TrafficTransitionOutcome.NONE,
            strategy.planHighToLow(
                highInput(
                    remainingUntilCloseMs = 1L,
                    beforeCloseMinutes = Int.MAX_VALUE
                )
            )
        )
    }

    @Test
    fun highToLow_shouldPreserveLegacyLongElapsedWraparound() {
        assertEquals(
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT,
            strategy.planHighToLow(
                highInput(
                    nowMs = Long.MIN_VALUE,
                    lastHighSupplementTimeMs = 1L,
                    inactivityThresholdMinutes = 1
                )
            )
        )
        assertEquals(Long.MAX_VALUE, subtractWithJvmWraparound(Long.MIN_VALUE, 1L))
    }

    @Test
    fun outcomes_shouldHaveFixedDirectionAndReasonWithoutPublicCopyOrConstructor() {
        val expected = mapOf(
            TrafficTransitionOutcome.NONE to
                (TrafficTransitionRequest.NONE to TrafficTransitionReason.NONE),
            TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND to
                (TrafficTransitionRequest.LOW_TO_HIGH to TrafficTransitionReason.EFFECTIVE_LOW_FRONT_DEMAND),
            TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE to
                (TrafficTransitionRequest.HIGH_TO_LOW to TrafficTransitionReason.BEFORE_BUSINESS_CLOSE),
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT to
                (TrafficTransitionRequest.HIGH_TO_LOW to TrafficTransitionReason.HIGH_MODE_INACTIVITY_TIMEOUT)
        )

        assertEquals(expected.keys, TrafficTransitionOutcome.entries.toSet())
        expected.forEach { (outcome, mapping) ->
            assertEquals(mapping.first, outcome.request)
            assertEquals(mapping.second, outcome.reason)
        }
        assertTrue(TrafficTransitionOutcome::class.java.isEnum)
        assertTrue(
            TrafficTransitionOutcome::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            }
        )
        assertFalse(
            TrafficTransitionOutcome::class.java.methods.any { it.name == "copy" }
        )
    }

    @Test
    fun highToLow_shouldMatchIndependentLegacyOracleForFixedSeedCases() {
        val random = Random(0x1C5DA7A5L)
        repeat(10_000) {
            val input = HighToLowDemandInput(
                remainingUntilCloseMs = random.nextLong(),
                beforeCloseMinutes = random.nextInt(),
                nowMs = random.nextLong(),
                lastHighSupplementTimeMs = if (random.nextBoolean()) random.nextLong() else null,
                inactivityThresholdMinutes = random.nextInt()
            )
            assertEquals(legacyHighToLow(input), strategy.planHighToLow(input))
        }
    }

    private fun highInput(
        remainingUntilCloseMs: Long = -1L,
        beforeCloseMinutes: Int = 30,
        nowMs: Long = 1_000L,
        lastHighSupplementTimeMs: Long? = 1_000L,
        inactivityThresholdMinutes: Int = 40
    ) = HighToLowDemandInput(
        remainingUntilCloseMs = remainingUntilCloseMs,
        beforeCloseMinutes = beforeCloseMinutes,
        nowMs = nowMs,
        lastHighSupplementTimeMs = lastHighSupplementTimeMs,
        inactivityThresholdMinutes = inactivityThresholdMinutes
    )

    private fun legacyHighToLow(input: HighToLowDemandInput): TrafficTransitionOutcome {
        val beforeCloseThresholdMs = (input.beforeCloseMinutes * 60) * 1000L
        if (
            input.remainingUntilCloseMs > 0L &&
            input.remainingUntilCloseMs < beforeCloseThresholdMs
        ) {
            return TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE
        }
        val lastSupplementTimeMs = input.lastHighSupplementTimeMs
            ?: return TrafficTransitionOutcome.NONE
        if (input.inactivityThresholdMinutes <= 0) {
            return TrafficTransitionOutcome.NONE
        }
        val thresholdMs = (input.inactivityThresholdMinutes * 60) * 1000L
        return if (input.nowMs - lastSupplementTimeMs > thresholdMs) {
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT
        } else {
            TrafficTransitionOutcome.NONE
        }
    }

    private fun subtractWithJvmWraparound(left: Long, right: Long): Long = left - right
}
