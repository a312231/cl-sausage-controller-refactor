package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighToLowOrderHandoffTest {

    @Test
    fun closeout_shouldCheckOrderBeforeEachPanAndRecomputePhysicalPositions() {
        val source = listOf(
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"),
            File("../app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        ).first { it.isFile }.readText()
        val start = source.indexOf("private suspend fun finishHighToLowCloseout")
        val end = source.indexOf("private fun highToLowCloseoutCompleteText", start)
        val block = source.substring(start, end)

        assertTrue(block.contains("while (true)"))
        assertTrue(block.contains("if (SupplementPauseController.shouldPauseForOrder())"))
        assertTrue(block.contains("KaoPanHelper.getKaoPanList()"))
        assertTrue(block.indexOf("shouldPauseForOrder()") < block.indexOf("moveKaoPanToKaoPan("))
        assertTrue(block.contains("if (!moved) break"))
    }
}
