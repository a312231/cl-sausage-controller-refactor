package cn.niuyannet.kaochang.android.updates

import java.io.File
import java.util.concurrent.TimeUnit

object RootSilentInstaller {
    const val PACKAGE_NAME = "cn.niuyannet.kaochang.android"
    const val LAUNCHER_COMPONENT = "cn.niuyannet.kaochang.android/.ui.StartInitActivity"

    data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    interface CommandRunner {
        fun runAndWait(command: List<String>, timeoutMs: Long): CommandResult
        fun start(command: List<String>): Process
    }

    object ProcessCommandRunner : CommandRunner {
        override fun runAndWait(command: List<String>, timeoutMs: Long): CommandResult {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return if (finished) {
                CommandResult(process.exitValue(), output)
            } else {
                process.destroyForcibly()
                CommandResult(-1, output)
            }
        }

        override fun start(command: List<String>): Process {
            return ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        }
    }

    sealed class LaunchResult {
        object Started : LaunchResult()
        data class Failed(
            val reason: String,
            val output: String
        ) : LaunchResult()
    }

    fun isRootAvailable(runner: CommandRunner = ProcessCommandRunner): Boolean {
        return runCatching {
            val result = runner.runAndWait(listOf("su", "0", "sh", "-c", "id"), timeoutMs = 5_000L)
            result.exitCode == 0 && isRootAvailableOutput(result.output)
        }.getOrDefault(false)
    }

    fun isRootAvailableOutput(output: String): Boolean {
        return output.contains("uid=0")
    }

    fun launchInstall(
        apkFile: File,
        targetVersionCode: Int,
        packageName: String = PACKAGE_NAME,
        launcherComponent: String = LAUNCHER_COMPONENT,
        runner: CommandRunner = ProcessCommandRunner
    ): LaunchResult {
        if (!apkFile.isFile) {
            return LaunchResult.Failed("apk_not_found", "apkFile=${apkFile.absolutePath}")
        }
        val script = buildInstallScript(
            sourceApkPath = apkFile.absolutePath,
            targetVersionCode = targetVersionCode,
            packageName = packageName,
            launcherComponent = launcherComponent
        )
        return runCatching {
            runner.start(listOf("su", "0", "sh", "-c", script))
            LaunchResult.Started
        }.getOrElse {
            LaunchResult.Failed("root_command_start_failed", it.message.orEmpty())
        }
    }

    fun buildInstallScript(
        sourceApkPath: String,
        targetVersionCode: Int,
        packageName: String,
        launcherComponent: String
    ): String {
        val tmpApk = "/data/local/tmp/leye-update-$targetVersionCode.apk"
        val quotedSource = shellQuote(sourceApkPath)
        val quotedTmp = shellQuote(tmpApk)
        val quotedPackage = shellQuote(packageName)
        val quotedLauncher = shellQuote(launcherComponent)
        return listOf(
            "rm -f /data/local/tmp/leye-update-*.apk",
            "cp $quotedSource $quotedTmp || exit 1",
            "chmod 0644 $quotedTmp || exit 1",
            "pm install -r -g --user 0 $quotedTmp || exit 1",
            "sleep 2",
            "am start -n $quotedLauncher || true",
            "sleep 3",
            "am start -n $quotedLauncher || true",
            "sleep 5",
            "monkey -p $quotedPackage -c android.intent.category.LAUNCHER 1 || true",
            "rm -f $quotedTmp"
        ).joinToString("; ")
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
