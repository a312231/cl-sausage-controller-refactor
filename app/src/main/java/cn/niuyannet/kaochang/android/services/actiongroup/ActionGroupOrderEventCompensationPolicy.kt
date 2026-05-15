package cn.niuyannet.kaochang.android.services.actiongroup

object ActionGroupOrderEventCompensationPolicy {
    fun shouldPersistPendingEvent(status: Int, publishOk: Boolean): Boolean {
        return !publishOk && status == ActionGroupOrderStatus.ERROR
    }
}
