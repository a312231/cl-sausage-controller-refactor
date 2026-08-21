package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

class SharedPreferenceActionGroupOrderEventStore(
    private val file: String = AppConfig.Sp_File_URL,
    private val key: String = KEY_PENDING_ACTION_GROUP_ORDER_EVENT
) : ActionGroupOrderEventStore {

    override fun load(): PendingActionGroupOrderEvent? {
        val raw = PreferenceUtils.getStringPreference(file, key, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw, PendingActionGroupOrderEvent::class.java)
        } catch (e: Exception) {
            LogUtils.w("【300/400动作组】读取待补偿订单事件失败，已按无待补偿事件处理：raw=$raw", e)
            null
        }
    }

    override fun save(event: PendingActionGroupOrderEvent): Boolean =
        PreferenceUtils.saveStringPreferenceDurable(file, key, JSON.toJSONString(event))

    override fun clear() {
        PreferenceUtils.saveStringPreference(file, key, "")
    }

    companion object {
        private const val KEY_PENDING_ACTION_GROUP_ORDER_EVENT = "sp_pending_action_group_order_event_json"
    }
}
