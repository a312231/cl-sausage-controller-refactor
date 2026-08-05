package cn.niuyannet.kaochang.android.services

/** 只允许一个订单接管残留 OUT_MANUAL 恢复，不持有机械资源、不落盘。 */
internal class ManualOutOrderRecoveryCoordinator {
    private val lock = Any()
    private var reservedOrderNo: String? = null

    fun tryReserve(orderNo: String): Boolean = synchronized(lock) {
        if (orderNo.isBlank()) return@synchronized false
        val current = reservedOrderNo
        if (current == null) {
            reservedOrderNo = orderNo
            true
        } else {
            current == orderNo
        }
    }

    fun isReservedFor(orderNo: String): Boolean = synchronized(lock) {
        orderNo.isNotBlank() && reservedOrderNo == orderNo
    }

    fun release(orderNo: String) {
        synchronized(lock) {
            if (reservedOrderNo == orderNo) {
                reservedOrderNo = null
            }
        }
    }
}
