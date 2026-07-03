package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupplementSupplyDegradeSourceTest {

    @Test
    fun serialTasteSupplement_shouldUseDetailedSupplyDegradeResultWithoutDeviceMaintenance() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausageToKaoPanByTasteDetailed(")
        assertTrue("必须存在按口味补肠详细结果函数", functionStart >= 0)
        val functionEnd = source.indexOf("private fun atomicFailureCodeOrZero", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue(
            "串行按口味补肠必须只按 in2 / IR_PICK_FAILED 降级",
            functionBlock.contains("SupplementPickFailurePolicy.isSerialIn2IrPickFailure(")
        )
        val noCandidateStart = functionBlock.indexOf("if (candidates.isEmpty())")
        assertTrue("必须显式处理无候选箱分支", noCandidateStart >= 0)
        val noCandidateEnd = functionBlock.indexOf("val irPickFailedBoxNos", noCandidateStart)
            .let { if (it >= 0) it else functionBlock.length }
        val noCandidateBlock = functionBlock.substring(noCandidateStart, noCandidateEnd)
        assertTrue(
            "无同口味候选箱应按供给不可用跳过，不应锁存整机硬故障",
            noCandidateBlock.contains("SupplementMoveResult.SupplyDegraded(")
        )
        assertFalse(
            "无同口味候选箱不应返回 HardFailure",
            noCandidateBlock.contains("HardFailure")
        )
        assertTrue(
            "串行同口味候选箱全部取肠失败应返回 SupplyDegraded",
            functionBlock.contains("SupplementMoveResult.SupplyDegraded(")
        )
        assertTrue(
            "串行供给降级分支不能把设备切维护",
            !functionBlock.contains("markDeviceErrorAndUpload(")
        )
    }

    @Test
    fun serialBatchFailure_shouldSkipSupplyDegradedTargetInsteadOfManualConfirmation() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausagesToKaoPansByTasteSerial(")
        assertTrue("必须存在串行补肠批次函数", functionStart >= 0)
        val functionEnd = source.indexOf("suspend fun moveSausagesToKaoPansByTaste(", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        assertTrue(
            "SupplyDegraded 必须标记 skipped，避免 remainingTargets 卡人工确认",
            functionBlock.contains("markSkippedBySupplyDegrade(")
        )
        assertTrue(
            "硬故障仍必须保留 requiresManualConfirm=true",
            functionBlock.contains("SupplementMoveResult.HardFailure") &&
                functionBlock.contains("requiresManualConfirm = true")
        )
    }

    @Test
    fun pipelineFailure_shouldCheckHeatSemiFaultBeforeSupplyDegradeAndMaintenance() {
        val source = sourceText()
        val functionStart = source.indexOf("private fun handlePipelineSupplementFailure(")
        assertTrue("必须存在预取流水线失败处理函数", functionStart >= 0)
        val functionEnd = source.indexOf("private fun describeAtomicActionResult", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val heatSemiFaultCheck = functionBlock.indexOf("LowerPlatformIsolationPolicy.classifyForSupplementBatch(")
        val degradeCheck = functionBlock.indexOf("SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(")
        val initializationHardFailureCheck = functionBlock.indexOf("SupplementBatchIntentRepository.isInitializationBatch(batchType)")
        assertTrue("补热区半故障判断必须存在", heatSemiFaultCheck >= 0)
        assertTrue("降级判断必须存在", degradeCheck >= 0)
        assertTrue("初始化硬故障判断必须存在", initializationHardFailureCheck >= 0)
        assertTrue("补热区半故障判断必须先于供给降级，避免 in2 被静默跳过", heatSemiFaultCheck < degradeCheck)
        assertTrue("初始化硬故障判断必须先于供给降级", initializationHardFailureCheck < degradeCheck)
        assertTrue(
            "预取供给降级必须标记 skipped",
            functionBlock.contains("markSkippedBySupplyDegrade(")
        )
    }

    @Test
    fun serialTasteSupplement_shouldPassBatchTypeSoHeatAndInitializationCanDiverge() {
        val source = sourceText()
        val serialStart = source.indexOf("private suspend fun moveSausagesToKaoPansByTasteSerial(")
        assertTrue("必须存在串行补肠批次函数", serialStart >= 0)
        val serialEnd = source.indexOf("suspend fun moveSausagesToKaoPansByTaste(", serialStart)
            .let { if (it >= 0) it else source.length }
        val serialBlock = source.substring(serialStart, serialEnd)
        assertTrue(
            "串行补肠必须把 batchType 传入按口味补肠，才能区分补热半故障和初始化硬故障",
            serialBlock.contains("batchType = batchType")
        )

        val byTasteStart = source.indexOf("private suspend fun moveSausageToKaoPanByTasteDetailed(")
        assertTrue("必须存在按口味补肠详细结果函数", byTasteStart >= 0)
        val byTasteEnd = source.indexOf("private fun atomicFailureCodeOrZero", byTasteStart)
            .let { if (it >= 0) it else source.length }
        val byTasteBlock = source.substring(byTasteStart, byTasteEnd)
        assertTrue(
            "初始化批次任意 in 失败必须直接硬故障，不能继续切箱或供给降级",
            byTasteBlock.contains("val isInitializationBatch = batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true") &&
                byTasteBlock.contains("if (isInitializationBatch)")
        )
        assertTrue(
            "补热半故障必须优先返回 LowerPlatformIsolated",
            byTasteBlock.indexOf("moveResult.lowerPlatformIsolationReason != null") in 0 until
                byTasteBlock.indexOf("SupplementPickFailurePolicy.isSerialIn2IrPickFailure(")
        )
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
