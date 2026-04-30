package cn.niuyannet.kaochang.android.ui

data class OrderDeliveryReadinessResult(
    val canStartDelivery: Boolean,
    val reason: String
)

object OrderDeliveryReadinessPolicy {
    fun evaluate(
        onlineStatus: Int,
        errorStatus: Int,
        inspectionMode: Int
    ): OrderDeliveryReadinessResult {
        return when {
            errorStatus != 0 -> OrderDeliveryReadinessResult(
                canStartDelivery = false,
                reason = "设备故障状态不允许出餐：errorStatus（设备故障状态）=$errorStatus（非0=存在故障）"
            )
            inspectionMode == 1 -> OrderDeliveryReadinessResult(
                canStartDelivery = false,
                reason = "检修模式不允许出餐：inspectionMode（检修模式）=1（检修中）"
            )
            onlineStatus == 3 -> OrderDeliveryReadinessResult(
                canStartDelivery = false,
                reason = "设备维护中不允许出餐：onlineStatus（设备营业状态）=3（维护中）"
            )
            onlineStatus !in setOf(1, 4) -> OrderDeliveryReadinessResult(
                canStartDelivery = false,
                reason = "设备非运营/非工作中不允许出餐：onlineStatus（设备营业状态）=$onlineStatus"
            )
            else -> OrderDeliveryReadinessResult(
                canStartDelivery = true,
                reason = "设备状态允许出餐"
            )
        }
    }
}
