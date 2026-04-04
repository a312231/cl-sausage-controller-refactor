package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage

/**
 * MQTT 业务回调封装。
 */
object VMMqttHelper : VMMqtt() {

    const val topic_by_server: String = "/cl/devices/{device_id}/get"

    private const val DISCONNECT_LOG_WINDOW_MS = 5_000L
    private var lastDisconnectLogAtMs = 0L
    private var lastDisconnectCause = ""

    private fun shouldLogDisconnect(causeText: String): Boolean {
        val now = System.currentTimeMillis()
        val shouldLog =
            causeText != lastDisconnectCause || now - lastDisconnectLogAtMs >= DISCONNECT_LOG_WINDOW_MS
        if (shouldLog) {
            lastDisconnectCause = causeText
            lastDisconnectLogAtMs = now
        }
        return shouldLog
    }

    /**
     * 设置订阅消息回调。
     */
    fun setTopicMessageCallBack(topicName: String, callback: (bean: TopicMessage) -> Unit) {
        mqttClient?.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                LogUtils.d("【MQTT消息】收到消息：topic=$topic, payload=${message.toString()}")
                if (topicName == topic) {
                    callback.invoke(JSON.parseObject(message.toString(), TopicMessage::class.java))
                }
            }

            override fun connectionLost(cause: Throwable?) {
                val causeText = cause?.toString() ?: "未知原因"
                if (shouldLogDisconnect(causeText)) {
                    LogUtils.w(
                        "【MQTT连接】连接断开：deviceId=${AppConfig.getDeviceId()}, topic=${AppConfig.getTopicDeviceIdUrl(topic_by_server)}, cause=$causeText"
                    )
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                LogUtils.d("【MQTT发布】消息投递完成")
            }
        })
    }
}
