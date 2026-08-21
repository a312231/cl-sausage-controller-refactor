package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDeliveredCloseoutUnknownTest {

    private val orderActivity = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
    private val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")

    @Test
    fun deliveredCloseoutUnknownBranch_shouldCountOnceReportStatus2AndStopWithoutStatus5() {
        val branch = orderActivity.substringAfter(
            "KaoChangOperate.TakeSausageResult.DELIVERED_CLOSEOUT_UNKNOWN -> {"
        ).substringBefore("KaoChangOperate.TakeSausageResult.NO_SELLABLE_STOCK -> {")
        val firstStatement = branch.lineSequence().map(String::trim).first { it.isNotEmpty() }

        assertEquals("deliveryConfirmedForCurrentUnit = true", firstStatement)
        assertTrue(branch.contains("deliveredCount++"))
        assertTrue(branch.contains("DeliveredCloseoutUnknownOrderEventPolicy.reportFinishedBestEffort"))
        assertTrue(branch.contains("return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.EVIDENCE_UNKNOWN)"))
        assertTrue(!branch.contains("ActionGroupOrderStatus.ERROR"))
        assertTrue(!branch.contains("OrderFailureMetadata"))
    }

    @Test
    fun outerCatch_shouldCheckDeliveredFlagBeforeAnyStatus5Fallback() {
        val catchBlock = orderActivity.substringAfter("private suspend fun handleOrderItemDelivery(")
            .substringAfter("} catch (e: Exception) {")
            .substringBefore("if (deliveredCount < item.quantity)")

        val flagIndex = catchBlock.indexOf("if (deliveryConfirmedForCurrentUnit)")
        val status5Index = catchBlock.indexOf("reportOrderEvent(")
        assertTrue(flagIndex >= 0)
        assertTrue(status5Index < 0 || flagIndex < status5Index)
        assertTrue(catchBlock.substring(flagIndex).contains(
            "return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.EVIDENCE_UNKNOWN)"
        ))
    }

    @Test
    fun takeResult_shouldResolveCurrentCallEvidenceAndNeverPublishTerminalMqtt() {
        val takeBody = operate.substringAfter("suspend fun takeSausageResult(")
            .substringBefore("internal fun applySuccessfulTakePanState")

        assertTrue(takeBody.contains("val out4CurrentCallEvidence = Out4CurrentCallEvidence()"))
        assertTrue(takeBody.contains("resolveAfterActionGroupFailure("))
        assertTrue(takeBody.contains("Out4DeliveryCloseoutPolicy.resolve("))
        assertTrue(takeBody.contains("TakeSausageResult.DELIVERED_CLOSEOUT_UNKNOWN"))
        assertTrue(!takeBody.contains("reportActionGroupOrderTerminalEvent("))
        assertTrue(!takeBody.contains("publishServiceUpdateOrder("))
    }

    private fun source(path: String): String =
        listOf(File(path), File("..", path)).first { it.isFile }.readText().replace("\r\n", "\n")
}
