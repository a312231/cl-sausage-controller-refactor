package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Out1SellableRecheckTest {

    @Test
    fun formalOut_shouldRecheckPendingAndPersistDispatchEvidenceBeforeRunner() {
        val source = sourceText()
        val start = source.indexOf("suspend fun takeSausageResult(")
        assertTrue("必须存在正式订单出餐入口", start >= 0)
        val end = source.indexOf("internal fun applySuccessfulTakePanState", start)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(start, end)

        assertTrue("out1 前必须重新读取当前烤盘", block.contains("val latestPan ="))
        assertTrue("out1 前必须排除待丢弃烤盘", block.contains("pendingDiscardPositionsOrEmpty()"))
        assertTrue("候选失效必须返回 NO_SELLABLE_STOCK", block.contains("TakeSausageResult.NO_SELLABLE_STOCK"))
        assertTrue("候选失效不能写入 out1 检查点", block.contains("if (!stillSellable)"))
        assertTrue("out1 命令写成功后才记录 DISPATCHED", block.contains("onActionCommandWritten"))
        assertTrue("动作组会话必须保存 out1 证据", block.contains("out1DispatchEvidence"))
        assertTrue("最终复核必须先于动作组执行", block.indexOf("if (!stillSellable)") < block.indexOf("OutActionGroupRunner"))
    }

    @Test
    fun actionGroupSession_shouldCarryThreeOut1EvidenceStates() {
        val source = sourceText("services/actiongroup/ResourceLocks.kt")
        assertTrue(source.contains("enum class Out1DispatchEvidence"))
        assertTrue(source.contains("PREPARED"))
        assertTrue(source.contains("NOT_DISPATCHED_CONFIRMED"))
        assertTrue(source.contains("DISPATCHED"))
        assertTrue(source.contains("val out1DispatchEvidence"))
    }

    private fun sourceText(relative: String = "services/KaoChangOperate.kt"): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/$relative"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/$relative")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
