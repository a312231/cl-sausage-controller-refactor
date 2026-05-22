package cn.niuyannet.kaochang.android.services

object LowTrafficDecisionLogFormatter {
    fun lowInitializationDecision(
        frontEmpty: Int,
        frontCapacity: Int,
        heatEmpty: Int,
        heatCapacity: Int,
        schedulingEnabled: Boolean,
        boxSupplyAvailable: Boolean,
        errorStatus: Int,
        isBeforeCloseWindow: Boolean
    ): String {
        val totalEmpty = frontEmpty + heatEmpty
        val totalCapacity = frontCapacity + heatCapacity
        val allLowTrafficPansEmpty = totalEmpty == totalCapacity
        val reason = when {
            !allLowTrafficPansEmpty -> "本地烤盘未全空"
            !schedulingEnabled -> "调度未开启"
            !boxSupplyAvailable -> "无可用烤肠箱库存"
            errorStatus != 0 -> "设备故障状态非正常"
            isBeforeCloseWindow -> "处于停业前收口窗口"
            else -> "满足低并发初始化条件"
        }
        val action = if (allLowTrafficPansEmpty &&
            schedulingEnabled &&
            boxSupplyAvailable &&
            errorStatus == 0 &&
            !isBeforeCloseWindow
        ) {
            "本轮触发低并发初始化"
        } else {
            "本轮未触发低并发初始化"
        }
        return "【低并发调度】$action：原因=$reason，" +
            "1~9空位=$frontEmpty/$frontCapacity，" +
            "13~18空位=$heatEmpty/$heatCapacity，" +
            "总空位=$totalEmpty/$totalCapacity，" +
            "boxSupplyAvailable（烤肠箱可用库存）=$boxSupplyAvailable，" +
            "schedulingEnabled（调度允许）=$schedulingEnabled，" +
            "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
            "isBeforeCloseWindow（停业前收口窗口）=$isBeforeCloseWindow"
    }

    private fun errorStatusText(value: Int): String = when (value) {
        0 -> "0（正常）"
        else -> "$value（故障）"
    }
}
