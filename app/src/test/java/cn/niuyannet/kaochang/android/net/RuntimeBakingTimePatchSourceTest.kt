package cn.niuyannet.kaochang.android.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBakingTimePatchSourceTest {

    @Test
    fun remoteTemperatureRefresh_shouldRequireEnabledAlgorithm() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val start = source.indexOf("private fun canApplyRemoteTemperatureConfig")
        assertTrue("必须存在远端温度配置应用门禁", start >= 0)
        val end = source.indexOf("private fun", start + 20).let { if (it >= 0) it else source.length }
        val body = source.substring(start, end)

        assertTrue("算法关闭时远端温度配置只能保存，不能恢复物理加热", body.contains("config.isEnable == 1"))
    }

    @Test
    fun runtimeControl_shouldReconcileActivePansAfterBakingTimePatch() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt")
        val start = source.indexOf("fun applyRemoteRuntimeControl")
        assertTrue("必须存在远端运行时控制应用入口", start >= 0)
        val end = source.indexOf("fun applyRemoteConcurrencyControl", start).let { if (it >= 0) it else source.length }
        val body = source.substring(start, end)

        assertTrue(
            "MQTT bakingTime 配置应用后必须立即回刷当前烤制盘",
            body.contains("patchResult.appliedFields.contains(\"bakingTime\")") &&
                body.contains("reconcileActiveBakingTime(")
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
