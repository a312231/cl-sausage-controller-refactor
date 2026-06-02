package cn.niuyannet.kaochang.android.updates

import android.content.Context
import com.alibaba.fastjson.JSON

data class PendingRootInstall(
    val targetVersionCode: Int,
    val targetVersionName: String,
    val forceUpdate: Boolean,
    val triggerId: String?,
    val apkSha256: String,
    val startedAtMs: Long,
    val firstDetectedAtMs: Long
)

object PendingRootInstallCodec {
    fun encode(pending: PendingRootInstall): String = JSON.toJSONString(pending)

    fun decode(raw: String?): PendingRootInstall? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching {
            JSON.parseObject(raw, PendingRootInstall::class.java)
        }.getOrNull()
    }
}

object PendingRootInstallPolicy {
    sealed class ReconcileAction {
        object Success : ReconcileAction()
        object Wait : ReconcileAction()
        object Timeout : ReconcileAction()
    }

    fun isTimedOut(pending: PendingRootInstall, nowMs: Long, timeoutMs: Long): Boolean {
        return nowMs - pending.startedAtMs >= timeoutMs
    }

    fun reconcileAction(
        pending: PendingRootInstall,
        currentVersionCode: Int,
        nowMs: Long,
        timeoutMs: Long
    ): ReconcileAction {
        if (currentVersionCode >= pending.targetVersionCode) {
            return ReconcileAction.Success
        }
        return if (isTimedOut(pending, nowMs, timeoutMs)) {
            ReconcileAction.Timeout
        } else {
            ReconcileAction.Wait
        }
    }
}

object PendingRootInstallStore {
    private const val SP_NAME = "app_update_state"
    private const val KEY_PENDING_ROOT_INSTALL = "pending_root_install"

    fun save(context: Context, pending: PendingRootInstall): Boolean {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_ROOT_INSTALL, PendingRootInstallCodec.encode(pending))
            .commit()
    }

    fun load(context: Context): PendingRootInstall? {
        val raw = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_ROOT_INSTALL, null)
        return PendingRootInstallCodec.decode(raw)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_ROOT_INSTALL)
            .apply()
    }
}
