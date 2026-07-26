package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import com.alibaba.fastjson.JSON

data class TakeStatusUpdateResponse(
    val confirmed: Boolean,
    val authoritativeStock: KaoPanBox?,
    val reason: String
)

object TakeStatusUpdateResponseParser {
    fun parse(content: String?): TakeStatusUpdateResponse {
        return runCatching {
            val root = JSON.parseObject(content)
            val data = root.getJSONObject("data")
            val stock = data?.getJSONArray("stocks")?.getJSONObject(0)?.let { item ->
                KaoPanBox().apply {
                    positionSn = item.getIntValue("positionSn")
                    cmdStatusTake = item.getIntValue("cmdStatusTake")
                    takeStatusRevision = item.getLongValue("takeStatusRevision")
                }
            }
            TakeStatusUpdateResponse(
                confirmed = ApiResponseCompat.isSuccessful(root) && data?.getBooleanValue("accepted") == true,
                authoritativeStock = stock,
                reason = data?.getString("reason") ?: ApiResponseCompat.codeText(root)
            )
        }.getOrElse {
            TakeStatusUpdateResponse(false, null, "INVALID_RESPONSE")
        }
    }
}
