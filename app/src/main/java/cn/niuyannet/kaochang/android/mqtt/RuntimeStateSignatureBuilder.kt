package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer
import com.alibaba.fastjson.JSON

internal object RuntimeStateSignatureBuilder {
    private const val KEEP_WARM_EXPIRED_ALERT_CODE = "KEEP_WARM_EXPIRED"

    fun build(
        config: AppConfigBean,
        lowerPlatformSnapshot: LowerPlatformEpisodeReducer.StateSnapshot =
            LowerPlatformEpisodeReducer.snapshot(config)
    ): String {
        val alertCode = reportableFoodSafetyAlertCode(config)
        val alertReason = reportableFoodSafetyAlertReason(config)
        val lowerPlatform = lowerPlatformSnapshot
        return buildString {
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
            append(lowerPlatform.status).append('|')
            append(lowerPlatform.isolationActionName).append('|')
            append(lowerPlatform.generation).append('|')
            append(lowerPlatform.aggregateVersion).append('|')
            append(JSON.toJSONString(lowerPlatform.episodes)).append('|')
            append(lowerPlatform.supplementEnabled).append('|')
            append(lowerPlatform.orderBlockReason).append('|')
            append(alertCode).append('|')
            append(alertReason).append('|')
            append(config.pendingDiscardPositions.toSortedSet().joinToString(",")).append('|')
            append(SourceBoxPositionPolicy.POLICY_VERSION).append('|')
            append(SourceBoxPositionPolicy.validRangePayloadText()).append('|')
            append(SourceBoxPositionPolicy.disabledBoxes().joinToString(","))
        }
    }

    fun reportableFoodSafetyAlertCode(config: AppConfigBean): String =
        config.foodSafetyAlertCode
            ?.takeUnless { it == KEEP_WARM_EXPIRED_ALERT_CODE }
            .orEmpty()

    fun reportableFoodSafetyAlertReason(config: AppConfigBean): String =
        config.foodSafetyAlertReason
            ?.takeIf { reportableFoodSafetyAlertCode(config).isNotBlank() }
            .orEmpty()
}
