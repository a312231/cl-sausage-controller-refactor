package cn.niuyannet.kaochang.android.services.actiongroup

object TakeSausageVoiceReminderPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun shouldPlay(
        orderContext: ActionGroupOrderContext?,
        deliveryIndex: Int?
    ): Boolean {
        return orderContext?.isValid() == true
    }
}
