package cn.niuyannet.kaochang.android.net

import com.alibaba.fastjson.JSONObject

/**
 * 云端 HTTP 响应兼容工具。
 *
 * 旧后台返回 code=200；新重构后端返回 success=true/code=OK。这里集中判断，避免字符串 code 触发 fastjson 数字转换异常。
 */
object ApiResponseCompat {
    fun isSuccessful(json: JSONObject?): Boolean {
        if (json == null) {
            return false
        }
        if (json.getBoolean("success") == true) {
            return true
        }
        val code = json["code"]
        return when (code) {
            is Number -> code.toInt() == 200
            is String -> code == "200" || code.equals("OK", ignoreCase = true)
            else -> false
        }
    }

    fun codeText(json: JSONObject?): String {
        return json?.get("code")?.toString() ?: "null"
    }

    fun messageText(json: JSONObject?, fallback: String): String {
        return json?.getString("msg")
            ?: json?.getString("message")
            ?: fallback
    }
}
