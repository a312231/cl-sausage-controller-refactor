package cn.niuyannet.kaochang.android.services.scheduler

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerShadowWiringSourceTest {

    @Test
    fun purchase_shouldObserveAfterLegacySelectionBeforeNullBranchAndExecuteLegacyReference() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
        )
        val purchase = source.substring(
            source.indexOf("suspend fun purchase("),
            source.indexOf("suspend fun resetKaoPan(")
        )

        val legacySelection = purchase.indexOf("TrafficModeHelper.selectSellCandidate(")
        val shadowGate = purchase.indexOf("LegacyCandidateShadowGate.observeAndKeep(")
        val nullBranch = purchase.indexOf("if (targetKaoPan == null)")
        val mechanicalCall = purchase.indexOf("KaoChangOperate.takeSausageResult(targetKaoPan,")

        assertTrue(legacySelection >= 0)
        assertTrue(shadowGate > legacySelection)
        assertTrue(nullBranch > shadowGate)
        assertTrue(mechanicalCall > nullBranch)
        assertFalse(purchase.contains("takeSausageResult(shadow"))
        assertFalse(purchase.contains("firstOrNull { it.positionSn == shadow"))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path，cwd=${File(".").absolutePath}")
    }
}
