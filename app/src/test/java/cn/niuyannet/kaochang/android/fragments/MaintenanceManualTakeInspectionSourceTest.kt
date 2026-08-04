package cn.niuyannet.kaochang.android.fragments

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceManualTakeInspectionSourceTest {

    private val source = fragmentSourceText("MaintenanceFragment.kt")
    private val operateSource = serviceSourceText("KaoChangOperate.kt")

    @Test
    fun manualTake_shouldRequireInspectionModeBeforeOpeningConfirmation() {
        val branch = actionBranch("手动取肠", "仅清除本位数据")

        assertTrue(branch.contains("AppConfig.getAppConfig().inspectionMode != AppConfigBean.INSPECTION_MODE_ACTIVE"))
        assertTrue(branch.contains("请先开启检修模式"))
        assertTrue(branch.indexOf("inspectionMode") < branch.indexOf("showManualTakeSausageDialog(kaoPan)"))
    }

    @Test
    fun clearPanPositionData_shouldRemainAvailableWithoutInspectionMode() {
        val branch = actionBranch("仅清除本位数据", "dialog.dismiss()")

        assertTrue(branch.contains("showClearPanPositionDataDialog(kaoPan)"))
        assertFalse(branch.contains("inspectionMode"))
    }

    @Test
    fun manualTake_shouldStartApplicationOwnedTaskInsteadOfRunningMechanicalActionInFragmentScope() {
        val method = methodBody("private fun showManualTakeSausageDialog", "private fun showClearPanPositionDataDialog")

        assertTrue(method.contains("KaoChangOperate.startManualTakeSausage(kaoPan)"))
        assertTrue(method.contains("manualTakeTask.await()"))
        assertTrue(method.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertFalse(method.contains("\n                lifecycleScope.launch"))
        assertFalse(method.contains("KaoChangOperate.takeSausage(kaoPan)"))
        assertFalse(method.contains("MaintenanceModbusRecoveryConnector.ensureConnected()"))
        assertFalse(method.contains("waitUntilMachineIdleOrTimeout("))
        assertTrue(
            method.indexOf("KaoChangOperate.startManualTakeSausage(kaoPan)") <
                method.indexOf("viewLifecycleOwner.lifecycleScope.launch")
        )
    }

    @Test
    fun manualTakeAction_shouldRecheckInspectionModeAndClaimOnlyIdleMoveStatus() {
        val method = sourceSection(
            operateSource,
            "suspend fun takeSausage(kaoPan: KaoPan)",
            "suspend fun takeSausageResult("
        )

        assertTrue(method.contains("inspectionMode != AppConfigBean.INSPECTION_MODE_ACTIVE"))
        assertTrue(method.contains("waitUntilMoveStatusIdleOrTimeout(\"维护手动取肠\")"))
        assertTrue(method.contains("allowedMoveStatuses = setOf(0)"))
        assertTrue(method.contains("claimGate = {"))
        assertTrue(method.contains("inspectionMode == AppConfigBean.INSPECTION_MODE_ACTIVE"))
        assertTrue(method.contains("!SupplementPauseController.shouldPauseForOrder()"))
        assertFalse(method.contains("allowedMoveStatuses = setOf(appState)"))
    }

    private fun actionBranch(startAction: String, endMarker: String): String {
        val start = source.indexOf("\"$startAction\" -> {")
        require(start >= 0) { "Missing action branch: $startAction" }
        val end = source.indexOf(endMarker, start + 1)
        require(end > start) { "Missing branch end marker: $endMarker" }
        return source.substring(start, end)
    }

    private fun fragmentSourceText(name: String): String {
        val file = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/fragments/$name"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/fragments/$name")
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    private fun serviceSourceText(name: String): String {
        val file = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/$name"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/$name")
        ).first { it.exists() }
        return file.readText().replace("\r\n", "\n")
    }

    private fun sourceSection(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "Missing section: $startMarker" }
        val end = text.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "Missing section end: $endMarker" }
        return text.substring(start, end)
    }

    private fun methodBody(startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "Missing method: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "Missing method end: $endMarker" }
        return source.substring(start, end)
    }
}
