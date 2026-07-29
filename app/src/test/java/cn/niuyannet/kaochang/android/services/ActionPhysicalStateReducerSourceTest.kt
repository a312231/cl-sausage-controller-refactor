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
        assertTrue("预取流水线必须按目标记录 in2 扣箱不可信状态", block.contains("stockInconsistentTargetPanNos"))
        assertTrue("流水线完成收口必须把动作级状态传给最终回写，避免双扣", block.contains("boxAlreadyDeducted ="))
        assertTrue("流水线完成收口失败时不能继续标记批次完成", block.contains("if (appliedMove == null)"))
        assertTrue("扣箱不可信目标不能继续标记批次完成", block.contains("targetPanNo in stockInconsistentTargetPanNos"))
    }

    @Test
    fun supplementPhysicalTelemetryReducer_shouldVerifyTelemetryCoordinatesBeforeApplyingState() {
        val source = sourceText()
        val functionStart = source.indexOf("private fun applySupplementPhysicalTelemetry(")
        assertTrue("必须存在补肠动作级物理状态 reducer", functionStart >= 0)
        val functionEnd = source.indexOf("private fun applySuccessfulSupplementMove(", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("reducer 回写 in2/in7 前必须校验 telemetry 与计划盘箱一致", block.contains("isSupplementTelemetryForPlannedMove("))
        assertTrue("坐标不一致时必须阻止本地扣箱", block.contains("已阻止本地扣箱"))
        assertTrue("坐标不一致时必须阻止本地落盘", block.contains("已阻止本地落盘"))
    }

    @Test
    fun supplementPhysicalTelemetryReducer_shouldPublishMqttPhysicalDeltaAfterTrustedPhysicalWrite() {
        val source = sourceText()
        val functionStart = source.indexOf("private fun applySupplementPhysicalTelemetry(")
        assertTrue("必须存在补肠动作级物理状态 reducer", functionStart >= 0)
        val functionEnd = source.indexOf("private fun applySuccessfulSupplementMove(", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("in2 扣箱成功后必须触发 MQTT 物理增量", block.contains("action_physical:in2:picked_from_box"))
        assertTrue("in2 扣箱成功后必须上报变化箱位", block.contains("PanPhysicalStateDeltaReporter.reportStockChanged("))
        assertTrue("in7 落盘成功后必须触发 MQTT 物理增量", block.contains("action_physical:in7:placed_on_pan"))
        assertTrue("in7 落盘成功后必须上报变化烤盘", block.contains("PanPhysicalStateDeltaReporter.reportPanChanged("))
    }

    @Test
    fun supplementCompletionLog_shouldExplainStockWasAlreadyDeductedAtIn2() {
        val source = sourceText()
        val functionStart = source.indexOf("private fun applySuccessfulSupplementMove(")
        assertTrue("必须存在补肠成功收口函数", functionStart >= 0)
        val functionEnd = source.indexOf("private fun reportSupplementCompletionPhysicalDeltaIfNeeded(", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue(
            "in2 已扣箱时日志必须说明当前值不是二次扣减前后值",
            block.contains("库存已在in2阶段扣减，当前库存=\${sourceBox.num}")
        )
    }

    @Test
    fun singleSupplement_shouldNotReportSuccessWhenPhysicalStockIsInconsistent() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        assertTrue("必须存在单根补肠详细函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 烤盘间搬移。", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("单根补肠必须记录 in2 扣箱不可信状态", block.contains("physicalStockInconsistent"))
        assertTrue("已落盘但扣箱不可信时必须进入维护", block.contains("已落盘但来源箱扣减不可信"))
        assertTrue("扣箱不可信时不能返回成功", block.contains("stateAppliedForThisMove = !physicalStockInconsistent"))
    }

    @Test
    fun formalOrderTakeSausage_shouldWireOut1TelemetryToPhysicalPanReducer() {
        val source = sourceText()
        val functionStart = source.indexOf("suspend fun takeSausageResult(")
        assertTrue("必须存在正式订单出餐函数", functionStart >= 0)
        val functionEnd = source.indexOf("internal fun applySuccessfulTakePanState", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("正式出餐必须处理 out1 物理离盘检查点", block.contains("applyOutPhysicalTelemetry("))
        assertTrue("正式出餐仍必须保留 out4/订单终态事件处理", block.contains("persistOutTerminalEventFromTelemetry("))
        assertOutPhysicalReducerUploadsSnapshot(source)
    }

    @Test
    fun manualTakeSausage_shouldWireOut1TelemetryToPhysicalPanReducer() {
        val source = sourceText()
        val functionStart = source.indexOf("suspend fun takeSausage(kaoPan: KaoPan)")
        assertTrue("必须存在维护手动取肠函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 正式订单出餐入口。", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("维护手动取肠必须处理 out1 物理离盘检查点", block.contains("applyOutPhysicalTelemetry("))
        assertOutPhysicalReducerUploadsSnapshot(source)
    }

    private fun assertOutPhysicalReducerUploadsSnapshot(source: String) {
        val functionStart = source.indexOf("private fun applyOutPhysicalTelemetry(")
        assertTrue("必须存在出餐动作级物理状态 reducer", functionStart >= 0)
        val functionEnd = source.indexOf("private fun buildPipelineSupplementPlan(", functionStart)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(functionStart, functionEnd)

        assertTrue("out1 reducer 必须识别 OUT_REMOVED_FROM_PAN", block.contains("shouldApplyOutRemovedFromPan("))
        assertTrue("out1 离盘后必须触发 MQTT 物理增量", block.contains("action_physical:out1:removed_from_pan"))
        assertTrue("out1 离盘后必须上报变化烤盘", block.contains("PanPhysicalStateDeltaReporter.reportPanChanged("))
        assertTrue("out1 reducer 必须耐久保存当前烤盘列表", block.contains("saveKaoPanListDurable("))
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
