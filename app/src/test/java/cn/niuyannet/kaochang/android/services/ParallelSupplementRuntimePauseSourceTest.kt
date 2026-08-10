package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParallelSupplementRuntimePauseSourceTest {

    @Test
    fun runtimeUnavailableAfterStableRecovery_shouldDeferAndReleaseOnlyDedicatedSession() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val handler = functionBlock(
            operate,
            "private suspend fun handleParallelSupplementResumePreparation",
            "private suspend fun resumePreparedParallelSupplement"
        )
        assertTrue(handler.contains("deferParallelSupplementForRuntimeRecovery("))
        assertTrue(handler.indexOf("deferParallelSupplementForRuntimeRecovery(") <
            handler.indexOf("resumePreparedParallelSupplement("))

        val algorithm = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val poll = functionBlock(algorithm, "private suspend fun pollKaoPanStatus", "private fun settleMaturedZonesBeforeDispatch")
        assertTrue(poll.contains("resumeParallelSupplementForRuntimeRecovery("))
        assertTrue(poll.indexOf("resumeParallelSupplementForRuntimeRecovery(") <
            poll.indexOf("isActionGroupRecoveryBlocked()"))
    }

    @Test
    fun recoveryAndAlgorithmEnable_shouldBypassOnlyVerifiedRuntimePausedSession() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        assertTrue(operate.contains("fun isParallelSupplementPausedForRuntimeRecovery()"))
        assertTrue(operate.contains("phase == SupplementPipelinePhase.PAUSED_FOR_RUNTIME"))
        assertTrue(operate.contains("actionGroupRecoveryVerified"))
        assertTrue(operate.contains("actionGroupSessionStore.load() == null"))
        assertTrue(operate.contains("panMoveJournalStore.load() == null"))
        assertTrue(operate.contains("actionGroupLocks.isIdle()"))
        val session = source("app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchPipelineSession.kt")
        assertTrue(session.contains("fun pauseForRuntimeRecovery(sessionId: String)"))
        assertTrue(session.contains("fun resumeFromRuntimeRecovery(sessionId: String)"))

        val algorithm = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt")
        val enable = functionBlock(algorithm, "fun enableRoastAlgorithm", "fun disableRoastAlgorithm")
        assertTrue(enable.contains("isParallelSupplementPausedForRuntimeRecovery()"))
        assertTrue(enable.contains("isActionGroupRecoveryBlocked()"))

        val resume = functionBlock(operate, "suspend fun resumeParallelSupplementForRuntimeRecovery", "private fun supplementPipelineOutcome")
        assertTrue(resume.contains("已有双 lane 会话仅允许人工恢复"))
        assertTrue(!resume.contains("handleParallelSupplementResumePreparation("))

        val resumeOnce = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplementOnce",
            "private suspend fun finishParallelSupplementResume"
        )
        assertTrue(resumeOnce.contains("resumeFromRuntimeRecovery"))
        assertTrue(resumeOnce.contains("allowedMoveStatuses = setOf(0)"))
        assertTrue(resumeOnce.contains("releaseResumeLock()"))
    }

    @Test
    fun orderCompletion_whenRuntimeUnavailable_shouldPersistDeferredResumeInsteadOfKeepingLock() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val orderCompletion = functionBlock(
            operate,
            "suspend fun clearSupplementPauseAndResume",
            "fun releaseOrderDeliveryMoveStatus"
        )

        assertTrue(orderCompletion.contains("allowRuntimeDefer = true"))
    }

    @Test
    fun runtimeBecomesUnavailableAfterPreparation_shouldStillPersistDeferredResume() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resume = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplement(",
            "private suspend fun resumePreparedParallelSupplementOnce("
        )
        assertTrue(resume.contains("allowRuntimeDefer: Boolean"))
        assertTrue(resume.contains("resumePreparedParallelSupplementOnce(plan, reason, lockAlreadyOwned, allowRuntimeDefer)"))

        val resumeOnce = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplementOnce(",
            "val pan = KaoPanHelper.getKaoPanList()"
        )
        assertTrue(resumeOnce.contains("allowRuntimeDefer: Boolean"))
        val secondRuntimeGate = functionBlock(resumeOnce, "val runtime = AppConfig.getAppConfig()", "if (!lockOwnedByResume)")
        assertTrue(secondRuntimeGate.contains("if (allowRuntimeDefer)"))
        assertTrue(secondRuntimeGate.contains("deferParallelSupplementForRuntimeRecovery("))
        assertTrue(secondRuntimeGate.contains("lockOwner = plan.sessionId"))
        assertTrue(secondRuntimeGate.indexOf("deferParallelSupplementForRuntimeRecovery(") <
            secondRuntimeGate.lastIndexOf("releaseResumeLock()"))
    }

    @Test
    fun runtimeResume_shouldHoldSingleFlightAcrossLockAcquireAndPreparation() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resume = functionBlock(
            operate,
            "suspend fun resumeParallelSupplementForRuntimeRecovery",
            "private fun supplementPipelineOutcome"
        )

        assertTrue(resume.contains("supplementResumeSingleFlight.tryEnter()"))
        assertTrue(resume.contains("supplementResumeSingleFlight.exit()"))
    }

    @Test
    fun runtimeDefer_shouldNotReleaseAnotherResumeAttemptOwner() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val defer = functionBlock(
            operate,
            "private fun deferParallelSupplementForRuntimeRecovery(",
            "suspend fun resumeParallelSupplementForRuntimeRecovery"
        )

        assertTrue(defer.contains("lockOwner"))
        assertTrue(defer.contains("supplementResumeSingleFlight.isRunning()"))
    }

    @Test
    fun alreadyPausedRuntimeDefer_shouldReleaseTheTemporaryOwnerBeforeReturning() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val defer = functionBlock(
            operate,
            "private fun deferParallelSupplementForRuntimeRecovery(",
            "suspend fun resumeParallelSupplementForRuntimeRecovery"
        )
        val alreadyPaused = defer.substring(
            defer.indexOf("if (session.mechanicalClaimState == null"),
            defer.indexOf("val currentMoveStatus")
        )

        assertTrue(alreadyPaused.contains("actionGroupLocks.release(ownerToRelease)"))
        assertTrue(alreadyPaused.indexOf("actionGroupLocks.release(ownerToRelease)") <
            alreadyPaused.indexOf("return@withFaultHeatTransitionLock true"))
    }

    @Test
    fun corruptedRuntimeEvidence_shouldRetainTheOwnerLock() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val defer = functionBlock(
            operate,
            "private fun deferParallelSupplementForRuntimeRecovery(",
            "suspend fun resumeParallelSupplementForRuntimeRecovery"
        )
        val evidenceRead = defer.substring(
            defer.indexOf("val session ="),
            defer.indexOf("val lane =")
        )

        assertTrue(evidenceRead.contains("DurableEvidenceReadResult.Corrupted"))
        val corrupted = evidenceRead.substring(evidenceRead.indexOf("DurableEvidenceReadResult.Corrupted"))
        assertTrue(corrupted.contains("enterActionGroupMaintenance"))
        assertTrue(!corrupted.contains("actionGroupLocks.release(plan.sessionId)"))
    }

    @Test
    fun noSessionResumePreparation_shouldReleaseTheKnownOwnedOwner() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val handler = functionBlock(
            operate,
            "private suspend fun handleParallelSupplementResumePreparation(",
            "private suspend fun resumePreparedParallelSupplement("
        )
        val noSession = handler.substring(
            handler.indexOf("SupplementPrefetchResumePreparation.NoSession"),
            handler.indexOf("is SupplementPrefetchResumePreparation.Unsafe")
        )

        assertTrue(noSession.contains("lockOwner"))
        assertTrue(noSession.contains("actionGroupLocks.release(lockOwner)"))
    }

    @Test
    fun startupRecovery_shouldRetainPersistedDoubleLaneOwnerForManualRecovery() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val recovery = functionBlock(
            operate,
            "suspend fun recoverActionGroupStateAfterModbusReady",
            "private fun recoverPanMoveJournalAfterModbusReady"
        )
        assertTrue(recovery.contains("已有双 lane 会话仅允许人工恢复"))
        assertTrue(!recovery.contains("actionGroupLocks.release(doubleLaneSession.sessionId)"))
        assertTrue(!recovery.contains("actionGroupLocks.forceAcquire"))
    }

    @Test
    fun runtimeResume_shouldFailClosedWithoutAcquiringOrPreparing() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resume = functionBlock(
            operate,
            "suspend fun resumeParallelSupplementForRuntimeRecovery",
            "private fun supplementPipelineOutcome"
        )
        assertTrue(resume.contains("enterActionGroupMaintenance"))
        assertTrue(resume.contains("已有双 lane 会话仅允许人工恢复"))
        assertTrue(!resume.contains("actionGroupLocks.tryAcquire"))
        assertTrue(!resume.contains("handleParallelSupplementResumePreparation("))
    }

    @Test
    fun runtimeResumeEvidenceRead_shouldKeepCorruptedEvidenceLocked() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resumeOnce = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplementOnce",
            "private suspend fun finishParallelSupplementResume"
        )

        assertTrue(resumeOnce.contains("readSupplementPrefetchEvidence()"))
        assertTrue(resumeOnce.contains("DurableEvidenceReadResult.Corrupted"))
        val corruptedStart = resumeOnce.indexOf("DurableEvidenceReadResult.Corrupted")
        val corrupted = resumeOnce.substring(corruptedStart, resumeOnce.indexOf("val lane", corruptedStart))
        assertTrue(corrupted.contains("enterActionGroupMaintenance"))
        assertTrue(!corrupted.contains("releaseResumeLock()"))
    }

    @Test
    fun runtimeResumePlanMismatch_shouldRetainTheOwnedLock() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resumeOnce = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplementOnce",
            "private suspend fun finishParallelSupplementResume"
        )
        val mismatch = resumeOnce.substring(
            resumeOnce.indexOf("if (session.sessionId != plan.sessionId"),
            resumeOnce.indexOf("val runtime = AppConfig.getAppConfig()")
        )

        assertTrue(mismatch.contains("enterActionGroupMaintenance"))
        assertTrue(!mismatch.contains("releaseResumeLock()"))
    }

    @Test
    fun runtimeResumeMissingPanOrBox_shouldRetainTheOwnedLock() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val resumeOnce = functionBlock(
            operate,
            "private suspend fun resumePreparedParallelSupplementOnce",
            "private suspend fun finishParallelSupplementResume"
        )
        val missingState = resumeOnce.substring(
            resumeOnce.indexOf("if (pan == null || box == null)"),
            resumeOnce.indexOf("val runtimePaused")
        )

        assertTrue(missingState.contains("enterActionGroupMaintenance"))
        assertTrue(!missingState.contains("releaseResumeLock()"))
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
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
