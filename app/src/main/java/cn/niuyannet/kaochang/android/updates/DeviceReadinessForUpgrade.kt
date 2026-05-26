package cn.niuyannet.kaochang.android.updates

object DeviceReadinessForUpgrade {
    data class State(
        val moveStatus: Int,
        val onlineStatus: Int,
        val hasProcessingOrder: Boolean,
        val hasRecentBusyOrder: Boolean,
        val hasOrderTracking: Boolean,
        val mqttPendingCount: Int,
        val pendingRuntimeAckCount: Int,
        val pendingAvailableAckCount: Int
    )

    data class Result(
        val ok: Boolean,
        val blockedBy: String?
    )

    fun check(state: State, minimalGate: Boolean = false): Result {
        if (state.moveStatus != 0) {
            return blocked("机械动作状态不是空闲：moveStatus（机械动作状态）=${state.moveStatus}")
        }
        if (state.hasOrderTracking) {
            return blocked("订单自清洁跟踪未结束")
        }
        if (minimalGate) {
            return Result(ok = true, blockedBy = null)
        }
        if (state.onlineStatus == 4) {
            return blocked("设备正在出餐：onlineStatus（设备营业状态）=4（工作中/正在出餐）")
        }
        if (state.hasProcessingOrder) {
            return blocked("存在处理中订单")
        }
        if (state.hasRecentBusyOrder) {
            return blocked("5秒内收到过忙拒单，等待订单入口稳定")
        }
        if (state.mqttPendingCount > 0) {
            return blocked("MQTT 发送队列未清空：pendingCount=${state.mqttPendingCount}")
        }
        if (state.pendingRuntimeAckCount > 0) {
            return blocked("runtime_state 待确认未清空：pendingRuntimeAckCount=${state.pendingRuntimeAckCount}")
        }
        if (state.pendingAvailableAckCount > 0) {
            return blocked("available_state 待确认未清空：pendingAvailableAckCount=${state.pendingAvailableAckCount}")
        }
        return Result(ok = true, blockedBy = null)
    }

    private fun blocked(reason: String): Result = Result(ok = false, blockedBy = reason)
}
