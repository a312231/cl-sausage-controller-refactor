package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParallelSupplementOrderEntrySourceTest {

    @Test
    fun orderEntries_shouldUseKaoChangOperatePersistentPauseBoundary() {
        val main = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")

        assertTrue(main.contains("KaoChangOperate.requestSupplementPauseForOrder(orderNo)"))
        assertTrue(order.contains("KaoChangOperate.requestSupplementPauseForOrder(safeOrderNo)"))
        assertTrue(order.contains("KaoChangOperate.clearSupplementPauseAndResume("))
        assertFalse(main.contains("SupplementPauseController.requestPauseForOrder"))
        assertFalse(order.contains("SupplementPauseController.clearOrderPause"))
    }

    @Test
    fun orderCompletion_shouldNotDirectlyClearMoveStatus() {
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")

        assertFalse(order.contains("AppConfig.getAppConfig().moveStatus = 0"))
        assertTrue(order.contains("KaoChangOperate.clearOrderMoveStatusIfRecoverySafe("))
    }

    @Test
    fun parallelFailurePolicy_shouldRetainSessionResourceLockAndMoveStatus() {
        assertTrue(
            SupplementPipelineFailureRetentionPolicy.shouldRetain(
                evidenceActive = true,
                outcome = SupplementPipelineOutcome.FAILED,
                safeReleaseConfirmed = false
            )
        )
        assertTrue(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.CANCELLED, false))
        assertTrue(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.RECOVERY_FAILED, false))
        assertTrue(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.PAUSED_FOR_ORDER, false))
        assertFalse(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.PAUSED_FOR_ORDER, true))
        assertTrue(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.SUCCESS, false))
        assertFalse(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.SUCCESS, true))
        assertFalse(SupplementPipelineFailureRetentionPolicy.shouldRetain(true, SupplementPipelineOutcome.SUPPLY_DEGRADED, true))
        assertFalse(SupplementPipelineFailureRetentionPolicy.shouldRetain(false, SupplementPipelineOutcome.FAILED, false))
    }

    @Test
    fun corruptedParallelEvidence_shouldFailClosedBeforeLegacyResume() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("suspend fun clearSupplementPauseAndResume")
        val end = operate.indexOf("fun clearOrderMoveStatusIfRecoverySafe", start)
        val block = operate.substring(start, end)

        assertTrue(block.contains("is DurableEvidenceReadResult.Corrupted ->"))
        assertTrue(block.indexOf("return false") < block.indexOf("resumePausedSupplementAfterOrder(reason)"))
    }

    @Test
    fun barrierWriteFailure_shouldContainForceAcquireConflictAndEnterPreservingMaintenance() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("fun requestSupplementPauseForOrder")
        val end = operate.indexOf("suspend fun clearSupplementPauseAndResume", start)
        val block = operate.substring(start, end)

        assertTrue(block.contains("runCatching {\n                    actionGroupLocks.forceAcquire"))
        assertTrue(block.contains("lockError="))
        assertTrue(block.contains("preserveMoveStatus = true"))
    }

    @Test
    fun orderGateAndPausedContinuation_shouldShareSafePausePredicate() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val busy = operate.substring(
            operate.indexOf("fun isActionGroupBusyForOrderGate"),
            operate.indexOf("fun isActionGroupIdleForMoveStatusRecovery")
        )
        val paused = operate.substring(
            operate.indexOf("fun hasPausedSupplementContinuation"),
            operate.indexOf("fun requestSupplementPauseForOrder")
        )

        assertTrue(busy.contains("SupplementPrefetchOrderPauseSafety.isSafe"))
        assertTrue(busy.contains("SupplementPrefetchOrderGatePolicy.isBusy"))
        assertTrue(paused.contains("SupplementPrefetchOrderPauseSafety"))
    }

    @Test
    fun safeParallelPauseRelease_shouldRequireSessionOwnerMatch() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("private suspend fun moveSausagesToKaoPansByTasteInternal")
        val end = operate.indexOf("private fun describeAtomicActionResult", start)
        val block = operate.substring(start, end)

        assertTrue(block.contains("safePausedSession?.sessionId == sessionId"))
        assertTrue(block.contains("actionGroupLocks.release(requireNotNull(safePausedSession).sessionId)"))
    }

    @Test
    fun fallbackReplan_shouldYieldToCommittedOrderBeforeCompletionCleanup() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("val completed = when (result)")
        val end = operate.indexOf("private fun describeAtomicActionResult", start)
        val block = operate.substring(start, end)

        val orderPause = block.indexOf("supplementPrefetchSessionRepository.pauseCommittedDeliveryForPendingOrder()")
        val safePausedRead = block.indexOf("val safePausedSession = activeSafePausedSupplementSession()")
        val replanRelease = block.indexOf("val safeReplanReleaseConfirmed")
        val completion = block.indexOf("SupplementPipelineCompletionCoordinator.complete(")

        assertTrue("订单 barrier 必须先转为安全暂停", orderPause >= 0)
        assertTrue("必须在重规划释放前重新读取安全订单暂停", orderPause < safePausedRead)
        assertTrue("订单暂停判定必须早于重规划释放判定", safePausedRead < replanRelease)
        assertTrue("重规划与订单仲裁必须发生在最终清 session 前", replanRelease < completion)
        assertTrue(block.contains("safePausedSession != null -> SupplementPipelineOutcome.PAUSED_FOR_ORDER"))
        assertTrue(block.contains("(result is InActionGroupPipelineResult.ReplanRequired && safeReplanReleaseConfirmed)"))
    }

    @Test
    fun fallbackReplan_shouldDeferFailureRetentionLogUntilFinalReleaseDecision() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val finallyStart = operate.indexOf("} finally {")
        val finallyEnd = operate.indexOf("val completed = when (result)", finallyStart)
        val finallyBlock = operate.substring(finallyStart, finallyEnd)

        assertTrue(finallyBlock.contains("val deferReplanRetentionLog"))
        assertTrue(finallyBlock.contains("if (retainFailureEvidence && !deferReplanRetentionLog)"))
        assertTrue(finallyBlock.contains("if (retainParallelEvidence && !deferReplanRetentionLog)"))

        val resultLogStart = operate.lastIndexOf("is InActionGroupPipelineResult.ReplanRequired ->")
        val resultLogEnd = operate.indexOf("is InActionGroupPipelineResult.Paused ->", resultLogStart)
        val resultLogBlock = operate.substring(resultLogStart, resultLogEnd)
        assertTrue(operate.contains("var pausedCleanupSucceeded = false"))
        assertTrue(operate.contains("pausedCleanupSucceeded = true"))
        assertTrue(resultLogBlock.contains("safePausedSession != null && pausedCleanupSucceeded"))
        assertTrue(resultLogBlock.contains("订单优先，保留暂停session，moveStatus/锁已释放"))
        assertTrue(resultLogBlock.contains("if (safeReplanReleaseConfirmed && !durableCompletionFailed)"))
        assertTrue(resultLogBlock.contains("logW("))
        assertTrue(resultLogBlock.contains("保留session/锁/moveStatus"))
    }

    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("source file not found: $path")
}
