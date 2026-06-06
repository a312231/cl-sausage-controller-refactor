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

    data class RootProbeResult(
        val available: Boolean,
        val commandLabel: String?,
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

    private data class RootShellCandidate(
        val label: String,
        val probeCommand: List<String>,
        val buildScriptCommand: (String) -> List<String>
    )

    private val rootShellCandidates = listOf(
        RootShellCandidate(
            label = "su 0 sh -c",
            probeCommand = listOf("su", "0", "sh", "-c", "id"),
            buildScriptCommand = { script -> listOf("su", "0", "sh", "-c", script) }
        ),
        RootShellCandidate(
            label = "su -c",
            probeCommand = listOf("su", "-c", "id"),
            buildScriptCommand = { script -> listOf("su", "-c", script) }
        ),
        RootShellCandidate(
            label = "su root sh -c",
            probeCommand = listOf("su", "root", "sh", "-c", "id"),
            buildScriptCommand = { script -> listOf("su", "root", "sh", "-c", script) }
        )
    )

    fun isRootAvailable(runner: CommandRunner = ProcessCommandRunner): Boolean {
        return probeRoot(runner).available
    }

    fun probeRoot(runner: CommandRunner = ProcessCommandRunner): RootProbeResult {
        val attempts = StringBuilder()
        for (candidate in rootShellCandidates) {
            val result = runCatching {
                runner.runAndWait(candidate.probeCommand, timeoutMs = 5_000L)
            }.getOrElse {
                CommandResult(-1, it.message.orEmpty())
            }
            val summary = formatAttempt(candidate.label, result)
            attempts.append(summary).append('\n')
            if (result.exitCode == 0 && isRootAvailableOutput(result.output)) {
                return RootProbeResult(
                    available = true,
                    commandLabel = candidate.label,
                    output = attempts.toString().trim()
                )
            }
        }
        return RootProbeResult(
            available = false,
            commandLabel = null,
            output = attempts.toString().trim()
        )
    }

    fun isRootAvailableOutput(output: String): Boolean {
        return output.contains("uid=0")
    }

    fun launchInstall(
        apkFile: File,
        targetVersionCode: Int,
        packageName: String = PACKAGE_NAME,
        launcherComponent: String = LAUNCHER_COMPONENT,
        preferredRootCommandLabel: String? = null,
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
        val failures = StringBuilder()
        for (candidate in orderedRootShellCandidates(preferredRootCommandLabel)) {
            val command = candidate.buildScriptCommand(script)
            val started = runCatching {
                runner.start(command)
                LaunchResult.Started
            }
            if (started.isSuccess) {
                return LaunchResult.Started
            }
            failures
                .append(candidate.label)
                .append(": ")
                .append(started.exceptionOrNull()?.message.orEmpty())
                .append('\n')
        }
        return LaunchResult.Failed("root_command_start_failed", failures.toString().trim())
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

    private fun orderedRootShellCandidates(preferredRootCommandLabel: String?): List<RootShellCandidate> {
        if (preferredRootCommandLabel.isNullOrBlank()) {
            return rootShellCandidates
        }
        val preferred = rootShellCandidates.firstOrNull { it.label == preferredRootCommandLabel }
            ?: return rootShellCandidates
        return listOf(preferred) + rootShellCandidates.filterNot { it.label == preferredRootCommandLabel }
    }

    private fun formatAttempt(label: String, result: CommandResult): String {
        return "$label: exitCode=${result.exitCode}, output=${singleLine(result.output)}"
    }

    private fun singleLine(value: String): String {
        return value.replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(500)
    }
}
