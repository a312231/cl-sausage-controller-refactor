package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCleanReservationSourceTest {

    @Test
    fun selfClean_shouldRemainAnUnwiredFutureEntryOnly() {
        val reservationEntry = source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        val businessEnd = extractFunctionBody(reservationEntry, "suspend fun endBusinessHours")
        val runtimeAndOperatorSources = listOf(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/ManualPanMovePolicy.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/services/ManualPanPositionDataClearPolicy.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/updates/AppUpdateManager.kt",
            "app/src/main/java/cn/niuyannet/kaochang/android/updates/DeviceReadinessForUpgrade.kt",
            "app/src/main/res/layout/fragment_test.xml"
        )

        assertTrue("必须保留未来可接入的自清洁入口", reservationEntry.contains("suspend fun runPanSelfCleanSequence("))
        val selfCleanBody = extractFunctionBody(reservationEntry, "suspend fun runPanSelfCleanSequence(")
        assertTrue("自清洁入口必须有营业/订单门禁", selfCleanBody.contains("SelfCleanStartPolicy.canStart("))
        assertTrue(
            "自清洁必须在等待/下发动作前检查营业和订单状态",
            selfCleanBody.indexOf("SelfCleanStartPolicy.canStart(") < selfCleanBody.indexOf("while (moveStatus != 0)")
        )
        assertFalse("营业结束不能接入自清洁", businessEnd.contains("runPanSelfCleanSequence("))
        runtimeAndOperatorSources.forEach { path ->
            assertFalse("当前运行链路不能接入自清洁：$path", source(path).contains("SelfClean"))
        }
        assertFalse("订单出餐不能记录自清洁状态", reservationEntry.contains("SelfCleanManager."))
        assertFalse("运行配置不能持久化三区自清洁状态", source("app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt").contains("zone3Clean"))
        assertFalse("运行配置对象不能保留三区自清洁状态", source("app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java").contains("zone3Clean"))
        assertFalse("算法不能把自清洁 pending 当作活动批次", source("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt").contains("zone3Clean"))
        assertFalse("三区自清洁管理器当前不应存在", sourceFileExists("app/src/main/java/cn/niuyannet/kaochang/android/services/SelfCleanManager.kt"))
    }

    private fun source(path: String): String = sourceFile(path).readText().replace("\r\n", "\n")

    private fun sourceFile(path: String): File {
        return listOf(File(path), File("..", path)).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码文件：$path")
    }

    private fun sourceFileExists(path: String): Boolean =
        listOf(File(path), File("..", path)).any { it.exists() }

    private fun extractFunctionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("找不到函数体：$signature", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        throw AssertionError("函数体未闭合：$signature")
    }
}
