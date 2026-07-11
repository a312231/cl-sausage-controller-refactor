package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMaintenanceHeatPauseSourceTest {

    @Test
    fun remoteTransitionToMaintenance_shouldStopHeatingAndBeginPause() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val start = source.indexOf("fun handleRemoteOnlineStatusTransition")
        val end = source.indexOf("fun applyRuntimeControl", start).let { if (it >= 0) it else source.length }
        val body = source.substring(start, end)
        val maintenanceGuard = body.indexOf("previousOnlineStatus != 3 && mergedConfig.onlineStatus == 3")
        val stopHeating = body.indexOf("KaoChangOperate.stopHeatingForFault(", maintenanceGuard)
        val operatingExitBranch = body.indexOf(
            "if (previousOnlineStatus in listOf(1, 4) && mergedConfig.onlineStatus in listOf(0, 2, 3))"
        )

        assertTrue(
            "任何非维护状态进入维护中都必须调用故障停热入口，不能只覆盖运营中/工作中",
            maintenanceGuard >= 0 &&
                stopHeating > maintenanceGuard &&
                operatingExitBranch > stopHeating
        )
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText()
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent.readText()
        throw AssertionError("找不到源码文件：$path")
    }
}
