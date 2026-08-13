package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LowCurrentTrafficModePendingBatchSourceTest {
    @Test
    fun blockedPendingBatch_shouldContinueMatureAndDiscardCloseoutWithoutNewSupplement() {
        val source = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt")
        ).first { it.exists() }.readText().replace("\r\n", "\n")
        val start = source.indexOf("suspend fun lowTrafficMode(")
        val end = source.indexOf("private fun lowConcurrencyIntestinalSupplementation", start)
            .let { if (it >= 0) it else source.length }
        val block = source.substring(start, end)
        val pending = block.indexOf("val pendingBatchHandling")
        val resumedOnlyReturn = block.indexOf("completedMechanicalWorkThisRound", pending)
        val canStart = block.indexOf("blocksNewSupplement", pending)
        val mature = block.indexOf("checkLowHeatSausage", pending)
        val discard = block.indexOf("checkLowHeatDiscard()", pending)
        val switchToHigh = block.indexOf("switchLowToHigh(kaoPanList)", pending)

        assertTrue("只有真正续补后才能结束本轮", resumedOnlyReturn > pending)
        assertTrue("BLOCKED/WAITING 必须只关闭新补肠", canStart > resumedOnlyReturn)
        assertTrue("pending BLOCKED 后仍必须能到达成熟回搬", mature > canStart)
        assertTrue("pending BLOCKED 后仍必须能到达过期丢弃", discard > mature)
        assertTrue(
            "pending BLOCKED 时不能自动切换并发模式",
            block.lastIndexOf("!pendingBatchHandling.blocksNewSupplement", switchToHigh) in (pending + 1) until switchToHigh
        )
        assertFalse("低并发不能再对所有非 NONE 直接返回", block.contains("pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE"))
    }
}
