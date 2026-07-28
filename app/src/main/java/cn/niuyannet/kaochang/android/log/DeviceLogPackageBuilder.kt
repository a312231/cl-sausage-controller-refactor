package cn.niuyannet.kaochang.android.log

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DeviceLogPackageBuilder {
    private val timestampPattern = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})(?:\\s|$)")

    data class Result(val entryCount: Int, val sha256: String, val size: Long)

    fun build(
        logDirectory: File,
        outputZip: File,
        requestId: String,
        deviceCode: String,
        rangeType: String,
        rangeStartEpochMs: Long,
        rangeEndEpochMs: Long
    ): Result {
        require(rangeStartEpochMs <= rangeEndEpochMs) { "日志开始时间不能晚于结束时间" }
        outputZip.parentFile?.mkdirs()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { isLenient = false }
        var entryCount = 0

        ZipOutputStream(outputZip.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("device.log"))
            logDirectory.listFiles { file -> file.isFile && file.name.endsWith(".log") }
                ?.sortedWith(compareBy<File>({ it.name.substringBeforeLast('_') }, { fileIndex(it.name) }))
                ?.forEach { file ->
                    var includeContinuation = false
                    file.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val match = timestampPattern.find(line)
                            if (match != null) {
                                val timestamp = try { formatter.parse(match.groupValues[1])?.time } catch (_: Exception) { null }
                                includeContinuation = timestamp != null &&
                                    timestamp >= rangeStartEpochMs && timestamp <= rangeEndEpochMs
                                if (includeContinuation) entryCount++
                            }
                            if (includeContinuation) {
                                zip.write(line.toByteArray(Charsets.UTF_8))
                                zip.write('\n'.code)
                            }
                        }
                    }
                }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("manifest.txt"))
            val manifest = "requestId=$requestId\n" +
                "deviceCode=$deviceCode\n" +
                "rangeType=$rangeType\n" +
                "rangeStartEpochMs=$rangeStartEpochMs\n" +
                "rangeEndEpochMs=$rangeEndEpochMs\n" +
                "entryCount=$entryCount\n"
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return Result(entryCount, sha256(outputZip), outputZip.length())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fileIndex(name: String): Int =
        name.substringAfterLast('_').substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE
}
