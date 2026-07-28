package cn.niuyannet.kaochang.android.log

import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DeviceLogPackageManager {
    private const val REQUEST_RETENTION_MS = 24L * 60 * 60 * 1000
    private val executor = Executors.newSingleThreadExecutor()
    private val handledRequests = ConcurrentHashMap<String, Long>()
    private val inFlight = AtomicBoolean(false)

    fun handle(content: JSONObject): Boolean {
        val requestId = content.getString("requestId").orEmpty()
        val deviceCode = content.getString("deviceCode").orEmpty()
        val uploadToken = content.getString("uploadToken").orEmpty()
        val rangeType = content.getString("rangeType").orEmpty()
        val rangeStart = content.getLongValue("rangeStartEpochMs")
        val rangeEnd = content.getLongValue("rangeEndEpochMs")
        val localDeviceCode = AppConfig.getDeviceId()
        if (requestId.isBlank() || uploadToken.isBlank() || deviceCode != localDeviceCode || rangeStart <= 0 || rangeEnd < rangeStart) {
            LogUtils.e("【远程日志】拒绝无效日志包请求：requestId=$requestId, deviceCode=$deviceCode")
            return true
        }
        cleanupHandledRequests()
        if (handledRequests.putIfAbsent(requestId, System.currentTimeMillis()) != null) {
            LogUtils.w("【远程日志】忽略重复日志包请求：requestId=$requestId")
            return true
        }
        if (!inFlight.compareAndSet(false, true)) {
            LogUtils.w("【远程日志】设备已有日志包任务，拒绝并发请求：requestId=$requestId")
            NetApi.reportLogPackageStatus(requestId, deviceCode, uploadToken, "FAILED", "设备已有日志包任务正在处理")
            return true
        }
        executor.execute { process(requestId, deviceCode, uploadToken, rangeType, rangeStart, rangeEnd) }
        return true
    }

    private fun process(requestId: String, deviceCode: String, token: String,
                        rangeType: String, rangeStart: Long, rangeEnd: Long) {
        var packageFile: File? = null
        try {
            NetApi.reportLogPackageStatus(requestId, deviceCode, token, "PACKAGING", null)
            if (!LogUtils.flushPendingWrites(10_000)) {
                throw IllegalStateException("等待本地日志写入完成超时")
            }
            val context = MyApp.instance()
            val outputDir = File(context.cacheDir, "device-log-packages").apply { mkdirs() }
            cleanupOldPackages(outputDir)
            packageFile = File(outputDir, "device-log-${safe(requestId)}.zip")
            val result = DeviceLogPackageBuilder.build(
                LogUtils.getLogDirectory(context), packageFile, requestId, deviceCode,
                rangeType, rangeStart, rangeEnd
            )
            NetApi.reportLogPackageStatus(requestId, deviceCode, token, "UPLOADING", null)
            NetApi.uploadLogPackage(requestId, deviceCode, token, result.sha256, packageFile) { success, message ->
                try {
                    if (success) {
                        LogUtils.i("【远程日志】日志包上传成功：requestId=$requestId, entries=${result.entryCount}, bytes=${result.size}")
                    } else {
                        LogUtils.e("【远程日志】日志包上传失败：requestId=$requestId, error=$message")
                        NetApi.reportLogPackageStatus(requestId, deviceCode, token, "FAILED", message)
                    }
                } finally {
                    packageFile.delete()
                    inFlight.set(false)
                }
            }
        } catch (e: Exception) {
            LogUtils.e("【远程日志】日志包准备失败：requestId=$requestId, error=${e.message}", e)
            NetApi.reportLogPackageStatus(requestId, deviceCode, token, "FAILED", e.message)
            packageFile?.delete()
            inFlight.set(false)
        }
    }

    private fun cleanupHandledRequests() {
        val cutoff = System.currentTimeMillis() - REQUEST_RETENTION_MS
        handledRequests.entries.removeIf { it.value < cutoff }
    }

    private fun cleanupOldPackages(directory: File) {
        val cutoff = System.currentTimeMillis() - REQUEST_RETENTION_MS
        directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
