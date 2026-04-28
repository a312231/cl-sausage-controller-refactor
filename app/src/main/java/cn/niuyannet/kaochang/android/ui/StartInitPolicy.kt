package cn.niuyannet.kaochang.android.ui

/**
 * 设备初始化入口策略。
 *
 * 当前主流程不再依赖视觉识别或 ARUCO 标定作为设备初始化前置条件。
 */
object StartInitPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun canInitializeDevice(
        forceVisionSuccess: Int,
        hasArucoCorners: Boolean
    ): Boolean = true
}
