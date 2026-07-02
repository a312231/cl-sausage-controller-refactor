package cn.niuyannet.kaochang.android.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PanPhysicalStateBaselineSourceTest {

    @Test
    fun uploadDataToServer_shouldComputeSnapshotHashAndRecordConfirmedPhysicalBaseline() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val body = extractFunctionBody(source, "fun uploadDataToServer")

        assertTrue(
            "直接上传 HTTP 快照时也必须补齐 snapshotHash，避免后续 physical delta 没有可校验 base hash",
            body.contains("val effectiveSnapshotHash = snapshotHash ?: SnapshotProjectionHash.hash(kaoPanList, kaoPanBoxList)")
        )
        assertTrue(
            "HTTP 快照请求必须使用 effectiveSnapshotHash，而不是继续传入可能为空的 snapshotHash",
            body.contains("snapshotHash = effectiveSnapshotHash")
        )
        assertTrue(
            "云端确认 HTTP 快照后必须记录 physical delta 可用的已确认基线",
            body.contains("PanPhysicalStateBaselineStore.recordConfirmedSnapshot(sequence, effectiveSnapshotHash, reportResult.panDeltaSeq)")
        )
        assertTrue(
            "云端确认 HTTP 快照后必须用服务端返回 panDeltaSeq 校准本地 physical seq",
            body.contains("PanPhysicalStateDeltaSequenceStore.resetToServerSequence(reportResult.panDeltaSeq)")
        )
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
