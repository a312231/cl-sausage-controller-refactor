package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighCurrentTrafficModeSourceTest {

    @Test
    fun lowToHighTransition_shouldNotLetPendingHighHeatBatchBlockOldLowHeatReturn() {
        val source = sourceText()
        val functionStart = source.indexOf("private suspend fun lowTrafficModeTransition(")
        assertTrue("必须存在低转高过渡调度函数", functionStart >= 0)
        val functionEnd = source.indexOf("/**\n     * 低转高期间", functionStart)
            .let { if (it >= 0) it else source.length }
        val functionBlock = source.substring(functionStart, functionEnd)

        val pendingHandling = functionBlock.indexOf("handlePendingSupplementBatchIfAny(")
        val resumedOnlyReturn = functionBlock.indexOf("PendingSupplementBatchHandling.RESUMED", pendingHandling)
        val supplementBlockFlag = functionBlock.indexOf("pendingHighHeatBatchBlocksSupplement", pendingHandling)
        val oldLowHeatReturn = functionBlock.indexOf("planOldLowHeatReturnGroups(", pendingHandling)

        assertTrue("低转高入口必须先检查未完成 25~33 补肠批次", pendingHandling >= 0)
        assertTrue("只有实际续补 RESUMED 后才应提前 return", resumedOnlyReturn > pendingHandling)
        assertTrue("等待人工确认/blocked 只能阻止继续补 25~33，不能阻止 13~18 回搬判断", supplementBlockFlag > resumedOnlyReturn)
        assertTrue("13~18 旧补热区回搬判断必须位于 pending 批次检查之后并仍可执行", oldLowHeatReturn > supplementBlockFlag)
    }

    private fun sourceText(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
