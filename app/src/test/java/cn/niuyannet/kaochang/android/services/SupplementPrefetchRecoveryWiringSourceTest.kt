package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupplementPrefetchRecoveryWiringSourceTest {

    @Test
    fun doubleLaneRecovery_shouldPrecedePanAndGenericRecovery() {
        val block = functionBlock("suspend fun recoverActionGroupStateAfterModbusReady", "private fun recoverPanMoveJournalAfterModbusReady")
        val failClosed = block.indexOf("if (doubleLaneSession != null)")
        val panRecovery = block.indexOf("recoverPanMoveJournalAfterModbusReady(reason)")
        val generic = block.indexOf("ActionGroupRecoveryBootstrap(")

        assertTrue(failClosed >= 0)
        assertTrue(block.contains("已有双 lane 会话仅允许人工恢复"))
        assertTrue(!block.contains("SupplementPrefetchRecoveryCoordinator("))
        assertTrue(panRecovery > failClosed)
        assertTrue(generic > panRecovery)
    }

    @Test
    fun activeSession_shouldBlockIdleRecoveryAndPanMoveBeforeJournalWrite() {
        val idle = functionBlock("fun isActionGroupIdleForMoveStatusRecovery()", "fun isPanMoveRecoveryBlocked()")
        assertTrue(idle.contains("hasBlockingSupplementPrefetchEvidence"))

        val panMove = functionBlock("private suspend fun moveKaoPanToKaoPanInternal(", "private fun")
        val sessionGate = panMove.indexOf("hasBlockingSupplementPrefetchEvidence")
        val journalWrite = panMove.indexOf("saveDurable(journal)")
        assertTrue(sessionGate >= 0 && journalWrite > sessionGate)
    }

    @Test
    fun manualCleanupEntries_shouldFenceActiveSessionBeforeClearingEvidence() {
        val residue = functionBlock("suspend fun confirmManualDeviceRecoveryResidue", "fun getLastActionGroupRecoveryFailureReason")
        val supplement = functionBlock("fun confirmPendingSupplementBatchSafety", "private fun clearFaultTimeoutCancelledReturnReservations")
        val doubleLaneCompletion = supplement.indexOf("SupplementPipelineCompletionCoordinator.complete(")
        val doubleLaneConfirm = supplement.indexOf("confirmManualSafetyPreservingRemainingTargets", doubleLaneCompletion)
        val genericConfirm = supplement.indexOf("confirmManualSafety(reason)")
        val strategyMark = supplement.indexOf("markManualConfirmStrategyPrefetchPreserveRemaining")

        assertTrue(residue.indexOf("readSupplementPrefetchEvidence()") in 0 until residue.indexOf("actionGroupSessionStore.clear()"))
        assertTrue(residue.indexOf("clearIfRevision") in 0 until residue.indexOf("actionGroupSessionStore.clear()"))
        assertTrue(!residue.contains("actionGroupLocks.clear()"))
        assertTrue(supplement.indexOf("readSupplementPrefetchEvidence()") in 0 until supplement.indexOf("confirmManualSafety"))
        assertTrue(strategyMark >= 0 && strategyMark < doubleLaneCompletion)
        assertTrue(genericConfirm >= 0 && genericConfirm < doubleLaneCompletion)
        assertTrue(doubleLaneCompletion >= 0 && doubleLaneConfirm > doubleLaneCompletion)
    }

    @Test
    fun legacyPipelineCleanup_shouldNotReleaseWhileParallelSessionExists() {
        val block = functionBlock("private suspend fun moveSausagesToKaoPansByTasteInternal", "private fun describeAtomicActionResult")
        assertTrue(block.contains("SupplementPipelineFailureRetentionPolicy.shouldRetain"))
        assertTrue(block.contains("补肠双 lane 失败，保留 session、资源锁和 moveStatus"))
    }

    @Test
    fun recoveredDelivery_shouldPromoteAndResumePrefetchOnlyAfterIn7Placement() {
        val block = functionBlock("private suspend fun finishParallelSupplementResume", "private fun hasPausedSupplementSession")
        val placementGate = block.indexOf("!lane.panPlacementApplied")
        val promote = block.indexOf("promotePrefetchAfterDelivery")
        val resume = block.indexOf("resumePreparedParallelSupplement")

        assertTrue(placementGate >= 0)
        assertTrue(promote > placementGate)
        assertTrue(resume > promote)
    }

    @Test
    fun completedParallelSessions_shouldUseEvidenceLastCleanupAndPreservingMaintenance() {
        val replan = functionBlock(
            "private suspend fun handleParallelSupplementResumePreparation",
            "private suspend fun resumePreparedParallelSupplement"
        )
        val finished = functionBlock(
            "private suspend fun finishParallelSupplementResume",
            "private fun hasPausedSupplementSession"
        )

        assertTrue(replan.contains("expectedMoveStatuses = setOf(1, 2)"))
        assertTrue(finished.contains("expectedMoveStatus = 1"))
        listOf(replan, finished).forEach { block ->
            assertTrue(block.contains("releaseMechanicalMove("))
            assertTrue(block.contains("idleMoveStatus = 0"))
            assertTrue(block.contains("actionGroupLocks.release("))
            assertTrue(block.contains("publishRuntimeStateWithHttpFallback("))
            assertTrue(block.contains("preserveMoveStatus = true"))
        }
    }

    @Test
    fun livePipeline_shouldFinalizeCommittedDeliveryAfterApplyingCompletedTasks() {
        val block = functionBlock(
            "private suspend fun moveSausagesToKaoPansByTasteInternal",
            "private fun describeAtomicActionResult"
        )
        val applyCompleted = block.indexOf("completed.forEach")
        val finalizePause = block.indexOf("pauseCommittedDeliveryForPendingOrder", applyCompleted)
        val safeRelease = block.indexOf("activeSafePausedSupplementSession", finalizePause)

        assertTrue(applyCompleted >= 0)
        assertTrue(finalizePause > applyCompleted)
        assertTrue(safeRelease > finalizePause)
    }

    @Test
    fun pendingHeatBatch_shouldCancelLongFaultOnlyAfterSchedulingIsAllowed() {
        val block = functionBlock(
            "suspend fun handlePendingSupplementBatchIfAny",
            "private fun isInitializationBatch"
        )
        val manualConfirmGate = block.indexOf("if (intent.requiresManualConfirm)")
        val schedulingGate = block.indexOf("if (!schedulingEnabled || !boxSupplyAvailable")
        val occupiedReconcile = block.indexOf("markCompleted(intent.batchId")
        val timeoutCancel = block.indexOf("cancelRemainingHeatTargetsAfterFaultTimeoutIfExpired")
        val resumeMove = block.indexOf("moveSausagesToKaoPansByTasteInternal")

        assertTrue(manualConfirmGate >= 0)
        assertTrue(schedulingGate > manualConfirmGate)
        assertTrue(occupiedReconcile > schedulingGate)
        assertTrue(timeoutCancel > occupiedReconcile)
        assertTrue(timeoutCancel > schedulingGate)
        assertTrue(resumeMove > timeoutCancel)
    }

    @Test
    fun persistedDoubleLaneRecovery_shouldFailClosedWithoutAsyncRecovery() {
        val block = functionBlock(
            "suspend fun recoverActionGroupStateAfterModbusReady",
            "private fun recoverPanMoveJournalAfterModbusReady"
        )
        assertTrue(block.contains("已有双 lane 会话仅允许人工恢复"))
        assertTrue(block.contains("preserveMoveStatus = true"))
        assertTrue(!block.contains("SupplementPrefetchRecoveryCoordinator("))
        assertTrue(!block.contains("throw cancellation"))
    }

    @Test
    fun manualPanClear_shouldAbandonOnlyOrderlessPrefetchResidueThroughDedicatedEntry() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val maintenance = source("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")

        assertTrue(operate.contains("fun abandonSupplementPrefetchForManualPanClear("))
        assertTrue(operate.contains("ManualPanClearPolicy.Evidence("))
        assertTrue(operate.contains("ManualPanClearCommitter.commit("))
        assertTrue(operate.contains("supplementPrefetchSessionRepository.clearIfRevision("))
        assertTrue(operate.contains("actionGroupLocks.release(session.sessionId)"))
        assertTrue(operate.contains("config.moveStatus = oldMoveStatus"))
        assertTrue(operate.contains("markActionGroupRecoveryVerified("))

        assertTrue(maintenance.contains("allowSupplementPrefetchAbandon: Boolean = false"))
        assertTrue(maintenance.contains("KaoChangOperate.abandonSupplementPrefetchForManualPanClear("))
        assertEquals(
            1,
            Regex("allowSupplementPrefetchAbandon\\s*=\\s*true").findAll(maintenance).count()
        )
    }

    private fun functionBlock(startMarker: String, endMarker: String): String {
        val source = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "missing end marker: $endMarker" }
        return source.substring(start, end)
    }

    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }
        ?.readText()
        ?.replace("\r\n", "\n")
        ?: error("source file not found: $path")
}
