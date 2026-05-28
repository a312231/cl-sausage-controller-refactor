package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean

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

    fun rejectFaultLatchedRestoreReason(
        snapshot: AppConfig.FaultRuntimeSnapshot,
        requestedOnlineStatus: Int?,
        requestedErrorStatus: Int?,
        requestedIsEnable: Int?
    ): String? {
        if (!snapshot.isNonModbusAutoRecoverableFault) {
            return null
        }
        val triesRestoreOnlineStatus = requestedOnlineStatus != null && requestedOnlineStatus != 3
        val triesClearFault = requestedErrorStatus == AppConfigBean.ERROR_STATUS_NORMAL
        val triesEnableAlgorithm = requestedIsEnable == 1
        return if (triesRestoreOnlineStatus || triesClearFault || triesEnableAlgorithm) {
            "fault_latched_requires_manual_recovery"
        } else {
            null
        }
    }
}
