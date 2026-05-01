package cn.niuyannet.kaochang.android.ui

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

internal object OrderProcessingProgressPolicy {
    const val INITIAL_CURRENT_COUNT = 1

    fun initialProgressText(orderMessage: String): String {
        return "$INITIAL_CURRENT_COUNT/${initialTotalCount(orderMessage)}"
    }

    fun initialTotalCount(orderMessage: String): Int {
        return try {
            val json = JSON.parseObject(orderMessage)
            val goodsItems = parseGoodsItems(json) ?: return 1
            val total = goodsItems.sumOf { item ->
                (item as? JSONObject)?.getIntValue("quantity") ?: 0
            }
            total.coerceAtLeast(1)
        } catch (_: Exception) {
            1
        }
    }

    private fun parseGoodsItems(json: JSONObject): JSONArray? {
        return when (val rawGoodsItems = json["goods_items"]) {
            is JSONArray -> rawGoodsItems
            is String -> JSON.parseArray(rawGoodsItems)
            else -> null
        }
    }
}
