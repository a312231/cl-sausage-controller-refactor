package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LowerPlatformIsolationSourceTest {

    @Test
    fun serialSupplement_shouldCheckLowerPlatformIsolationBeforeDeviceMaintenance() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        assertTrue("必须存在单根补肠详细函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 烤盘间搬移。", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)
        val isolationCheck = functionBlock.indexOf("LowerPlatformIsolationPolicy.classifySupplementActionFailure(")
        val maintenance = functionBlock.indexOf("markActionGroupFailure(\"补肠\", actionGroupResult)")

        assertTrue("单根补肠失败必须存在下平台隔离判断", isolationCheck >= 0)
        assertTrue("下平台隔离判断必须先于整机维护", maintenance > isolationCheck)
        assertTrue("隔离分支必须写入 ISOLATED 运行态", functionBlock.contains("markLowerPlatformIsolatedRuntimeState("))
    }

    @Test
    fun pipelineFailure_shouldCheckLowerPlatformIsolationBeforeManualFailureAndMaintenance() {
        val source = sourceText()
        val functionStart = source.indexOf("private fun handlePipelineSupplementFailure(")
        assertTrue("必须存在预取流水线失败处理函数", functionStart >= 0)
        val functionEnd = source.indexOf("private fun describeAtomicActionResult", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)
        val supplyDegradeCheck = functionBlock.indexOf("SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(")
        val isolationCheck = functionBlock.indexOf("LowerPlatformIsolationPolicy.classifySupplementActionFailure(")
        val markFailure = functionBlock.indexOf("supplementBatchIntentRepository.markFailure(", isolationCheck)
        val maintenance = functionBlock.indexOf("enterActionGroupMaintenance(")

        assertTrue("供给降级判断必须存在", supplyDegradeCheck >= 0)
        assertTrue("下平台隔离判断必须存在", isolationCheck >= 0)
        assertTrue("下平台隔离判断应在供给降级之后，避免覆盖 in2/IR_PICK_FAILED", isolationCheck > supplyDegradeCheck)
        assertTrue("下平台隔离分支必须能为 in5 残肠风险 markFailure", markFailure > isolationCheck)
        assertTrue("下平台隔离判断必须先于 enterActionGroupMaintenance", maintenance > isolationCheck)
        assertTrue("流水线隔离分支必须支持低风险目标跳过", functionBlock.contains("markSkippedBySupplyDegrade("))
        assertTrue("流水线隔离分支必须支持 in5 残肠风险人工确认", functionBlock.contains("requiresManualConfirm = true"))
        assertTrue("流水线隔离分支必须写入 ISOLATED 运行态", functionBlock.contains("markLowerPlatformIsolatedRuntimeState("))
        val isolationReturn = functionBlock.indexOf("requiresManualConfirm=")
        val fallbackMaintenance = functionBlock.indexOf("if (result.hasPrefetchedNext)", isolationReturn)
        assertTrue("流水线隔离分支必须在维护兜底前显式 return", isolationReturn >= 0 && fallbackMaintenance > isolationReturn)
    }

    @Test
    fun serialAndPipelineIn5Isolation_shouldRequireManualConfirmWithoutFallingIntoMaintenanceFallback() {
        val source = sourceText()
        val pipelineStart = source.indexOf("private fun handlePipelineSupplementFailure(")
        val pipelineEnd = source.indexOf("private fun describeAtomicActionResult", pipelineStart)
            .let { if (it >= 0) it else source.length }
        val pipelineBlock = source.substring(pipelineStart, pipelineEnd)
        val pipelineIsolation = pipelineBlock.indexOf("lowerPlatformIsolationDecision")
        val pipelineManualFailure = pipelineBlock.indexOf("requiresManualConfirm = true", pipelineIsolation)
        val pipelineReturn = pipelineBlock.indexOf("return", pipelineManualFailure)
        val pipelineMaintenance = pipelineBlock.indexOf("enterActionGroupMaintenance(", pipelineReturn)

        assertTrue("流水线必须先分类下平台隔离", pipelineIsolation >= 0)
        assertTrue("流水线 in5 隔离需人工确认必须调用 markFailure", pipelineManualFailure > pipelineIsolation)
        assertTrue("流水线 in5 隔离需人工确认必须显式 return", pipelineReturn > pipelineManualFailure)
        assertTrue("流水线 in5 隔离不能落到维护兜底", pipelineMaintenance > pipelineReturn)

        val serialStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val serialEnd = source.indexOf("/**\n     * 烤盘间搬移。", serialStart)
            .let { if (it >= 0) it else source.length }
        val serialBlock = source.substring(serialStart, serialEnd)

        assertTrue("串行补肠必须计算下平台隔离三态", serialBlock.contains("classifySupplementActionFailure("))
        assertTrue("串行补肠必须把人工确认要求传给批次层", serialBlock.contains("lowerPlatformIsolationRequiresManualConfirm"))
        assertTrue("串行批次层必须保留 in5 残肠目标并要求人工确认", source.contains("result.requiresManualConfirm") &&
            source.contains("supplementBatchIntentRepository.markFailure("))
    }

    @Test
    fun serialTasteSupplement_shouldTreatNoCandidateAsSupplyUnavailableNotHardFailure() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausageToKaoPanByTasteDetailed(")
        assertTrue("必须存在按口味补肠详细结果函数", functionStart >= 0)
        val functionEnd = source.indexOf("private fun atomicFailureCodeOrZero", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)
        val noCandidateStart = functionBlock.indexOf("if (candidates.isEmpty())")
        assertTrue("必须显式处理无候选箱分支", noCandidateStart >= 0)
        val noCandidateEnd = functionBlock.indexOf("val irPickFailedBoxNos", noCandidateStart)
            .let { if (it >= 0) it else functionBlock.length }
        val noCandidateBlock = functionBlock.substring(noCandidateStart, noCandidateEnd)

        assertTrue("无同口味候选箱应按供给不可用跳过", noCandidateBlock.contains("SupplementMoveResult.SupplyDegraded("))
        assertFalse("无同口味候选箱不应锁存整机硬故障", noCandidateBlock.contains("SupplementMoveResult.HardFailure("))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
