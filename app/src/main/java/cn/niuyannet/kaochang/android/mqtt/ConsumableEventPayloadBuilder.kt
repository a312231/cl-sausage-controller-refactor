package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSONObject
import java.util.UUID

data class ConsumableEvent(
    val deviceCode: String,
    val consumableType: String,
    val eventType: String,
    val deltaValue: Long,
    val consumableEventEpoch: String,
    val consumableEventSeq: Long,
    val sourceEventKey: String,
    val source: String,
    val detail: String?,
    val eventTime: Long
)

interface ConsumableEventSeqStore {
    fun getOrCreateEpoch(): String
    fun nextSeq(): Long
}

object PreferenceConsumableEventSeqStore : ConsumableEventSeqStore {
    private const val KEY_EPOCH = "sp_consumable_event_epoch"
    private const val KEY_SEQ = "sp_consumable_event_seq"

    @Synchronized
    override fun getOrCreateEpoch(): String {
        val existing = PreferenceUtils.getStringPreference(AppConfig.Sp_File_URL, KEY_EPOCH, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val created = UUID.randomUUID().toString()
        PreferenceUtils.saveStringPreference(AppConfig.Sp_File_URL, KEY_EPOCH, created)
        return created
    }

    @Synchronized
    override fun nextSeq(): Long {
        val current = PreferenceUtils.getLongPreference(AppConfig.Sp_File_URL, KEY_SEQ, 0L)
        val next = current + 1L
        PreferenceUtils.saveLongPreference(AppConfig.Sp_File_URL, KEY_SEQ, next)
        return next
    }
}

object ConsumableEventPayloadBuilder {
    @Synchronized
    fun nextEvent(
        store: ConsumableEventSeqStore = PreferenceConsumableEventSeqStore,
        deviceCode: String = AppConfig.getDeviceId(),
        consumableType: String,
        eventType: String,
        deltaValue: Long,
        source: String,
        detail: String? = null,
        eventTime: Long = System.currentTimeMillis()
    ): ConsumableEvent {
        val epoch = store.getOrCreateEpoch()
        val seq = store.nextSeq()
        return ConsumableEvent(
            deviceCode = deviceCode,
            consumableType = consumableType,
            eventType = eventType,
            deltaValue = deltaValue,
            consumableEventEpoch = epoch,
            consumableEventSeq = seq,
            sourceEventKey = "$deviceCode:$epoch:$seq",
            source = source,
            detail = detail,
            eventTime = eventTime
        )
    }

    fun buildPayload(event: ConsumableEvent): JSONObject {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_CONSUMABLE_EVENT
        payload["deviceCode"] = event.deviceCode
        payload["consumableType"] = event.consumableType
        payload["eventType"] = event.eventType
        payload["deltaValue"] = event.deltaValue
        payload["consumableEventEpoch"] = event.consumableEventEpoch
        payload["consumableEventSeq"] = event.consumableEventSeq
        payload["sourceEventKey"] = event.sourceEventKey
        payload["source"] = event.source
        event.detail?.takeIf { it.isNotBlank() }?.let { payload["detail"] = it }
        payload["eventTime"] = event.eventTime
        return payload
    }

    fun shouldReportBusinessCloseWaste(residualCount: Int, success: Boolean): Boolean {
        return success && residualCount > 0
    }
}
