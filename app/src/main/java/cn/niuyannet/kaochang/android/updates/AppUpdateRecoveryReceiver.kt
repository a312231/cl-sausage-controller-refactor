package cn.niuyannet.kaochang.android.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.niuyannet.kaochang.android.ui.StartInitActivity
import cn.niuyannet.kaochang.android.utils.LogUtils

class AppUpdateRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pending = PendingRootInstallStore.load(context)
        if (pending == null) {
            LogUtils.d("【应用更新】收到恢复广播但无 pending 静默安装任务：action=$action")
            return
        }
        LogUtils.i("【应用更新】收到恢复广播，尝试拉起上位机：action=$action，targetVersionCode=${pending.targetVersionCode}")
        runCatching {
            context.startActivity(
                Intent(context, StartInitActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }.onFailure {
            LogUtils.w("【应用更新】恢复广播拉起上位机失败，等待静默安装链路或外部守护兜底：error=${it.message}")
        }
    }
}
