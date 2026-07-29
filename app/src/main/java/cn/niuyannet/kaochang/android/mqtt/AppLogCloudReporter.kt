package cn.niuyannet.kaochang.android.mqtt

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 将本地关键 App 日志实时上报到云端设备日志表。
 *
 * MQTT 不在线或发布队列拒绝时，W/E 会进入有界本地补传队列。
 */
object AppLogCloudReporter {
    private const val TAG = "AppLogCloudReporter"
    private const val TOPIC_BY_DEVICE = "/cl/devices/{device_id}/service"
    private const val DRAIN_BATCH_SIZE = 20
    private const val DRAIN_RETRY_DELAY_MS = 1_000L
    private val outbox by lazy { CriticalLogOutbox(CriticalLogPreferenceStorage) }
    private val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainInProgress = AtomicBoolean(false)
    private val CLOUD_NOISE_KEYWORDS = listOf(
        "设备快照投影未变化，跳过HTTP同步",
        "【动作组通信】启动前读取",
        "【动作组通信】下发完成",
        "【动作组通信】动作结束，动作=",
        "【取肠语音】 已开始播放取肠提醒",
        "【取肠语音】 取肠提醒播放完成",
        "【取肠语音】 播放资源已释放",
        "【应用更新】开始检查应用版本",
        "【应用更新】已是最新版本",
        "【订单流转】订单页重新进入前台",
        "【管理员操作】进入管理界面",
        "【管理员操作】管理员无操作超时"
    )
    private val CLOUD_KEEP_KEYWORDS = listOf(
        "【订单】",
        "【订单流转】",
        "【出餐】",
        "【动作组】",
        "【动作组故障锁存】",
        "【补肠】",
        "【搬盘】",
        "【失败原因】",
        "【状态切换】",
        "【云端同步】",
        "【履约完结】",
        "【维护操作】",
        "【管理员操作】",
        "【丢弃流程】",
        "【并发切换】",
        "【烤制算法】",
        "【自清洁】",
        "【故障停热】",
        "【应用更新】",
        "【补肠安全】",
        "动作组恢复失败",
        "Modbus 重连"
    )

    fun report(level: String, tag: String, content: String, reportedAt: Long = System.currentTimeMillis()): Boolean {
        if (!shouldReportToCloud(level, content)) {
            return false
        }
        val published = publishEntry(level, tag, content, reportedAt)
        if (!published) {
            val saved = persistCriticalIfNeeded(level, tag, content, reportedAt)
            if (saved && VMMqttHelper.mqttClient?.isConnected == true) {
                onMqttConnected()
            }
        }
        return published
    }

    fun onMqttConnected() {
        if (!drainInProgress.compareAndSet(false, true)) return
        drainScope.launch {
            try {
                while (VMMqttHelper.mqttClient?.isConnected == true) {
                    val batch = outbox.peekBatch(System.currentTimeMillis(), DRAIN_BATCH_SIZE)
                    if (batch.isEmpty()) return@launch
                    for (entry in batch) {
                        while (VMMqttHelper.mqttClient?.isConnected == true) {
                            if (publishEntry(entry.level, entry.tag, entry.content, entry.reportedAt)) {
                                outbox.remove(entry.id, System.currentTimeMillis())
                                break
                            }
                            delay(DRAIN_RETRY_DELAY_MS)
                        }
                        if (VMMqttHelper.mqttClient?.isConnected != true) return@launch
                    }
                    yield()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Critical log outbox drain failed: ${e.message}")
                if (VMMqttHelper.mqttClient?.isConnected == true) {
                    delay(DRAIN_RETRY_DELAY_MS)
                }
            } finally {
                drainInProgress.set(false)
                if (VMMqttHelper.mqttClient?.isConnected == true &&
                    outbox.peekBatch(System.currentTimeMillis(), 1).isNotEmpty()
                ) {
                    onMqttConnected()
                }
            }
        }
    }

    fun buildPayloadForTest(level: String, tag: String, content: String, reportedAt: Long): JSONObject {
        return buildPayload(level, tag, content, reportedAt)
    }

    fun shouldReportToCloudForTest(level: String, content: String): Boolean = shouldReportToCloud(level, content)

    fun cloudPriorityForTest(level: String, content: String): MqttPublishPriority = cloudPriority(level, content)

    fun cloudCoalesceKeyForTest(level: String, content: String): String? = cloudCoalesceKey(level, content)

    private fun publishEntry(level: String, tag: String, content: String, reportedAt: Long): Boolean {
        if (VMMqttHelper.mqttClient?.isConnected != true) return false
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
                priority = cloudPriority(level, content),
                coalesceKey = cloudCoalesceKey(level, content),
                logLifecycle = false
            )
        } catch (e: Throwable) {
            Log.w(TAG, "App log cloud report failed: ${e.message}")
            false
        }
    }

    private fun persistCriticalIfNeeded(
        level: String,
        tag: String,
        content: String,
        reportedAt: Long
    ): Boolean {
        val normalizedLevel = level.trim().uppercase()
        if (normalizedLevel != "W" && normalizedLevel != "E") return false
        val nowMs = System.currentTimeMillis()
        return try {
            val saved = outbox.enqueue(
                CriticalLogEntry(
                    id = UUID.randomUUID().toString(),
                    level = normalizedLevel,
                    tag = tag.ifBlank { "KaoChang" },
                    content = content,
                    reportedAt = reportedAt,
                    enqueuedAt = nowMs
                ),
                nowMs
            )
            if (!saved) {
                Log.w(TAG, "Critical log exceeds bounded outbox capacity")
            }
            saved
        } catch (e: Throwable) {
            Log.w(TAG, "Critical log outbox persist failed: ${e.message}")
            false
        }
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

    private fun shouldReportToCloud(level: String, content: String): Boolean {
        val normalizedLevel = level.trim().uppercase()
        if (normalizedLevel == "W" || normalizedLevel == "E") {
            return true
        }
        if (isExplicitCloudNoise(content)) {
            return false
        }
        if (isCloudCriticalContent(content)) {
            return true
        }
        if (CLOUD_NOISE_KEYWORDS.any { content.contains(it) }) {
            return false
        }
        return CLOUD_KEEP_KEYWORDS.any { content.contains(it) }
    }

    private fun isExplicitCloudNoise(content: String): Boolean {
        return content.contains("【管理员操作】管理员无操作超时")
    }

    private fun isCloudCriticalContent(content: String): Boolean {
        return content.contains("结果=失败") ||
            content.contains("失败") ||
            content.contains("错误") ||
            content.contains("异常") ||
            content.contains("超时") ||
            content.contains("维护") ||
            content.contains("故障") ||
            content.contains("未应用设备状态") ||
            content.contains("HTTP上传失败") ||
            content.contains("仍未收到")
    }

    private fun cloudPriority(level: String, content: String): MqttPublishPriority {
        val normalizedLevel = level.trim().uppercase()
        if (normalizedLevel == "W" || normalizedLevel == "E") {
            return MqttPublishPriority.HIGH
        }
        if (content.contains("故障") || content.contains("失败") || content.contains("维护")) {
            return MqttPublishPriority.HIGH
        }
        return MqttPublishPriority.LOW
    }

    private fun cloudCoalesceKey(level: String, content: String): String? {
        val normalizedLevel = level.trim().uppercase()
        if (normalizedLevel == "W" || normalizedLevel == "E") {
            return null
        }
        if (content.contains("故障") || content.contains("失败") || content.contains("维护")) {
            return null
        }
        return "app_log"
    }
}
