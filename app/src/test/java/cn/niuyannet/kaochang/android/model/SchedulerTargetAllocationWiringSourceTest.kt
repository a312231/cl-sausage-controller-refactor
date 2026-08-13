package cn.niuyannet.kaochang.android.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTargetAllocationWiringSourceTest {

    @Test
    fun ratioEntrypoints_shouldOnlyFilterAggregateDelegateLogAndReturnMutableCopies() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt"
        )
        val standard = extractBlock(source, "fun computeKaoPamRatio(")
        val highHeat = extractBlock(source, "fun computeHighKaoPamRatio(")

        listOf(standard, highHeat).forEach { body ->
            assertEquals(1, occurrences(body, "targetAllocationStrategy.allocate("))
            assertEquals(1, occurrences(body, "SupplementTargetAllocationInput("))
            assertEquals(1, occurrences(body, ".filter { isSupplyBoxAvailableForAutoSupplement(it) }"))
            assertTrue(body.contains(".groupBy { it.tasteCode!! }"))
            assertTrue(body.contains("items.sumOf { it.num }"))
            assertTrue(body.contains(".toMutableMap()"))
            assertTrue(!body.contains("sortedByDescending"))
            assertTrue(!body.contains("sortedWith"))
            assertTrue(!body.contains("while ("))
            assertTrue(!body.contains("ratio"))
        }
        assertTrue(standard.contains("SupplementTargetScenario.STANDARD"))
        assertTrue(highHeat.contains("SupplementTargetScenario.HIGH_HEAT"))
        assertEquals(1, occurrences(source, "fun computeKaoPamRatio_old("))
        assertEquals(1, occurrences(source, "computeKaoPamRatio_old("))
    }

    @Test
    fun sixExistingCallSites_shouldKeepUsingTheAppCompatibilityEntrypoints() {
        val low = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt"
        )
        val high = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"
        )
        val traffic = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/TrafficModeHelper.kt"
        )
        val highPlanner = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt"
        )

        assertEquals(2, occurrences(low, "KaoPanHelper.computeKaoPamRatio("))
        assertEquals(2, occurrences(high, "KaoPanHelper.computeKaoPamRatio("))
        assertEquals(1, occurrences(traffic, "KaoPanHelper.computeKaoPamRatio("))
        assertEquals(1, occurrences(highPlanner, "KaoPanHelper.computeHighKaoPamRatio("))

        listOf(low, high, traffic, highPlanner).forEach { source ->
            assertTrue(!source.contains("InventoryProportionalTargetStrategy"))
            assertTrue(!source.contains("SupplementTargetAllocationInput"))
            assertTrue(!source.contains("SupplementTargetScenario"))
        }
    }

    @Test
    fun incompleteSubStrategy_shouldNotLeakIntoTasteMixConfigOrMaintenanceUi() {
        val tasteMix = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt"
        )
        val config = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java"
        )
        val maintenance = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )

        listOf(tasteMix, config, maintenance).forEach { source ->
            assertTrue(!source.contains("InventoryProportionalTargetStrategy"))
            assertTrue(!source.contains("SupplementTargetAllocationInput"))
            assertTrue(!source.contains("inventory-proportional-target-v1"))
        }
    }

    private fun sourceFile(path: String): String = sourceFilePath(path).readText().replace("\r\n", "\n")

    private fun occurrences(source: String, token: String): Int =
        Regex(Regex.escape(token)).findAll(source).count()

    private fun sourceFilePath(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent
        throw AssertionError("找不到源码文件：$path")
    }

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到代码块：$marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        throw AssertionError("代码块未闭合：$marker")
    }
}
