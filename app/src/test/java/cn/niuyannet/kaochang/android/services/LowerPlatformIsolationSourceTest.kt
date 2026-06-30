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
        val isolationCheck = functionBlock.indexOf("LowerPlatformIsolationPolicy.isSupplementActionIsolatable(")
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
        val isolationCheck = functionBlock.indexOf("LowerPlatformIsolationPolicy.isSupplementActionIsolatable(")
        val markFailure = functionBlock.indexOf("supplementBatchIntentRepository.markFailure(")
        val maintenance = functionBlock.indexOf("enterActionGroupMaintenance(")

        assertTrue("供给降级判断必须存在", supplyDegradeCheck >= 0)
        assertTrue("下平台隔离判断必须存在", isolationCheck >= 0)
        assertTrue("下平台隔离判断应在供给降级之后，避免覆盖 in2/IR_PICK_FAILED", isolationCheck > supplyDegradeCheck)
        assertTrue("下平台隔离判断必须先于 markFailure", markFailure > isolationCheck)
        assertTrue("下平台隔离判断必须先于 enterActionGroupMaintenance", maintenance > isolationCheck)
        assertTrue("流水线隔离分支必须标记当前目标跳过", functionBlock.contains("markSkippedBySupplyDegrade("))
        assertTrue("流水线隔离分支必须写入 ISOLATED 运行态", functionBlock.contains("markLowerPlatformIsolatedRuntimeState("))
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
