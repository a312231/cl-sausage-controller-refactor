package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParallelSupplementPipelineWiringSourceTest {

    @Test
    fun newParallelPipeline_shouldRequireBothFlagsAndCaptureDecisionForActiveSession() {
        val source = operateSource()
        val gate = functionBlock(source, "private fun isParallelSupplementPrefetchEnabled()", "fun isActionGroupBusyForOrderGate")
        assertTrue(gate.contains("enableActionGroupPrefetch == 1"))
        assertTrue(gate.contains("enableParallelSupplementPrefetch == 1"))

        val pipeline = pipelineBlock(source)
        assertTrue(pipeline.contains("val parallelEnabled = isParallelSupplementPrefetchEnabled()"))
        assertTrue(pipeline.contains("parallelEnabled = parallelEnabled"))
        assertEquals(1, Regex("isParallelSupplementPrefetchEnabled\\(\\)").findAll(pipeline).count())
    }

    @Test
    fun activeDurableSessionRecovery_shouldNotRecheckRemoteParallelFlag() {
        val source = operateSource()
        val recovery = functionBlock(
            source,
            "suspend fun recoverActionGroupStateAfterModbusReady",
            "private fun recoverPanMoveJournalAfterModbusReady"
        )

        assertTrue(recovery.contains("SupplementPrefetchRecoveryCoordinator("))
        assertTrue(!recovery.contains("isParallelSupplementPrefetchEnabled()"))
    }

    @Test
    fun newPipelineEntry_shouldStopAfterHandlingActiveDurableSession() {
        val source = operateSource()
        val block = pipelineBlock(source)
        val recovery = block.indexOf("recoverActionGroupStateAfterModbusReady(")
        val stopSignal = block.indexOf("stopAfterParallelSessionHandled = true", recovery)
        val earlyReturn = block.indexOf("return 0", stopSignal)
        val newPlan = block.indexOf("buildPipelineSupplementPlan(")
        val recoveryBlock = functionBlock(
            source,
            "suspend fun recoverActionGroupStateAfterModbusReady",
            "private fun recoverPanMoveJournalAfterModbusReady"
        )

        assertTrue(recovery >= 0)
        assertTrue(stopSignal > recovery)
        assertTrue(earlyReturn > stopSignal)
        assertTrue(newPlan > earlyReturn)
        assertTrue(recoveryBlock.contains("if (stopAfterParallelSessionHandled)"))
    }

    @Test
    fun parallelSession_shouldOwnLockAndMustNotWriteGenericActionGroupSession() {
        val source = operateSource()
        val block = pipelineBlock(source)
        val sessionId = block.indexOf("val sessionId = \"in_pipeline:")
        val preparedOwner = block.indexOf("val preparedOwnerStore = if (parallelEnabled)")
        val claim = block.indexOf("claimPreparedSupplementMove(")
        val acquire = block.indexOf("actionGroupLocks.tryAcquire(lockMask, sessionId)")
        val parallelBranchEnd = block.indexOf("} else {", preparedOwner)
        val parallelBranch = block.substring(preparedOwner, parallelBranchEnd)
        val ownerStore = functionBlock(
            source,
            "private fun supplementPipelineClaimOwnerStore(",
            "@Volatile\n    private var lastActionGroupRecoveryFailureReason"
        )

        assertTrue(sessionId >= 0)
        assertTrue(preparedOwner > sessionId)
        assertTrue(claim > preparedOwner)
        assertTrue(acquire > claim)
        assertTrue(parallelBranch.contains("supplementPipelineClaimOwnerStore("))
        assertTrue(!parallelBranch.contains("actionGroupSessionStore"))
        assertTrue(ownerStore.contains("supplementPrefetchSessionRepository.create("))
        assertTrue(ownerStore.contains("MechanicalClaimState.PREPARED"))
        assertTrue(ownerStore.contains("markMechanicalClaimed(owner)"))
    }

    @Test
    fun parallelRunner_shouldWireBarrierLifecyclePhysicalAndPromotionHooks() {
        val block = pipelineBlock(operateSource())

        assertTrue(block.contains("SupplementPrefetchPhysicalStateCoordinator("))
        assertTrue(block.contains("beforeDeliveryCommit ="))
        assertTrue(block.contains("SupplementDeliveryCommitCoordinator("))
        assertTrue(block.contains("orderPending = SupplementPauseController::shouldPauseForOrder"))
        assertTrue(block.contains("if (parallelEnabled)"))
        assertTrue(block.contains("supplementPrefetchSessionRepository.tryCommitDelivery("))
        assertTrue(block.contains("onActionCommandWritten ="))
        assertTrue(block.contains("onActionRunningConfirmed ="))
        assertTrue(block.contains("onActionTerminalConfirmed ="))
        assertTrue(block.contains("onPrefetchPromoted ="))
        assertTrue(block.contains("onPausedPrefetchPromoted ="))
        assertTrue(
            Regex("promotePrefetchAfterDelivery\\(\\s*allowPausedCheckpoint\\s*=\\s*true\\s*\\)")
                .containsMatchIn(block)
        )
        assertTrue(block.contains("context.laneRole"))
        assertTrue(block.contains("releaseSupplementMoveClaimIfOwned("))
    }

    @Test
    fun parallelPhysicalCoordinator_shouldOnlyHandleDurableInventoryAndPanCheckpoints() {
        val block = pipelineBlock(operateSource())
        val branchStart = block.indexOf("val applied = if (parallelEnabled &&")
        val branchEnd = block.indexOf(") {", branchStart)

        assertTrue(branchStart >= 0)
        assertTrue(branchEnd > branchStart)
        val admission = block.substring(branchStart, branchEnd)
        assertTrue(admission.contains("ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(telemetry)"))
        assertTrue(admission.contains("ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(telemetry)"))
        assertTrue(!admission.contains("telemetry.physicalCheckpoint != null"))
    }

    @Test
    fun parallelE5SafeClosure_shouldPassSessionContextAndUseSeparateOutcome() {
        val block = pipelineBlock(operateSource())
        val handler = functionBlock(
            operateSource(),
            "private suspend fun handlePipelineSupplementFailure",
            "private fun describeAtomicActionResult"
        )

        assertTrue(handler.contains("parallelSessionActive = parallelSessionActive"))
        assertTrue(block.contains("SupplementPipelineOutcome.SUPPLY_DEGRADED"))
        assertTrue(block.contains("markSkippedBySupplyDegrade"))
        assertTrue(operateSource().contains("clearIfMechanicalClaimRevision"))
    }

    @Test
    fun everyExplicitE5Candidate_shouldBeDurablyIsolatedBeforeOrDuringExhaustionClosure() {
        val source = operateSource()
        val block = pipelineBlock(source)
        val helper = functionBlock(
            source,
            "private fun handleParallelE5SupplyDegrade",
            "private suspend fun handlePipelineSupplementFailure"
        )

        assertTrue(block.contains("onPickFailureSkipped = { sourceBoxNo ->"))
        assertTrue(block.contains("onTerminalPrefetchPickFailure = { context ->"))
        assertTrue(block.contains("补肠双 lane prefetch 最终取肠失败"))
        assertTrue(block.contains("recordIrPickFailureAndSave("))
        assertTrue(helper.contains("result.failedSourceBoxNos + result.sourceBoxNo"))
        assertTrue(helper.contains("result.laneRole == SupplementLaneRole.DELIVERY"))
        assertTrue(helper.contains("result.sourceBoxNo?.let { finalFailedBox ->"))
        assertTrue(helper.contains("recordIrPickFailureAndSave("))
    }

    private fun pipelineBlock(source: String): String = functionBlock(
        source,
        "private suspend fun moveSausagesToKaoPansByTasteInternal",
        "private fun describeAtomicActionResult"
    )

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "missing end marker: $endMarker" }
        return source.substring(start, end)
    }

    private fun operateSource(): String = source(
        "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"
    ).replace("\r\n", "\n")

    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("source file not found: $path")
}
