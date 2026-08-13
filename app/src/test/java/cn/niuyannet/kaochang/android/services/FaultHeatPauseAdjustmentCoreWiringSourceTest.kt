package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultHeatPauseAdjustmentCoreWiringSourceTest {

    @Test
    fun manager_usesOneCoreAdapterWithoutKeepingASecondDecisionFormula() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt"
        )

        assertEquals(1, occurrences(source, "FaultHeatPauseAdjustmentPolicy.calculate("))
        assertTrue(source.contains("FaultHeatPauseAdjustmentInput("))
        assertTrue(source.contains("FaultHeatPanSnapshot("))
        assertTrue(source.contains("normalizedFaultBakingPauseAbortMs(config)"))
        assertTrue(source.contains("normalizedFaultPauseAnomalyMs(config)"))
        listOf(
            "TemperatureControlPolicy.hasAnySausageInPhysicalPans(pans)",
            "nowMs < pauseStartedAtMs",
            "hasFutureBakingStart",
            "pauseDurationMs > normalizedFaultPauseAnomalyMs(config)",
            "overlapMs > normalizedFaultBakingPauseAbortMs(config)",
            "nowMs - pan.holdingTime > pan.closeTime"
        ).forEach { forbidden ->
            assertFalse("App must not retain decision formula: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun diagnosticOverlap_remainsLogOnlyAndCannotControlState() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt"
        )
        assertEquals(2, occurrences(source, "actualPauseOverlapMs("))
        val adjustment = functionBody(source, "internal fun adjustPansForPause(")
        val callIndex = adjustment.indexOf("actualPauseOverlapMs(")
        val logIndex = adjustment.indexOf("logPendingDiscardPan(")
        val mutationIndex = adjustment.indexOf("addPendingDiscardPositions(")

        assertTrue("diagnostic overlap must be inside pending log arguments", callIndex > logIndex)
        assertTrue("diagnostic overlap must run before state mutation", callIndex < mutationIndex)
        assertFalse(adjustment.substring(mutationIndex).contains("actualPauseOverlapMs("))
    }

    @Test
    fun previewAndPowerLossKeepExistingSideEffectOrderAroundTheCoreAdapter() {
        val manager = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt"
        )
        val preview = functionBody(manager, "fun previewFaultHeatPauseEnd(")
        assertOrdered(
            preview,
            "normalizeStaleBakingHoldingTimes(pans, config)",
            "KaoPanHelper.saveKaoPanList(pans)",
            "AppConfig.saveAppConfig(config)",
            "return calculatePauseAdjustment("
        )

        val powerLoss = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuard.kt"
        )
        val recoverBeforeRuntime = functionBody(powerLoss, "fun recoverBeforeRuntimeStart(")
        assertOrdered(
            recoverBeforeRuntime,
            "replayPendingRecoveryJournalIfNeeded",
            "recoverPansAfterColdStart("
        )
        val recover = functionBody(powerLoss, "fun recoverPansAfterColdStart(")
        assertOrdered(
            recover,
            "normalizeStaleBakingHoldingTimes",
            "adjustPansForPause(",
            "lastRuntimeAliveAtMs = nowMs"
        )
    }

    @Test
    fun recoveryCorePolicyHasNoAndroidIoClockThreadOrProtocolDependency() {
        val source = sourceFile(
            "recovery-core/src/main/kotlin/cn/niuyannet/kaochang/recovery/" +
                "FaultHeatPauseAdjustmentPolicy.kt"
        )
        listOf(
            "android.",
            "cn.niuyannet.kaochang.android",
            "mqtt",
            "modbus",
            "fastjson",
            "System.currentTimeMillis",
            "SystemClock",
            "Thread",
            "Coroutine",
            "File(",
            "UUID"
        ).forEach { forbidden ->
            assertFalse("recovery-core policy must not depend on $forbidden", source.contains(forbidden))
        }
    }

    private fun assertOrdered(source: String, vararg needles: String) {
        var previous = -1
        needles.forEach { needle ->
            val index = source.indexOf(needle, startIndex = previous + 1)
            assertTrue("Missing or out of order: $needle", index > previous)
            previous = index
        }
    }

    private fun occurrences(source: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(source).count()

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) throw AssertionError("找不到函数：$signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        throw AssertionError("函数括号未闭合：$signature")
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val parent = File("..", path)
        if (parent.exists()) return parent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }
}
