package cn.niuyannet.kaochang.android.mqtt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SellableRulesPhysicalDeltaPublishTest {

    @Test
    fun publishServiceUpdateStatus_shouldAttemptRuleOnlyPhysicalDeltaBeforeRuntimeState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/SendServerHelper.kt")
        val body = extractFunctionBody(source, "fun publishServiceUpdateStatus")
        val ruleDeltaIndex = body.indexOf("PanPhysicalStateDeltaReporter.reportRulesChanged")
        val runtimePayloadIndex = body.indexOf("RuntimeStatePayloadBuilder.build")
        val runtimePublishIndex = body.indexOf("VMMqttHelper.publishService")

        assertTrue("runtime reporting must delegate rule-only physical delta publishing", ruleDeltaIndex >= 0)
        assertTrue("rule-only physical delta must be attempted before runtime payload creation", ruleDeltaIndex < runtimePayloadIndex)
        assertTrue("rule-only physical delta must be attempted before runtime MQTT enqueue", ruleDeltaIndex < runtimePublishIndex)
        assertTrue("runtime reporting must use atomic sellable-rules dedupe", source.contains("SellableRulesPhysicalDeltaDeduper"))
    }

    @Test
    fun sellableRulesDedupe_shouldSkipUnchangedRuleAndRestoreAfterFailure() {
        val deduper = SellableRulesPhysicalDeltaDeduper()
        var calls = 0

        assertFalse(deduper.publishIfChanged("rules-A") {
            calls++
            false
        })
        assertTrue(deduper.publishIfChanged("rules-A") {
            calls++
            true
        })
        assertFalse(deduper.publishIfChanged("rules-A") {
            calls++
            true
        })
        assertEquals(2, calls)
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
        throw AssertionError("source file not found: $path")
    }

    private fun extractFunctionBody(source: String, functionSignature: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("function not found: $functionSignature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("function body not found: $functionSignature", bodyStart >= 0)
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
        throw AssertionError("unclosed function body: $functionSignature")
    }
}
