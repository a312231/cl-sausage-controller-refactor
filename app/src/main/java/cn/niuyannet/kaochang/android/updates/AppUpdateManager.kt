package cn.niuyannet.kaochang.android.updates

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.niuyannet.kaochang.android.BuildConfig
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.SelfCleanManager
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.OrderStateManager
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.FileCallback
import com.lzy.okgo.model.Progress
import com.lzy.okgo.model.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppUpdateManager {
    private const val CHANNEL = "android12"
    private const val SP_NAME = "app_update_state"
    private const val KEY_FORCE_FIRST_DETECTED_PREFIX = "force_update_first_detected_at_"
    private const val STARTUP_ACK_WAIT_TIMEOUT_MS = 10 * 60_000L
    private const val ACK_POLL_INTERVAL_MS = 1_000L
    private const val BUSY_ORDER_WINDOW_MS = 5_000L
    private const val MQTT_TRIGGER_DEDUP_WINDOW_MS = 30_000L

    interface TimeSource {
        fun nowMs(): Long
    }

    private object SystemTimeSource : TimeSource {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    internal sealed class InstallWaitAction {
        object InstallNow : InstallWaitAction()
        data class PollAgain(val delayMs: Long) : InstallWaitAction()
        data class RetryLater(val delayMs: Long) : InstallWaitAction()
    }

    @Volatile
    private var lastStatusText: String = "尚未检查更新"
    @Volatile
    private var checking = false
    @Volatile
    private var checkingStartedAtMs = 0L
    @Volatile
    private var downloadingVersionCode: Int? = null

    private var autoCheckJob: Job? = null
    private var pendingInstall: PendingInstall? = null
    private var pendingUnknownSourceInstall: PendingInstall? = null
    private var latestActivityRef: WeakReference<Activity>? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recentUpdateTriggers = ConcurrentHashMap<String, Long>()

    internal var timeSource: TimeSource = SystemTimeSource

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val forceUpdate: Boolean,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSha256: String,
        val apkSize: Long,
        val minSupportedVersionCode: Int?,
        val releaseTime: Long?,
        val releaseNotes: String,
        val signatureSha256: String?
    )

    private data class PendingInstall(
        val info: UpdateInfo,
        val apkFile: File,
        val force: Boolean,
        val firstDetectedAtMs: Long
    )

    fun startAutoChecks(activity: Activity) {
        if (autoCheckJob?.isActive == true) {
            latestActivityRef = WeakReference(activity)
            return
        }
        latestActivityRef = WeakReference(activity)
        autoCheckJob = managerScope.launch {
            val acked = waitFirstRuntimeStateAck()
            if (acked) {
                delay(AppUpdatePolicy.STARTUP_CHECK_DELAY_AFTER_FIRST_ACK_MS)
                latestActivityRef?.get()?.let { checkNow(it, manual = false) }
            } else {
                updateStatus("启动自动检查已跳过：首次 runtime_state 未在等待窗口内收到 ACK")
            }
            while (true) {
                delay(AppUpdatePolicy.PERIODIC_CHECK_INTERVAL_MS)
                latestActivityRef?.get()?.let { checkNow(it, manual = false) }
            }
        }
    }

    fun onActivityResumed(activity: Activity) {
        latestActivityRef = WeakReference(activity)
        val pending = pendingUnknownSourceInstall ?: return
        pendingUnknownSourceInstall = null
        tryInstall(activity, pending)
    }

    fun lastStatusText(): String = lastStatusText

    fun checkNow(activity: Activity, manual: Boolean, triggerId: String? = null) {
        latestActivityRef = WeakReference(activity)
        val nowMs = timeSource.nowMs()
        if (checking) {
            if (shouldResetStaleChecking(checking, checkingStartedAtMs, nowMs)) {
                updateStatus(
                    "检查更新状态超时，已复位并重新发起：checkingStartedAtMs=$checkingStartedAtMs，" +
                        "elapsedMs=${nowMs - checkingStartedAtMs}"
                )
                checking = false
                checkingStartedAtMs = 0L
            } else {
                val elapsedMs = if (checkingStartedAtMs > 0L) nowMs - checkingStartedAtMs else 0L
                updateStatus("检查更新已跳过：已有检查进行中，elapsedMs=$elapsedMs")
                if (manual) ToastUtils.showShort("正在检查更新，请稍候")
                triggerId?.let {
                    publishUpdateCheckResult(
                        triggerId = it,
                        success = false,
                        hasUpdate = false,
                        targetVersionCode = null,
                        targetVersionName = null,
                        forceUpdate = false,
                        reason = "check_in_progress"
                    )
                }
                return
            }
        }
        val credential = AppConfig.getAppConfig().mqttPassword
        if (credential.isNullOrBlank()) {
            updateStatus("检查更新失败：设备凭证未就绪")
            if (manual) ToastUtils.showShort("检查更新失败：设备凭证未就绪")
            triggerId?.let {
                publishUpdateCheckResult(it, false, false, null, null, false, "credential_not_ready")
            }
            return
        }
        checking = true
        checkingStartedAtMs = nowMs
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME
        updateStatus(
            "开始检查应用版本：deviceCode=${AppConfig.getDeviceId()}，" +
                "versionCode（当前版本号）=$currentVersionCode，" +
                "versionName（当前版本名）=$currentVersionName，channel=$CHANNEL，" +
                "url=${NetApi.checkUpdate_url}"
        )
        NetApi.checkAppUpdate(
            credential = credential,
            versionCode = currentVersionCode,
            versionName = currentVersionName,
            packageName = activity.packageName,
            channel = CHANNEL
        ) { code, content ->
            checking = false
            checkingStartedAtMs = 0L
            if (code != 0) {
                updateStatus("检查更新失败：网络请求失败")
                if (manual) ToastUtils.showShort("检查更新失败，请稍后重试")
                triggerId?.let {
                    publishUpdateCheckResult(it, false, false, null, null, false, "network_failed")
                }
                return@checkAppUpdate
            }
            val info = parseUpdateInfo(content)
            if (info == null) {
                updateStatus("检查应用版本请求成功但响应字段不完整")
                updateStatus("检查更新失败：响应字段不完整")
                if (manual) ToastUtils.showShort("检查更新失败：响应字段不完整")
                triggerId?.let {
                    publishUpdateCheckResult(it, false, false, null, null, false, "invalid_response")
                }
                return@checkAppUpdate
            }
            updateStatus(
                "检查应用版本请求成功：hasUpdate=${info.hasUpdate}，" +
                    "remoteVersionCode=${info.versionCode}，currentVersionCode=$currentVersionCode"
            )
            if (!AppUpdatePolicy.shouldUpdate(currentVersionCode, info.versionCode, info.hasUpdate)) {
                clearForceUpdateFirstDetectedMarkers(activity)
                updateStatus("已是最新版本：v$currentVersionName($currentVersionCode)")
                if (manual) ToastUtils.showShort("当前已是最新版本")
                triggerId?.let {
                    publishUpdateCheckResult(it, true, false, info.versionCode, info.versionName, false, "no_update")
                }
                return@checkAppUpdate
            }
            val force = AppUpdatePolicy.shouldForceUpdate(
                currentVersionCode = currentVersionCode,
                forceUpdate = info.forceUpdate,
                minSupportedVersionCode = info.minSupportedVersionCode
            )
            triggerId?.let {
                publishUpdateCheckResult(it, true, true, info.versionCode, info.versionName, force, "update_found")
            }
            handleUpdate(activity, info, force, manual)
        }
    }

    fun handleMqttUpdateCheck(activity: Activity, content: String) {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            LogUtils.e("【应用更新】MQTT 更新检查触发解析失败：error=${e.message}")
            return
        }
        val triggerId = json.getString("triggerId")?.trim().orEmpty()
        if (triggerId.isBlank()) {
            LogUtils.w("【应用更新】MQTT 更新检查触发缺少 triggerId，已忽略")
            return
        }
        val strategyId = json.getLong("strategyId")
        val reason = json.getString("reason").orEmpty()
        if (!shouldAcceptUpdateTrigger(triggerId, timeSource.nowMs())) {
            LogUtils.d("【应用更新】30 秒内重复 MQTT 更新触发已忽略：triggerId=$triggerId")
            SendServerHelper.publishAppUpdateCheckAck(triggerId, strategyId, "duplicate_ignored")
            return
        }
        SendServerHelper.publishAppUpdateCheckAck(triggerId, strategyId, reason.ifBlank { "mqtt_trigger" })
        LogUtils.i("【应用更新】收到 MQTT 更新检查触发：triggerId=$triggerId，strategyId=${strategyId ?: "null"}")
        checkNow(activity, manual = false, triggerId = triggerId)
    }

    internal fun shouldAcceptUpdateTrigger(triggerId: String, nowMs: Long): Boolean {
        val last = recentUpdateTriggers[triggerId]
        if (last != null && nowMs - last < MQTT_TRIGGER_DEDUP_WINDOW_MS) {
            return false
        }
        recentUpdateTriggers[triggerId] = nowMs
        recentUpdateTriggers.entries.removeIf { nowMs - it.value > MQTT_TRIGGER_DEDUP_WINDOW_MS }
        return true
    }

    internal fun shouldResetStaleChecking(
        isChecking: Boolean,
        checkingStartedAtMs: Long,
        nowMs: Long
    ): Boolean {
        return isChecking &&
            checkingStartedAtMs > 0L &&
            nowMs - checkingStartedAtMs > AppUpdatePolicy.CHECK_IN_PROGRESS_TIMEOUT_MS
    }

    private fun handleUpdate(activity: Activity, info: UpdateInfo, force: Boolean, manual: Boolean) {
        updateStatus(
            "发现新版本：v${info.versionName}(${info.versionCode})，" +
                "forceUpdate（是否强制更新）=$force"
        )
        val firstDetectedAtMs = if (force) {
            rememberForceUpdateFirstDetectedAt(activity, info.versionCode)
        } else {
            timeSource.nowMs()
        }
        if (force) {
            val readiness = currentReadiness(minimalGate = false)
            if (!readiness.ok) {
                LogUtils.w("【应用更新】检测到强制更新但设备未空闲，延后提示：${readiness.blockedBy}")
                if (manual) ToastUtils.showLong("检测到强制更新，等待设备空闲后安装：${readiness.blockedBy}")
                downloadAndPrepare(activity, info, force, showProgress = manual, firstDetectedAtMs = firstDetectedAtMs)
                return
            }
            showUpdateDialog(activity, info, force, firstDetectedAtMs)
            return
        }
        showUpdateDialog(activity, info, force, firstDetectedAtMs)
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo, force: Boolean, firstDetectedAtMs: Long) {
        val notes = info.releaseNotes.ifBlank { "暂无更新说明" }
        AlertDialog.Builder(activity)
            .setTitle(if (force) "发现强制更新" else "发现新版本")
            .setMessage("版本：v${info.versionName}(${info.versionCode})\n\n$notes")
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndPrepare(activity, info, force, showProgress = true, firstDetectedAtMs = firstDetectedAtMs)
            }
            .apply {
                if (!force) {
                    setNegativeButton("稍后更新", null)
                }
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(!force)
                setCancelable(!force)
            }
            .show()
    }

    private fun downloadAndPrepare(
        activity: Activity,
        info: UpdateInfo,
        force: Boolean,
        showProgress: Boolean,
        firstDetectedAtMs: Long
    ) {
        if (downloadingVersionCode == info.versionCode) {
            if (showProgress) ToastUtils.showShort("正在下载更新包")
            return
        }
        val precheck = validateBeforeDownload(activity, info)
        if (precheck != null) {
            updateStatus(precheck)
            if (showProgress) ToastUtils.showLong(precheck)
            return
        }
        val updatesDir = updatesDir(activity)
        cleanupOldApks(updatesDir, keepVersionCode = info.versionCode)
        val fileName = apkFileName(info.versionCode)
        downloadingVersionCode = info.versionCode
        val progressDialog = if (showProgress) {
            AlertDialog.Builder(activity)
                .setTitle("正在下载更新")
                .setMessage("准备下载...")
                .setCancelable(false)
                .create()
                .also { it.show() }
        } else {
            null
        }
        downloadWithRetry(activity, info, updatesDir, fileName, progressDialog, force, firstDetectedAtMs, attempt = 0)
    }

    private fun downloadWithRetry(
        activity: Activity,
        info: UpdateInfo,
        updatesDir: File,
        fileName: String,
        progressDialog: AlertDialog?,
        force: Boolean,
        firstDetectedAtMs: Long,
        attempt: Int
    ) {
        OkGo.get<File>(info.apkUrl)
            .execute(object : FileCallback(updatesDir.absolutePath, fileName) {
                override fun downloadProgress(progress: Progress?) {
                    val percent = ((progress?.fraction ?: 0f) * 100).toInt().coerceIn(0, 100)
                    progressDialog?.setMessage("下载进度：$percent%")
                }

                override fun onSuccess(response: Response<File>?) {
                    downloadingVersionCode = null
                    progressDialog?.dismiss()
                    val apkFile = response?.body()
                    if (apkFile == null) {
                        onDownloadFailed(
                            activity,
                            info,
                            updatesDir,
                            fileName,
                            progressDialog,
                            force,
                            firstDetectedAtMs,
                            attempt,
                            "下载文件为空"
                        )
                        return
                    }
                    val verifyError = verifyDownloadedApk(activity, info, apkFile)
                    if (verifyError != null) {
                        apkFile.delete()
                        updateStatus(verifyError)
                        ToastUtils.showLong(verifyError)
                        return
                    }
                    val pending = PendingInstall(
                        info = info,
                        apkFile = apkFile,
                        force = force,
                        firstDetectedAtMs = firstDetectedAtMs
                    )
                    pendingInstall = pending
                    waitForReadinessAndInstall(activity, pending)
                }

                override fun onError(response: Response<File>?) {
                    super.onError(response)
                    onDownloadFailed(
                        activity,
                        info,
                        updatesDir,
                        fileName,
                        progressDialog,
                        force,
                        firstDetectedAtMs,
                        attempt,
                        response?.exception?.message ?: "未知错误"
                    )
                }
            })
    }

    private fun onDownloadFailed(
        activity: Activity,
        info: UpdateInfo,
        updatesDir: File,
        fileName: String,
        progressDialog: AlertDialog?,
        force: Boolean,
        firstDetectedAtMs: Long,
        attempt: Int,
        reason: String
    ) {
        File(updatesDir, fileName).delete()
        if (attempt < AppUpdatePolicy.DOWNLOAD_RETRY_DELAYS_MS.size) {
            val delayMs = AppUpdatePolicy.DOWNLOAD_RETRY_DELAYS_MS[attempt]
            val retryText = "下载更新失败，将在${delayMs / 1000}秒后重试：$reason"
            updateStatus(retryText)
            progressDialog?.setMessage(retryText)
            managerScope.launch {
                delay(delayMs)
                downloadWithRetry(activity, info, updatesDir, fileName, progressDialog, force, firstDetectedAtMs, attempt + 1)
            }
        } else {
            downloadingVersionCode = null
            progressDialog?.dismiss()
            updateStatus("下载更新失败次数过多：$reason")
            SendServerHelper.publishAppUpgradeNotice(
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                targetVersionCode = info.versionCode,
                targetVersionName = info.versionName,
                forceUpdate = force,
                reason = "download_failed:$reason"
            )
        }
    }

    private fun waitForReadinessAndInstall(activity: Activity, pending: PendingInstall) {
        managerScope.launch {
            val start = timeSource.nowMs()
            while (true) {
                val now = timeSource.nowMs()
                val minimalGate = shouldUseMinimalGate(pending.force, pending.firstDetectedAtMs, now)
                val readiness = currentReadiness(minimalGate)
                    when (val action = nextInstallWaitAction(readiness.ok, start, now)) {
                    InstallWaitAction.InstallNow -> {
                        tryInstall(activity, pending)
                        return@launch
                    }
                    is InstallWaitAction.PollAgain -> {
                        updateStatus("等待设备空闲后安装：${readiness.blockedBy}")
                        delay(action.delayMs)
                    }
                    is InstallWaitAction.RetryLater -> {
                        updateStatus("等待 MQTT 收口超时，${action.delayMs / 60_000}分钟后重试安装")
                        delay(action.delayMs)
                        latestActivityRef?.get()?.let { waitForReadinessAndInstall(it, pending) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun tryInstall(activity: Activity, pending: PendingInstall) {
        val readiness = currentReadiness(shouldUseMinimalGate(pending.force, pending.firstDetectedAtMs, timeSource.nowMs()))
        if (!readiness.ok) {
            updateStatus("安装前门控未通过：${readiness.blockedBy}")
            waitForReadinessAndInstall(activity, pending)
            return
        }
        revalidatePendingInstall(activity, pending) { result ->
            when (result) {
                PendingValidationResult.Valid -> continueInstallAfterRevalidation(activity, pending)
                PendingValidationResult.NotMatched -> {
                    pending.apkFile.delete()
                    if (pendingInstall == pending) {
                        pendingInstall = null
                    }
                    updateStatus("更新策略已变更或禁用，已删除待安装更新包")
                    ToastUtils.showLong("更新策略已变更，已取消本次安装")
                }
                PendingValidationResult.Failed -> {
                    updateStatus("安装前重新验证更新策略失败，5 分钟后重试")
                    managerScope.launch {
                        delay(AppUpdatePolicy.MQTT_DRAIN_RETRY_DELAY_MS)
                        latestActivityRef?.get()?.let { waitForReadinessAndInstall(it, pending) }
                    }
                }
            }
        }
    }

    private enum class PendingValidationResult {
        Valid,
        NotMatched,
        Failed
    }

    private fun revalidatePendingInstall(
        activity: Activity,
        pending: PendingInstall,
        callback: (PendingValidationResult) -> Unit
    ) {
        val credential = AppConfig.getAppConfig().mqttPassword
        if (credential.isNullOrBlank()) {
            callback(PendingValidationResult.Failed)
            return
        }
        NetApi.checkAppUpdate(
            credential = credential,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            packageName = activity.packageName,
            channel = CHANNEL
        ) { code, content ->
            if (code != 0) {
                callback(PendingValidationResult.Failed)
                return@checkAppUpdate
            }
            val latest = parseUpdateInfo(content)
            if (latest == null) {
                callback(PendingValidationResult.Failed)
                return@checkAppUpdate
            }
            val stillMatched = isPendingInstallStillMatched(latest.hasUpdate, latest.versionCode, pending.info.versionCode)
            callback(if (stillMatched) PendingValidationResult.Valid else PendingValidationResult.NotMatched)
        }
    }

    internal fun isPendingInstallStillMatched(hasUpdate: Boolean, remoteVersionCode: Int, pendingVersionCode: Int): Boolean {
        return hasUpdate && remoteVersionCode == pendingVersionCode
    }

    private fun continueInstallAfterRevalidation(activity: Activity, pending: PendingInstall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            pendingUnknownSourceInstall = pending
            updateStatus("等待授权安装未知应用")
            ToastUtils.showLong("请允许本应用安装更新包，返回后会继续安装")
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            runCatching {
                activity.startActivity(intent)
            }.onFailure {
                pendingUnknownSourceInstall = null
                updateStatus("无法打开未知来源授权设置，请在系统设置中允许本应用安装未知应用")
                ToastUtils.showLong("无法打开授权设置，请联系运维在系统设置中允许本应用安装未知应用")
            }
            return
        }
        SendServerHelper.publishAppUpgradeNotice(
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
            targetVersionCode = pending.info.versionCode,
            targetVersionName = pending.info.versionName,
            forceUpdate = pending.force,
            reason = "before_install"
        )
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            pending.apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        updateStatus("已拉起系统安装器：v${pending.info.versionName}(${pending.info.versionCode})")
        runCatching {
            activity.startActivity(intent)
        }.onFailure {
            updateStatus("拉起系统安装器失败：${it.message ?: "未知错误"}")
            ToastUtils.showLong("无法打开系统安装器，请联系运维处理")
        }
    }

    private fun publishUpdateCheckResult(
        triggerId: String,
        success: Boolean,
        hasUpdate: Boolean,
        targetVersionCode: Int?,
        targetVersionName: String?,
        forceUpdate: Boolean,
        reason: String
    ) {
        SendServerHelper.publishAppUpdateCheckResult(
            SendServerHelper.AppUpdateCheckResultPayload(
                triggerId = triggerId,
                success = success,
                hasUpdate = hasUpdate,
                targetVersionCode = targetVersionCode,
                targetVersionName = targetVersionName,
                forceUpdate = forceUpdate,
                reason = reason
            )
        )
    }

    private fun currentReadiness(minimalGate: Boolean): DeviceReadinessForUpgrade.Result {
        val config = AppConfig.getAppConfig()
        return DeviceReadinessForUpgrade.check(
            DeviceReadinessForUpgrade.State(
                moveStatus = config.moveStatus,
                onlineStatus = config.onlineStatus,
                hasProcessingOrder = OrderStateManager.hasProcessingOrder(),
                hasRecentBusyOrder = OrderStateManager.hasRecentBusyOrder(BUSY_ORDER_WINDOW_MS),
                hasOrderTracking = SelfCleanManager.hasOrderTracking(),
                mqttPendingCount = VMMqttHelper.pendingPublishCount(),
                pendingRuntimeAckCount = SendServerHelper.pendingRuntimeAckCount(),
                pendingAvailableAckCount = SendServerHelper.pendingAvailableAckCount()
            ),
            minimalGate = minimalGate
        )
    }

    internal fun shouldUseMinimalGate(force: Boolean, firstDetectedAtMs: Long, nowMs: Long): Boolean {
        return force && nowMs - firstDetectedAtMs >= AppUpdatePolicy.FORCE_UPDATE_MINIMAL_GATE_AFTER_MS
    }

    internal fun nextInstallWaitAction(readinessOk: Boolean, waitStartedAtMs: Long, nowMs: Long): InstallWaitAction {
        if (readinessOk) {
            return InstallWaitAction.InstallNow
        }
        return if (nowMs - waitStartedAtMs > AppUpdatePolicy.MQTT_DRAIN_TIMEOUT_MS) {
            InstallWaitAction.RetryLater(AppUpdatePolicy.MQTT_DRAIN_RETRY_DELAY_MS)
        } else {
            InstallWaitAction.PollAgain(ACK_POLL_INTERVAL_MS)
        }
    }

    private fun rememberForceUpdateFirstDetectedAt(context: Context, versionCode: Int): Long {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val key = forceFirstDetectedKey(versionCode)
        val existing = sp.getLong(key, 0L)
        if (existing > 0L) {
            return existing
        }
        val now = timeSource.nowMs()
        if (!sp.edit().putLong(key, now).commit()) {
            LogUtils.w("【应用更新】强更首次检测时间同步落盘失败：versionCode=$versionCode")
        }
        return now
    }

    private fun clearForceUpdateFirstDetectedMarkers(context: Context) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()
        sp.all.keys
            .filter { it.startsWith(KEY_FORCE_FIRST_DETECTED_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun forceFirstDetectedKey(versionCode: Int): String {
        return "$KEY_FORCE_FIRST_DETECTED_PREFIX$versionCode"
    }

    private suspend fun waitFirstRuntimeStateAck(): Boolean {
        val start = timeSource.nowMs()
        while (timeSource.nowMs() - start <= STARTUP_ACK_WAIT_TIMEOUT_MS) {
            if (SendServerHelper.lastAckedRuntimeStateSeq() > 0L) {
                return true
            }
            delay(ACK_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun parseUpdateInfo(content: String?): UpdateInfo? {
        val json = try {
            JSON.parseObject(content ?: return null)
        } catch (_: Exception) {
            return null
        }
        if (json.getIntValue("code") != 200) {
            return null
        }
        val data = json.getJSONObject("data") ?: return null
        val hasUpdate = data.getBooleanValue("hasUpdate")
        if (!hasUpdate) {
            return UpdateInfo(
                hasUpdate = false,
                forceUpdate = false,
                versionCode = data.getIntValue("versionCode"),
                versionName = data.getString("versionName").orEmpty(),
                apkUrl = "",
                apkSha256 = "",
                apkSize = 0L,
                minSupportedVersionCode = data.getInteger("minSupportedVersionCode"),
                releaseTime = data.getLong("releaseTime"),
                releaseNotes = data.getString("releaseNotes").orEmpty(),
                signatureSha256 = data.getString("signatureSha256")?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        val apkSha256 = data.getString("apkSha256")?.trim().orEmpty()
        val apkSize = data.getLongValue("apkSize")
        val apkUrl = data.getString("apkUrl")?.trim().orEmpty()
        if (apkSha256.isBlank() || apkSize <= 0L || apkUrl.isBlank()) {
            return null
        }
        return UpdateInfo(
            hasUpdate = hasUpdate,
            forceUpdate = data.getBooleanValue("forceUpdate"),
            versionCode = data.getIntValue("versionCode"),
            versionName = data.getString("versionName").orEmpty(),
            apkUrl = apkUrl,
            apkSha256 = apkSha256,
            apkSize = apkSize,
            minSupportedVersionCode = data.getInteger("minSupportedVersionCode"),
            releaseTime = data.getLong("releaseTime"),
            releaseNotes = data.getString("releaseNotes").orEmpty(),
            signatureSha256 = data.getString("signatureSha256")?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun validateBeforeDownload(context: Context, info: UpdateInfo): String? {
        val updatesDir = updatesDir(context)
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            return "无法创建更新目录"
        }
        val availableBytes = StatFs(updatesDir.absolutePath).availableBytes
        if (availableBytes < info.apkSize * 2) {
            return "存储空间不足：availableBytes=$availableBytes，requiredBytes=${info.apkSize * 2}"
        }
        return null
    }

    private fun verifyDownloadedApk(context: Context, info: UpdateInfo, apkFile: File): String? {
        if (!apkFile.exists()) {
            return "更新包不存在"
        }
        if (apkFile.length() != info.apkSize) {
            return "更新包大小不一致：actual=${apkFile.length()}，expected=${info.apkSize}"
        }
        val actualSha256 = sha256(apkFile)
        if (!actualSha256.equals(info.apkSha256, ignoreCase = true)) {
            return "更新包 SHA-256 校验失败"
        }
        val apkSignature = apkSignatureSha256(context, apkFile)
            ?: return "更新包签名读取失败"
        val expectedSignature = info.signatureSha256 ?: installedSignatureSha256(context)
            ?: return "当前应用签名读取失败"
        if (!apkSignature.equals(expectedSignature, ignoreCase = true)) {
            return "更新包签名不一致，拒绝安装"
        }
        return null
    }

    private fun apkSignatureSha256(context: Context, apkFile: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        return firstSignatureSha256(info)
    }

    private fun installedSignatureSha256(context: Context): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return firstSignatureSha256(info)
    }

    private fun firstSignatureSha256(info: PackageInfo): String? {
        val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        return sha256(signatureBytes)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun updatesDir(context: Context): File = File(context.getExternalFilesDir(null), "updates")

    private fun apkFileName(versionCode: Int): String = "app-update-$versionCode.apk"

    private fun cleanupOldApks(dir: File, keepVersionCode: Int) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != apkFileName(keepVersionCode) && file.name.startsWith("app-update-")) {
                file.delete()
            }
        }
    }

    private fun updateStatus(text: String) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        lastStatusText = "$now $text"
        LogUtils.i("【应用更新】$text")
    }
}
