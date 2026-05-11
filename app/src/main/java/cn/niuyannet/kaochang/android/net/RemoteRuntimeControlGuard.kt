package cn.niuyannet.kaochang.android.net

object RemoteRuntimeControlGuard {
    fun rejectReason(
        controlSeq: Long,
        lastAppliedControlSeq: Long,
        incomingConfigVersion: Long?,
        localConfigVersion: Long
    ): String? {
        // Phase 3 引入超时重试后，同一 controlSeq 已应用时应重发 ACK，而不是简单拒收。
        if (controlSeq > 0 && lastAppliedControlSeq >= 0 && controlSeq <= lastAppliedControlSeq) {
            return "stale_control_seq"
        }
        if (incomingConfigVersion != null && incomingConfigVersion < localConfigVersion) {
            return "stale_version"
        }
        return null
    }
}
