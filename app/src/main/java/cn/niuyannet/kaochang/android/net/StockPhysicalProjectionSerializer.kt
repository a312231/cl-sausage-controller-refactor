package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import com.alibaba.fastjson.JSON

object StockPhysicalProjectionSerializer {
    fun projectForTargets(boxes: List<KaoPanBox>, targetPositions: Set<Int>): List<KaoPanBox> =
        boxes.filter { it.positionSn in targetPositions }

    fun serialize(stocks: List<KaoPanBox>): String {
        val projection = JSON.parseArray(JSON.toJSONString(stocks))
        projection.forEach { value ->
            val stock = value as? com.alibaba.fastjson.JSONObject ?: return@forEach
            stock.remove("cmdStatusTake")
            stock.remove("takeStatusRevision")
        }
        return projection.toJSONString()
    }

    fun applyInventoryFields(
        current: List<KaoPanBox>,
        proposed: List<KaoPanBox>,
        targetPositions: Set<Int>
    ): Boolean {
        val proposedByPosition = proposed.associateBy { it.positionSn }
        var changed = false
        current.filter { it.positionSn in targetPositions }.forEach { target ->
            val source = proposedByPosition[target.positionSn] ?: return@forEach
            target.productId = source.productId
            target.tasteCode = source.tasteCode
            target.tasteName = source.tasteName
            target.checkStatus = source.checkStatus
            target.num = source.num
            changed = true
        }
        return changed
    }
}
