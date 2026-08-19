package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResetSelfCheckDisplayTest {
    @Test
    fun businessSelfCheck_shouldKeepRawReadsAndExplainResetValues() {
        val source = File("src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt").readText()

        assertTrue(source.contains("readHoldingRegisters(201, 1)"))
        assertTrue(source.contains("复位过程中读数仅供诊断，不代表机械已经回到初始位置"))
    }
}
