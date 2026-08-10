package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceCodec
import cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceReadResult
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

    /**
     * 自动恢复场景必须区分“确实不存在”和“本地证据损坏”；保留 load() 的兼容行为给旧调用方。
     */
    fun readEvidence(): DurableEvidenceReadResult<ActionGroupSession> =
        DurableEvidenceCodec.read(
            raw = PreferenceUtils.getStringPreference(file, key, null),
            parser = { raw -> JSON.parseObject(raw, ActionGroupSession::class.java) },
            validator = ::isValid
        )

    private fun isValid(session: ActionGroupSession): Boolean =
        session.sessionId.isNotBlank() &&
            session.sourceNo > 0 &&
            session.resourceMask >= 0 &&
            session.startedAtMs >= 0L &&
            session.mechanicalClaimRevision >= 0L &&
            runCatching { session.type.name }.isSuccess

    override fun save(session: ActionGroupSession) {
        check(PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(session))) {
            "动作组会话检查点同步落盘失败：sessionId=${session.sessionId}，action=${session.currentAction}"
        }
    }

    override fun clear() {
        check(PreferenceUtils.saveStringPreferenceDurable(file, key, "")) {
            "动作组会话清理同步落盘失败"
        }
    }

    companion object {
        private const val KEY_ACTION_GROUP_SESSION = "sp_action_group_session_json"
        const val KEY_PAUSED_SUPPLEMENT_SESSION = "sp_paused_supplement_session_json"
    }
}
