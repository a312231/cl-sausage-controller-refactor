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
}
