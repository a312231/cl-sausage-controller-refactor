package cn.niuyannet.kaochang.android.net

import com.alibaba.fastjson.JSON

object ConsumableStateResponseParser {
    private const val TYPE_STICK = "STICK"
    private const val TYPE_WASTE = "WASTE"
    private const val UNAVAILABLE_TEXT = "耗材状态暂不可用"

    data class Result(
        val success: Boolean,
        val states: List<ConsumableStateView>,
        val reason: String = ""
    )

    data class ConsumableStateView(
        val consumableType: String,
        val capacity: Long?,
        val thresholdValue: Long?,
        val currentValue: Long?,
        val enabled: Long?,
        val updateTime: String?,
        val lastHandledAt: String?
    )

    fun parse(content: String?): Result {
        if (content.isNullOrBlank()) {
            return Result(false, emptyList(), "云端响应为空")
        }

        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            return Result(false, emptyList(), "云端响应不是有效JSON：${e.message ?: "unknown"}")
        } ?: return Result(false, emptyList(), "云端响应JSON为空")

        if (!ApiResponseCompat.isSuccessful(json)) {
            val code = ApiResponseCompat.codeText(json)
            val message = ApiResponseCompat.messageText(json, "")
            return Result(false, emptyList(), "云端业务返回失败：code=$code，message=$message")
        }

        val data = json.getJSONArray("data") ?: return Result(false, emptyList(), "云端耗材状态为空")
        val states = data.mapNotNull { item ->
            val state = item as? com.alibaba.fastjson.JSONObject ?: return@mapNotNull null
            ConsumableStateView(
                consumableType = state.getString("consumableType") ?: return@mapNotNull null,
                capacity = state.getLong("capacity"),
                thresholdValue = state.getLong("thresholdValue"),
                currentValue = state.getLong("currentValue"),
                enabled = state.getLong("enabled"),
                updateTime = state.getString("updateTime"),
                lastHandledAt = state.getString("lastHandledAt")
            )
        }
        return Result(states.isNotEmpty(), states, if (states.isEmpty()) "云端耗材状态无有效行" else "")
    }

    fun buildDisplayText(states: List<ConsumableStateView>): String {
        if (states.isEmpty()) {
            return UNAVAILABLE_TEXT
        }
        val stick = states.firstOrNull { it.consumableType == TYPE_STICK }
        val waste = states.firstOrNull { it.consumableType == TYPE_WASTE }
        val updateTime = latestUpdateTime(stick, waste)

        return "${formatStick(stick)}  ${formatWaste(waste)}  ${formatUpdateTime(updateTime)}"
    }

    private fun formatStick(state: ConsumableStateView?): String {
        if (state == null) {
            return "竹签 --"
        }
        val value = state.currentValue?.toString() ?: "--"
        val quantity = if (state.capacity != null) "$value/${state.capacity}" else value
        return "竹签 $quantity（${warningText(state.enabled)}）"
    }

    private fun formatWaste(state: ConsumableStateView?): String {
        if (state == null) {
            return "弃肠 --"
        }
        val value = state.currentValue?.toString() ?: "--"
        val quantity = if (state.thresholdValue != null) "$value/${state.thresholdValue}" else value
        val detail = if (state.thresholdValue != null) {
            warningText(state.enabled)
        } else {
            "阈值未设，${warningText(state.enabled)}"
        }
        return "弃肠 $quantity（$detail）"
    }

    private fun warningText(enabled: Long?): String {
        return if (enabled == 1L) "预警启用" else "预警停用"
    }

    private fun latestUpdateTime(stick: ConsumableStateView?, waste: ConsumableStateView?): String? {
        return listOfNotNull(stick?.updateTime, waste?.updateTime).maxOrNull()
    }

    private fun formatUpdateTime(updateTime: String?): String {
        if (updateTime.isNullOrBlank()) {
            return "尚未同步"
        }
        return if (updateTime.length >= 16) {
            "更新 ${updateTime.substring(11, 16)}"
        } else {
            "更新 $updateTime"
        }
    }
}
