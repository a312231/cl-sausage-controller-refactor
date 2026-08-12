package cn.niuyannet.kaochang.android.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalSnapshotSellableProjectionSourceTest {

    @Test
    fun uploadDataToServer_shouldCapturePhysicalSequenceAndSellableProjectionForSnapshot() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val body = extractFunctionBody(source, "fun uploadDataToServer")

        assertTrue(body.contains("PanPhysicalStateDeltaSequenceStore.currentSequence()"))
        assertTrue(body.contains("SellablePanPhysicalStateProjection.sellablePositions"))
        assertTrue(body.contains("SellablePanPhysicalStateProjection.rulesSignature"))
        assertTrue(body.contains("physicalPanDeltaSeq（烤盘物理版本号）=${'$'}physicalPanDeltaSeq"))
        assertTrue(body.contains("physicalPanDeltaSeq = physicalPanDeltaSeq"))
        assertTrue(body.contains("sellablePositions = sellablePositions"))
        assertTrue(body.contains("sellableRulesSignature = sellableRulesSignature"))
    }

    @Test
    fun reportSnapshot_shouldPassPhysicalSequenceAndSellableProjectionThroughJson() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/NetApi.kt")
        val body = extractFunctionBody(source, "fun reportSnapshot")

        assertTrue(source.contains("physicalPanDeltaSeq: Long"))
        assertTrue(source.contains("sellablePositions: List<Int>"))
        assertTrue(source.contains("sellableRulesSignature: String"))
        assertTrue(body.contains("json[\"physicalPanDeltaSeq\"] = physicalPanDeltaSeq"))
        assertTrue(body.contains("json[\"sellablePositions\"] = sellablePositions"))
        assertTrue(body.contains("json[\"sellableRulesSignature\"] = sellableRulesSignature"))
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
