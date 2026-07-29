package cn.niuyannet.kaochang.android.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogRateLimitSourceTest {

    @Test
    fun modbusRepeatedProbeDetails_shouldStayDebugAndAggregateFailureShouldBeRateLimited() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/modbus/VMModbusHelper.kt"
        )

        assertTrue(source.contains("LogUtils.d(\"串口已打开，但未收到有效的下位机响应："))
        assertTrue(source.contains("LogUtils.d(\"串口探测失败："))
        assertTrue(source.contains("reconnectFailureLogGate.evaluate("))
        assertTrue(source.contains("suppressedCount="))
    }

    @Test
    fun mqttPeriodicDisconnectedCheck_shouldBeDebugBecauseReconnectOwnsFaultWarnings() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/utils/ConnectManageHelper.kt"
        )

        assertTrue(
            source.contains(
                "LogUtils.d(\"【MQTT巡检】定时巡检发现连接已断开，正在尝试发起兜底重连...\")"
            )
        )
    }

    @Test
    fun faultPauseSummary_shouldUseMonotonicClock() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt"
        )

        assertTrue(source.contains("SystemClock.elapsedRealtime()"))
    }

    @Test
    fun maintenanceWait_shouldLogNaturalIdleTerminalOutcome() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt"
        )

        assertTrue(source.contains("等待机械空闲已结束：reason=\$reason"))
        assertTrue(source.contains("waitedMs="))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val parent = File("..", path)
        if (parent.exists()) return parent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path")
    }
}
