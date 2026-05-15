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
        return token
    }

    fun endAdminBusy(token: AdminBusyState.Token?) {
        if (token == null) {
            return
        }
        adminBusyState.finish(token)
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
            LogUtils.d(
                "管理员无操作超时暂缓退出：后台关键操作仍在执行，" +
                    "关键操作数量=${adminBusyState.activeCount()}，忙碌原因=${adminBusyState.activeReasons().joinToString("/")}"
            )
            resetAdminIdleTimer("后台关键操作仍在执行")
            return
        }
        returnToHomeByAdminIdleTimeout()
    }

    private fun isAdminBusy(): Boolean {
        return adminBusyState.isBusy { token, elapsedMs ->
            LogUtils.d("清理管理员后台忙碌保护：忙碌原因=${token.reason}，持续时间=${elapsedMs}ms，兜底超时=${ADMIN_BUSY_STALE_MS}ms")
        }
    }

    protected open fun returnToHomeByAdminIdleTimeout() {
        returningHome = true
        adminIdleHandler.removeCallbacks(adminIdleTimeoutRunnable)
        Toast.makeText(this, "管理员界面已超时，自动返回首页", Toast.LENGTH_SHORT).show()
        AdminOperationLogger.i(
            action = "管理员无操作超时",
            page = AdminOperationLogger.PAGE_SETTING,
            result = AdminOperationLogger.RESULT_AUTO_EXIT,
            detail = "超时时间=${ADMIN_IDLE_TIMEOUT_MS / 1000}秒，返回页面=首页"
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }
}
