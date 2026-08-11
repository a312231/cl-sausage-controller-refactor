package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SupplementRecoverySafetySourceTest {

    @Test
    fun emergencyResetFence_shouldShortCircuitColdStartRecoveryAndAlgorithmStartup() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val start = source.indexOf("private fun startRuntimeAfterConfigLoaded(")
        val end = source.indexOf("override fun onResume", start)
        val body = source.substring(start, end)
        val fenceCheck = body.indexOf("KaoChangOperate.enforceEmergencyResetRecoveryOnStartup()")
        val powerLoss = body.indexOf("PowerLossRecoveryGuard.recoverBeforeRuntimeStart(source)")
        val businessStart = body.indexOf("recoverBusinessStartIfNeeded")
        val algorithmStart = body.indexOf("KaoChangAlgorithm.runServer()")

        assertTrue("启动时必须先应用紧急复位 fence", fenceCheck >= 0)
        assertTrue(fenceCheck < powerLoss)
        assertTrue(fenceCheck < businessStart)
        assertTrue(fenceCheck < algorithmStart)
        assertTrue(body.contains("if (emergencyResetPending)"))
        assertTrue(body.contains("syncDeviceStatusToServer(retryDelaysMs = listOf(3000L))"))
        assertTrue(body.contains("return"))
    }

    @Test
    fun coldStartPowerLossRecovery_shouldRunBeforeBusinessStartCorrection() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val start = source.indexOf("private fun startRuntimeAfterConfigLoaded(")
        assertTrue("找不到启动入口", start >= 0)
        val end = source.indexOf("\n    }", start).let { if (it >= 0) it else source.length }
        val body = source.substring(start, end)
        assertTrue("断电恢复必须早于营业开始纠偏", body.indexOf("PowerLossRecoveryGuard.recoverBeforeRuntimeStart") >= 0 &&
            body.indexOf("PowerLossRecoveryGuard.recoverBeforeRuntimeStart") < body.indexOf("recoverBusinessStartIfNeeded"))
    }
    @Test
    fun successfulSupplementPaths_shouldRecoverTheActualSourceBoxEpisode() {
        val source = operateSource()
        val pipelineStart = source.indexOf("fun applyCompletedPipelineTask(")
        val pipelineEnd = source.indexOf("var actionGroupCompleted", pipelineStart)
        val pipelineBlock = source.substring(pipelineStart, pipelineEnd)
        assertTrue(
            "流水线完整落盘成功后必须按实际来源箱恢复对应供给降级事件",
            pipelineBlock.contains("markSupplyBoxRecoveredAfterSuccessfulTake(") &&
                pipelineBlock.contains("completedTask.sourceBoxNo")
        )

        val singleSuccessStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val singleSuccessEnd = source.indexOf("/**\n     * 烤盘间搬移", singleSuccessStart)
        val singleSuccessBlock = source.substring(singleSuccessStart, singleSuccessEnd)
        assertTrue(
            "单根完整补肠成功后必须恢复同一来源箱事件",
            singleSuccessBlock.contains("markSupplyBoxRecoveredAfterSuccessfulTake(") &&
                singleSuccessBlock.contains("sourceBoxNo = completionSourceBox.positionSn")
        )
    }

    @Test
    fun episodeFailureCalls_shouldCarryCoordinatesBatchAndRawResult() {
        val source = operateSource()
        val pipelineFailureStart = source.indexOf("private suspend fun handlePipelineSupplementFailure(")
        val pipelineFailureEnd = source.indexOf("private fun describeAtomicActionResult", pipelineFailureStart)
        val pipelineFailure = source.substring(pipelineFailureStart, pipelineFailureEnd)
        assertTrue(pipelineFailure.contains("LowerPlatformEpisodeDetails("))
        assertTrue(pipelineFailure.contains("sourceBoxNo = sourceBoxNo"))
        assertTrue(pipelineFailure.contains("targetPanNo = targetPanNo"))
        assertTrue(pipelineFailure.contains("tasteCode = plannedMove?.tasteCode"))
        assertTrue(pipelineFailure.contains("batchId = batchId"))
        assertTrue(pipelineFailure.contains("batchType = batchType"))
        assertTrue(pipelineFailure.contains("rawResult = describeAtomicActionResult(resolvedAtomicResult)"))

        val singleStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val singleEnd = source.indexOf("/**\n     * 烤盘间搬移", singleStart)
        val singleBlock = source.substring(singleStart, singleEnd)
        assertTrue(singleBlock.contains("LowerPlatformEpisodeDetails("))
        assertTrue(singleBlock.contains("sourceBoxNo = kaoPanBox.positionSn"))
        assertTrue(singleBlock.contains("targetPanNo = kaoPan.positionSn"))
        assertTrue(singleBlock.contains("rawResult = actionGroupResultDesc(actionGroupResult)"))
    }

    @Test
    fun singleSupplementFailure_shouldNeverAutoClearLegacyPickFailureFallback() {
        val source = operateSource()
        val start = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val isolationPublish = source.indexOf("markLowerPlatformIsolatedRuntimeState(", start)
        val end = source.indexOf("/**\n     * 烤盘间搬移", start)
        val block = source.substring(start, end)
        val safeFallback = block.indexOf("val canClearFailedSession =")
        assertTrue("旧E5不得自动释放恢复证据", safeFallback >= 0)
        assertTrue(block.substring(safeFallback).contains("val canClearFailedSession = false"))

        val failureTail = source.substring(isolationPublish, source.indexOf("if (moveSuccess && supplementStateApplied)", isolationPublish))
        assertTrue(
            "其它物理不确定失败必须保留session并直接返回",
            failureTail.contains("actionGroupResult is ActionGroupResult.Failed && !canClearFailedSession")
        )
        assertTrue(failureTail.contains("保留session、资源锁和moveStatus等待恢复"))
    }

    @Test
    fun serialFailureCloseout_shouldUseRawProofAndKeepOrderTakeoverStatus() {
        val source = operateSource()
        val singleStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val singleEnd = source.indexOf("/**\n     * 烤盘间搬移", singleStart)
        val block = source.substring(singleStart, singleEnd)

        assertTrue(block.contains("val rawActionGroupResult = actionGroupResult"))
        assertTrue(block.contains("serialFailureProof?.let { closeSerialSupplementFailure(it) }"))
        assertTrue(block.contains("rawResult = (rawActionGroupResult as? ActionGroupResult.Failed)?.result"))

        val closeoutStart = source.indexOf("private fun closeSerialSupplementFailure(")
        val closeoutEnd = source.indexOf("private fun closeKnownIsolatedSupplementFailure(", closeoutStart)
        assertTrue("找不到统一补肠失败收口 helper", closeoutStart >= 0 && closeoutEnd > closeoutStart)
        val closeoutBlock = source.substring(closeoutStart, closeoutEnd)
        assertTrue(closeoutBlock.contains("SupplementFailureCloseoutPolicy.canAutoRelease"))
        assertTrue(closeoutBlock.contains("releaseOwnerKeepingMoveStatus"))
        assertTrue(closeoutBlock.contains("releaseIfOwned"))
        assertTrue(
            "claim/session CAS 必须先于资源释放",
            closeoutBlock.indexOf("val claimRelease") in 0 until closeoutBlock.indexOf("releaseIfOwned")
        )
    }

    @Test
    fun recoveryBootstrap_shouldDelegateKnownIsolationCloseoutInsteadOfClearingItself() {
        val bootstrap = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRecoveryBootstrap.kt"
        )
        val start = bootstrap.indexOf("val terminalFailures")
        val end = bootstrap.indexOf("terminalFailures.firstOrNull()", start)
        val block = bootstrap.substring(start, end)

        assertTrue(block.contains("closeKnownIsolatedTerminalFailure"))
        assertFalse(block.contains("clearSessionIfCurrent(session)"))
        assertFalse(block.contains("resourceLocks.release(session.sessionId)"))
    }

    @Test
    fun inSessionCheckpoint_shouldBeUpdatedBeforeEachPipelineCommand() {
        val source = operateSource()
        val pipelineStart = source.indexOf("InActionGroupPrefetchPipelineRunner(")
        val pipelineEnd = source.indexOf(".run(plannedMoves.map { it.task })", pipelineStart)
        val pipelineBlock = source.substring(pipelineStart, pipelineEnd)
        assertTrue(pipelineBlock.contains("onCurrentActionChanged = { context ->"))
        assertTrue(pipelineBlock.contains("actionGroupSessionStore.save("))
        assertTrue(pipelineBlock.contains("currentAction = context.actionName"))
        assertTrue(pipelineBlock.contains("checkpointVersion ="))
    }

    @Test
    fun prefetchPipeline_shouldDurablyApplyCompletedTasksBeforeClearingSession() {
        val source = operateSource()
        val start = source.indexOf("private suspend fun moveSausagesToKaoPansByTasteInternal")
        val end = source.indexOf("private fun describeAtomicActionResult", start)
        val block = source.substring(start, end)
        val completedForEach = block.indexOf("completed.forEach")
        val finalRelease = block.lastIndexOf("releaseSupplementMoveClaimIfOwned(")
        val pausedRelease = block.indexOf("补肠暂停等待订单完成")
        assertTrue("预取完成目标必须先批量回写再进入 owner/resource 收口", completedForEach >= 0 && finalRelease > completedForEach)
        assertTrue("预取暂停也必须先回写完成目标再释放机械状态", pausedRelease > completedForEach)
        assertTrue("预取收口不能使用异步盘箱保存", !block.contains("saveKaoPanBoxListSync"))
        assertTrue("预取收口不能使用异步烤盘保存", !block.contains("saveKaoPanListSync"))
    }

    @Test
    fun recoveredInSuccess_shouldCompleteOnlyPersistedBatchTarget() {
        val source = operateSource()
        val start = source.indexOf("private fun handleRecoveredSuccess(")
        val end = source.indexOf("private fun handleRecoveredDiscarded", start)
        val block = source.substring(start, end)
        assertTrue(block.contains("session.batchId"))
        assertTrue(block.contains("supplementBatchIntentRepository.markCompleted("))
        assertTrue(block.contains("session.targetNo"))
    }

    @Test
    fun mainStartup_shouldRecoverPowerLossBeforeRestoringOrStartingAlgorithm() {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        ).first { it.exists() }
        val source = sourceFile.readText().replace("\r\n", "\n")
        val start = source.indexOf("private fun startRuntimeAfterConfigLoaded(")
        val end = source.indexOf("override fun onResume", start)
        val block = source.substring(start, end)
        val recover = block.indexOf("PowerLossRecoveryGuard.recoverBeforeRuntimeStart(source)")
        val restore = block.indexOf("KaoChangAlgorithm.restoreRoastAlgorithmState()")
        val run = block.indexOf("KaoChangAlgorithm.runServer()")
        assertTrue(recover >= 0 && restore > recover && run > restore)
    }

    @Test
    fun algorithmStartup_shouldPreserveBusyStateUntilUnifiedRecoveryCompletes() {
        val algorithm = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
        )
        val runStart = algorithm.indexOf("fun runServer()")
        val runEnd = algorithm.indexOf("private fun updateAvailableState", runStart)
        val runBlock = algorithm.substring(runStart, runEnd)
        assertTrue("算法启动不得在动作恢复前把moveStatus强制清零", !runBlock.contains("config.moveStatus = 0"))

        val pollStart = algorithm.indexOf("private suspend fun pollKaoPanStatus()")
        val pollEnd = algorithm.indexOf("private fun check", pollStart).let { if (it > pollStart) it else algorithm.length }
        val pollBlock = algorithm.substring(pollStart, pollEnd)
        val recoveryGate = pollBlock.indexOf("KaoChangOperate.isActionGroupRecoveryBlocked()")
        val firstDispatchPreparation = pollBlock.indexOf("KaoChangOperate.stopAllHeatingIfNoSausage")
        assertTrue(
            "任何自动机械调度准备前必须经过统一恢复门禁",
            recoveryGate >= 0 && firstDispatchPreparation > recoveryGate
        )

        val enableStart = algorithm.indexOf("fun enableRoastAlgorithm(")
        val enableEnd = algorithm.indexOf("fun disableRoastAlgorithm(", enableStart)
        assertTrue(
            "人工开启算法也必须拒绝未收口恢复状态",
            algorithm.substring(enableStart, enableEnd).contains("KaoChangOperate.isActionGroupRecoveryBlocked()")
        )
    }

    @Test
    fun uiAndSchedulingIntent_shouldUseSessionJournalAndBootstrapRecoveryGate() {
        val operate = operateSource()
        val gateStart = operate.indexOf("fun isActionGroupRecoveryBlocked()")
        assertTrue("找不到统一动作恢复门禁", gateStart >= 0)
        val gateEnd = operate.indexOf("fun hasActiveSupplementReturnBinding", gateStart)
        val gate = operate.substring(gateStart, gateEnd)
        assertTrue(gate.contains("!actionGroupRecoveryVerified"))
        assertTrue(gate.contains("actionGroupSessionStore.load() != null"))
        assertTrue(gate.contains("panMoveJournalStore.load() != null"))

        val activeStart = operate.indexOf("fun activeSupplementBatchIntentForUi()")
        val activeEnd = operate.indexOf("fun clearCompletedHeatReturnBindingIfSourcesCleared", activeStart)
        assertTrue(
            operate.substring(activeStart, activeEnd).contains("isActionGroupRecoveryBlocked()")
        )

        val maintenance = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val refreshStart = maintenance.indexOf("private fun refreshKaoPanGrid()")
        val refreshEnd = maintenance.indexOf("private fun setupGrid()", refreshStart)
        assertTrue(
            maintenance.substring(refreshStart, refreshEnd)
                .contains("recoveryBlocked = KaoChangOperate.isActionGroupRecoveryBlocked()")
        )

        val clearPanBlock = maintenance.substring(
            maintenance.indexOf("private fun setupButtons()"),
            maintenance.indexOf("private fun showConfirmDiscardAndResetPanDialog")
        )
        assertTrue("维护清盘前必须阻止未收口IN/OUT会话", clearPanBlock.contains("isActionGroupRecoveryBlocked()"))
        val testPage = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt"
        )
        val moveStart = testPage.indexOf("private fun executePanMoveCommandFromTestPage()")
        val moveEnd = testPage.indexOf("private fun formatCommandHex", moveStart)
        assertTrue(
            "测试页裸搬盘必须经过统一恢复门禁",
            testPage.substring(moveStart, moveEnd).contains("isActionGroupRecoveryBlocked()")
        )
        val atomicStart = testPage.indexOf("private fun executeAtomicActionFromTestPage")
        val atomicEnd = testPage.indexOf("private fun", atomicStart + 10)
        assertTrue(
            "测试页原子动作必须经过统一恢复门禁",
            testPage.substring(atomicStart, atomicEnd).contains("isActionGroupRecoveryBlocked()")
        )
        assertTrue(
            "测试页裸搬盘仅允许全盘空的维护校准",
            testPage.substring(moveStart, moveEnd).contains("!it.isHasSausage && it.status == KaoPan.STATUS_EMPTY")
        )
    }

    @Test
    fun orderFastPathAndOutFailures_shouldPreserveAllRecoveryEvidence() {
        val source = operateSource()
        val gateStart = source.indexOf("suspend fun ensureActionGroupReadyForOrderDispatch")
        val gateEnd = source.indexOf("suspend fun recoverActionGroupStateAfterModbusReady", gateStart)
        assertTrue(
            "订单快速门禁必须显式包含搬盘journal",
            source.substring(gateStart, gateEnd).contains("panMoveJournalStore.load()")
        )

        val manualStart = source.indexOf("suspend fun takeSausage(kaoPan: KaoPan)")
        val formalStart = source.indexOf("suspend fun takeSausageResult(", manualStart)
        val manual = source.substring(manualStart, formalStart)
        val manualFailure = manual.substring(
            manual.indexOf("is ActionGroupResult.Failed ->"),
            manual.indexOf("} finally {", manual.indexOf("is ActionGroupResult.Failed ->"))
        )
        assertTrue("维护OUT失败不得清session", !manualFailure.contains("actionGroupSessionStore.clear()"))
        assertTrue("维护OUT失败必须耐久保存错误盘状态", manualFailure.contains("saveKaoPanListDurable"))

        val formalEnd = source.indexOf("internal fun applySuccessfulTakePanState", formalStart)
        val formal = source.substring(formalStart, formalEnd)
        val formalFailure = formal.substring(
            formal.indexOf("if (takeResult == TakeSausageResult.ERROR ||"),
            formal.indexOf("when (takeResult)")
        )
        assertTrue("正式OUT失败不得清session", !formalFailure.contains("actionGroupSessionStore.clear()"))
        assertTrue("出餐证据不明也必须保留session", formalFailure.contains("TakeSausageResult.EVIDENCE_UNKNOWN"))
        assertTrue("OUT finally只能在会话已收口后清moveStatus", formal.contains("actionGroupSessionStore.load()?.sessionId"))
    }

    @Test
    fun manualSupplementSafetyConfirmation_shouldOnlyClearMatchingInSessionWithoutMoveJournal() {
        val source = operateSource()
        val start = source.indexOf("fun confirmPendingSupplementBatchSafety")
        val end = source.indexOf("private fun clearFaultTimeoutCancelledReturnReservations", start)
        val block = source.substring(start, end)
        val journalGate = block.indexOf("panMoveJournalStore.load()")
        val confirm = block.indexOf("confirmManualSafety")
        val moveDurable = block.indexOf("saveAppConfigDurable")
        val sessionClear = block.indexOf("actionGroupSessionStore.clear()")
        assertTrue("确认前必须先拒绝搬盘journal冲突", journalGate >= 0 && journalGate < confirm)
        assertTrue(block.contains("ActionGroupSessionType.IN_SUPPLEMENT"))
        assertTrue(block.contains("session.batchId != pending.batchId"))
        assertTrue("必须先耐久清moveStatus再清session", moveDurable >= 0 && sessionClear > moveDurable)
    }

    @Test
    fun durableDoubleLaneEvidence_shouldBlockAutomaticMoveStatusReset() {
        val source = operateSource()
        val start = source.indexOf("fun isActionGroupIdleForMoveStatusRecovery()")
        val end = source.indexOf("fun isPanMoveRecoveryBlocked()", start)
        val block = source.substring(start, end)

        assertTrue(block.contains("hasBlockingSupplementPrefetchEvidence"))
    }

    @Test
    fun maintenance_shouldPreserveMoveStatusWhileDoubleLaneEvidenceExists() {
        val source = operateSource()
        val start = source.indexOf("private fun enterActionGroupMaintenance(")
        val end = source.indexOf("private fun", start + 20)
        val block = source.substring(start, end)

        assertTrue(block.contains("hasBlockingSupplementPrefetchEvidence"))
        assertTrue(block.contains("保留双 lane 恢复证据和 moveStatus"))
    }

    private fun operateSource(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun sourceFile(path: String): String {
        val file = listOf(File(path), File("..", path)).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码文件：$path")
        return file.readText().replace("\r\n", "\n")
    }
}
