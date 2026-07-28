package cn.niuyannet.kaochang.android.log

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLogPackageSafetySourceTest {
    @Test
    fun manager_shouldLimitInFlightAndDeleteTemporaryZip() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/log/DeviceLogPackageManager.kt")

        assertTrue(source.contains("private val inFlight = AtomicBoolean(false)"))
        assertTrue(source.contains("if (!inFlight.compareAndSet(false, true))"))
        assertTrue(source.contains("finally {\n                    packageFile.delete()\n                    inFlight.set(false)"))
        assertTrue(source.contains("cleanupOldPackages(outputDir)"))
    }

    @Test
    fun upload_shouldPutCredentialsInHeaders() {
        val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/net/NetApi.kt")

        assertTrue(source.contains(".headers(\"X-Log-Request-Id\", requestId)"))
        assertTrue(source.contains(".headers(\"X-Log-Upload-Token\", uploadToken)"))
        assertTrue(source.contains(".headers(\"X-Log-Sha256\", sha256)"))
        assertTrue(source.contains(".upFile(file)"))
    }

    private fun sourceFile(path: String): String {
        val direct = File(path)
        if (direct.exists()) return direct.readText().replace("\r\n", "\n")
        val parent = File("..", path)
        if (parent.exists()) return parent.readText().replace("\r\n", "\n")
        throw AssertionError("找不到源码文件：$path")
    }
}
