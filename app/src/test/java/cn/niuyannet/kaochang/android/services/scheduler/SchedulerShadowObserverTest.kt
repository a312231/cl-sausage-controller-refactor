package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.scheduler.OldestHoldingFirstStrategy
import cn.niuyannet.kaochang.scheduler.PanLifecycle
import cn.niuyannet.kaochang.scheduler.PanSnapshot
import cn.niuyannet.kaochang.scheduler.SellCandidateInput
import cn.niuyannet.kaochang.scheduler.SellCandidateStrategy
import cn.niuyannet.kaochang.scheduler.StrategyId
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SchedulerShadowObserverTest {

    @Test
    fun gate_shouldKeepExactLegacyReferenceForSuccessNullAndException() {
        val legacy = KaoPan()

        assertSame(legacy, LegacyCandidateShadowGate.observeAndKeep(true, legacy) { })
        assertSame(legacy, LegacyCandidateShadowGate.observeAndKeep(true, legacy) { error("boom") })
        assertNull(LegacyCandidateShadowGate.observeAndKeep<KaoPan>(true, null) { error("boom") })
    }

    @Test
    fun disabledGate_shouldNotInvokeObserver() {
        val legacy = KaoPan()
        var invoked = false

        val result = LegacyCandidateShadowGate.observeAndKeep(false, legacy) { invoked = true }

        assertSame(legacy, result)
        assertEquals(false, invoked)
    }

    @Test
    fun nullLegacyWithNonNullOracle_shouldOnlyRecordBaselineDeviation() {
        val logs = mutableListOf<String>()
        val observer = SchedulerShadowObserver(
            strategy = OldestHoldingFirstStrategy(),
            sessionId = "test-session",
            logSink = logs::add
        )

        observer.observe(legacyPosition = null, input = sellableInput())

        val metrics = observer.metricsSnapshot()
        assertEquals(1L, metrics.comparisonCount)
        assertEquals(1L, metrics.baselineDeviationCount)
        assertEquals(0L, metrics.algorithmDifferenceCount)
    }

    @Test
    fun runner_shouldContainProviderStrategyAndSinkFailures() {
        val observer = SchedulerShadowObserver(
            strategy = throwingStrategy(),
            sessionId = "test-session",
            logSink = { error("sink failure") }
        )
        val runner = SchedulerShadowRunner(observer)

        runner.observeSafely(legacyPosition = 1) { error("mapper failure") }
        runner.observeSafely(legacyPosition = 1) { sellableInput() }

        assertEquals(2L, observer.metricsSnapshot().exceptionCount)
    }

    @Test
    fun observer_shouldKeepExactConcurrentCountsSamplesAndDetailBudget() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val observer = SchedulerShadowObserver(
            strategy = fixedStrategy(null),
            sessionId = "test-session",
            logSink = logs::add
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(1_000) {
                executor.submit { observer.observe(legacyPosition = 1, input = sellableInput()) }
            }
        } finally {
            executor.shutdown()
        }
        assertEquals(true, executor.awaitTermination(30, TimeUnit.SECONDS))

        val metrics = observer.metricsSnapshot()
        assertEquals(1_000L, metrics.comparisonCount)
        assertEquals(1_000L, metrics.algorithmDifferenceCount)
        assertEquals(1, logs.count { it.contains("shadow_start") })
        assertEquals(20, logs.count { it.contains("shadow_detail") })
        val sampleCounts = logs
            .filter { it.contains("shadow_sample") }
            .map { message ->
                Regex("comparisons=(\\d+)").find(message)?.groupValues?.get(1)?.toLong()
            }
        assertEquals((listOf(1L) + (25L..1_000L step 25)).toSet(), sampleCounts.toSet())
        assertEquals(41, sampleCounts.size)
    }

    private fun sellableInput(): SellCandidateInput = SellCandidateInput(
        nowMs = 1_000L,
        tasteCode = "A",
        productId = 104,
        sellRange = 1..9,
        pendingDiscardPositions = emptySet(),
        pans = listOf(
            PanSnapshot(
                positionSn = 1,
                hasSausage = true,
                lifecycle = PanLifecycle.SELLABLE_KEEP_WARM,
                holdingTime = 500L,
                closeTime = 1_000L,
                tasteCode = "A",
                productId = 104
            )
        )
    )

    private fun fixedStrategy(position: Int?): SellCandidateStrategy = object : SellCandidateStrategy {
        override val id = StrategyId("test-fixed-strategy")
        override fun selectPosition(input: SellCandidateInput): Int? = position
    }

    private fun throwingStrategy(): SellCandidateStrategy = object : SellCandidateStrategy {
        override val id = StrategyId("test-throwing-strategy")
        override fun selectPosition(input: SellCandidateInput): Int? = error("strategy failure")
    }
}
