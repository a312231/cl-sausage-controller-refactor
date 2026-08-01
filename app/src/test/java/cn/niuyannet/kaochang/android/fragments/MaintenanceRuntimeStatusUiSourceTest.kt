package cn.niuyannet.kaochang.android.fragments

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceRuntimeStatusUiSourceTest {

    @Test
    fun maintenanceLayout_shouldExposeSeparateFaultAndBusinessStatusTexts() {
        val layout = sourceFile("app/src/main/res/layout/fragment_maintenance.xml")

        assertTrue(layout.contains("@+id/tv_erro_status"))
        assertTrue(layout.contains("故障状态：正常"))
        assertTrue(layout.contains("@+id/tv_online_status"))
        assertTrue(layout.contains("营业状态：正在同步后台"))
    }

    @Test
    fun maintenanceFragment_shouldBuildStatusDisplayFromLocalAndReadOnlyCloudState() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val updateBody = extractFunctionBody(source, "private fun updateRuntimeStatusUi()")
        val refreshBody = extractFunctionBody(
            source,
            "private fun refreshRemoteRuntimeStatusForDisplay"
        )

        assertTrue(updateBody.contains("MaintenanceRuntimeStatusDisplayPolicy.build"))
        assertTrue(updateBody.contains("needsLocalFaultRecovery(config)"))
        assertTrue(updateBody.contains("ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly"))
        assertTrue(updateBody.contains("binding.tvErroStatus"))
        assertTrue(updateBody.contains("binding.tvOnlineStatus"))
        assertTrue(updateBody.contains("binding.btnErroStatus.text"))
        assertFalse("状态展示不能改变恢复入口可用性", updateBody.contains("isEnabled"))

        assertTrue(refreshBody.contains("DataManagementAPI.preflightRemoteDeviceState"))
        assertTrue(refreshBody.contains("CloudState.Available"))
        assertTrue(refreshBody.contains("CloudState.Unavailable"))
        assertFalse("只读状态对比不能合并后台配置", refreshBody.contains("mergeRemoteConfig"))
        assertFalse("只读状态对比不能上报或写设备状态", refreshBody.contains("uploadDeviceInfo"))
    }

    @Test
    fun maintenanceFragment_shouldRefreshStatusWithoutChangingRecoveryTransaction() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )
        val createdBody = extractFunctionBody(source, "override fun onViewCreated")
        val timerBody = extractFunctionBody(source, "private fun startRefreshTimer()")
        val callbackBody = extractPropertyLambdaBody(source, "maintenanceUiRefreshCallback")
        val recoveryBody = extractFunctionBody(source, "binding.btnErroStatus.setOnClickListener")

        assertTrue(createdBody.contains("refreshRemoteRuntimeStatusForDisplay"))
        assertTrue(timerBody.contains("updateRuntimeStatusUi()"))
        assertTrue(timerBody.contains("refreshRemoteRuntimeStatusForDisplay"))
        assertTrue(callbackBody.contains("refreshRemoteRuntimeStatusForDisplay"))

        assertTrue(recoveryBody.contains("confirmManualRecoveryInCloud"))
        assertTrue(recoveryBody.contains("applyManualRecoveredState"))
        assertFalse(recoveryBody.contains("refreshRemoteRuntimeStatusForDisplay"))
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
        return extractBraceBody(source, source.indexOf('{', start), functionSignature)
    }

    private fun extractPropertyLambdaBody(source: String, propertyName: String): String {
        val start = source.indexOf(propertyName)
        assertTrue("找不到属性：$propertyName", start >= 0)
        return extractBraceBody(source, source.indexOf('{', start), propertyName)
    }

    private fun extractBraceBody(source: String, bodyStart: Int, label: String): String {
        assertTrue("找不到函数体：$label", bodyStart >= 0)
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
        throw AssertionError("函数体未闭合：$label")
    }
}
