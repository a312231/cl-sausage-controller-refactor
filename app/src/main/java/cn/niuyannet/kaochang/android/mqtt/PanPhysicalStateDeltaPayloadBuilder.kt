package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.SellablePanPhysicalStateProjection
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import java.security.MessageDigest

object PanPhysicalStateDeltaPayloadBuilder {
    const val PROJECTION_TYPE_PHYSICAL_STATE_V1 = "physical_state_v1"
    const val PROTOCOL_VERSION = 1

    fun build(
        deviceCode: String,
        panDeltaSeq: Long,
        eventId: String,
        baseSnapshotSequence: Long?,
        baseSnapshotHash: String?,
        allPans: List<KaoPan>,
        allStocks: List<KaoPanBox>,
        changedPans: List<KaoPan>,
        changedStocks: List<KaoPanBox>,
        reason: String,
        source: String,
        changedAt: Long,
        config: AppConfigBean = AppConfigBean()
    ): JSONObject {
        val fullStateHash = buildFullStateHash(allPans, allStocks)
        val sellablePositions = SellablePanPhysicalStateProjection.sellablePositions(allPans, config, changedAt)
        val sellableRulesSignature = SellablePanPhysicalStateProjection.rulesSignature(config)
        return orderedJson().apply {
            put("messageType", MqttProtocol.TYPE_PAN_STATE_DELTA)
            put("deviceCode", deviceCode)
            put("panDeltaSeq", panDeltaSeq)
            put("eventId", eventId)
            put("projectionType", PROJECTION_TYPE_PHYSICAL_STATE_V1)
            put("protocolVersion", PROTOCOL_VERSION)
            baseSnapshotSequence?.let { put("baseSnapshotSequence", it) }
            baseSnapshotHash?.let { put("baseSnapshotHash", it) }
            put("source", source)
            put("reason", reason)
            put("changedAt", changedAt)
            put("changedPans", pansToArray(changedPans))
            put("changedStocks", stocksToArray(changedStocks))
            put("fullStateHash", fullStateHash)
            put("sellablePositions", sellablePositions)
            put("sellableRulesSignature", sellableRulesSignature)
        }
    }

    fun buildFullStateHash(allPans: List<KaoPan>, allStocks: List<KaoPanBox>): String {
        val canonical = orderedJson().apply {
            put("kaoPanList", pansToArray(allPans))
            put("stockList", stocksToArray(allStocks))
        }
        return sha256(JSON.toJSONString(canonical))
    }

    private fun pansToArray(pans: List<KaoPan>): JSONArray {
        val array = JSONArray()
        pans.sortedBy { it.positionSn }.forEach { pan ->
            array.add(panToJson(pan))
        }
        return array
    }

    private fun stocksToArray(stocks: List<KaoPanBox>): JSONArray {
        val array = JSONArray()
        stocks.sortedBy { it.positionSn }.forEach { stock ->
            array.add(stockToJson(stock))
        }
        return array
    }

    private fun panToJson(pan: KaoPan): JSONObject =
        orderedJson().apply {
            put("id", pan.id)
            put("positionSn", pan.positionSn)
            put("positionRegion", pan.positionRegion)
            put("startTime", pan.startTime)
            put("bakingTime", pan.bakingTime)
            put("holdingTime", pan.holdingTime)
            put("closeTime", pan.closeTime)
            put("taste", tasteToJson(pan.taste))
            put("temperature", pan.temperature)
            put("status", pan.status)
            put("hasSausage", pan.isHasSausage)
            put("hasGrilling", pan.isHasGrilling)
            put("cmdValueMove", pan.cmdValueMove)
            put("cmdValueTake", pan.cmdValueTake)
            put("cmdStatusMove", pan.cmdStatusMove)
            put("cmdStatusTake", pan.cmdStatusTake)
            put("cmdValueDiscard", pan.cmdValueDiscard)
            put("cmdStatusDiscard", pan.cmdStatusDiscard)
        }

    private fun tasteToJson(taste: Taste?): JSONObject? {
        if (taste == null) {
            return null
        }
        return orderedJson().apply {
            put("tasteId", taste.tasteId)
            put("tasteCode", taste.tasteCode ?: "")
            put("tasteName", taste.tasteName ?: "")
            put("productId", taste.productId)
            put("productName", taste.productName ?: "")
        }
    }

    private fun stockToJson(stock: KaoPanBox): JSONObject =
        orderedJson().apply {
            put("id", stock.id)
            put("deviceId", stock.deviceId ?: "")
            put("productId", stock.productId ?: "")
            put("skuId", stock.skuId ?: "")
            put("positionSn", stock.positionSn)
            put("lastPositionSn", stock.lastPositionSn)
            put("num", stock.num)
            put("tasteCode", stock.tasteCode ?: "")
            put("tasteName", stock.tasteName ?: "")
            put("cmdValueTake", stock.cmdValueTake)
            put("isCheckStatus", stock.checkStatus ?: false)
        }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun orderedJson(): JSONObject = JSONObject(true)
}
