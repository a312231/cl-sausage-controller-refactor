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
            failureBranch.contains("DataManagementAPI.uploadActionGroupTelemetry(payload)")
        )
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
