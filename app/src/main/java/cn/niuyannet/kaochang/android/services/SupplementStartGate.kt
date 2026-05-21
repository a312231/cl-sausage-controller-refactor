package cn.niuyannet.kaochang.android.services

data class SupplementStartGateDecision(
    val canStart: Boolean,
    val reason: String
)

object SupplementStartGate {
    fun evaluate(
        onlineStatus: Int,
        errorStatus: Int,
        isEnable: Int
    ): SupplementStartGateDecision {
        return when {
            onlineStatus != 1 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：onlineStatus（设备营业状态）=${onlineStatusText(onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
                    "isEnable（烤肠算法开关）=${isEnableText(isEnable)}"
            )
            errorStatus != 0 -> SupplementStartGateDecision(
                canStart = false,
                reason = "补肠启动被拒绝：onlineStatus（设备营业状态）=${onlineStatusText(onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
                    "isEnable（烤肠算法开关）=${isEnableText(isEnable)}"
            )
            else -> SupplementStartGateDecision(
                canStart = true,
                reason = "允许启动补肠：onlineStatus（设备营业状态）=${onlineStatusText(onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
                    "isEnable（烤肠算法开关）=${isEnableText(isEnable)}"
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
}
