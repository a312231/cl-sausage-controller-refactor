package cn.niuyannet.kaochang.android.services.actiongroup

import com.alibaba.fastjson.JSONObject

data class ActionGroupOrderContext(
    val orderNo: String = "",
    val itemId: Int = 0,
    val productId: Int = 0,
    val tasteCode: String = ""
) {
    fun isValid(): Boolean {
        return orderNo.isNotBlank() && itemId > 0 && productId > 0 && tasteCode.isNotBlank()
    }
}

data class PendingActionGroupOrderEvent(
    val orderContext: ActionGroupOrderContext = ActionGroupOrderContext(),
    val status: Int = 0,
    val panNo: Int = 0,
    val discarded: Boolean = false,
    val failureReason: OrderFailureReason? = null,
    val deliveryEvidence: DeliveryEvidence? = null,
    val out1Dispatched: Boolean? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

object ActionGroupOrderEventPayloadBuilder {
    fun build(
        orderContext: ActionGroupOrderContext,
        status: Int,
        failureReason: OrderFailureReason? = null,
        deliveryEvidence: DeliveryEvidence? = null,
        out1Dispatched: Boolean? = null
    ): JSONObject {
        return JSONObject().apply {
            put("order_no", orderContext.orderNo)
            put("item_id", orderContext.itemId)
            put("product_id", orderContext.productId)
            put("taste_code", orderContext.tasteCode)
            put("status", status)
            if (status == ActionGroupOrderStatus.ERROR && failureReason != null) {
                put("failure_reason", failureReason.name)
                put("delivery_evidence", deliveryEvidence?.name)
                if (out1Dispatched != null) {
                    put("out1_dispatched", out1Dispatched)
                }
            }
        }
    }
}

interface ActionGroupOrderEventStore {
    fun load(): PendingActionGroupOrderEvent?
    fun save(event: PendingActionGroupOrderEvent): Boolean
    fun clear()
}

object DeliveredCloseoutUnknownOrderEventPolicy {
    fun reportFinishedBestEffort(reportFinished: () -> Boolean): Boolean =
        runCatching(reportFinished).getOrDefault(false)
}
