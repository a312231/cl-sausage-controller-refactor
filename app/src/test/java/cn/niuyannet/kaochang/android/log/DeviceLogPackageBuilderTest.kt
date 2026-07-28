package cn.niuyannet.kaochang.android.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DeviceLogPackageBuilderTest {
    @Test
    fun build_shouldFilterByTimestampAndKeepStackContinuation() {
        val root = Files.createTempDirectory("device-log-package-test").toFile()
        val logs = File(root, "logs").apply { mkdirs() }
        File(logs, "2026-07-28_1.log").writeText(
            "2026-07-28 09:59:59.999 I/KaoChang: old\n" +
                "old continuation\n" +
                "2026-07-28 10:00:00.000 E/KaoChang: failure\n" +
                "java.lang.IllegalStateException: test\n" +
                "\tat sample.Line.run(Line.kt:1)\n" +
                "2026-07-28 11:00:00.001 I/KaoChang: future\n"
        )
        val output = File(root, "result.zip")

        val result = DeviceLogPackageBuilder.build(
            logDirectory = logs,
            outputZip = output,
            requestId = "request-1",
            deviceCode = "device-1",
            rangeType = "LAST_HOUR",
            rangeStartEpochMs = epoch("2026-07-28 10:00:00.000"),
            rangeEndEpochMs = epoch("2026-07-28 11:00:00.000")
        )

        assertEquals(1, result.entryCount)
        assertTrue(output.isFile)
        ZipFile(output).use { zip ->
            val logText = zip.getInputStream(zip.getEntry("device.log")).bufferedReader().readText()
            assertTrue(logText.contains("failure"))
            assertTrue(logText.contains("IllegalStateException"))
            assertTrue(logText.contains("sample.Line.run"))
            assertTrue(!logText.contains("old"))
            assertTrue(!logText.contains("future"))
            assertTrue(zip.getEntry("manifest.txt") != null)
        }
        root.deleteRecursively()
    }

    private fun epoch(value: String): Long =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).parse(value)!!.time
}
