package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import com.alibaba.fastjson.JSONObject

object ActionGroupTelemetryPayloadBuilder {
    fun build(deviceCode: String, telemetry: ActionGroupTelemetry): JSONObject {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_ACTION_GROUP_TELEMETRY
        payload["deviceCode"] = deviceCode
        payload["eventId"] = telemetry.eventId
        payload["eventPhase"] = telemetry.eventPhase
        payload["reportedAt"] = telemetry.reportedAt
        payload["actionProtocolVersion"] = telemetry.actionProtocolVersion
        payload["sessionId"] = telemetry.sessionId
        payload["actionName"] = telemetry.actionName
        payload["registerAddress"] = telemetry.registerAddress
        payload["resultRegister"] = telemetry.resultRegister
        payload["infoValue"] = telemetry.infoValue
        payload["commandValue"] = telemetry.commandValue
        payload["resultCode"] = telemetry.resultCode
        payload["errorCode"] = telemetry.errorCode
        payload["errorMessage"] = telemetry.errorMessage
        payload["boxNo"] = telemetry.boxNo
        payload["panNo"] = telemetry.panNo
        payload["orderId"] = telemetry.orderId
        payload["resourceLocks"] = telemetry.resourceLocks
        payload["physicalCheckpoint"] = telemetry.physicalCheckpoint
        payload["physicalLocation"] = telemetry.physicalLocation
        payload["elapsedMs"] = telemetry.elapsedMs
        return payload
    }
}
