package cn.niuyannet.kaochang.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDispatchV2WiringSourceTest {

    private val main = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
    private val order = source("app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt")
    private val operate = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
    private val protocol = source("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/MqttProtocol.kt")
    private val send = source("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")

    @Test
    fun frozenV2Types_shouldStayOnAction2AndLegacyAction1ShouldRemain() {
        listOf(
            "order_dispatch_offer_v2",
            "order_dispatch_offer_ack_v2",
            "order_dispatch_commit_v2",
            "order_dispatch_commit_rejected_v2"
        ).forEach { assertTrue(protocol.contains(it)) }
        assertTrue(protocol.contains("ORDER_DISPATCH_PROTOCOL_VERSION = 2"))
        assertTrue(main.contains("MqttProtocol.ACTION_ORDER_EVENT ->"))
        assertTrue(main.contains("MqttProtocol.ACTION_SERVICE_STATE -> handleServiceStateMessage"))
    }

    @Test
    fun offerAckAndCommitRejected_shouldPublishFrozenFieldsOnlyOnAction2() {
        val ack = send.substringAfter("fun publishOrderDispatchOfferAckV2(")
            .substringBefore("fun publishOrderDispatchCommitRejectedV2(")
        val rejected = send.substringAfter("fun publishOrderDispatchCommitRejectedV2(")
            .substringBefore("fun publishServiceUpdateStatus(")
        listOf(ack, rejected).forEach { block ->
            listOf(
                "messageType",
                "protocolVersion",
                "dispatch_id",
                "order_no",
                "device_id",
                "payload_hash",
                "accepted",
                "reason",
                "reported_at_ms"
            ).forEach { field -> assertTrue(block.contains("\"$field\"")) }
            assertTrue(block.contains("action = MqttProtocol.ACTION_SERVICE_STATE"))
            assertFalse(block.contains("action = MqttProtocol.ACTION_ORDER_EVENT"))
        }
    }

    @Test
    fun offerAndV2BusyCommit_shouldUseAction2WithoutStatus5OrMechanicalAdmission() {
        val offer = main.substringAfter("private fun handleOrderDispatchOfferV2(")
            .substringBefore("private fun handleOrderDispatchCommitV2(")
        val commit = main.substringAfter("private fun handleOrderDispatchCommitV2(")
            .substringBefore("private fun startAcceptedOrder(")

        assertFalse(offer.contains("acceptIncomingOrderForMechanicalDispatch"))
        assertFalse(offer.contains("requestSupplementPauseForOrder"))
        assertFalse(offer.contains("tryClaimOrderDeliveryMoveStatus"))
        assertFalse(offer.contains("purchaseOrder("))
        assertTrue(main.contains("publishOrderDispatchCommitRejectedV2"))
        assertFalse(commit.contains("reportOrderEvent("))
        assertFalse(commit.contains("reportBusyRejectedOrderFailure("))
    }

    @Test
    fun admissionCallbackAndPauseFailure_shouldReleaseOnlyCurrentReservationAndMemory() {
        val admission = operate.substringAfter("fun acceptIncomingOrderForMechanicalDispatch(")
            .substringBefore("fun requestSupplementPauseForOrder(")

        assertTrue(admission.contains("beforeAccept"))
        assertTrue(admission.contains("OrderStateManager.checkAndAcceptOrder(orderNo, beforeAccept)"))
        assertTrue(admission.contains("manualOutOrderRecoveryCoordinator.release(orderNo)"))
        assertTrue(admission.contains("OrderStateManager.releaseProcessing(orderNo)"))
        assertTrue(admission.contains("requestSupplementPauseForOrder(orderNo)"))
    }

    @Test
    fun v2EventsAndOfflineCompensation_shouldRequireExecutionStateAndCarryDispatch() {
        assertTrue(order.contains("dispatchId = activeDispatchId"))
        assertTrue(order.contains("protocolVersion = activeDispatchProtocolVersion"))
        assertTrue(order.contains("markTerminalDurably"))
        assertTrue(order.contains("canPublishOrderEvent"))
        assertTrue(operate.contains("canPublishOrderEvent"))
        assertTrue(operate.contains("orderContext.dispatchId"))
        assertTrue(operate.contains("PendingActionGroupOrderEvent("))
    }

    private fun source(path: String): String =
        listOf(File(path), File("..", path)).first { it.isFile }.readText().replace("\r\n", "\n")
}
