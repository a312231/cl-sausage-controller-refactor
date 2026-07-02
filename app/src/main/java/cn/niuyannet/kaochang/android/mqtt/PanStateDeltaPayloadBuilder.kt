package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.services.KaoPanCloudSyncReporter
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

object PanStateDeltaPayloadBuilder {
    const val PROJECTION_TYPE_SELLABLE_PANS_V1 = "sellable_pans_v1"
    const val PROTOCOL_VERSION = 1

    fun build(
        deviceCode: String,
        panDeltaSeq: Long,
        eventId: String,
        before: KaoPanCloudSyncReporter.SellableSnapshot,
        after: KaoPanCloudSyncReporter.SellableSnapshot,
        reason: String,
        source: String,
        changedAt: Long
    ): JSONObject {
        val payload = JSONObject()
        payload["messageType"] = MqttProtocol.TYPE_PAN_STATE_DELTA
        payload["deviceCode"] = deviceCode
        payload["panDeltaSeq"] = panDeltaSeq
        payload["eventId"] = eventId
        payload["projectionType"] = PROJECTION_TYPE_SELLABLE_PANS_V1
        payload["protocolVersion"] = PROTOCOL_VERSION
        payload["source"] = source
        payload["reason"] = reason
        payload["changedAt"] = changedAt
        payload["beforeSellablePans"] = toArray(before)
        payload["afterSellablePans"] = toArray(after)
        payload["changedPans"] = changedPans(before, after)
        return payload
    }

    private fun toArray(snapshot: KaoPanCloudSyncReporter.SellableSnapshot): JSONArray {
        val array = JSONArray()
        snapshot.sellablePans.forEach { pan ->
            array.add(JSONObject().apply {
                put("positionSn", pan.positionSn)
                put("tasteCode", pan.tasteCode ?: "")
                pan.productId?.let { put("productId", it) }
            })
        }
        return array
    }

    private fun changedPans(
        before: KaoPanCloudSyncReporter.SellableSnapshot,
        after: KaoPanCloudSyncReporter.SellableSnapshot
    ): JSONArray {
        val beforeByPosition = before.sellablePans.associateBy { it.positionSn }
        val afterByPosition = after.sellablePans.associateBy { it.positionSn }
        val changedPositions = (beforeByPosition.keys + afterByPosition.keys).sorted().filter { position ->
            beforeByPosition[position] != afterByPosition[position]
        }
        val array = JSONArray()
        changedPositions.forEach { position ->
            val beforePan = beforeByPosition[position]
            val afterPan = afterByPosition[position]
            array.add(JSONObject().apply {
                put("positionSn", position)
                put("before", beforePan?.let { panToJson(it) })
                put("after", afterPan?.let { panToJson(it) })
            })
        }
        return array
    }

    private fun panToJson(pan: KaoPanCloudSyncReporter.SellablePan): JSONObject =
        JSONObject().apply {
            put("positionSn", pan.positionSn)
            put("tasteCode", pan.tasteCode ?: "")
            pan.productId?.let { put("productId", it) }
        }
}
