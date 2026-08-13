package cn.niuyannet.kaochang.android.services.scheduler

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerProfileWiringSourceTest {

    @Test
    fun appProductionSources_useOneProfileRuntimeInsteadOfConstructingAComponents() {
        val appMain = sourceDirectory("app/src/main/java")
        val forbiddenConstructors = listOf(
            "OldestHoldingFirstStrategy()",
            "InventoryProportionalTargetStrategy()",
            "RelativeDeficitTasteMixStrategy()",
            "LegacyFixedBatchHeatPositionStrategy()",
            "LegacyDemandTimeoutTransitionStrategy()"
        )
        val violations = appMain.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .flatMap { file ->
                forbiddenConstructors.asSequence()
                    .filter { constructor -> file.readText().contains(constructor) }
                    .map { constructor -> "${file.path}:$constructor" }
            }
            .toList()

        assertTrue("App must not directly construct A components: $violations", violations.isEmpty())
    }

    @Test
    fun allEightFormerConstructionSites_readTheExpectedComponentFromOneRuntime() {
        val expectations = linkedMapOf(
            "app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt" to
                "SchedulerProfileRuntime.activeProfile.targetAllocationStrategy",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt" to
                "SchedulerProfileRuntime.activeProfile.tasteMixStrategy",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt" to
                "SchedulerProfileRuntime.activeProfile.batchHeatPositionStrategy",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt" to
                "SchedulerProfileRuntime.activeProfile.batchHeatPositionStrategy",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGate.kt" to
                "SchedulerProfileRuntime.activeProfile.demandTransitionStrategy",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerShadowRuntime.kt" to
                "SchedulerProfileRuntime.activeProfile.sellCandidateStrategy"
        )
        expectations.forEach { (path, reference) ->
            assertTrue("$path must use $reference", sourceFile(path).contains(reference))
        }

        val high = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"
        )
        assertEquals(
            1,
            occurrences(high, "SchedulerProfileRuntime.activeProfile.batchHeatPositionStrategy")
        )
        assertEquals(
            1,
            occurrences(high, "SchedulerProfileRuntime.activeProfile.demandTransitionStrategy")
        )
    }

    @Test
    fun runtime_isFixedToAAndDoesNotExposeSelectionOrFallback() {
        val runtime = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntime.kt"
        )

        assertEquals(1, occurrences(runtime, "LegacySchedulerProfileA.create()"))
        assertTrue(runtime.contains("PinnedSchedulerProfile.create("))
        assertTrue(runtime.contains("LegacySchedulerProfileA.ID.value"))
        assertFalse(runtime.contains("runCatching"))
        assertFalse(runtime.contains("fallback"))
        assertFalse(runtime.contains("fun switch"))
        assertFalse(runtime.contains("var activeProfile"))
    }

    @Test
    fun sellCandidateRemainsShadowOnlyAndLegacyCandidateRemainsMechanicalAuthority() {
        val shadow = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerShadowRuntime.kt"
        )
        assertTrue(shadow.contains("SchedulerProfileRuntime.activeProfile.sellCandidateStrategy"))

        val algorithm = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
        )
        val purchase = algorithm.substring(
            algorithm.indexOf("suspend fun purchase("),
            algorithm.indexOf("suspend fun resetKaoPan(")
        )
        assertTrue(purchase.contains("TrafficModeHelper.selectSellCandidate("))
        assertTrue(purchase.contains("LegacyCandidateShadowGate.observeAndKeep("))
        assertTrue(purchase.contains("KaoChangOperate.takeSausageResult(targetKaoPan,"))
        assertFalse(purchase.contains("takeSausageResult(shadow"))
    }

    private fun occurrences(source: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(source).count()

    private fun sourceDirectory(path: String): File {
        val direct = File(path)
        if (direct.isDirectory) return direct
        val fromParent = File("..", path)
        if (fromParent.isDirectory) return fromParent
        throw AssertionError("找不到源码目录：$path，cwd=${File(".").absolutePath}")
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }
}
