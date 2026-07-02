package cn.niuyannet.kaochang.android.mqtt

object PanPhysicalStateDeltaAckPolicy {
    enum class Decision {
        VerifiedAccepted,
        UnverifiedAccepted,
        StaleRejected,
        Rejected
    }

    fun classify(
        accepted: Boolean,
        duplicate: Boolean,
        stale: Boolean,
        serverPhysicalStateHash: String?
    ): Decision {
        if (accepted || duplicate) {
            return if (serverPhysicalStateHash.isNullOrBlank()) {
                Decision.UnverifiedAccepted
            } else {
                Decision.VerifiedAccepted
            }
        }
        if (stale) {
            return Decision.StaleRejected
        }
        return Decision.Rejected
    }
}
