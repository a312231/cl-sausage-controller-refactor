package cn.niuyannet.kaochang.android.services

enum class PanMove203Decision {
    WaitingForStart,
    Running,
    Success,
    Failed,
    ReadFailed
}

object PanMove203Policy {
    const val pollDelayMs: Long = 500L
    const val waitOtherActionDelayMs: Long = 2_000L

    fun evaluate(
        code: Int,
        hasObservedRunning: Boolean,
        commandRegisterValue: Int? = null
    ): PanMove203Decision {
        return when {
            code == -1 -> PanMove203Decision.ReadFailed
            code == 0x0001 -> PanMove203Decision.Running
            code == 0x0002 && hasObservedRunning -> PanMove203Decision.Success
            code == 0x0002 && commandRegisterValue == 0x0000 -> PanMove203Decision.Success
            code == 0x0000 && hasObservedRunning -> PanMove203Decision.Failed
            code == 0x0000 || code == 0x0002 -> PanMove203Decision.WaitingForStart
            code in 0x00F8..0x00FB -> PanMove203Decision.Failed
            else -> PanMove203Decision.Failed
        }
    }
}
