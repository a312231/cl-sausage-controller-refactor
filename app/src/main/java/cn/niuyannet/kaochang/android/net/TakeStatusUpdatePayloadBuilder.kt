package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

object TakeStatusUpdatePayloadBuilder {
    fun build(deviceCode: String, credential: String?, box: KaoPanBox): JSONObject {
        val stock = JSONObject().apply {
            put("positionSn", box.positionSn)
            put("cmdStatusTake", box.cmdStatusTake)
            put("takeStatusRevision", box.takeStatusRevision)
        }
        return JSONObject().apply {
            put("deviceCode", deviceCode)
            if (!credential.isNullOrBlank()) put("credentialNo", credential)
            put("takeStatusOnly", true)
            put("content", JSONArray().apply { add(stock) }.toJSONString())
        }
    }
}
