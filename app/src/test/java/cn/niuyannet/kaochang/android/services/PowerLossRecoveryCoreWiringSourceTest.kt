package cn.niuyannet.kaochang.android.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLossRecoveryCoreWiringSourceTest {

    @Test
    fun guard_shouldUseCoreAsTheOnlyColdStartAndHeartbeatDecisionOwner() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuard.kt"
        )
        val recoveryBody = extractFunctionBody(source, "fun recoverPansAfterColdStart")
        val heartbeatBody = extractFunctionBody(source, "fun markAliveIfDue")
        val compactRecovery = compact(recoveryBody)
        val compactHeartbeat = compact(heartbeatBody)

        assertEquals(1, occurrences(compactRecovery, "PowerLossRecoveryPolicy.coldStartAction("))
        assertEquals(1, occurrences(compactRecovery, "when("))
        assertTrue(compactRecovery.contains("returnwhen(coldStartAction){"))
        assertTrue(!compactRecovery.contains("if("))
        assertTrue(!compactRecovery.contains("FaultHeatPauseManager.shouldSkipBakingCompletion("))

        assertEquals(1, occurrences(compactHeartbeat, "PowerLossRecoveryPolicy.shouldMarkAlive("))
        assertEquals(1, occurrences(compactHeartbeat, "if("))
        assertTrue(
            compactHeartbeat.contains(
                "if(!PowerLossRecoveryPolicy.shouldMarkAlive(config.lastRuntimeAliveAtMs,nowMs)){returnfalse}"
            )
        )
        assertTrue(!compactHeartbeat.contains("nowMs-"))
        assertTrue(!compactHeartbeat.contains("-config.lastRuntimeAliveAtMs"))
        assertTrue(!compactHeartbeat.contains("60_000"))
        assertTrue(!compactHeartbeat.contains("60000"))
        assertTrue(!source.contains("HEARTBEAT_INTERVAL_MS"))
    }

    @Test
    fun guard_shouldKeepJournalPersistenceLoggingAndPublishingInApp() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuard.kt"
        )
        val startupBody = extractFunctionBody(source, "fun recoverBeforeRuntimeStart")
        val journal = startupBody.indexOf("replayPendingRecoveryJournalIfNeeded")
        val recovery = startupBody.indexOf("recoverPansAfterColdStart(")
        val panSave = startupBody.indexOf("saveKaoPanListDurable")
        val configSave = startupBody.indexOf("saveAppConfigDurable")
        val log = startupBody.indexOf("logRecoveryResult")
        val publish = startupBody.indexOf("publishRecoveryState")

        assertTrue(journal >= 0 && journal < recovery)
        assertTrue(recovery < panSave && panSave < configSave && configSave < log)
        assertTrue(log < publish)
        assertTrue(source.contains("System.currentTimeMillis()"))
        assertTrue(source.contains("SendServerHelper.publishServiceUpdateStatus"))
        assertTrue(source.contains("SendServerHelper.publishAvailableState"))
        assertTrue(source.contains("NetApi.updateDeviceInfo()"))
    }

    @Test
    fun algorithm_shouldStillMarkHeartbeatBeforePollingPans() {
        val source = sourceFile(
            "app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt"
        )
        val runServer = extractFunctionBody(source, "fun runServer")
        val loop = extractBlock(runServer, "while (isRunning)")
        val heartbeat = loop.indexOf("PowerLossRecoveryGuard.markAliveIfDueAndPersist()")
        val panPoll = loop.indexOf("pollKaoPanStatus()")

        assertEquals(1, occurrences(loop, "PowerLossRecoveryGuard.markAliveIfDueAndPersist()"))
        assertEquals(1, occurrences(loop, "pollKaoPanStatus()"))
        assertTrue(heartbeat >= 0 && panPoll > heartbeat)
    }

    private fun sourceFile(path: String): String = sourceFilePath(path).readText().replace("\r\n", "\n")

    private fun compact(source: String): String = source.replace(Regex("\\s+"), "")

    private fun occurrences(source: String, token: String): Int =
        Regex(Regex.escape(token)).findAll(source).count()

    private fun sourceFilePath(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        val fromParent = File("..", path)
        if (fromParent.exists()) return fromParent
        throw AssertionError("找不到源码文件：$path")
    }

    private fun extractFunctionBody(source: String, signature: String): String {
        return extractBlock(source, signature)
    }

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("找不到代码块：$marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        throw AssertionError("代码块未闭合：$marker")
    }
}
