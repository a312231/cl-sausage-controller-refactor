package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OrderSupplementResumeFailureSourceTest {

    @Test
    fun successfulOrder_shouldHandleFalseSupplementResumeResultAsMaintenance() {
        val source = listOf(
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("..", "app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
            ?: error("OrderActivity.kt not found")
        val finallyBlock = source.substringAfter("} finally {").substringBefore("OrderStateManager.releaseProcessing")
        val purchaseBlock = source.substringAfter("fun purchaseOrder(")
            .substringBefore("private suspend fun handleOrderItemDelivery")

        assertTrue(finallyBlock.contains("supplementResumeSucceeded"))
        assertFalse(finallyBlock.contains("runCatching"))
        assertTrue(finallyBlock.contains("clearSupplementPauseAndResume"))
        assertTrue(finallyBlock.contains("shouldEnterMaintenance(orderOutcome, supplementResumeSucceeded)"))
        assertTrue(finallyBlock.contains("var supplementResumeCancellation: CancellationException?"))
        assertTrue(finallyBlock.contains("supplementResumeCancellation?.let { throw it }"))
        assertTrue(
            purchaseBlock.contains(
                "orderCompleted = orderCompleted && OrderSupplementResumePolicy.shouldResume(orderOutcome)"
            )
        )
        assertTrue(
            purchaseBlock.indexOf("catch (cancelled: CancellationException)") in
                0 until purchaseBlock.indexOf("catch (e: Exception)")
        )
        val cancellationCatch = purchaseBlock.substringAfter("catch (cancelled: CancellationException)")
            .substringBefore("catch (e: Exception)")
        val exceptionCatch = purchaseBlock.substringAfter("catch (e: Exception)")
            .substringBefore("} finally {")
        assertTrue(cancellationCatch.contains("orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN"))
        assertTrue(exceptionCatch.contains("orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN"))
    }

    @Test
    fun settledFailure_shouldPersistTerminalStateBeforeReleasingClaimAndProcessing() {
        val source = listOf(
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt"),
            File("..", "app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        ).firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
            ?: error("OrderActivity.kt not found")
        val purchaseBlock = source.substringAfter("fun purchaseOrder(")
            .substringBefore("private suspend fun handleOrderItemDelivery")

        val persistFailed = purchaseBlock.indexOf("OrderStateManager.markAsFailedDurable(safeOrderNo)")
        val releaseClaim = purchaseBlock.indexOf("KaoChangOperate.releaseOrderDeliveryMoveStatus(")
        val releaseProcessing = purchaseBlock.indexOf("OrderStateManager.releaseProcessing(safeOrderNo)")

        assertTrue(persistFailed >= 0)
        assertTrue(releaseClaim > persistFailed)
        assertTrue(releaseProcessing > releaseClaim)
        assertTrue(purchaseBlock.contains("OrderSupplementResumePolicy.shouldReleaseClaim(orderOutcome)"))
        assertTrue(purchaseBlock.contains("if (orderClaimReleased)"))
    }

    @Test
    fun manualClaimCloseout_shouldReuseTwoSnapshotsWithoutBackgroundWork() {
        val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val closeout = operate.substringAfter("suspend fun closeStaleOrderDeliveryClaimForMaintenance(")
            .substringBefore("private fun staleOrderClaimCloseoutRejection")
        val rejection = operate.substringAfter("private fun staleOrderClaimCloseoutRejection")
            .substringBefore("fun clearSettledOrderPause")
        val delivery = operate.substringAfter("suspend fun takeSausageResult(")
            .substringBefore("private fun reportSellSuccess")
        val maintenance = source("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clearAll = maintenance.substringAfter("private fun performClearAll(")
            .substringBefore("private fun showConfirmDiscardAndResetPanDialog")

        assertTrue(closeout.contains("readOrphanDeliveryBusySnapshot()"))
        assertTrue(closeout.contains("delay(500L)"))
        assertTrue(closeout.contains("evaluateIdleSnapshots(snapshots)"))
        assertTrue(closeout.contains("OrderStateManager.ensureTerminalForStaleCloseoutDurable(claim.orderNo)"))
        assertTrue(closeout.contains("closeOrderDeliveryMoveForMaintenance("))
        assertTrue(closeout.contains("OrderStateManager.releaseProcessing(claim.orderNo)"))
        assertFalse(closeout.contains("operateScope.launch"))
        assertFalse(closeout.contains("while ("))
        assertTrue(rejection.contains("OrderStateManager.canCloseStaleClaim(claim.orderNo)"))
        assertTrue(delivery.contains("freshExecutionObserved"))
        assertTrue(
            closeout.indexOf("OrderStateManager.canCloseStaleClaim(claim.orderNo)") in
                0 until closeout.indexOf("config.onlineStatus = ORDER_CLAIM_MAINTENANCE_STATUS")
        )
        assertTrue(maintenance.contains("确认收口订单 ${'$'}{claim.orderNo}"))
        assertTrue(maintenance.contains("staleClaimClosed || waitUntilMachineIdleOrTimeout("))
        val processingCheck = clearAll.indexOf(
            "if (staleClaim != null && !OrderStateManager.canCloseStaleClaim(staleClaim.orderNo))"
        )
        val disableAlgorithm = clearAll.indexOf("disableAlgorithmForMaintenance(\"点击清空烤盘\")")
        assertTrue(processingCheck in 0 until disableAlgorithm)
        assertTrue(clearAll.contains("catch (cancelled: CancellationException)"))
        assertTrue(clearAll.contains("catch (error: Exception)"))
        val exceptionCatch = clearAll.substringAfter("catch (error: Exception)")
        assertTrue(exceptionCatch.contains("setMaintenanceActionButtonsEnabled(true)"))
        assertTrue(exceptionCatch.contains("clearPanCooldownJob = null"))
    }

    @Test
    fun committedOrderCloseout_shouldNotBeDowngradedByLaterUiOrSyncFailure() {
        val activity = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
        val purchase = activity.substringAfter("fun purchaseOrder(")
            .substringBefore("private suspend fun handleOrderItemDelivery")
        val cancellationCatch = purchase.substringAfter("catch (cancelled: CancellationException)")
            .substringBefore("catch (e: Exception)")
        val exceptionCatch = purchase.substringAfter("catch (e: Exception)")
            .substringBefore("} finally {")

        assertTrue(purchase.contains("var orderCloseoutCommitted = false"))
        assertTrue(purchase.contains("orderCloseoutCommitted = terminalStatePersisted && orderClaimReleased"))
        assertTrue(cancellationCatch.contains("if (!orderCloseoutCommitted)"))
        assertTrue(exceptionCatch.contains("if (!orderCloseoutCommitted)"))
        assertTrue(exceptionCatch.contains("hideProcessingDialog()"))
        assertTrue(exceptionCatch.contains("setDataToView()"))
    }

    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }
        ?.readText()
        ?.replace("\r\n", "\n")
        ?: error("source file not found: $path")
}
