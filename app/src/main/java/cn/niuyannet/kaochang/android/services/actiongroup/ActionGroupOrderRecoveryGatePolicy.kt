package cn.niuyannet.kaochang.android.services.actiongroup

enum class ActionGroupOrderRecoveryDecision {
    FAST_PATH,
    FULL_RECOVERY
}

object ActionGroupOrderRecoveryGatePolicy {
    fun evaluate(
        modbusConnected: Boolean,
        recoveryVerified: Boolean,
        hasPendingSession: Boolean,
        resourceLocksIdle: Boolean
    ): ActionGroupOrderRecoveryDecision {
        return if (
            modbusConnected &&
            recoveryVerified &&
            !hasPendingSession &&
            resourceLocksIdle
        ) {
            ActionGroupOrderRecoveryDecision.FAST_PATH
        } else {
            ActionGroupOrderRecoveryDecision.FULL_RECOVERY
        }
    }
}
