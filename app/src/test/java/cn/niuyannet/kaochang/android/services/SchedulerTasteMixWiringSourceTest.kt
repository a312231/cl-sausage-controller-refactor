package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTasteMixWiringSourceTest {

    @Test
    fun planner_shouldOnlyAdaptTheCoreStrategyAndKeepCommitFilteringInApp() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt"
        )
        val planBody = extractBlock(source, "fun plan(")
        val committedBody = extractBlock(source, "fun committedReturnTasteByPosition(")

        assertEquals(1, occurrences(planBody, "strategy.plan("))
        assertEquals(1, occurrences(planBody, "SupplementTasteMixInput("))
        assertTrue(!planBody.contains("sortedWith"))
        assertTrue(!planBody.contains("coerceAtMost"))
        assertTrue(!source.contains("private fun selectTaste("))
        assertTrue(!source.contains("private fun compareTastePriority("))

        assertTrue(committedBody.contains("!it.cancelledByFaultTimeout"))
        assertTrue(committedBody.contains("!it.skippedBySupplyDegrade"))
    }

    @Test
    fun lowAndHighTrafficCallChains_shouldRemainOwnedByApp() {
        val low = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt"
        )
        val high = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"
        )
        val highPlanner = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt"
        )

        assertTrue(low.contains("KaoPanHelper.computeKaoPamRatio(listBox, 9)"))
        assertTrue(low.contains("SupplementTasteMixPlanner.plan("))
        assertTrue(!low.contains("RelativeDeficitTasteMixStrategy"))
        assertTrue(!low.contains("SupplementTasteMixInput"))

        assertTrue(high.contains("HighHeatSupplementResumePlanner.planInitialTargets("))
        assertTrue(!high.contains("SupplementTasteMixPlanner.plan("))
        assertTrue(!high.contains("RelativeDeficitTasteMixStrategy"))
        assertTrue(!high.contains("SupplementTasteMixInput"))

        assertTrue(highPlanner.contains("KaoPanHelper.computeHighKaoPamRatio(listBox, 21)"))
        assertTrue(highPlanner.contains("SupplementTasteMixPlanner.plan("))
        assertTrue(!highPlanner.contains("RelativeDeficitTasteMixStrategy"))
        assertTrue(!highPlanner.contains("SupplementTasteMixInput"))
    }

    @Test
    fun app_shouldNotExposeAnIncompleteTasteOnlyStrategyAsAFullSchedulerSwitch() {
        val config = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java"
        )
        val maintenance = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )

        listOf(config, maintenance).forEach { source ->
            assertTrue(!source.contains("relative-deficit-taste-mix-v1"))
            assertTrue(!source.contains("schedulerStrategyId"))
            assertTrue(!source.contains("SupplementTasteMixStrategy"))
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
