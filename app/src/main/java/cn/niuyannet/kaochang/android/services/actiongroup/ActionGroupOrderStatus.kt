package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.KaoChangOperate

object ActionGroupOrderStatus {
    const val DELIVERING = 1
    const val FINISHED = 2
    const val CUSTOMER_TIMEOUT_DISCARDED = 6
    const val ERROR = 5

    fun statusFor(result: KaoChangOperate.TakeSausageResult): Int {
        return when (result) {
            KaoChangOperate.TakeSausageResult.TAKEN_BY_USER -> FINISHED
            KaoChangOperate.TakeSausageResult.DISCARDED -> FINISHED
            KaoChangOperate.TakeSausageResult.DELIVERED_CLOSEOUT_UNKNOWN -> FINISHED
            KaoChangOperate.TakeSausageResult.NO_SELLABLE_STOCK,
            KaoChangOperate.TakeSausageResult.NO_DISPATCH,
            KaoChangOperate.TakeSausageResult.EVIDENCE_UNKNOWN,
            KaoChangOperate.TakeSausageResult.ERROR -> ERROR
        }
    }
}
