package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.net.ApiResponseCompat
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object ManualRecoveryCloudHttpConfirmation {

    fun evaluate(
        updateTransportSucceeded: Boolean,
        updateResponse: String?,
        verifyTransportSucceeded: Boolean,
        verifyResponse: String?,
        targetOnlineStatus: Int,
        localStateStillRecovered: Boolean
    ): ManualRecoveryCloudConfirmationPolicy.Decision {
        if (!localStateStillRecovered) {
            return ManualRecoveryCloudConfirmationPolicy.Decision(
                false,
                "HTTP补偿期间本机状态再次变化"
            )
        }

        val updateResult = parseResponse(updateTransportSucceeded, updateResponse)
        val verifyResult = parseResponse(verifyTransportSucceeded, verifyResponse)
        if (!verifyResult.success) {
            return ManualRecoveryCloudConfirmationPolicy.Decision(
                false,
                "HTTP补偿后读取后台状态失败：${verifyResult.reason}；更新结果=${updateResult.reason}"
            )
        }

        val data = verifyResult.json?.getJSONObject("data")
        val remoteOnlineStatus = intValue(data, "onlineStatus")
        val remoteErrorStatus = intValue(data, "errorStatus")
        val remoteIsEnable = intValue(data, "isEnable")
        return if (
            remoteOnlineStatus == targetOnlineStatus &&
            remoteErrorStatus == 0 &&
            remoteIsEnable == 0
        ) {
            ManualRecoveryCloudConfirmationPolicy.Decision(
                true,
                "后台已通过HTTP回读确认恢复"
            )
        } else {
            ManualRecoveryCloudConfirmationPolicy.Decision(
                false,
                "后台仍未恢复：onlineStatus=$remoteOnlineStatus，errorStatus=$remoteErrorStatus，" +
                    "isEnable=$remoteIsEnable；" +
                    "更新结果=${updateResult.reason}"
            )
        }
    }

    private data class ParsedResponse(
        val success: Boolean,
        val reason: String,
        val json: JSONObject?
    )

    private fun parseResponse(transportSucceeded: Boolean, content: String?): ParsedResponse {
        if (!transportSucceeded) {
            return ParsedResponse(false, "网络请求失败", null)
        }
        if (content.isNullOrBlank()) {
            return ParsedResponse(false, "云端响应为空", null)
        }
        val json = runCatching { JSON.parseObject(content) }.getOrNull()
            ?: return ParsedResponse(false, "云端响应不是有效JSON", null)
        if (!ApiResponseCompat.isSuccessful(json)) {
            return ParsedResponse(
                false,
                "code=${ApiResponseCompat.codeText(json)}，message=${ApiResponseCompat.messageText(json, "未知错误")}",
                json
            )
        }
        return ParsedResponse(
            true,
            "code=${ApiResponseCompat.codeText(json)}，message=${ApiResponseCompat.messageText(json, "成功")}",
            json
        )
    }

    private fun intValue(json: JSONObject?, key: String): Int? {
        return when (val value = json?.get(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
