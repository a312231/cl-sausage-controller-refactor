package cn.niuyannet.kaochang.android.services

data class SupplementStartGateDecision(
    val canStart: Boolean,
    val reason: String
)

object SupplementStartGate {
    fun evaluate(
        onlineStatus: Int,
        errorStatus: Int,
        isEnable: Int,
        faultHeatPauseActive: Int
    ): SupplementStartGateDecision {
        val stateSummary = "onlineStatus（设备营业状态）=${onlineStatusText(onlineStatus)}，" +
            "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
            "isEnable（烤肠算法开关）=${isEnableText(isEnable)}，" +
            "faultHeatPauseActive（故障停热暂停）=${pauseText(faultHeatPauseActive)}"
        return when {
            onlineStatus != 1 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：$stateSummary"
            )
            errorStatus != 0 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：$stateSummary"
            )
            isEnable != 1 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：$stateSummary"
            )
            faultHeatPauseActive != 0 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：$stateSummary"
            )
            else -> SupplementStartGateDecision(
                canStart = true,
                reason = "允许启动补肠：$stateSummary"
            )
        }
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0（待投运）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    private fun errorStatusText(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（识别/流程错误）"
        2 -> "2（通信/硬件级故障）"
        else -> "$value（未知故障）"
    }

    private fun isEnableText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    private fun pauseText(value: Int): String = when (value) {
        0 -> "0（未暂停）"
        1 -> "1（暂停中）"
        else -> "$value（未知）"
    }
}
