package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActionPhysicalStateReducerSourceTest {

    @Test
    fun serialSupplement_shouldWireTelemetryToPhysicalStateReducerAndAvoidDoubleDeduction() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        assertTrue("必须存在单根补肠详细函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 烤盘间搬移。", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("单根补肠必须把动作 telemetry 接入本地物理状态 reducer", block.contains("applySupplementPhysicalTelemetry("))
        assertTrue("单根补肠必须记录 in2 是否已扣箱", block.contains("physicalBoxDeducted"))
        assertTrue("in2 已扣箱后，in7/整组成功收口不能再次扣箱", block.contains("applySuccessfulSupplementPanStateWithoutBoxDeduction("))
    }

    @Test
    fun pipelineSupplement_shouldWireTelemetryToPhysicalStateReducerAndAvoidDoubleDeduction() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausagesToKaoPansByTasteInternal(")
        assertTrue("必须存在批量补肠内部函数", functionStart >= 0)
        val functionEnd = source.indexOf("private data class AppliedSupplementMove", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("预取流水线必须把动作 telemetry 接入本地物理状态 reducer", block.contains("applySupplementPhysicalTelemetry("))
        assertTrue("预取流水线必须按目标记录已扣箱状态", block.contains("physicallyDeductedTargetPanNos"))
        assertTrue("预取流水线必须按目标记录已落盘状态", block.contains("physicallyPlacedTargetPanNos"))
        assertTrue("流水线完成收口必须把动作级状态传给最终回写，避免双扣", block.contains("boxAlreadyDeducted ="))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
