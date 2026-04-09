package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object SendServerHelper {

    data class RuntimeControlAckPayload(
        val controlSeq: Long,
        val applied: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val isEnable: Int,
        val restStatusSource: String?
    )

    /**
     * 通过 MQTT 上报订单动作事件（action=1）。
     * `status（订单事件状态）` 的含义仍保持不变：
     * - 1 = 开始夹取
     * - 2 = 单次履约完成
     * - 5 = 单次履约失败
     */
    fun publishServiceUpdateOrder(msgObj: JSONObject): Boolean {
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_ORDER_EVENT
                content = JSON.toJSONString(msgObj)
            }
        )
    }

    /**
     * 通过 MQTT 上报设备运行时状态（action=2）。
     * 当前第一批收口的主字段：
     * - onlineStatus（设备营业状态）
     * - isEnable（烤肠算法开关）
     *
     * 仍保留必要运行态字段，便于服务端继续复用现有消费逻辑。
     */
    fun publishServiceUpdateStatus(): Boolean {
        val config = AppConfig.getAppConfig()
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["status"] = config.status
        payload["onlineStatus"] = config.onlineStatus
        payload["isEnable"] = AppConfig.getAlgorithmEnableFlag()
        payload["modeType"] = config.modeType
        payload["transitionMode"] = config.transitionMode
        payload["moveStatus"] = config.moveStatus
        payload["errorStatus"] = config.errorStatus
        payload["restStatusSource"] = config.restStatusSource
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    /**
     * 通过 MQTT 上报首页展示相关状态（action=2）。
     * 当前只收口：
     * - available（二维码页预计时间）
     * - availableCountdownLatched（二维码页预计时间锁存标记）
     */
    fun publishAvailableState(source: String): Boolean {
        val config = AppConfig.getAppConfig()
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_AVAILABLE_STATE
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["available"] = config.available
        payload["availableCountdownLatched"] = config.availableCountdownLatched
        payload["onlineStatus"] = config.onlineStatus
        payload["status"] = config.status
        payload["source"] = source
        payload["reportedAt"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }

    /**
     * 回执远端运行时控制（action=2）。
     * ack 仅确认“是否应用了这条 controlSeq”，不承担设备状态同步职责。
     */
    fun publishRuntimeControlAck(ack: RuntimeControlAckPayload): Boolean {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_RUNTIME_CONTROL_ACK
        payload["deviceCode"] = AppConfig.getDeviceId()
        payload["controlSeq"] = ack.controlSeq
        payload["applied"] = ack.applied
        payload["reason"] = ack.reason
        payload["onlineStatus"] = ack.onlineStatus
        payload["isEnable"] = ack.isEnable
        payload["restStatusSource"] = ack.restStatusSource
        payload["ackTs"] = System.currentTimeMillis()
        return VMMqttHelper.publishService(
            TopicMessage().apply {
                action = MqttProtocol.ACTION_SERVICE_STATE
                content = JSON.toJSONString(payload)
            }
        )
    }
}
