package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import com.alibaba.fastjson.JSONObject

internal object RuntimeStatePayloadBuilder {
    fun build(
        deviceCode: String,
        runtimeSeq: Long,
        config: AppConfigBean,
        source: String,
        reportedAt: Long
    ): JSONObject {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_STATE
        payload["deviceCode"] = deviceCode
        payload["runtimeSeq"] = runtimeSeq
        payload["onlineStatus"] = config.onlineStatus
        payload["supplyStatus"] = config.supplyStatus
        payload["inspectionMode"] = config.inspectionMode
        payload["isEnable"] = if (config.isEnable == 1) 1 else 0
        payload["modeType"] = config.modeType
        payload["transitionMode"] = config.transitionMode
        payload["highConcurrencyResidualDrain"] = config.highConcurrencyResidualDrain
        payload["moveStatus"] = config.moveStatus
        payload["errorStatus"] = config.errorStatus
        payload["lowerPlatformStatus"] = config.lowerPlatformStatus ?: AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        payload["supplementEnabled"] = config.supplementEnabled
        payload["orderBlockReason"] = config.orderBlockReason ?: ""
        payload["sourceBoxPolicyVersion"] = SourceBoxPositionPolicy.POLICY_VERSION
        payload["validSourceBoxRanges"] = SourceBoxPositionPolicy.validRangePayloadText()
        payload["disabledSourceBoxes"] = SourceBoxPositionPolicy.disabledBoxes()
        payload["foodSafetyAlertCode"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertCode(config)
        payload["foodSafetyAlertReason"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertReason(config)
        payload["pendingDiscardPositions"] = config.pendingDiscardPositions
        if (config.errorStatus != 0) {
            config.errorCode?.takeIf { it.isNotBlank() }?.let { payload["errorCode"] = it }
            config.errorCategory?.takeIf { it.isNotBlank() }?.let { payload["errorCategory"] = it }
            config.errorReason?.takeIf { it.isNotBlank() }?.let { payload["errorReason"] = it }
        }
        payload["restStatusSource"] = config.restStatusSource
        payload["source"] = source
        payload["reportedAt"] = reportedAt
        return payload
    }
}
