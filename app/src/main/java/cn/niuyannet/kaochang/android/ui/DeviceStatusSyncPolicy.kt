package cn.niuyannet.kaochang.android.ui

/**
 * 设备营业状态 HTTP 镜像策略。
 *
 * onlineStatus（设备营业状态）：
 * 0 = 未启用，1 = 运营中，2 = 休息中，3 = 维护中，4 = 工作中/正在出餐。
 */
internal object DeviceStatusSyncPolicy {
    fun shouldMirrorOnlineStatusToHttp(onlineStatus: Int): Boolean = onlineStatus in 0..4
}
