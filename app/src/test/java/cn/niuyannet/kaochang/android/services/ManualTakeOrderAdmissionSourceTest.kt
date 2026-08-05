package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTakeOrderAdmissionSourceTest {

    private val operate = source("services/KaoChangOperate.kt")
    private val main = source("ui/MainActivity.kt")
    private val order = source("ui/OrderActivity.kt")

    @Test
    fun incomingOrder_shouldUseMechanicalTransitionLockForFinalAdmission() {
        val admission = section(
            operate,
            "fun acceptIncomingOrderForMechanicalDispatch(",
            "fun requestSupplementPauseForOrder("
        )
        val orderBranch = section(main, "if (!OrderMoveStatusPolicy.canAcceptIncomingOrder(", "OrderProcessingStageBridge.publish(")

        assertTrue(admission.contains("withFaultHeatTransitionLock"))
        assertTrue(admission.contains("OrderMoveStatusPolicy.canAcceptIncomingOrder("))
        assertFalse(admission.contains("config.moveStatus != 0"))
        assertTrue(admission.contains("OrderStateManager.checkAndAcceptOrder(orderNo)"))
        assertTrue(admission.contains("requestSupplementPauseForOrder(orderNo)"))
        assertTrue(orderBranch.contains("KaoChangOperate.acceptIncomingOrderForMechanicalDispatch(orderNo)"))
        assertFalse(orderBranch.contains("OrderStateManager.checkAndAcceptOrder(orderNo)"))
    }

    @Test
    fun incomingOrder_shouldOnlyBypassBusyGateForManualOutRecoveryCandidate() {
        val admission = section(
            operate,
            "fun acceptIncomingOrderForMechanicalDispatch(",
            "fun requestSupplementPauseForOrder("
        )
        val orderBranch = section(main, "val actionGroupBusy = KaoChangOperate.isActionGroupBusyForOrderGate()", "OrderProcessingStageBridge.publish(")

        assertTrue(admission.contains("shouldRecoverManualOutBeforeIncomingOrder()"))
        assertTrue(orderBranch.contains("manualOutRecoveryPending"))
        assertTrue(orderBranch.contains("!manualOutRecoveryPending"))
    }

    @Test
    fun orderFlow_shouldRecoverManualOutBeforeWaitingAndAvoidLongBusyTimeout() {
        val purchase = section(order, "fun purchaseOrder(", "private suspend fun handleOrderItemDelivery(")

        assertTrue(purchase.contains("shouldRecoverManualOutBeforeIncomingOrder()"))
        assertTrue(purchase.contains("ensureActionGroupReadyForOrderDispatch("))
        assertTrue(purchase.contains("避免无意义等待"))
    }

    @Test
    fun manualTakeClaim_shouldAlsoRejectAcceptedProcessingOrder() {
        val manualTake = section(
            operate,
            "suspend fun takeSausage(kaoPan: KaoPan)",
            "suspend fun takeSausageResult("
        )

        assertTrue(manualTake.contains("!OrderStateManager.hasProcessingOrder()"))
        assertTrue(manualTake.contains("!SupplementPauseController.shouldPauseForOrder()"))
    }

    private fun source(relativePath: String): String {
        val file = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/$relativePath"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/$relativePath")
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    private fun section(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "Missing section: $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "Missing section end: $endMarker" }
        return text.substring(start, end)
    }
}
