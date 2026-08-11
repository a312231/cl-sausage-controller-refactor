package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyResetAppRestarterSourceTest {

    @Test
    fun manifest_shouldDeclareExactAlarmPermission() {
        val manifest = sourceText("app/src/main/AndroidManifest.xml")

        assertTrue(
            "冷重启依赖精确闹钟，Manifest 必须声明 USE_EXACT_ALARM",
            manifest.contains("android.permission.USE_EXACT_ALARM")
        )
    }

    @Test
    fun scheduler_shouldRequireCapabilityAndUseExactIdleAlarm() {
        val source = sourceText(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/EmergencyResetAppRestarter.kt"
        )

        assertTrue(
            "Android 12+ 必须先确认精确闹钟能力",
            source.contains("canScheduleExactAlarms()")
        )
        assertTrue(
            "冷重启必须使用可穿透 idle 的精确闹钟",
            source.contains("setExactAndAllowWhileIdle(")
        )
        assertTrue(
            "禁止继续使用可能被系统长期延迟的非精确闹钟",
            !source.contains("setAndAllowWhileIdle(")
        )
    }

    private fun sourceText(path: String): String =
        listOf(File(path), File("..", path)).first { it.exists() }.readText().replace("\r\n", "\n")
}
