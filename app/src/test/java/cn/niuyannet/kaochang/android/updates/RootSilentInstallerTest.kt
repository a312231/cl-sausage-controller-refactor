package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class RootSilentInstallerTest {

    @Test
    fun isRootAvailable_requiresUidZero() {
        assertTrue(RootSilentInstaller.isRootAvailableOutput("uid=0(root) gid=0(root)"))
        assertFalse(RootSilentInstaller.isRootAvailableOutput("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootSilentInstaller.isRootAvailableOutput(""))
    }

    @Test
    fun probeRoot_triesFallbackCommandsAndReturnsSuccessfulCommandLabel() {
        val runner = FakeRunner(
            waitResults = mapOf(
                listOf("su", "0", "sh", "-c", "id") to RootSilentInstaller.CommandResult(1, "permission denied"),
                listOf("su", "-c", "id") to RootSilentInstaller.CommandResult(0, "uid=0(root) gid=0(root)")
            )
        )

        val result = RootSilentInstaller.probeRoot(runner)

        assertTrue(result.available)
        assertEquals("su -c", result.commandLabel)
        assertTrue(result.output.contains("su 0 sh -c: exitCode=1"))
        assertTrue(result.output.contains("su -c: exitCode=0"))
    }

    @Test
    fun probeRoot_reportsAllAttemptsWhenUnavailable() {
        val runner = FakeRunner()

        val result = RootSilentInstaller.probeRoot(runner)

        assertFalse(result.available)
        assertEquals(null, result.commandLabel)
        assertTrue(result.output.contains("su 0 sh -c: exitCode=127"))
        assertTrue(result.output.contains("su -c: exitCode=127"))
        assertTrue(result.output.contains("su root sh -c: exitCode=127"))
    }

    @Test
    fun buildInstallScript_stagesApkInstallsStartsTwiceThenMonkeyAndCleansUp() {
        val script = RootSilentInstaller.buildInstallScript(
            sourceApkPath = "/sdcard/Android/data/cn.niuyannet.kaochang.android/files/updates/app-update-130.apk",
            targetVersionCode = 130,
            packageName = "cn.niuyannet.kaochang.android",
            launcherComponent = "cn.niuyannet.kaochang.android/.ui.StartInitActivity"
        )

        assertTrue(script.contains("rm -f /data/local/tmp/leye-update-*.apk"))
        assertTrue(script.contains("cp '/sdcard/Android/data/cn.niuyannet.kaochang.android/files/updates/app-update-130.apk' '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("chmod 0644 '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("pm install -r -g --user 0 '/data/local/tmp/leye-update-130.apk'"))
        assertTrue(script.contains("sleep 2"))
        assertTrue(script.contains("am start -n 'cn.niuyannet.kaochang.android/.ui.StartInitActivity' || true"))
        assertTrue(script.contains("sleep 3"))
        assertTrue(script.contains("sleep 5"))
        assertTrue(script.contains("monkey -p 'cn.niuyannet.kaochang.android' -c android.intent.category.LAUNCHER 1 || true"))
        assertTrue(script.contains("rm -f '/data/local/tmp/leye-update-130.apk'"))
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("'a'\"'\"'b'", RootSilentInstaller.shellQuote("a'b"))
    }

    @Test
    fun launchInstall_usesPreferredRootCommandLabel() {
        val apk = File.createTempFile("leye-update", ".apk")
        try {
            val runner = FakeRunner()

            val result = RootSilentInstaller.launchInstall(
                apkFile = apk,
                targetVersionCode = 130,
                preferredRootCommandLabel = "su -c",
                runner = runner
            )

            assertEquals(RootSilentInstaller.LaunchResult.Started, result)
            assertEquals("su", runner.startedCommands.single()[0])
            assertEquals("-c", runner.startedCommands.single()[1])
            assertTrue(runner.startedCommands.single()[2].contains("pm install -r -g --user 0"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun launchInstall_fallsBackWhenPreferredStartFails() {
        val apk = File.createTempFile("leye-update", ".apk")
        try {
            val preferredCommandPrefix = listOf("su", "-c")
            val runner = FakeRunner(failedStartPrefixes = setOf(preferredCommandPrefix))

            val result = RootSilentInstaller.launchInstall(
                apkFile = apk,
                targetVersionCode = 130,
                preferredRootCommandLabel = "su -c",
                runner = runner
            )

            assertEquals(RootSilentInstaller.LaunchResult.Started, result)
            assertEquals(preferredCommandPrefix, runner.startedCommands[0].take(2))
            assertEquals(listOf("su", "0", "sh", "-c"), runner.startedCommands[1].take(4))
        } finally {
            apk.delete()
        }
    }

    private class FakeRunner(
        private val waitResults: Map<List<String>, RootSilentInstaller.CommandResult> = emptyMap(),
        private val failedStartPrefixes: Set<List<String>> = emptySet()
    ) : RootSilentInstaller.CommandRunner {
        val startedCommands = mutableListOf<List<String>>()

        override fun runAndWait(command: List<String>, timeoutMs: Long): RootSilentInstaller.CommandResult {
            return waitResults[command] ?: RootSilentInstaller.CommandResult(127, "not found")
        }

        override fun start(command: List<String>): Process {
            startedCommands.add(command)
            if (failedStartPrefixes.any { prefix -> command.take(prefix.size) == prefix }) {
                error("start failed")
            }
            return FakeProcess()
        }
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }
}
