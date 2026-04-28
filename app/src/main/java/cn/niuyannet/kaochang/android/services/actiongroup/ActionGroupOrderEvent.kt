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
    val createdAtMs: Long = System.currentTimeMillis()
)

object ActionGroupOrderEventPayloadBuilder {
    fun build(orderContext: ActionGroupOrderContext, status: Int): JSONObject {
        return JSONObject().apply {
            put("order_no", orderContext.orderNo)
            put("item_id", orderContext.itemId)
            put("product_id", orderContext.productId)
            put("taste_code", orderContext.tasteCode)
            put("status", status)
        }
    }
}

interface ActionGroupOrderEventStore {
    fun load(): PendingActionGroupOrderEvent?
    fun save(event: PendingActionGroupOrderEvent)
    fun clear()
}
