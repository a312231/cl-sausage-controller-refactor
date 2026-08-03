package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 顾客订单优先级开关。
 *
 * 订单进入出餐链路后，自动补肠允许当前单根动作组安全完成，
 * 但不再启动同一批里的下一根补肠，避免顾客等待整批补肠结束。
 */
object SupplementPauseController {
    @Volatile
    private var pauseOrderNo: String? = null

    @Synchronized
    fun requestPauseForOrder(orderNo: String): Boolean {
        if (orderNo.isBlank()) {
            return false
        }
        if (pauseOrderNo == orderNo) {
            return false
        }
        pauseOrderNo = orderNo
        return true
    }

    @Synchronized
    fun clearOrderPause(orderNo: String) {
        if (orderNo.isBlank()) {
            return
        }
        if (pauseOrderNo == orderNo) {
            pauseOrderNo = null
            LogUtils.i("【订单优先】顾客订单出餐结束，允许自动补肠继续调度：orderNo=$orderNo")
        }
    }

    fun shouldPauseForOrder(): Boolean = !pauseOrderNo.isNullOrBlank()

    fun currentOrderNo(): String? = pauseOrderNo

    @Synchronized
    fun isPausedForDifferentOrder(orderNo: String): Boolean =
        !pauseOrderNo.isNullOrBlank() && pauseOrderNo != orderNo
}
