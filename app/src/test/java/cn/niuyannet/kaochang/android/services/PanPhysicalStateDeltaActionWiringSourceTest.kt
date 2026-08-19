package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PanPhysicalStateDeltaActionWiringSourceTest {

    @Test
    fun periodicMaturityTransitions_shouldPublishPhysicalDeltaAfterSave() {
        val lowSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt")
        val lowBody = extractFunctionBody(lowSource, "private fun checkLowHeatEnd(")
        assertOrder(
            lowBody,
            "KaoPanHelper.saveKaoPanList(",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "低并发前区成熟必须保存后递增物理版本"
        )
        assertTrue(lowBody.contains("source = \"low_front_matured_keep_warm\""))

        val highSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        val highBody = extractFunctionBody(highSource, "private fun checkHighHeatEnd(")
        assertOrder(
            highBody,
            "KaoPanHelper.saveKaoPanList(",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "高并发各区成熟必须保存后递增物理版本"
        )
        assertTrue(highBody.contains("source = \"high_area_matured_keep_warm\""))
    }

    @Test
    fun transitionNormalizationAndBakingTimeRefresh_shouldPublishPhysicalDeltaAfterSave() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        listOf(
            "private fun settleOldLowHeatSourcesBeforePendingDiscard(" to "low_to_high_old_heat_settled",
            "private fun normalizeOldLowHeatSourcesBeforeAvailabilityProjection(" to "low_to_high_old_heat_normalized",
            "private fun refreshActiveBakingTimeBeforeMaturityCheck(" to "active_baking_time_refreshed"
        ).forEach { (functionName, expectedSource) ->
            val body = extractFunctionBody(source, functionName)
            assertOrder(
                body,
                "KaoPanHelper.saveKaoPanList(",
                "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
                "$functionName 必须保存后递增物理版本"
            )
            assertTrue("$functionName 必须使用专用 source", body.contains("source = \"$expectedSource\""))
        }
    }

    @Test
    fun partialHeatBatchMaturity_shouldPublishPhysicalDeltaBeforeWaitingOrMoving() {
        val lowSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt")
        val lowBody = extractFunctionBody(lowSource, "private suspend fun moveFinishedLowHeatPansToFront(")
        assertOrder(
            lowBody,
            "LowHeatSupplementResumePlanner.markFinishedPansKeepWarm(",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "低并发残缺补热批次成熟必须递增物理版本"
        )
        assertTrue(lowBody.contains("source = \"low_partial_heat_matured_keep_warm\""))

        val highSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        val highBody = extractFunctionBody(highSource, "private suspend fun moveFinishedPartialHighHeatBatchToFront(")
        assertOrder(
            highBody,
            "HighHeatSupplementResumePlanner.markFinishedPartialBatchKeepWarm(",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "高并发残缺补热批次成熟必须递增物理版本"
        )
        assertOrder(
            highBody,
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "if (!canReturnHighHeatBatchToFront(",
            "高并发残缺批次即使暂无前区空位也必须先同步成熟状态"
        )
        assertTrue(highBody.contains("source = \"high_partial_heat_matured_keep_warm\""))
    }

    @Test
    fun supplementPlanning_shouldPublishPhysicalDeltaBeforeMechanicalDispatch() {
        val lowSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt")
        listOf(
            Triple("private suspend fun initLowTrafficMode(", "low_initialization_planned", "KaoChangOperate.moveSausagesToKaoPansByTaste("),
            Triple("private suspend fun resumeLowInitialization(", "low_initialization_resume_planned", "KaoChangOperate.moveSausagesToKaoPansByTaste("),
            Triple("private suspend fun resumeLowHeatSupplementGroup(", "low_heat_resume_planned", "KaoChangOperate.moveSausagesToKaoPansForResume(")
        ).forEach { (functionName, expectedSource, dispatchCall) ->
            val body = extractFunctionBody(lowSource, functionName)
            assertOrder(
                body,
                "\"$expectedSource\"",
                dispatchCall,
                "$functionName 必须在机械补肠前同步规划状态"
            )
        }
        val lowHeatBody = extractFunctionBody(lowSource, "private suspend fun addLowHeatSausage(")
        listOf(
            "low_heat_13_15_planned",
            "low_heat_13_15_reservation_settled"
        ).forEach { expectedSource ->
            assertTrue("低并发补热规划必须同步 source=$expectedSource", lowHeatBody.contains("\"$expectedSource\""))
        }
        val lowHeat16To18Body = extractFunctionBody(lowSource, "private suspend fun addLowHeatSausage16_18(")
        listOf(
            "low_heat_16_18_planned",
            "low_heat_16_18_reservation_settled"
        ).forEach { expectedSource ->
            assertTrue("低并发补热规划必须同步 source=$expectedSource", lowHeat16To18Body.contains("\"$expectedSource\""))
        }

        val highSource = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        listOf(
            Triple("private suspend fun initHighTrafficMode(", "high_initialization_planned", "KaoChangOperate.moveSausagesToKaoPansByTaste("),
            Triple("private suspend fun resumeHighInitialization(", "high_initialization_resume_planned", "KaoChangOperate.moveSausagesToKaoPansByTaste("),
            Triple("private suspend fun addHighHeatSausage(", "high_heat_25_33_planned", "KaoChangOperate.moveSausagesToKaoPansByTaste("),
            Triple("private suspend fun resumeIncompleteHighHeatSupplement(", "high_heat_25_33_resume_planned", "KaoChangOperate.moveSausagesToKaoPansForResume(")
        ).forEach { (functionName, expectedSource, dispatchCall) ->
            val body = extractFunctionBody(highSource, functionName)
            assertOrder(
                body,
                "\"$expectedSource\"",
                dispatchCall,
                "$functionName 必须在机械补肠前同步规划状态"
            )
        }
        val transitionBody = extractFunctionBody(highSource, "private suspend fun lowTrafficModeTransition(")
        assertOrder(
            transitionBody,
            "\"low_to_high_high_heat_planned\"",
            "KaoChangOperate.moveSausagesToKaoPansByTaste(",
            "低转高实际数量补肠必须在机械动作前同步规划状态"
        )
    }

    @Test
    fun supplementPlanningReporter_shouldSaveFirstAndIsolateSyncFailure() {
        listOf(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"
        ).forEach { path ->
            val body = extractFunctionBody(sourceFile(path), "private fun reportPlannedPanState(")
            assertOrder(
                body,
                "KaoPanHelper.saveKaoPanList(",
                "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
                "$path 的规划状态必须先保存再发布物理增量"
            )
            assertOrder(
                body,
                "runCatching {",
                "}.onFailure { error ->",
                "$path 的物理增量异常必须隔离，不能阻塞机械调度"
            )
        }
    }

    @Test
    fun maturedZoneKeepWarm_shouldPublishPhysicalDeltaAfterPanStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val body = extractFunctionBody(source, "private fun settleMaturedZonesBeforeDispatch(")

        assertOrder(
            body,
            "KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "整区成熟转保温的物理增量必须在本地烤盘保存后发布"
        )
        assertTrue("整区成熟转保温必须仅上报实际变化盘位", body.contains("settlements.flatMap { it.positions }"))
        assertTrue("整区成熟转保温必须使用专用 source", body.contains("source = \"matured_zone_keep_warm\""))
        assertOrder(
            body,
            "runCatching {",
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "整区成熟转保温物理增量必须隔离同步异常"
        )
        assertOrder(
            body,
            "PanPhysicalStateDeltaReporter.reportPanPositionsChanged(",
            "}.onFailure { error ->",
            "整区成熟转保温物理增量异常必须记录后继续调度"
        )
        assertTrue(
            "整区成熟转保温物理增量异常必须等待现有快照补偿",
            body.contains("整区成熟已完成，物理增量上报异常，等待快照补偿")
        )
        assertOrder(
            body,
            "}.onFailure { error ->",
            "KaoPanCloudSyncReporter.uploadIfSellableStateChanged(",
            "整区成熟转保温必须在物理增量异常隔离后继续可售投影同步"
        )
    }

    @Test
    fun supplementReducer_shouldPublishPhysicalDeltaOnlyAfterLocalStateIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val body = extractBetween(
            source,
            "private fun applySupplementPhysicalTelemetry(",
            "private fun applySuccessfulSupplementMove("
        )
        val placementCommit = extractBetween(
            source,
            "private fun commitSupplementPlacedOnPan(",
            "private fun applySupplementPhysicalTelemetry("
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
            placementCommit,
            "SupplementPanStateWriter.applySupplementPlacedOnPan(",
            "KaoPanHelper.saveKaoPanListSync(currentPans)",
            "in7 落盘必须先更新本地烤盘再耐久保存"
        )
        assertOrder(
            body,
            "val commit = commitSupplementPlacedOnPan(plannedPan, plannedBox)",
            "PanPhysicalStateDeltaReporter.reportPanChanged(",
            "in7 物理增量必须在耐久落盘提交返回后发布"
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
    fun irPickFailureBoxMark_shouldRegisterIntentBeforeFailureMarkIsSaved() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val atomicHelper = extractFunctionBody(source, "private fun recordIrPickFailedBoxAndReport(")
        assertTrue(
            "E5 失败标记必须通过协调器先登记显式意图",
            atomicHelper.contains("DataManagementAPI.updateTakeStatusPrepared(")
        )
        assertOrder(
            atomicHelper,
            "prepare = {",
            "SupplementPickFailurePolicy.recordIrPickFailedBox(",
            "E5 失败标记必须位于显式意图 prepare 临界区"
        )
        assertOrder(
            atomicHelper,
            "KaoPanHelper.updateKaoPanBoxListDurable { currentBoxes ->",
            "SupplementPickFailurePolicy.recordIrPickFailedBox(",
            "E5 失败箱必须在当前箱表事务内重新解析并标记"
        )
        assertTrue("E5 落盘异常必须进入维护", atomicHelper.contains("enterActionGroupMaintenance("))
        assertTrue("E5 落盘异常必须保留 moveStatus", atomicHelper.contains("preserveMoveStatus = true"))
        assertTrue("E5 落盘异常不得继续补肠", atomicHelper.contains("throw error"))

        val serialBody = extractBetween(
            source,
            "private suspend fun moveSausageToKaoPanByTasteDetailed(",
            "private fun continueAfterSupplementBatchFailure"
        )
        assertTrue("串行 in2 失败箱必须走原子 helper", serialBody.contains("recordIrPickFailedBoxAndReport("))
        assertTrue("串行 in2 入口不得在登记 token 前直接标记", !serialBody.contains("recordIrPickFailedBox(box, candidates)"))
        assertTrue("串行 in2 失败箱必须使用专用 source", serialBody.contains("source = \"action_failure_serial_in2_ir_pick\""))

        val pipelineHelper = extractFunctionBody(source, "private fun recordIrPickFailureAndSave(")
        assertTrue("预取流水线单箱 in2 失败箱必须走原子 helper", pipelineHelper.contains("recordIrPickFailedBoxAndReport("))
        assertTrue("预取流水线单箱 in2 失败箱必须使用专用 source", pipelineHelper.contains("source = \"action_failure_pipeline_in2_ir_pick\""))

        listOf(
            "action_failure_initialization_in2_ir_pick",
            "action_failure_pipeline_initialization_ir_pick",
            "action_failure_pipeline_supply_degrade",
            "action_failure_pipeline_ir_pick_maintenance",
            "action_failure_single_in2_ir_pick"
        ).forEach { sourceName ->
            assertTrue("E5 上报入口必须保留 source=$sourceName", source.contains("source = \"$sourceName\""))
        }
    }

    @Test
    fun maintenanceBoxOperations_shouldUseVersionedTakeStatusAfterInventorySave() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceBoxFragment.kt")
        val restoreBody = extractFunctionBody(source, "private fun restoreSelectedBoxesAutoTake(")
        val refillBody = extractFunctionBody(source, "private fun showTasteSelectDialog(")

        assertTrue("维护箱位页面必须使用普通库存投影", source.contains("StockPhysicalProjectionSerializer"))
        assertTrue("仅恢复自动取肠不得发送普通库存", !restoreBody.contains("NetApi.updateStock(") && !restoreBody.contains("NetApi.clearStock("))
        assertTrue("仅恢复自动取肠必须直接逐箱确认状态", restoreBody.contains("confirmTakeStatusChanges("))
        assertOrder(
            refillBody,
            "StockPhysicalProjectionSerializer.applyInventoryFields(",
            "confirmTakeStatusChanges(",
            "维护清空/加满必须先合并库存字段，再逐箱确认取肠状态"
        )
        assertTrue("取肠状态必须复用版本接口", source.contains("DataManagementAPI.updateTakeStatus"))
        assertOrder(
            refillBody,
            "val proposedBoxes = copyBoxes(KaoPanHelper.getKaoPanBoxList())",
            "for (box in proposedSelectedBoxes) {",
            "加满烤肠箱必须先复制列表，不能提前修改全局库存"
        )
        assertTrue("加满烤肠箱接口未知必须回读", refillBody.contains("refreshAfterUnknownStockWrite(\"加满烤肠箱状态未知\")"))
    }

    @Test
    fun stockRefresh_shouldConditionAutomaticPushOnUnchangedExplicitIntentGeneration() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val refreshBody = extractBetween(source, "fun refreshStockData(", "private fun KaoPanBox.takeStatusSnapshot(")

        assertTrue(
            "库存回读必须在箱表锁内重新观察箱级自动回推 generation",
            refreshBody.contains("KaoPanHelper.withKaoPanBoxTableLock {") &&
                refreshBody.contains("takeStatusIntentRegistry.observeAutomaticGeneration(")
        )
        assertOrder(
            refreshBody,
            "takeStatusIntentRegistry.observeAutomaticGeneration(",
            "KaoPanHelper.saveKaoPanBoxListSync(remoteBoxes)",
            "库存回读必须在 durable 提交前重新校验 generation"
        )
        assertTrue(
            "库存回读必须走 generation 条件自动回推入口",
            refreshBody.contains("updateTakeStatusAutomaticallyIfUnchanged(box, observedGeneration)")
        )
        assertTrue(
            "库存回读不得通过公开入口生成比人工恢复更晚的显式 token",
            !refreshBody.contains("updateTakeStatus(box)")
        )
    }

    @Test
    fun takeStatusUpdates_shouldUseCoordinatorForExplicitAndAutomaticIntents() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val explicitBody = extractFunctionBody(source, "fun updateTakeStatus(")
        val automaticBody = extractFunctionBody(source, "private fun updateTakeStatusAutomaticallyIfUnchanged(")

        assertTrue(
            "显式取肠状态必须通过协调器获得单次冲突重试和统一终态清理",
            explicitBody.contains("takeStatusUpdateCoordinator.updateExplicitly(box, onResult)")
        )
        assertTrue(
            "自动回推必须通过无重试资格的协调器入口",
            automaticBody.contains("takeStatusUpdateCoordinator.updateAutomaticallyIfUnchanged(box, observedGeneration)")
        )
        assertTrue(
            "DataManagementAPI 不得绕过协调器直接发送取肠状态请求",
            !explicitBody.contains("NetApi.updateTakeStatus(") && !automaticBody.contains("NetApi.updateTakeStatus(")
        )
    }

    @Test
    fun maintenancePanResetOperations_shouldPublishPanDeltaAfterManualPanReset() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")

        assertTrue("维护烤盘页面必须引入 physical delta reporter", source.contains("PanPhysicalStateDeltaReporter"))
        assertOrder(
            source,
            "KaoPanHelper.clearKaoPan()\n                KaoPanHelper.init()",
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

        assertTrue("算法开启必须通过持锁温控恢复入口", enableBody.contains("restoreHeatingStateFromCurrentPansLocked("))
        val successCheck = applyTemperatureBody.indexOf("allWritesSucceeded")
        val endPause = applyTemperatureBody.indexOf("FaultHeatPauseManager.endFaultHeatPauseAndAdjustIfNeeded(reason)")
        assertTrue("按盘态恢复温度必须提交故障暂停结束", endPause >= 0)
        assertTrue("必须先确认温区写入成功再结束暂停", successCheck >= 0 && successCheck < endPause)
        assertTrue("锁内温控提交不得直接发布物理增量", !applyTemperatureBody.contains("syncRecoveredPhysicalState"))
        assertTrue("单区写入不能提前结束整机暂停", !setAreaBody.contains("endFaultHeatPauseAndAdjustIfNeeded"))
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
