package cn.niuyannet.kaochang.android.mqtt

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

/**
 * 将本地关键 App 日志实时上报到云端设备日志表。
 *
 * MQTT 不在线或发布失败时直接丢弃云端副本，本地 .log 文件仍由 LogUtils 保留。
 */
object AppLogCloudReporter {
    private const val TAG = "AppLogCloudReporter"
    private const val TOPIC_BY_DEVICE = "/cl/devices/{device_id}/service"

    fun report(level: String, tag: String, content: String, reportedAt: Long = System.currentTimeMillis()): Boolean {
        val client = VMMqttHelper.mqttClient
        if (client?.isConnected != true) {
            return false
        }
        return try {
            val payload = buildPayload(level, tag, content, reportedAt)
            val topicMessage = TopicMessage().apply {
                action = MqttProtocol.ACTION_DEVICE_LOG
                this.content = JSON.toJSONString(payload)
            }
            VMMqttHelper.publish(
                topic = AppConfig.getTopicDeviceIdUrl(TOPIC_BY_DEVICE),
                msg = JSON.toJSONString(topicMessage),
                qos = 1,
                retained = false,
                priority = MqttPublishPriority.LOW,
                coalesceKey = "app_log",
                logLifecycle = false
            )
        } catch (e: Throwable) {
            Log.w(TAG, "App log cloud report dropped: ${e.message}")
            false
        }
    }

    fun buildPayloadForTest(level: String, tag: String, content: String, reportedAt: Long): JSONObject {
        return buildPayload(level, tag, content, reportedAt)
    }

    private fun buildPayload(level: String, tag: String, content: String, reportedAt: Long): JSONObject {
        val normalizedLevel = level.ifBlank { "D" }
        return JSONObject().apply {
            put("messageType", MqttProtocol.TYPE_APP_LOG_ENTRY)
            put("title", "AppLog-$normalizedLevel")
            put("content", content)
            put("level", normalizedLevel)
            put("tag", tag.ifBlank { "KaoChang" })
            put("reportedAt", reportedAt)
        }
    }
}
