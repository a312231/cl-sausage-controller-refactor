package cn.niuyannet.kaochang.android.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import cn.niuyannet.kaochang.android.ui.StartInitActivity
import java.util.UUID

object AppProcessIdentity {
    val currentId: String = UUID.randomUUID().toString()
}

class EmergencyResetRestartCoordinator(
    private val scheduleRestart: () -> Boolean,
    private val terminateCurrentProcess: () -> Unit
) {
    fun scheduleAndTerminate(): Boolean {
        val scheduled = runCatching(scheduleRestart).getOrDefault(false)
        if (!scheduled) return false
        terminateCurrentProcess()
        return true
    }
}

object EmergencyResetAppRestarter {
    private const val RESTART_REQUEST_CODE = 99901
    private const val RESTART_DELAY_MS = 1_000L

    fun scheduleAndTerminate(context: Context): Boolean {
        val appContext = context.applicationContext
        return EmergencyResetRestartCoordinator(
            scheduleRestart = {
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return@EmergencyResetRestartCoordinator false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !alarmManager.canScheduleExactAlarms()
                ) {
                    return@EmergencyResetRestartCoordinator false
                }
                val launchIntent = Intent(appContext, StartInitActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("emergency_reset_cold_restart", true)
                }
                val pendingIntent = PendingIntent.getActivity(
                    appContext,
                    RESTART_REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                    pendingIntent
                )
                true
            },
            terminateCurrentProcess = {
                Process.killProcess(Process.myPid())
            }
        ).scheduleAndTerminate()
    }
}
