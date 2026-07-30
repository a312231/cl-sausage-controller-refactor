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
        assertTrue(resume.contains("actionGroupLocks.release(session.sessionId)"))

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
            "fun clearOrderMoveStatusIfRecoverySafe"
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
        assertTrue(secondRuntimeGate.contains("return deferParallelSupplementForRuntimeRecovery(plan, reason)"))
        assertTrue(secondRuntimeGate.indexOf("return deferParallelSupplementForRuntimeRecovery(plan, reason)") <
            secondRuntimeGate.lastIndexOf("releaseResumeLock()"))
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
