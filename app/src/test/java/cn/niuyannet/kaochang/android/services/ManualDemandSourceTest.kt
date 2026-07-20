package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDemandSourceTest {
    @Test
    fun manualPanRemoval_shouldRecordNonDemandInsteadOfClearingGate() {
        val source = sourceText("KaoChangOperate.kt")
        val recovery = functionBlock(source, "private fun clearRecoveredOutPan(", "private fun clearFaultTimeoutCancelledReturnReservations")
        val manual = functionBlock(source, "suspend fun takeSausage(kaoPan: KaoPan)", "suspend fun takeSausageResult(")

        listOf(recovery, manual).forEach { block ->
            assertTrue(block.contains("LowToHighDemandGate.recordNonDemandEmpty("))
            assertTrue(block.contains("manual_clear"))
            assertFalse(block.contains("LowToHighDemandGate.clearPosition("))
        }
    }

    @Test
    fun normalSale_shouldNotRecordNonDemand() {
        val source = sourceText("KaoChangOperate.kt")
        val sale = functionBlock(source, "suspend fun takeSausageResult(", "private suspend fun takeSausageByActionGroup(")

        assertFalse(sale.contains("LowToHighDemandGate.recordNonDemandEmpty("))
    }

    @Test
    fun batchReset_shouldRecordEveryClearedFrontPosition() {
        val source = sourceText("KaoChangAlgorithm.kt")
        val reset = functionBlock(source, "suspend fun resetKaoPan(", "fun stopServer()")

        assertTrue(reset.contains("filter { it in 1..21 }"))
        assertTrue(reset.contains("LowToHighDemandGate.recordNonDemandEmpty("))
        assertTrue(reset.contains("manual_clear"))
        assertFalse(reset.contains("LowToHighDemandGate.clearAll("))
    }

    @Test
    fun maintenanceClearAll_shouldRecordOnlyOccupiedFrontPansBeforeClearing() {
        val source = fragmentSourceText("MaintenanceFragment.kt")
        val handler = functionBlock(
            source,
            "binding.btnClearAll.setOnClickListener",
            "binding.btnCz.setOnClickListener"
        )
        val readPans = handler.indexOf("KaoPanHelper.getKaoPanList()")
        val occupiedFrontFilter = handler.indexOf(
            ".filter { it.positionSn in 1..21 && it.isHasSausage }",
            readPans
        )
        val record = handler.indexOf(
            "LowToHighDemandGate.recordNonDemandEmpty(positionSn, \"manual_clear\")",
            occupiedFrontFilter
        )
        val clear = handler.indexOf("KaoPanHelper.clearKaoPan()")

        assertTrue("清空前必须读取当前烤盘", readPans >= 0)
        assertTrue("空盘和非前区盘不得记录 manual_clear", occupiedFrontFilter > readPans)
        assertTrue("实际前区有肠盘必须逐个记录 manual_clear", record > occupiedFrontFilter)
        assertTrue("manual_clear 必须早于 clearKaoPan", clear > record)
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("缺少函数：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start).let { if (it >= 0) it else source.length }
        return source.substring(start, end)
    }

    private fun sourceText(name: String): String {
        val file = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/$name"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/$name")
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    private fun fragmentSourceText(name: String): String {
        val file = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/fragments/$name"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/fragments/$name")
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }
}
