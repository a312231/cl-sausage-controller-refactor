package cn.niuyannet.kaochang.android.services.actiongroup

enum class OrderFailureReason {
    NO_SELLABLE_STOCK,
    DEVICE_BUSY_NO_DISPATCH,
    DELIVERY_EVIDENCE_UNKNOWN,
    MECHANICAL_FAILED
}

enum class DeliveryEvidence {
    NOT_DISPATCHED,
    UNKNOWN,
    MECHANICAL_FAILED
}

data class OrderFailureMetadata(
    val failureReason: OrderFailureReason,
    val deliveryEvidence: DeliveryEvidence,
    val out1Dispatched: Boolean?
) {
    companion object {
        val NO_SELLABLE_STOCK = OrderFailureMetadata(
            OrderFailureReason.NO_SELLABLE_STOCK,
            DeliveryEvidence.NOT_DISPATCHED,
            false
        )
        val DEVICE_BUSY_NO_DISPATCH = OrderFailureMetadata(
            OrderFailureReason.DEVICE_BUSY_NO_DISPATCH,
            DeliveryEvidence.NOT_DISPATCHED,
            false
        )
        val EVIDENCE_UNKNOWN = OrderFailureMetadata(
            OrderFailureReason.DELIVERY_EVIDENCE_UNKNOWN,
            DeliveryEvidence.UNKNOWN,
            null
        )
        val MECHANICAL_FAILED = OrderFailureMetadata(
            OrderFailureReason.MECHANICAL_FAILED,
            DeliveryEvidence.MECHANICAL_FAILED,
            true
        )
    }
}
