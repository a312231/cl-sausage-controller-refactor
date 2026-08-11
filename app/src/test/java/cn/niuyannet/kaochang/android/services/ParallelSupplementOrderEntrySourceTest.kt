package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParallelSupplementOrderEntrySourceTest {

    @Test
    fun orderEntries_shouldUseKaoChangOperatePersistentPauseBoundary() {
        val main = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")

        assertTrue(main.contains("KaoChangOperate.acceptIncomingOrderForMechanicalDispatch(orderNo)"))
        assertFalse(main.contains("OrderStateManager.checkAndAcceptOrder(orderNo)"))
        assertFalse(main.contains("KaoChangOperate.requestSupplementPauseForOrder(orderNo)"))
        assertTrue(order.contains("KaoChangOperate.requestSupplementPauseForOrder(safeOrderNo)"))
        assertTrue(order.contains("KaoChangOperate.clearSupplementPauseAndResume("))
        assertFalse(main.contains("SupplementPauseController.requestPauseForOrder"))
        assertFalse(order.contains("SupplementPauseController.clearOrderPause"))

        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val admissionStart = operate.indexOf("fun acceptIncomingOrderForMechanicalDispatch")
        val admissionEnd = operate.indexOf("fun requestSupplementPauseForOrder", admissionStart)
        val admission = operate.substring(admissionStart, admissionEnd)
        assertTrue(admission.contains("withFaultHeatTransitionLock"))
        assertTrue(admission.contains("OrderStateManager.checkAndAcceptOrder(orderNo)"))
        assertTrue(admission.contains("requestSupplementPauseForOrder(orderNo)"))

        val clearStart = operate.indexOf("suspend fun clearSupplementPauseAndResume(")
        val clearEnd = operate.indexOf("fun releaseOrderDeliveryMoveStatus", clearStart)
        val clear = operate.substring(clearStart, clearEnd)
        assertTrue(clear.contains("isPausedForDifferentOrder(orderNo)"))
    }

    @Test
    fun orderCompletion_shouldNotDirectlyClearMoveStatus() {
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")

        assertFalse(order.contains("AppConfig.getAppConfig().moveStatus = 0"))
        assertTrue(order.contains("KaoChangOperate.releaseOrderDeliveryMoveStatus("))
        assertTrue(order.contains("preDeliveryClaim"))
    }

    @Test
    fun completedOrderClaimRelease_shouldNotWaitForPausedParallelSupplementEvidence() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("fun releaseOrderDeliveryMoveStatus")
        val end = operate.indexOf("private fun restoreParallelSupplementBusyMoveStatus", start)
        val release = operate.substring(start, end)

        assertTrue(release.contains("actionGroupSessionStore.load() == null"))
        assertTrue(release.contains("panMoveJournalStore.load() == null"))
        assertTrue(release.contains("discardJournalStore.load() == null"))
        assertTrue(release.contains("actionGroupLocks.isIdle()"))
        assertFalse(release.contains("hasBlockingSupplementPrefetchEvidence()"))
    }

    @Test
    fun orderMoveStatusTakeover_shouldUseDurableOrderClaimAtBothWriteSites() {
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")

        assertEquals(2, "KaoChangOperate.tryClaimOrderDeliveryMoveStatus".toRegex().findAll(order).count())
        assertTrue(order.contains("tryClaimOrderDeliveryMoveStatus(safeOrderNo)"))
        assertTrue(order.contains("tryClaimOrderDeliveryMoveStatus(orderNo)"))
        assertTrue(operate.contains("private val orderDeliveryClaimStore = OrderDeliveryClaimStore("))
        assertFalse(operate.contains("mutationAllowed = { !isEmergencyResetRecoveryPending() }"))
        assertTrue(operate.contains("claimOrderDeliveryMove("))
        assertTrue(operate.contains("releaseOrderDeliveryMove("))
        assertFalse(order.contains("AppConfig.getAppConfig().moveStatus = 2"))
        assertFalse(order.contains("config.moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING"))
    }

    @Test
    fun orderCloseout_shouldResumeSupplementOnlyAfterWholeOrderSuccess() {
        val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        val start = order.indexOf("fun purchaseOrder")
        val end = order.indexOf("private suspend fun handleOrderItemDelivery", start)
        val block = order.substring(start, end)

        assertTrue(block.contains("var orderOutcome = OrderExecutionOutcome.UNKNOWN"))
        assertTrue(block.contains("orderOutcome = OrderExecutionOutcome.SUCCESS"))
        assertTrue(block.contains("orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN"))
        assertTrue(block.contains("OrderSupplementResumePolicy.shouldResume(orderOutcome)"))
        assertTrue(
            block.contains(
                "OrderSupplementResumePolicy.shouldEnterMaintenance(orderOutcome, supplementResumeSucceeded)"
            )
        )
        assertFalse(block.contains("if (preDeliveryReady) {\n                    KaoChangOperate.clearOrderMoveStatusIfRecoverySafe"))
    }

    @Test
    fun parallelResume_shouldAcceptOrderMoveStatusOnlyAtDedicatedHandoff() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("private suspend fun resumePreparedParallelSupplementOnce")
        val end = operate.indexOf("private fun hasPausedSupplementSession", start)
        val block = operate.substring(start, end)

        assertTrue(block.contains("allowedMoveStatuses = setOf(0, 1, 2)"))
        assertEquals(1, Regex("allowedMoveStatuses = setOf\\(0, 1, 2\\)").findAll(operate).count())
        assertTrue(block.contains("allowedMoveStatuses = setOf(0)"))

        val recoveryStart = operate.indexOf("suspend fun recoverActionGroupStateAfterModbusReady")
        val recoveryEnd = operate.indexOf("private fun recoverPanMoveJournalAfterModbusReady", recoveryStart)
        val recovery = operate.substring(recoveryStart, recoveryEnd)
        assertFalse(recovery.contains("handleParallelSupplementResumePreparation("))
    }

    @Test
    fun persistedParallelRecovery_shouldFailClosedWithoutForceAcquireOrAutoRelease() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("suspend fun recoverActionGroupStateAfterModbusReady")
        val end = operate.indexOf("private fun recoverPanMoveJournalAfterModbusReady", start)
        val recovery = operate.substring(start, end)
        val parallelStart = recovery.indexOf("if (doubleLaneSession != null)")
        val parallel = recovery.substring(parallelStart, recovery.indexOf("return false", parallelStart) + "return false".length)

        assertFalse(parallel.contains("actionGroupLocks.forceAcquire"))
        assertFalse(parallel.contains("SupplementPrefetchRecoveryCoordinator("))
        assertFalse(parallel.contains("config.moveStatus = 0"))
        assertTrue(parallel.contains("已有双 lane 会话仅允许人工恢复"))
    }

    @Test
    fun guardedSupplementResidue_shouldBeRejectedInsideAtomicAdmissionBoundary() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("fun acceptIncomingOrderForMechanicalDispatch")
        val end = operate.indexOf("fun requestSupplementPauseForOrder", start)
        val admission = operate.substring(start, end)

        assertTrue(admission.contains("withFaultHeatTransitionLock"))
        assertTrue(admission.contains("hasGuardedSupplementCloseoutResidue"))
        assertTrue(admission.indexOf("hasGuardedSupplementCloseoutResidue") < admission.indexOf("OrderStateManager.checkAndAcceptOrder"))
    }

    @Test
    fun completedDeliveryReplan_shouldReleaseAfterOrderHandoffMoveStatus() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val start = operate.indexOf("is SupplementPrefetchResumePreparation.ReplanRemaining ->")
        val end = operate.indexOf("is SupplementPrefetchResumePreparation.Ready ->", start)
        val block = operate.substring(start, end)

        assertTrue(block.contains("expectedMoveStatuses = setOf(1, 2)"))
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
        val end = operate.indexOf("fun releaseOrderDeliveryMoveStatus", start)
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
        val lockStart = block.indexOf("withFaultHeatTransitionLock {")
        val lockEnd = block.indexOf("\n        }", lockStart)
        val lockedAdmission = block.substring(lockStart, lockEnd)

        assertTrue(lockedAdmission.contains("SupplementPauseController.requestPauseForOrder(orderNo)"))
        assertTrue(lockedAdmission.contains("supplementPrefetchSessionRepository.requestOrderBarrier(orderNo)"))
        assertTrue(Regex("runCatching \\{\\s+actionGroupLocks\\.forceAcquire").containsMatchIn(block))
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
        assertTrue(block.contains("actionGroupLocks.releaseIfOwned(pausedOwner, pausedResourceMask)"))
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
        val completion = block.indexOf("val completionFailure = if (parallelEnabled)", replanRelease)
        val ownerFencedRelease = block.indexOf("releaseSupplementMoveClaimIfOwned(", completion)

        assertTrue("订单 barrier 必须先转为安全暂停", orderPause >= 0)
        assertTrue("必须在重规划释放前重新读取安全订单暂停", orderPause < safePausedRead)
        assertTrue("订单暂停判定必须早于重规划释放判定", safePausedRead < replanRelease)
        assertTrue("重规划与订单仲裁必须发生在最终收口分支前", replanRelease < completion)
        assertTrue("双 lane 最终收口必须使用 owner 条件释放", ownerFencedRelease > completion)
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
