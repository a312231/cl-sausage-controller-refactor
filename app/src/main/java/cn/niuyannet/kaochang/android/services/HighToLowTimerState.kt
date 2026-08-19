package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

internal data class HighToLowTimerState(
    val armed: Boolean = false,
    val anchorAtMs: Long = 0L
)

internal interface HighToLowTimerStateStore {
    fun load(): HighToLowTimerState
    fun saveDurable(state: HighToLowTimerState): Boolean
}

internal class SharedPreferenceHighToLowTimerStateStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_HIGH_TO_LOW_TIMER_STATE
) : HighToLowTimerStateStore {

    override fun load(): HighToLowTimerState = decode(
        PreferenceUtils.getStringPreference(file, key, null)
    )

    override fun saveDurable(state: HighToLowTimerState): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(state))

    companion object {
        private const val KEY_HIGH_TO_LOW_TIMER_STATE = "sp_high_to_low_timer_state_json"

        internal fun decode(raw: String?): HighToLowTimerState {
            if (raw.isNullOrBlank()) {
                return HighToLowTimerState()
            }
            val parsed = runCatching {
                JSON.parseObject(raw, HighToLowTimerState::class.java)
            }.getOrNull() ?: return HighToLowTimerState()
            return when {
                parsed.armed && parsed.anchorAtMs > 0L -> parsed
                !parsed.armed -> HighToLowTimerState()
                else -> HighToLowTimerState()
            }
        }
    }
}

internal class HighToLowTimerStateRepository(
    private val store: HighToLowTimerStateStore
) {
    fun disarmDurably(): Boolean = saveIfChanged(HighToLowTimerState())

    fun armDurably(nowMs: Long): Boolean {
        if (nowMs <= 0L) {
            return false
        }
        return saveIfChanged(HighToLowTimerState(armed = true, anchorAtMs = nowMs))
    }

    private fun saveIfChanged(next: HighToLowTimerState): Boolean {
        if (store.load() == next) {
            return true
        }
        return store.saveDurable(next)
    }
}
