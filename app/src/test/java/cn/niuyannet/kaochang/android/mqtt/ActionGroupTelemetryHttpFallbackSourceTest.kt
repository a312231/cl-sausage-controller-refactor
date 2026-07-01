package cn.niuyannet.kaochang.android.mqtt

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupTelemetryHttpFallbackSourceTest {

    @Test
    fun publishActionGroupTelemetry_shouldTriggerHttpFallbackWhenMqttQueueRejects() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val body = extractFunctionBody(source, "fun publishActionGroupTelemetry")
        val failureBranch = body.substringAfter("} else {", missingDelimiterValue = "")

        assertTrue("publishActionGroupTelemetry 必须保留 MQTT 入队结果判断", body.contains("val ok = VMMqttHelper.publishService"))
        assertTrue(
            "action_group_telemetry MQTT 入队失败时必须调用 HTTP 兜底，避免动作轨迹只停留在本地日志",
            failureBranch.contains("triggerActionGroupTelemetryHttpFallback(snapshot)")
        )
        assertTrue(
            "动作遥测 HTTP 兜底路径最终必须调用 DataManagementAPI.uploadActionGroupTelemetry",
            source.contains("DataManagementAPI.uploadActionGroupTelemetry(pending.payload)")
        )
    }

    @Test
    fun publishActionGroupTelemetry_shouldKeepPendingUntilBackendAckOrHttpFallback() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val body = extractFunctionBody(source, "fun publishActionGroupTelemetry")

        assertTrue("action_group_telemetry 必须有独立 ACK 超时时间", source.contains("ACTION_GROUP_TELEMETRY_ACK_TIMEOUT_MS"))
        assertTrue("action_group_telemetry 必须有 pending ack map", source.contains("pendingActionGroupTelemetryAcks"))
        assertTrue("MQTT 入队成功后必须登记 pending，不能把入队成功当作云端已落库", body.contains("pendingActionGroupTelemetryAcks[eventId]"))
        assertTrue("MQTT 入队成功后必须安排 ACK 超时检查", body.contains("scheduleActionGroupTelemetryAckTimeout(snapshot)"))
        assertTrue("ACK 超时后必须 HTTP 兜底补发", source.contains("triggerActionGroupTelemetryHttpFallback(pending)"))
        assertTrue("HTTP 失败必须进入有上限的内存重试队列", source.contains("enqueueActionGroupTelemetryRetry(pending)"))
    }

    @Test
    fun actionGroupTelemetryAck_shouldRemovePendingEvent() {
        val protocol = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/MqttProtocol.kt")
        val sender = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val receiver = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/VMMqttHelper.kt")
        val consumeBody = extractFunctionBody(receiver, "private fun consumeActionGroupTelemetryAckIfNeeded")

        assertTrue("协议必须声明 action_group_telemetry_ack", protocol.contains("TYPE_ACTION_GROUP_TELEMETRY_ACK = \"action_group_telemetry_ack\""))
        assertTrue("SendServerHelper 必须提供动作遥测 ACK 标记入口", sender.contains("fun markActionGroupTelemetryAcked(eventId: String?)"))
        assertTrue("SendServerHelper 必须提供 rejected ACK 兜底入口", sender.contains("fun handleRejectedActionGroupTelemetryAck(eventId: String?, reason: String)"))
        assertTrue("VMMqttHelper 必须消费动作遥测 ACK", receiver.contains("consumeActionGroupTelemetryAckIfNeeded"))
        assertTrue("成功/重复/已持久化 ACK 才能移除 pending", consumeBody.contains("if (accepted || duplicate || persisted)"))
        assertTrue("成功 ACK 必须通过 SendServerHelper 移除 pending", consumeBody.contains("SendServerHelper.markActionGroupTelemetryAcked(eventId)"))
        assertTrue("rejected ACK 必须保留 pending 并触发 HTTP 兜底", consumeBody.contains("SendServerHelper.handleRejectedActionGroupTelemetryAck(eventId, reason)"))
    }

    @Test
    fun dataManagementApi_shouldExposeActionGroupTelemetryUploadEntry() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")

        assertTrue(
            "DataManagementAPI 必须暴露动作组遥测 HTTP 兜底入口，供 MQTT 入队失败路径复用",
            source.contains("fun uploadActionGroupTelemetry(payload: JSONObject") &&
                source.contains("NetApi.uploadActionGroupTelemetry(payload, callback)")
        )
    }

    @Test
    fun netApi_shouldPostActionGroupTelemetryToBackendFallbackEndpoint() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/NetApi.kt")
        val body = extractFunctionBody(source, "fun uploadActionGroupTelemetry")

        assertTrue("NetApi 必须声明后端动作遥测 HTTP 兜底地址", source.contains("actionTelemetry_url = \"\$API_URL_START/actionTelemetry\""))
        assertTrue("动作遥测 HTTP 兜底必须 POST 到 /actionTelemetry", body.contains("OkGo.post<String>(actionTelemetry_url)"))
        assertTrue("动作遥测 HTTP 兜底必须上传原始 action_group_telemetry JSON", body.contains(".upJson(payload.toJSONString())"))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) {
            return direct.readText().replace("\r\n", "\n")
        }
        val fromParent = File("..", path)
        if (fromParent.exists()) {
            return fromParent.readText().replace("\r\n", "\n")
        }
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("找不到函数：$functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$functionSignature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }
        throw AssertionError("函数体未闭合：$functionSignature")
    }
}
