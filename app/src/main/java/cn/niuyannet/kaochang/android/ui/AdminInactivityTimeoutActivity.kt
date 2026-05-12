package cn.niuyannet.kaochang.android.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.WeakHashMap

open class AdminInactivityTimeoutActivity : AppCompatActivity() {
    companion object {
        const val ADMIN_IDLE_TIMEOUT_MS = 120_000L
        const val ADMIN_BUSY_STALE_MS = 10 * 60_000L
    }

    private val adminIdleHandler = Handler(Looper.getMainLooper())
    private val adminBusyState = AdminBusyState(ADMIN_BUSY_STALE_MS) {
        SystemClock.elapsedRealtime()
    }
    private var adminTimerActive = false
    private var returningHome = false
    private val watchedEditTexts = WeakHashMap<EditText, TextWatcher>()

    private val adminIdleTimeoutRunnable = Runnable {
        handleAdminIdleTimeout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogUtils.d(
            "【管理员超时】初始化管理员后台空闲计时：" +
                "adminIdleTimeout（管理员后台空闲超时）=${ADMIN_IDLE_TIMEOUT_MS}ms，" +
                "adminBusyStale（管理员后台忙碌兜底超时）=${ADMIN_BUSY_STALE_MS}ms"
        )
    }

    override fun onResume() {
        super.onResume()
        adminTimerActive = true
        resetAdminIdleTimer("页面恢复")
    }

    override fun onPause() {
        adminTimerActive = false
        adminIdleHandler.removeCallbacks(adminIdleTimeoutRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        adminIdleHandler.removeCallbacks(adminIdleTimeoutRunnable)
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetAdminIdleTimer("触摸事件")
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetAdminIdleTimer("按键事件")
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        resetAdminIdleTimer("外接输入事件")
        return super.dispatchGenericMotionEvent(ev)
    }

    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun resetAdminIdleTimer(reason: String = "用户操作") {
        if (!adminTimerActive || returningHome) {
            return
        }
        adminIdleHandler.removeCallbacks(adminIdleTimeoutRunnable)
        adminIdleHandler.postDelayed(adminIdleTimeoutRunnable, ADMIN_IDLE_TIMEOUT_MS)
    }

    fun beginAdminBusy(reason: String): AdminBusyState.Token {
        val token = adminBusyState.begin(reason)
        resetAdminIdleTimer("进入忙碌保护:$reason")
        LogUtils.i(
            "【管理员超时】进入后台忙碌保护：" +
                "reason=$reason，" +
                "adminBusyCount（后台关键操作计数）=${adminBusyState.activeCount()}"
        )
        return token
    }

    fun endAdminBusy(token: AdminBusyState.Token?) {
        if (token == null) {
            return
        }
        adminBusyState.finish(token)
        LogUtils.i(
            "【管理员超时】退出后台忙碌保护：" +
                "reason=${token.reason}，" +
                "adminBusyCount（后台关键操作计数）=${adminBusyState.activeCount()}"
        )
        resetAdminIdleTimer("忙碌保护结束:${token.reason}")
    }

    fun registerAdminTextInputs(root: View?) {
        if (root == null) {
            return
        }
        when (root) {
            is EditText -> registerAdminEditText(root)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    registerAdminTextInputs(root.getChildAt(index))
                }
            }
        }
    }

    private fun registerAdminEditText(editText: EditText) {
        if (watchedEditTexts.containsKey(editText)) {
            return
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                resetAdminIdleTimer("文本输入")
            }
        }
        editText.addTextChangedListener(watcher)
        watchedEditTexts[editText] = watcher
    }

    private fun handleAdminIdleTimeout() {
        if (returningHome || !adminTimerActive) {
            return
        }
        if (isAdminBusy()) {
            LogUtils.i(
                "【管理员超时】到达空闲超时但后台关键操作仍在执行，延后返回首页：" +
                    "adminBusyCount（后台关键操作计数）=${adminBusyState.activeCount()}，" +
                    "activeReasons（忙碌原因）=${adminBusyState.activeReasons().joinToString("/")}"
            )
            resetAdminIdleTimer("后台关键操作仍在执行")
            return
        }
        returnToHomeByAdminIdleTimeout()
    }

    private fun isAdminBusy(): Boolean {
        return adminBusyState.isBusy { token, elapsedMs ->
            LogUtils.w(
                "【管理员超时】后台忙碌保护超过兜底时间，已自动清理：" +
                    "reason=${token.reason}，elapsedMs（持续毫秒）=$elapsedMs，" +
                    "adminBusyStale（管理员后台忙碌兜底超时）=${ADMIN_BUSY_STALE_MS}ms"
            )
        }
    }

    protected open fun returnToHomeByAdminIdleTimeout() {
        returningHome = true
        adminIdleHandler.removeCallbacks(adminIdleTimeoutRunnable)
        Toast.makeText(this, "管理员界面已超时，自动返回首页", Toast.LENGTH_SHORT).show()
        LogUtils.i(
            "【管理员超时】管理员后台无操作超时，自动返回首页：" +
                "adminIdleTimeout（管理员后台空闲超时）=${ADMIN_IDLE_TIMEOUT_MS}ms，" +
                "target=MainActivity（首页视频/二维码界面）"
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
}
