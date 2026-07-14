package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PanPhysicalStateDeltaActionWiringSourceTest {

    @Test
    fun supplementReducer_shouldPublishPhysicalDeltaOnlyAfterLocalStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractBetween(
            source,
            "private fun applySupplementPhysicalTelemetry(",
            "private fun applySuccessfulSupplementMove("
        )

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "PanPhysicalStateDeltaReporter.reportStockChanged(",
            "in2 物理增量必须在本地箱库存保存后发布"
        )
        assertTrue("in2 必须用 action_physical_in2 source", body.contains("source = \"action_physical_in2\""))
        assertTrue("in2 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:in2:picked_from_box"))

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "in7 物理增量必须在本地烤盘保存后发布"
        )
        assertTrue("in7 必须用 action_physical_in7 source", body.contains("source = \"action_physical_in7\""))
        assertTrue("in7 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:in7:placed_on_pan"))
    }

    @Test
    fun outReducer_shouldPublishPhysicalDeltaOnlyAfterPanClearIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractBetween(
            source,
            "private fun applyOutPhysicalTelemetry(",
            "private fun buildPipelineSupplementPlan("
        )

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "out1 物理增量必须在本地清盘保存后发布"
        )
        assertTrue("out1 必须用 action_physical_out1 source", body.contains("source = \"action_physical_out1\""))
        assertTrue("out1 必须继续保留 HTTP 快照兜底触发点", body.contains("action_physical:out1:removed_from_pan"))
    }

    @Test
    fun panMoveInternal_shouldPublishPhysicalDeltaAfterMoveStateWriteAndKeepSchedulingGate() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "private suspend fun moveKaoPanToKaoPanInternal(")

        assertTrue("搬盘核心必须继续保留自动调度门禁", body.contains("AutoMechanicalDispatchPolicy.evaluate("))
        assertTrue("搬盘核心必须继续保留原可售投影同步", body.contains("KaoPanCloudSyncReporter.uploadIfSellableStateChanged("))
        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPansChanged(",
            "搬盘物理增量必须在源/目标盘状态保存后发布"
        )
        assertTrue("搬盘必须同时上报源盘和目标盘", body.contains("changedPans = listOf(kaoPan, newKaoPan)"))
        assertTrue("搬盘必须用 pan_move_success source", body.contains("source = \"pan_move_success\""))
    }

    @Test
    fun manualPanPositionClear_shouldPublishPhysicalDeltaAfterSavedAndKeepSnapshotFallback() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/ManualPanPositionDataClearExecutor.kt")
        val body = extractFunctionBody(source, "fun execute(")

        assertOrder(
            body,
            "dependencies.savePansSync(result.pans)",
            "dependencies.reportPhysicalDelta(",
            "维护页清盘物理增量必须在本地清盘保存后发布"
        )
        assertOrder(
            body,
            "dependencies.reportPhysicalDelta(",
            "dependencies.uploadSnapshot(\"maintenance_clear_pan_position:${'$'}positionSn\")",
            "维护页清盘必须保留 HTTP 快照兜底，且在物理增量之后"
        )
        assertTrue("维护页清盘必须用 maintenance_clear_pan_position source", source.contains("\"maintenance_clear_pan_position\""))
        assertTrue("维护页清盘默认 reporter 必须调用物理增量上报", source.contains("PanPhysicalStateDeltaReporter.reportPanChanged("))
    }

    @Test
    fun discardSausage_shouldPublishPhysicalDeltaAfterSuccessfulClearIsSavedAndKeepSnapshotFallback() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun discardSausage(")

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "丢弃成功清盘物理增量必须在本地清盘保存后发布"
        )
        assertOrder(
            body,
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "KaoPanCloudSyncReporter.uploadIfSellableStateChanged(",
            "丢弃成功清盘必须保留 HTTP 快照兜底，且在物理增量之后"
        )
        assertTrue("丢弃成功清盘必须用 discardSausage_success source", body.contains("source = \"discardSausage_success\""))
    }

    @Test
    fun physicalDeltaReporter_shouldUseAction2PublisherNotOrderAction() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/PanPhysicalStateDeltaReporter.kt")

        assertTrue("reporter 必须调用 SendServerHelper.publishPanPhysicalStateDelta", source.contains("SendServerHelper.publishPanPhysicalStateDelta("))
        assertTrue("reporter 不应出现 action=1 订单/动作指令常量", !source.contains("ACTION_ORDER_EVENT"))
    }

    @Test
    fun actionGroupRecovery_shouldPublishPhysicalDeltaAfterRecoveredStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val recoveredSupplement = extractBetween(
            source,
            "private fun handleRecoveredSuccess(",
            "private fun handleRecoveredDiscarded("
        )
        assertOrder(
            recoveredSupplement,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "reportSupplementCompletionPhysicalDeltaIfNeeded(",
            "补肠恢复成功补偿物理增量必须在本地盘箱保存后发布"
        )
        assertTrue("补肠恢复必须使用同步保存烤盘", recoveredSupplement.contains("KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())"))
        assertTrue("补肠恢复必须使用同步保存箱位", recoveredSupplement.contains("KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())"))
        assertTrue("补肠恢复必须上报库存和烤盘变化", recoveredSupplement.contains("reportStock = true") && recoveredSupplement.contains("reportPan = true"))
        assertTrue("补肠恢复必须使用恢复专用 source", recoveredSupplement.contains("source = \"action_recovery_in\""))

        val recoveredOut = extractBetween(
            source,
            "private fun clearRecoveredOutPan(",
            "private fun markDeviceErrorAndUpload"
        )
        assertOrder(
            recoveredOut,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "reportOutCompletionPhysicalDelta(",
            "出餐/维护取肠恢复成功补偿物理增量必须在本地清盘保存后发布"
        )
        assertTrue("出餐恢复必须使用恢复专用 source", recoveredOut.contains("source = \"action_recovery_out\""))
    }

    @Test
    fun supplementCompletionFallback_shouldPublishOnlyMissingPhysicalDeltasAfterSave() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val singleSupplement = extractBetween(
            source,
            "private suspend fun moveSausageToKaoPanDetailed(",
            "/**\n     * 烤盘间搬移。"
        )
        assertOrder(
            singleSupplement,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "reportSupplementCompletionPhysicalDeltaIfNeeded(",
            "单根补肠完成兜底物理增量必须在本地盘箱保存后发布"
        )
        assertTrue("单根补肠兜底不能重复已由 in2 telemetry 上报的扣箱", singleSupplement.contains("completionReportStock = !physicalBoxDeducted"))
        assertTrue("单根补肠兜底不能重复已由 in7 telemetry 上报的落盘", singleSupplement.contains("completionReportPan = !physicalPanPlaced"))
        assertTrue("单根补肠兜底必须使用完成收口 source", singleSupplement.contains("source = \"action_completion_in\""))

        val pipelineCompletion = extractBetween(
            source,
            "val appliedMove = applySuccessfulSupplementMove(",
            "logD(\n                LOG_SUPPLEMENT,\n                \"补肠预取流水线单根状态已回写"
        )
        assertOrder(
            pipelineCompletion,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "reportSupplementCompletionPhysicalDeltaIfNeeded(",
            "预取流水线单根完成兜底物理增量必须在本地盘箱保存后发布"
        )
        assertTrue("预取流水线兜底必须使用完成收口 source", pipelineCompletion.contains("source = \"action_completion_pipeline\""))
    }

    @Test
    fun outCompletionFallback_shouldPublishPanDeltaOnlyWhenOut1TelemetryDidNotAlreadyClearPan() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun takeSausageResult(")

        assertTrue("正式出餐必须记录 out1 telemetry 是否已清盘", body.contains("var outPanClearedByTelemetry = false"))
        assertTrue("正式出餐必须在 out1 telemetry 成功后置位", body.contains("outPanClearedByTelemetry = true"))
        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "if (!outPanClearedByTelemetry) {",
            "正式出餐完成兜底物理增量必须在本地清盘保存后判断"
        )
        assertTrue("正式出餐兜底必须避免重复 out1 物理增量", body.contains("if (!outPanClearedByTelemetry) {"))
        assertTrue("正式出餐兜底必须使用完成收口 source", body.contains("source = \"action_completion_out\""))
    }

    @Test
    fun maintenanceManualOut_shouldPublishPhysicalDeltaAfterSuccessfulClearIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractFunctionBody(source, "suspend fun takeSausage(")

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "reportOutCompletionPhysicalDelta(",
            "维护手动取肠完成物理增量必须在本地清盘保存后发布"
        )
        assertTrue("维护手动取肠必须使用专用 source", body.contains("source = \"maintenance_manual_out_completion\""))
        assertTrue("维护手动取肠必须记录 out1 telemetry 是否已清盘", body.contains("var outPanClearedByTelemetry = false"))
        assertTrue("维护手动取肠必须在 out1 telemetry 成功后置位", body.contains("outPanClearedByTelemetry = true"))
        assertTrue("维护手动取肠完成兜底必须避免重复 out1 物理增量", body.contains("if (!outPanClearedByTelemetry) {"))
    }

    @Test
    fun stablePanRemoval_shouldRefreshTemperatureOnlyAfterTerminalStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")

        val formalOut = extractFunctionBody(source, "suspend fun takeSausageResult(")
        assertOrder(
            formalOut,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "refreshTemperatureAfterStablePanMutation(",
            "正式出餐必须在终态盘位保存后重算温控"
        )

        val manualOut = extractFunctionBody(source, "suspend fun takeSausage(")
        assertOrder(
            manualOut,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "refreshTemperatureAfterStablePanMutation(",
            "手动取肠必须在终态盘位保存后重算温控"
        )

        val discard = extractFunctionBody(source, "suspend fun discardSausage(")
        assertOrder(
            discard,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "refreshTemperatureAfterStablePanMutation(",
            "丢弃成功必须在终态盘位保存后重算温控"
        )

        val recoveredOut = extractBetween(
            source,
            "private fun clearRecoveredOutPan(",
            "private fun markDeviceErrorAndUpload"
        )
        assertOrder(
            recoveredOut,
            "KaoPanHelper.saveKaoPanListSync(KaoPanHelper.getKaoPanList())",
            "refreshTemperatureAfterStablePanMutation(",
            "出餐恢复成功必须在终态盘位保存后重算温控"
        )

        val physicalOut = extractBetween(
            source,
            "private fun applyOutPhysicalTelemetry(",
            "private fun buildPipelineSupplementPlan("
        )
        assertTrue("out1 中间物理检查点不得触发温控重算", !physicalOut.contains("refreshTemperatureAfterStablePanMutation("))

        val clearOnly = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/ManualPanPositionDataClearExecutor.kt")
        assertTrue("仅清除本位数据不得触发普通温控重算", !clearOnly.contains("refreshTemperatureAfterStablePanMutation("))
    }

    @Test
    fun irPickFailureBoxMark_shouldPublishStockDeltaAfterFailureMarkIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val serialBody = extractBetween(
            source,
            "private suspend fun moveSausageToKaoPanByTasteDetailed(",
            "private fun continueAfterSupplementBatchFailure"
        )
        assertOrder(
            serialBody,
            "SupplementPickFailurePolicy.recordIrPickFailedBox(box, candidates)",
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "串行 in2 失败箱必须先标记并同步保存"
        )
        assertOrder(
            serialBody,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "reportIrPickFailedStocksDelta(",
            "串行 in2 失败箱物理增量必须在箱状态保存后发布"
        )
        assertTrue("串行 in2 失败箱必须使用专用 source", serialBody.contains("source = \"action_failure_serial_in2_ir_pick\""))

        val pipelineHelper = extractFunctionBody(source, "private fun recordIrPickFailureAndSave(")
        assertOrder(
            pipelineHelper,
            "KaoPanHelper.saveKaoPanBoxListSync(boxList)",
            "reportIrPickFailedStocksDelta(",
            "预取流水线单箱 in2 失败箱物理增量必须在箱状态保存后发布"
        )
        assertTrue("预取流水线单箱 in2 失败箱必须使用专用 source", pipelineHelper.contains("source = \"action_failure_pipeline_in2_ir_pick\""))
    }

    @Test
    fun maintenanceBoxOperations_shouldPublishStockDeltaAfterManualStockSave() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceBoxFragment.kt")
        val refillBody = extractFunctionBody(source, "private fun showTasteSelectDialog(")

        assertTrue("维护箱位页面必须引入 physical delta reporter", source.contains("PanPhysicalStateDeltaReporter"))
        assertOrder(
            source,
            "KaoPanHelper.saveKaoPanBoxListSync(KaoPanHelper.getKaoPanBoxList())",
            "PanPhysicalStateDeltaReporter.reportStocksChanged(",
            "维护清空/加满烤肠箱必须在本地箱状态保存后发布物理增量"
        )
        assertTrue("维护清空箱必须使用专用 source", source.contains("source = \"maintenance_box_clear\""))
        assertTrue("维护加满箱必须使用专用 source", source.contains("source = \"maintenance_box_refill\""))
        assertOrder(
            refillBody,
            "val snapshots = selectedBoxes.map { box ->",
            "for (box in selectedBoxes) {",
            "加满烤肠箱必须在改内存前记录原始快照"
        )
        assertTrue("加满烤肠箱业务失败必须恢复本地箱状态", refillBody.contains("restoreClearSnapshots(snapshots)\n                        ToastUtils.showShort(ApiResponseCompat.messageText(json, \"设置失败\"))"))
        assertTrue("加满烤肠箱接口失败必须恢复本地箱状态", refillBody.contains("restoreClearSnapshots(snapshots)\n                    ToastUtils.showShort(\"添加失败\")"))
    }

    @Test
    fun maintenancePanResetOperations_shouldPublishPanDeltaAfterManualPanReset() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")

        assertTrue("维护烤盘页面必须引入 physical delta reporter", source.contains("PanPhysicalStateDeltaReporter"))
        assertOrder(
            source,
            "KaoPanHelper.clearKaoPan()\n                    KaoPanHelper.init()",
            "PanPhysicalStateDeltaReporter.reportPansChanged(",
            "维护清空/重置烤盘必须在本地烤盘重建后发布物理增量"
        )
        assertTrue("维护清空烤盘必须使用专用 source", source.contains("source = \"maintenance_pan_clear_all\""))
        assertTrue("维护重置烤盘必须使用专用 source", source.contains("source = \"maintenance_pan_reset_all\""))
    }

    @Test
    fun faultHeatRecovery_shouldSyncOnlyAfterTemperatureWritesSucceed() {
        val algorithm = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val operate = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val enableBody = extractFunctionBody(algorithm, "fun enableRoastAlgorithm(")
        val applyTemperatureBody = extractFunctionBody(operate, "private fun applyConfiguredTemperatureByPanStateInternal(")
        val setAreaBody = extractFunctionBody(operate, "private fun setAreaTargetTemperature(")

        assertTrue("算法开启必须通过统一温控恢复入口", enableBody.contains("restoreHeatingStateFromCurrentPans("))
        val successCheck = applyTemperatureBody.indexOf("allWritesSucceeded")
        val syncPause = applyTemperatureBody.indexOf("FaultHeatPauseManager.endFaultHeatPauseAndSyncIfNeeded(reason)")
        assertTrue("按盘态恢复温度必须走故障恢复同步包装", syncPause >= 0)
        assertTrue("必须先确认温区写入成功再结束暂停", successCheck >= 0 && successCheck < syncPause)
        assertTrue("单区写入不能提前结束整机暂停", !setAreaBody.contains("endFaultHeatPauseAndSyncIfNeeded"))
    }

    private fun assertOrder(source: String, before: String, after: String, message: String) {
        val beforeIndex = source.indexOf(before)
        val afterIndex = source.indexOf(after)
        assertTrue("$message：找不到前置片段 $before", beforeIndex >= 0)
        assertTrue("$message：找不到后置片段 $after", afterIndex >= 0)
        assertTrue(message, beforeIndex < afterIndex)
    }

    private fun extractBetween(source: String, startText: String, endText: String): String {
        val start = source.indexOf(startText)
        assertTrue("找不到起点：$startText", start >= 0)
        val end = source.indexOf(endText, start).let { if (it >= 0) it else source.length }
        return source.substring(start, end)
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return normalizeSaveNames(direct.readText().replace("\r\n", "\n"))
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return normalizeSaveNames(fromParent.readText().replace("\r\n", "\n"))
        }
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }

    private fun normalizeSaveNames(source: String): String = source
        .replace("saveKaoPanListDurable", "saveKaoPanListSync")
        .replace("saveKaoPanBoxListDurable", "saveKaoPanBoxListSync")

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("找不到函数：$functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$functionSignature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        throw AssertionError("函数体未闭合：$functionSignature")
    }
}
