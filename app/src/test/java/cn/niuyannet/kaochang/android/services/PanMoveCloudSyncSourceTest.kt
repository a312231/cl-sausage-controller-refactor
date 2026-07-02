package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PanMoveCloudSyncSourceTest {

    @Test
    fun moveKaoPanToKaoPanInternal_shouldUploadAfterEachSuccessfulPhysicalMove() {
        val source = sourceText()
        val body = extractFunctionBody(source, "private suspend fun moveKaoPanToKaoPanInternal(")

        val successDecision = body.indexOf("PanMove203Decision.Success -> break")
        val beforeSnapshot = body.indexOf("val beforeMoveSellable")
        val writeTarget = body.indexOf("newKaoPan.isHasSausage = true")
        val clearSource = body.indexOf("kaoPan.isHasSausage = false")
        val completedLog = body.indexOf("\"【搬盘】搬移完成：来源烤盘=${'$'}")
        val upload = body.indexOf("KaoPanCloudSyncReporter.uploadIfSellableStateChanged(")

        assertTrue("搬盘核心必须保留 203=2 成功判定", successDecision >= 0)
        assertTrue(
            "搬盘核心只应在成功收口分支内触发一次单根同步，失败/超时分支不能上传",
            upload >= 0 && upload == body.lastIndexOf("KaoPanCloudSyncReporter.uploadIfSellableStateChanged(")
        )
        assertTrue("单根搬盘成功后必须先抓取搬盘前可售快照", beforeSnapshot > successDecision)
        assertTrue("搬盘前快照必须早于目标盘写入，避免活对象别名导致变化丢失", beforeSnapshot < writeTarget)
        assertTrue("目标盘写入必须早于源盘清空", writeTarget < clearSource)
        assertTrue("搬盘完成日志必须位于源/目标状态写入之后", completedLog > clearSource)
        assertTrue("每根搬盘完成后必须立即触发云端可售状态同步", upload > completedLog)
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val braceStart = source.indexOf('{', start)
        assertTrue("找不到函数体开始：$signature", braceStart >= 0)
        var depth = 0
        for (index in braceStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(braceStart, index + 1)
                    }
                }
            }
        }
        throw AssertionError("找不到函数体结束：$signature")
    }
}
