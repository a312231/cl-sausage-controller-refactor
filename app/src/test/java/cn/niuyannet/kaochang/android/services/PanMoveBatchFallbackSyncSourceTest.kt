package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PanMoveBatchFallbackSyncSourceTest {

    @Test
    fun highHeatReturn_shouldKeepBatchUploadOnlyAsNoSuccessfulMoveFallback() {
        val source = sourceText("HighCurrentTrafficMode.kt")

        assertTrue(
            "25~33 完整批次回搬已有单根成功同步后，不应再用循环前快照重复批量上传",
            source.contains("if (movedTargetPositions.isEmpty()) {\n                KaoPanCloudSyncReporter.uploadIfSellableStateChanged(\n                    beforeSellable,\n                    KaoPanHelper.getKaoPanList(),\n                    \"高并发25~33补热区烤熟搬回1~21兜底同步\"")
        )
        assertTrue(
            "25~33 残缺批次回搬已有单根成功同步后，不应再用循环前快照重复批量上传",
            source.contains("if (movedTargetPositions.isEmpty()) {\n            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(\n                beforeSellable,\n                KaoPanHelper.getKaoPanList(),\n                \"高并发25~33残缺批次烤熟搬回1~21兜底同步\"")
        )
        assertTrue(
            "低转高旧补热区回搬应使用 movedAny 收窄批量兜底同步",
            source.contains("if (!movedAny) {\n            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(\n                beforeSellable,\n                KaoPanHelper.getKaoPanList(),\n                \"低转高过渡${'$'}{groupName}旧补热区烤熟搬回1~9兜底同步\"")
        )
        assertTrue(
            "高转低残留收拢应使用 movedAny 收窄批量兜底同步",
            source.contains("if (!movedAny) {\n                KaoPanCloudSyncReporter.uploadIfSellableStateChanged(\n                    beforeSellable,\n                    KaoPanHelper.getKaoPanList(),\n                    \"高转低/残留收拢10~21剩余可售肠回1~9兜底同步\"")
        )
    }

    @Test
    fun lowHeatReturn_shouldKeepBatchUploadOnlyAsNoSuccessfulMoveFallback() {
        val source = sourceText("LowCurrentTrafficMode.kt")

        assertTrue(
            "13~18 补热区回搬已有单根成功同步后，不应再用循环前快照重复批量上传",
            source.contains("if (!movedAny) {\n            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(\n                beforeSellable,\n                KaoPanHelper.getKaoPanList(),\n                \"低并发补热区残缺批次烤熟搬回1~9兜底同步\"")
        )
    }

    private fun sourceText(fileName: String): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/$fileName"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/$fileName")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
