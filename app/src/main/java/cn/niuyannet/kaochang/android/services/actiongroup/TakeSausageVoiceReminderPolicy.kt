package cn.niuyannet.kaochang.android.services.actiongroup

object TakeSausageVoiceReminderPolicy {
    fun shouldPlay(
        orderContext: ActionGroupOrderContext?,
        deliveryIndex: Int?
    ): Boolean {
        return orderContext?.isValid() == true && (deliveryIndex == null || deliveryIndex <= 1)
    }
}
