package cn.niuyannet.kaochang.android.fragments

data class MaintenanceBoxOperationDecision(
    val allowed: Boolean,
    val reason: String
)

object MaintenanceBoxOperationPolicy {
    fun evaluate(isEnable: Int, moveStatus: Int): MaintenanceBoxOperationDecision {
        return when {
            isEnable != 0 -> MaintenanceBoxOperationDecision(false, "请先关闭烤肠算法：isEnable=$isEnable")
            moveStatus != 0 -> MaintenanceBoxOperationDecision(false, "机械动作状态不是空闲：moveStatus=$moveStatus")
            else -> MaintenanceBoxOperationDecision(true, "允许人工修改库存")
        }
    }
}
