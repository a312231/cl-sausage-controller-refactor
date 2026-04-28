package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

class SharedPreferenceActionGroupSessionStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_ACTION_GROUP_SESSION
) : ActionGroupSessionStore {

    override fun load(): ActionGroupSession? {
        val raw = PreferenceUtils.getStringPreference(file, key, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw, ActionGroupSession::class.java)
        } catch (e: Exception) {
            LogUtils.w("【300/400动作组】读取本地动作组会话失败，已按无会话处理：raw=$raw", e)
            null
        }
    }

    override fun save(session: ActionGroupSession) {
        PreferenceUtils.saveStringPreference(file, key, JSON.toJSONString(session))
    }

    override fun clear() {
        PreferenceUtils.saveStringPreference(file, key, "")
    }

    companion object {
        private const val KEY_ACTION_GROUP_SESSION = "sp_action_group_session_json"
    }
}
