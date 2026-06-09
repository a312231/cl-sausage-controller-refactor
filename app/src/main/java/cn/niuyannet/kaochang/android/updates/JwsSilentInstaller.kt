package cn.niuyannet.kaochang.android.updates

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.jws.systemfor3rdservice.ISystemFor3RDService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object JwsSilentInstaller {
    const val SERVICE_PACKAGE = "com.jws.systemfor3rdservice"
    const val SERVICE_ACTION = "action.com.jws.systemfor3rdservice"
    const val TRANSACTION_JWS_SILENT_INSTALL_APP = 41
    const val BROADCAST_ACTION = "jws.intent.action.SILENT_INSTALL"
    const val EXTRA_INSTALL_PATH = "install_path"
    const val EXTRA_SELF_STARTING = "self_starting"
    const val BROADCAST_APK_NAME = "Update.apk"

    sealed class BindResult {
        data class Bound(val session: Session) : BindResult()
        data class Failed(
            val reason: String,
            val output: String
        ) : BindResult()
    }

    sealed class LaunchResult {
        object Started : LaunchResult()
        data class Failed(
            val reason: String,
            val output: String
        ) : LaunchResult()
    }

    fun launchBroadcastInstall(context: Context, apkFile: File, selfStarting: Boolean = true): LaunchResult {
        if (!apkFile.isFile) {
            return LaunchResult.Failed("apk_not_found", "apkFile=${apkFile.absolutePath}")
        }
        val appContext = context.applicationContext
        val intent = Intent(BROADCAST_ACTION).apply {
            putExtra(EXTRA_INSTALL_PATH, apkFile.absolutePath)
            putExtra(EXTRA_SELF_STARTING, selfStarting)
        }
        val receiverCount = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.queryBroadcastReceivers(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                ).size
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.queryBroadcastReceivers(intent, 0).size
            }
        }.getOrDefault(-1)
        if (receiverCount == 0) {
            return LaunchResult.Failed(
                "jws_broadcast_receiver_not_found",
                "action=$BROADCAST_ACTION, apkFile=${apkFile.absolutePath}"
            )
        }
        return runCatching {
            appContext.sendBroadcast(intent)
            LaunchResult.Started
        }.getOrElse {
            LaunchResult.Failed("jws_broadcast_send_failed", it.message ?: it.javaClass.name)
        }
    }

    class Session internal constructor(
        private val context: Context,
        private val connection: ServiceConnection,
        private val service: ISystemFor3RDService
    ) {
        fun launchInstall(apkFile: File, targetVersionCode: Int? = null, selfStarting: Boolean = true): LaunchResult {
            if (!apkFile.isFile) {
                return LaunchResult.Failed("apk_not_found", "apkFile=${apkFile.absolutePath}")
            }
            return runCatching {
                service.jwsSilentInstallApp(apkFile.absolutePath, selfStarting)
                LaunchResult.Started
            }.getOrElse {
                if (targetVersionCode != null && waitInstalledVersionAtLeast(targetVersionCode)) {
                    return LaunchResult.Started
                }
                LaunchResult.Failed("jws_call_failed", it.message ?: it.javaClass.name)
            }
        }

        fun close() {
            runCatching {
                context.unbindService(connection)
            }
        }

        private fun waitInstalledVersionAtLeast(targetVersionCode: Int): Boolean {
            val deadline = SystemClock.uptimeMillis() + 8_000L
            while (SystemClock.uptimeMillis() < deadline) {
                if (installedVersionCode() >= targetVersionCode) {
                    return true
                }
                Thread.sleep(250L)
            }
            return installedVersionCode() >= targetVersionCode
        }

        private fun installedVersionCode(): Long {
            return runCatching {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            }.getOrDefault(0L)
        }
    }

    suspend fun bind(context: Context, timeoutMs: Long = 10_000L): BindResult {
        val appContext = context.applicationContext
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lateinit var connection: ServiceConnection

        return suspendCancellableCoroutine { continuation ->
            fun finish(result: BindResult) {
                if (!completed.compareAndSet(false, true)) {
                    return
                }
                handler.removeCallbacksAndMessages(connection)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        finish(BindResult.Failed("binder_null", "name=$name"))
                        return
                    }
                    val service = runCatching {
                        ISystemFor3RDService.Stub.asInterface(binder)
                    }.getOrElse {
                        finish(BindResult.Failed("as_interface_failed", it.message ?: it.javaClass.name))
                        return
                    }
                    finish(BindResult.Bound(Session(appContext, this, service)))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(BindResult.Failed("service_disconnected", "name=$name"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    finish(BindResult.Failed("binding_died", "name=$name"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(BindResult.Failed("null_binding", "name=$name"))
                }
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    runCatching { appContext.unbindService(connection) }
                }
            }

            val timeout = Runnable {
                finish(BindResult.Failed("bind_timeout", "timeoutMs=$timeoutMs"))
                runCatching { appContext.unbindService(connection) }
            }
            handler.postAtTime(timeout, connection, SystemClock.uptimeMillis() + timeoutMs)

            val bound = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrElse {
                handler.removeCallbacksAndMessages(connection)
                finish(BindResult.Failed("bind_exception", it.message ?: it.javaClass.name))
                return@suspendCancellableCoroutine
            }
            if (!bound) {
                handler.removeCallbacksAndMessages(connection)
                finish(BindResult.Failed("bind_returned_false", "package=$SERVICE_PACKAGE, action=$SERVICE_ACTION"))
            }
        }
    }
}
