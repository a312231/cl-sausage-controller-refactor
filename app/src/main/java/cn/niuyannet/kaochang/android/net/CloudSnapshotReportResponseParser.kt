package cn.niuyannet.kaochang.android.net

import com.alibaba.fastjson.JSON

object CloudSnapshotReportResponseParser {
    data class Result(
        val accepted: Boolean,
        val reason: String
    )

    fun parse(content: String?): Result {
        if (content.isNullOrBlank()) {
            return Result(false, "云端响应为空，无法确认快照已接收")
        }

        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            return Result(false, "云端响应不是有效JSON：${e.message ?: "unknown"}")
        } ?: return Result(false, "云端响应JSON为空")

        val code = json.getIntValue("code")
        val message = json.getString("msg") ?: json.getString("message") ?: ""
        val data = json.getJSONObject("data")
        val ignored = data?.getBoolean("ignored") == true
        val ignoredReason = data?.getString("reason").orEmpty()
        if (code == 200 && ignored && ignoredReason == "same_hash") {
            return Result(true, "云端已确认相同快照：reason=$ignoredReason，message=$message")
        }
        val messageIndicatesIgnoredSnapshot =
            message.contains("忽略") ||
                message.contains("旧快照") ||
                message.contains("old snapshot", ignoreCase = true) ||
                message.contains("ignored", ignoreCase = true) ||
                (
                    message.contains("incomingSequence", ignoreCase = true) &&
                        message.contains("currentSequence", ignoreCase = true)
                    )

        if (code != 200) {
            return Result(false, "云端业务返回失败：code=$code，message=$message")
        }
        if (messageIndicatesIgnoredSnapshot) {
            return Result(false, "云端已忽略旧快照：message=$message")
        }
        return Result(true, "云端已接收快照：code=$code，message=$message")
    }
}
