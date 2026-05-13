package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean

internal object RuntimeStateSignatureBuilder {
    fun build(config: AppConfigBean): String {
        return buildString {
            append(config.status).append('|')
            append(config.onlineStatus).append('|')
            append(config.supplyStatus).append('|')
            append(config.inspectionMode).append('|')
            append(if (config.isEnable == 1) 1 else 0).append('|')
            append(config.modeType).append('|')
            append(config.transitionMode).append('|')
            append(config.highConcurrencyResidualDrain).append('|')
            append(config.moveStatus).append('|')
            append(config.errorStatus).append('|')
            append(config.errorCode ?: "").append('|')
            append(config.restStatusSource ?: "").append('|')
            append(config.foodSafetyAlertCode ?: "").append('|')
            append(config.foodSafetyAlertReason ?: "").append('|')
            append(config.pendingDiscardPositions.toSortedSet().joinToString(","))
        }
    }
}
