package cn.niuyannet.kaochang.android.services.actiongroup

class SupplementDeliveryCommitCoordinator(
    private val transitionLock: Any,
    private val orderPending: () -> Boolean,
    private val commitDelivery: () -> Boolean
) {
    fun tryCommit(): Boolean = synchronized(transitionLock) {
        if (orderPending()) false else commitDelivery()
    }
}
