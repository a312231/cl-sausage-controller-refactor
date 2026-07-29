package cn.niuyannet.kaochang.android.mqtt

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalLogRetryWiringSourceTest {

    @Test
    fun reporter_shouldPersistOnlyWarningAndErrorWhenImmediatePublishFails() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/AppLogCloudReporter.kt")

        assertTrue(source.contains("persistCriticalIfNeeded(level, tag, content, reportedAt)"))
        assertTrue(source.contains("normalizedLevel != \"W\" && normalizedLevel != \"E\""))
        assertTrue(source.contains("CriticalLogOutbox"))
    }

    @Test
    fun reconnectHook_shouldDrainOutsideMqttCallbackWithSingleFlightGuard() {
        val reporter = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/AppLogCloudReporter.kt")
        val helper = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/VMMqttHelper.kt")

        assertTrue(reporter.contains("AtomicBoolean(false)"))
        assertTrue(reporter.contains("drainScope.launch"))
        assertTrue(reporter.contains("peekBatch("))
        assertTrue(reporter.contains("outbox.remove("))
        assertTrue(helper.contains("AppLogCloudReporter.onMqttConnected()"))
    }

    @Test
    fun onlineQueueRejection_shouldTriggerDrainAndRetryWithDelay() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/AppLogCloudReporter.kt")

        assertTrue(source.contains("val saved = persistCriticalIfNeeded(level, tag, content, reportedAt)"))
        assertTrue(source.contains("if (saved && VMMqttHelper.mqttClient?.isConnected == true)"))
        assertTrue(source.contains("delay(DRAIN_RETRY_DELAY_MS)"))
    }

    @Test
    fun drainFinally_shouldReleaseSingleFlightBeforeRestartingForPendingLogs() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/mqtt/AppLogCloudReporter.kt")
        val finallyBlock = source.substringAfter("finally {").substringBefore("\n            }")

        val releaseIndex = finallyBlock.indexOf("drainInProgress.set(false)")
        val pendingIndex = finallyBlock.indexOf("outbox.peekBatch(")
        val restartIndex = finallyBlock.indexOf("onMqttConnected()")
        assertTrue(releaseIndex >= 0)
        assertTrue(pendingIndex > releaseIndex)
        assertTrue(restartIndex > pendingIndex)
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val parent = File("..", path)
        if (parent.exists()) return parent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path")
    }
}
