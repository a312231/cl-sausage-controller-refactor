package cn.niuyannet.kaochang.android.fragments

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceLowerPlatformUiSourceTest {

    @Test
    fun maintenanceLayout_shouldExposeLowerPlatformRuntimeStatusText() {
        val layout = sourceFile("app/src/main/res/layout/fragment_maintenance.xml")

        assertTrue("维护页必须有下平台状态展示控件", layout.contains("@+id/tv_lower_platform_status"))
        assertTrue("维护页下平台状态默认文案应明确显示正常", layout.contains("下平台：正常"))
        assertTrue("下平台状态应独占横向宽度，避免挤掉恢复按钮", layout.contains("android:layout_width=\"match_parent\""))
        assertTrue("下平台长文案应单行省略，避免维护页顶栏溢出", layout.contains("android:ellipsize=\"end\""))
    }

    @Test
    fun maintenanceFragment_shouldRefreshLowerPlatformStatusAndButtonText() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val refreshBody = extractFunctionBody(source, "private fun startRefreshTimer()")
        val updateBody = extractFunctionBody(source, "private fun updateLowerPlatformUi()")

        assertTrue("维护页定时刷新必须更新下平台状态 UI", refreshBody.contains("updateLowerPlatformUi()"))
        assertTrue("下平台隔离必须展示半故障/暂停补肠", updateBody.contains("下平台半故障"))
        assertTrue("下平台隔离必须展示暂停自动补肠", updateBody.contains("暂停自动补肠"))
        assertTrue("供给降级必须展示检查烤肠箱", updateBody.contains("供给降级"))
        assertTrue("隔离恢复按钮文案必须明确恢复补肠", updateBody.contains("确认下平台安全并恢复补肠"))
        assertTrue("有续补批次时按钮文案必须保留续补安全语义", updateBody.contains("确认续补安全并恢复补肠"))
    }

    @Test
    fun lowerPlatformRecoveryButton_shouldNotUseWholeDeviceRecoveryPath() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnConfirmSupplementSafety.setOnClickListener")

        assertTrue(
            "下平台恢复必须走 LowerPlatformRuntimeStateReporter，保持 MQTT/HTTP 状态一致",
            clickBody.contains("LowerPlatformRuntimeStateReporter.confirmLowerPlatformIsolationAfterManualAction")
        )
        assertFalse(
            "下平台恢复按钮不能复用整机恢复，避免误改 onlineStatus/errorStatus/isEnable",
            clickBody.contains("applyManualRecoveredState")
        )
    }

    @Test
    fun maintenanceLayout_shouldExposeOrphanReturnButton() {
        val layout = sourceFile("app/src/main/res/layout/fragment_maintenance.xml")
        assertTrue(layout.contains("@+id/btn_rebind_orphan_returns"))
    }

    @Test
    fun maintenanceFragment_shouldExplainUnboundHeatSources() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val updateBody = extractFunctionBody(source, "private fun updateLowerPlatformUi()")
        assertTrue(updateBody.contains("补热区存在未绑定烤肠"))
        assertTrue(updateBody.contains("需要按当前空位建立回搬目标"))
    }

    @Test
    fun orphanReturnClick_shouldRequireExplicitConfirmationAndCallRebindOnly() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
        val clickBody = extractFunctionBody(source, "binding.btnRebindOrphanReturns.setOnClickListener")
        assertTrue(clickBody.contains("setPositiveButton"))
        assertTrue(clickBody.contains("previewOrphanSupplementReturns"))
        assertTrue(clickBody.contains("rebindOrphanSupplementReturnsToCurrentEmptyFront"))
        assertTrue(clickBody.contains("expectedBindings"))
        assertTrue(clickBody.contains("confirmOrphanSupplementSourceTaste"))
        assertTrue(clickBody.contains("activeSupplementBatchTargetPositions"))
        assertTrue(clickBody.contains("PAN_PERSIST_FAILED"))
        assertFalse(clickBody.contains("moveKaoPanToKaoPan"))
    }

    @Test
    fun orphanReturnCommit_shouldPersistFullPanSnapshot() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val commitBody = extractFunctionBody(
            source,
            "internal suspend fun rebindOrphanSupplementReturnsToCurrentEmptyFront"
        )

        assertTrue(commitBody.contains("frontPans = pans,"))
        assertFalse(commitBody.contains("frontPans = pans.filter"))
    }

    @Test
    fun orphanReturnPreview_shouldNotNormalizeProductionPanState() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val previewBody = extractFunctionBody(source, "internal fun previewOrphanSupplementReturns()")
        val sourceSelectionBody = extractFunctionBody(source, "private fun orphanSupplementSourcePans")

        assertTrue(previewBody.contains("orphanSupplementSourcePans"))
        assertFalse(sourceSelectionBody.contains("normalizeLegacySellableKeepWarmSources"))
        assertTrue(sourceSelectionBody.contains("isRecognizedSource"))
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
