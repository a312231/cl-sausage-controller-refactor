package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.scheduler.HighToLowDemandInput
import cn.niuyannet.kaochang.scheduler.LowToHighDemandInput
import cn.niuyannet.kaochang.scheduler.StrategyId
import cn.niuyannet.kaochang.scheduler.TrafficDemandTransitionStrategy
import cn.niuyannet.kaochang.scheduler.TrafficTransitionOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficDemandTransitionWiringSourceTest {

    @Test
    fun appAdapters_shouldFailClosedForOutcomesFromTheWrongDirection() {
        val lowInput = LowToHighDemandInput(
            effectiveEmptyFrontCount = 8,
            hasCompleteActiveLowHeatBatch = true
        )
        val highInput = HighToLowDemandInput(
            remainingUntilCloseMs = -1L,
            beforeCloseMinutes = 30,
            nowMs = 10_000L,
            lastHighSupplementTimeMs = 1_000L,
            inactivityThresholdMinutes = 40
        )

        listOf(
            TrafficTransitionOutcome.NONE,
            TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE,
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT
        ).forEach { wrongLowOutcome ->
            assertFalse(
                acceptsLowToHighDemand(
                    FakeTrafficDemandTransitionStrategy(lowOutcome = wrongLowOutcome),
                    lowInput
                )
            )
        }
        assertTrue(
            acceptsLowToHighDemand(
                FakeTrafficDemandTransitionStrategy(
                    lowOutcome = TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND
                ),
                lowInput
            )
        )

        assertNull(
            acceptedHighToLowDemandOutcome(
                FakeTrafficDemandTransitionStrategy(
                    highOutcome = TrafficTransitionOutcome.LOW_TO_HIGH_EFFECTIVE_DEMAND
                ),
                highInput
            )
        )
        listOf(
            TrafficTransitionOutcome.NONE,
            TrafficTransitionOutcome.HIGH_TO_LOW_BEFORE_CLOSE,
            TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT
        ).forEach { acceptedOutcome ->
            assertEquals(
                acceptedOutcome,
                acceptedHighToLowDemandOutcome(
                    FakeTrafficDemandTransitionStrategy(highOutcome = acceptedOutcome),
                    highInput
                )
            )
        }
    }

    @Test
    fun highTrafficAnchorAdapter_shouldTreatEveryNonPositiveValueAsMissing() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalidAnchor ->
            assertNull(validHighSupplementTime(invalidAnchor))
        }
        assertEquals(1L, validHighSupplementTime(1L))
        assertEquals(Long.MAX_VALUE, validHighSupplementTime(Long.MAX_VALUE))
    }

    @Test
    fun lowTraffic_shouldKeepAppGatesBeforePureDemandStrategy() {
        val lowTrafficSource = sourceText("LowCurrentTrafficMode.kt")
        val gateStart = lowTrafficSource.indexOf("if (!pendingBatchHandling.blocksNewSupplement &&")
        val pendingBatchGate = lowTrafficSource.indexOf("pendingBatchHandling.blocksNewSupplement", gateStart)
        val schedulingGate = lowTrafficSource.indexOf("schedulingEnabled", pendingBatchGate)
        val supplyGate = lowTrafficSource.indexOf("boxSupplyAvailable", schedulingGate)
        val modeGate = lowTrafficSource.indexOf("config.modeType == 1", supplyGate)
        val transitionGate = lowTrafficSource.indexOf("config.transitionMode == 0", modeGate)
        val demandGate = lowTrafficSource.indexOf("LowToHighDemandGate.canTriggerLowToHigh(", transitionGate)

        assertTrue(gateStart >= 0)
        assertTrue(pendingBatchGate >= gateStart)
        assertTrue(supplyGate > schedulingGate)
        assertTrue(modeGate > supplyGate)
        assertTrue(transitionGate > modeGate)
        assertTrue(demandGate > transitionGate)

        val demandGateSource = sourceText("LowToHighDemandGate.kt")
        val functionStart = demandGateSource.indexOf("fun canTriggerLowToHigh(")
        val functionEnd = demandGateSource.indexOf("fun markExpiredDiscardAndFoldToLow(", functionStart)
        val functionBody = demandGateSource.substring(functionStart, functionEnd)
        assertTrue(functionBody.contains("acceptsLowToHighDemand("))
        assertTrue(functionBody.contains("LowToHighDemandInput("))
        assertFalse(functionBody.contains(".size > 7"))
        assertFalse(functionBody.contains("&&\n            hasActiveLowHeatBatch"))
    }

    @Test
    fun highTraffic_shouldPreserveCloseAnchorTimeoutAndReasonOrder() {
        val source = sourceText("HighCurrentTrafficMode.kt")
        val functionStart = source.indexOf("private fun checkSwitchToLow(")
        val functionEnd = source.indexOf("private fun getRemainingTimeUntilClose(", functionStart)
        val body = source.substring(functionStart, functionEnd)
        val closeGate = body.indexOf("remainingTimeUntilClose > 0")
        val closeSwitch = body.indexOf("switchHighToLow(\"接近营业结束\"", closeGate)
        val durableTimerGate = body.indexOf("restoreOrArmHighToLowTimerIfSafe(", closeSwitch)
        val strategyCall = body.indexOf("acceptedHighToLowDemandOutcome(", durableTimerGate)
        val timeoutBranch = body.indexOf(
            "outcome == TrafficTransitionOutcome.HIGH_TO_LOW_INACTIVITY_TIMEOUT",
            strategyCall
        )
        val supplyReason = body.indexOf("val reason = if (boxSupplyAvailable)", timeoutBranch)

        assertTrue(body.contains("validHighSupplementTime(lastFillPitTime)"))
        assertTrue(closeGate >= 0)
        assertTrue(closeSwitch > closeGate)
        assertTrue(durableTimerGate > closeSwitch)
        assertTrue(strategyCall > durableTimerGate)
        assertTrue(body.indexOf("acceptedHighToLowDemandOutcome(", strategyCall + 1) < 0)
        assertTrue(timeoutBranch > strategyCall)
        assertTrue(supplyReason > timeoutBranch)
        assertFalse(body.contains("applySupplementTrackingState("))

        val strategyInputStart = body.indexOf("HighToLowDemandInput(", strategyCall)
        val strategyInputEnd = body.indexOf(")\n        )", strategyInputStart)
        assertTrue(strategyInputStart >= 0 && strategyInputEnd > strategyInputStart)
        assertFalse(body.substring(strategyInputStart, strategyInputEnd).contains("boxSupplyAvailable"))
    }

    @Test
    fun bothSwitches_shouldKeepLegalResolverPersistenceAndRuntimePublishOwnership() {
        val lowSource = sourceText("LowCurrentTrafficMode.kt")
        val lowSwitch = functionBody(lowSource, "private fun switchLowToHigh(", "private fun panStatusSummary(")
        assertInOrder(
            lowSwitch,
            "resolveAutomaticTrafficTransition(",
            "config.modeType = resolvedTransition.modeType",
            "config.transitionMode = resolvedTransition.transitionMode",
            "AppConfig.saveAppConfig(config)",
            "SendServerHelper.publishServiceUpdateStatus()"
        )

        val highSource = sourceText("HighCurrentTrafficMode.kt")
        val highSwitch = highSource.substring(highSource.indexOf("private fun switchHighToLow("))
        assertInOrder(
            highSwitch,
            "resolveAutomaticTrafficTransition(",
            "config.modeType = resolvedTransition.modeType",
            "config.transitionMode = resolvedTransition.transitionMode",
            "KaoChangOperate.clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation",
            "AppConfig.saveAppConfig(config)",
            "SendServerHelper.publishServiceUpdateStatus()",
            "resetSupplementTracking(\"进入高转低过渡\")"
        )

        assertFalse(highSource.contains("shouldSwitchHighToLowByTimeout"))
        assertFalse(sourceText("HighCurrentTrafficModeStateHelper.kt").contains("shouldSwitchHighToLowByTimeout"))
    }

    @Test
    fun poller_shouldKeepIoScopeTenSecondCadenceAndTransitionFirstRouting() {
        val source = sourceText("KaoChangAlgorithm.kt")
        val routeStart = source.indexOf("when {\n            config.transitionMode != 0")
        val transitionHandler = source.indexOf("HighCurrentTrafficMode.highTrafficMode(", routeStart)
        val lowHandler = source.indexOf("LowCurrentTrafficMode.lowTrafficMode(", transitionHandler)
        val stableHighHandler = source.indexOf("config.modeType == 2", lowHandler)

        assertTrue(source.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(source.contains("delay(10 * 1000)"))
        assertTrue(routeStart >= 0)
        assertTrue(transitionHandler > routeStart)
        assertTrue(lowHandler > transitionHandler)
        assertTrue(stableHighHandler > lowHandler)
    }

    private fun assertInOrder(source: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val current = source.indexOf(fragment, previous + 1)
            assertTrue("Missing or out-of-order fragment: $fragment", current > previous)
            previous = current
        }
    }

    private fun functionBody(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start)
        assertTrue(start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun sourceText(name: String): String {
        val direct = File("src/main/java/cn/niuyannet/kaochang/android/services/$name")
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        return File(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/$name"
        ).readText().replace("\r\n", "\n")
    }

    private class FakeTrafficDemandTransitionStrategy(
        private val lowOutcome: TrafficTransitionOutcome = TrafficTransitionOutcome.NONE,
        private val highOutcome: TrafficTransitionOutcome = TrafficTransitionOutcome.NONE
    ) : TrafficDemandTransitionStrategy {
        override val id = StrategyId("fake-traffic-demand-transition-v1")

        override fun planLowToHigh(input: LowToHighDemandInput): TrafficTransitionOutcome =
            lowOutcome

        override fun planHighToLow(input: HighToLowDemandInput): TrafficTransitionOutcome =
            highOutcome
    }
}
