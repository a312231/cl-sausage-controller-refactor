package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighCurrentTrafficModeSourceTest {

    @Test
    fun highInitialization_shouldUseTypedCommonSupplementEntry() {
        val source = sourceText()
        val resume = functionBlock(source, "private suspend fun resumeHighInitialization(", "/**\n     * 低并发 -> 高并发过渡")
        val initialize = functionBlock(source, "private suspend fun initHighTrafficMode(", "/**\n     * 向 25~33")

        assertTrue(resume.contains("batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21"))
        assertTrue(initialize.contains("batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21"))
    }

    @Test
    fun newHighHeatBatchEntrypoints_shouldRequireCompletePlanBeforeHeating() {
        val source = sourceText()
        val transition = functionBlock(source, "private suspend fun lowTrafficModeTransition(", "/**\n     * 低转高期间")
        val stable = functionBlock(source, "private suspend fun addHighHeatSausage(", "private suspend fun resumeIncompleteHighHeatSupplement(")

        listOf(transition, stable).forEach { block ->
            val guard = block.indexOf("HighHeatSupplementResumePlanner.isCompleteInitialPlan(")
            val heating = block.indexOf("KaoChangOperate.heating(3)")
            assertTrue("新批次必须先检查完整九目标", guard >= 0)
            assertTrue("完整九目标检查必须早于三区加热", heating > guard)
            assertFalse("不能只按非空目标启动新批次", block.contains("if (targets.isNotEmpty())"))
        }
    }

    @Test
    fun lowToHighTransition_shouldNotLetPendingHighHeatBatchBlockOldLowHeatReturn() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun lowTrafficModeTransition(")
        assertTrue("必须存在低转高过渡调度函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 低转高期间", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val pendingHandling = functionBlock.indexOf("handlePendingSupplementBatchIfAny(")
        val resumedOnlyReturn = functionBlock.indexOf("PendingSupplementBatchHandling.RESUMED", pendingHandling)
        val supplementBlockFlag = functionBlock.indexOf("pendingHighHeatBatchBlocksSupplement", pendingHandling)
        val oldLowHeatReturn = functionBlock.indexOf("planOldLowHeatReturnGroups(", pendingHandling)

        assertTrue("低转高入口必须先检查未完成 25~33 补肠批次", pendingHandling >= 0)
        assertTrue("只有实际续补 RESUMED 后才应提前 return", resumedOnlyReturn > pendingHandling)
        assertTrue("等待人工确认/blocked 只能阻止继续补 25~33，不能阻止 13~18 回搬判断", supplementBlockFlag > resumedOnlyReturn)
        assertTrue("13~18 旧补热区回搬判断必须位于 pending 批次检查之后并仍可执行", oldLowHeatReturn > supplementBlockFlag)
    }

    @Test
    fun highToLowTransition_shouldPreserveManualHighHeatBatchIntentForTenMinuteWindow() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun highTrafficModeTransition(")
        assertTrue("必须存在高转低过渡调度函数", functionStart >= 0)
        val functionEnd = source.indexOf("private suspend fun finishHighToLowCloseout(", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val manualConfirmCheck = functionBlock.indexOf("pendingSupplementBatchRequiresManualConfirm()")
        val highHeatTypeCheck = functionBlock.indexOf("SupplementBatchType.HIGH_HEAT_25_33", manualConfirmCheck)
        val clearIntent = functionBlock.indexOf("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(", manualConfirmCheck)
        val pendingResume = functionBlock.indexOf("handlePendingSupplementBatchIfAny(", clearIntent)
        val checkHighHeat = functionBlock.indexOf("checkHighHeatSausage(list)", manualConfirmCheck)

        assertTrue("高转低必须先检查25~33人工确认批次", manualConfirmCheck >= 0 && highHeatTypeCheck > manualConfirmCheck)
        assertTrue("高转低不能在25~33人工确认批次存在时无条件清意图", clearIntent > highHeatTypeCheck)
        assertTrue("高转低已确认的25~33未完成故障批次必须先尝持续补，再判断残缺回搬", pendingResume > clearIntent)
        assertTrue("25~33成熟/残缺回搬判断必须在续补入口之后执行，才能避免确认后拆批", checkHighHeat > pendingResume)
    }

    @Test
    fun highHeatIntentCleanup_shouldUseManualConfirmationGuardForAllTwentyFiveToThirtyThreePaths() {
        val highSource = sourceText()
        val operateSource = operateSourceText()

        assertTrue(
            "高并发模式内的25~33清批次必须使用人工确认保护方法",
            highSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"高转低过渡不再启动新的25~33补肠\")") &&
                highSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"写入高转低过渡态后停止25~33未完成补肠意图\")")
        )
        assertTrue(
            "过保丢弃触发高转低/残留消化时也必须保护25~33人工确认批次",
            operateSource.contains("clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(\"过保丢弃进入高转低/残留消化，停止25~33未完成补肠意图\")")
        )
    }

    @Test
    fun highHeatIntentCleanupGuard_shouldPreserveConfirmedFaultRecoveryBatchUntilResumeCompletes() {
        val source = operateSourceText()
        val functionStart = source.indexOf("fun clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation(")
        assertTrue("必须存在25~33清批次保护方法", functionStart >= 0)
        val functionEnd = source.indexOf("fun activeSupplementBatchTargetPositions(", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue("保护条件必须限定25~33补热批次", functionBlock.contains("intent?.type == SupplementBatchType.HIGH_HEAT_25_33"))
        assertTrue("10分钟内确认后 requiresManualConfirm=false 但 remaining 仍在，不能清掉待续补目标", functionBlock.contains("intent.remainingTargets().isNotEmpty()"))
        assertTrue("保护条件不能只看 requiresManualConfirm，还必须保留已确认但带 failureAtMs 的故障恢复批次", functionBlock.contains("(intent.requiresManualConfirm || intent.failureAtMs > 0L)"))
    }

    @Test
    fun transitionBranches_shouldPersistPanStateAfterStateOnlyCorrections() {
        val source = sourceText()
        val functionStart = source.indexOf("suspend fun highTrafficMode(")
        assertTrue("必须存在高并发主处理器", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 低并发内部高并发残留收口专用收尾。", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue(
            "低转高分支可能只做 13~18 状态纠偏而不搬盘，返回前必须保存烤盘",
            functionBlock.contains("lowTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)\n                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())")
        )
        assertTrue(
            "高转低分支可能只做等待回搬/过保状态处理，返回前必须保存烤盘",
            functionBlock.contains("highTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)\n                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())")
        )
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun operateSourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("缺少函数：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start).let { if (it >= 0) it else source.length }
        return source.substring(start, end)
    }
}
