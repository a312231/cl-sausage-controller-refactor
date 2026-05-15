package cn.niuyannet.kaochang.android.services

object AlgorithmStateLogFormatter {

    fun stateName(
        modeType: Int,
        transitionMode: Int,
        highConcurrencyResidualDrain: Int,
        schedulingEnabled: Boolean
    ): String {
        if (!schedulingEnabled) {
            return "算法关闭/暂停调度"
        }
        if (highConcurrencyResidualDrain == 1) {
            return "高并发残留消化态"
        }
        return when (transitionMode) {
            1 -> "低转高过渡态"
            2 -> "高转低过渡态"
            else -> when (modeType) {
                1 -> "低并发稳态"
                2 -> "高并发稳态"
                else -> "未知算法状态"
            }
        }
    }

    fun buildChangeLog(
        previousStateName: String?,
        modeType: Int,
        transitionMode: Int,
        highConcurrencyResidualDrain: Int,
        onlineStatus: Int,
        supplyStatus: Int,
        homeQrHidden: Int,
        inspectionMode: Int,
        moveStatus: Int,
        errorStatus: Int,
        isEnable: Int,
        schedulingEnabled: Boolean
    ): String {
        val currentStateName = stateName(
            modeType = modeType,
            transitionMode = transitionMode,
            highConcurrencyResidualDrain = highConcurrencyResidualDrain,
            schedulingEnabled = schedulingEnabled
        )
        val headline = when {
            previousStateName.isNullOrBlank() -> "首次记录：当前状态=$currentStateName"
            previousStateName == currentStateName -> "状态字段变化：$currentStateName"
            else -> "状态变化：$previousStateName -> $currentStateName"
        }
        val reason = when {
            previousStateName.isNullOrBlank() -> "首次状态快照"
            previousStateName == currentStateName -> "运行字段变化"
            else -> stateChangeReason(currentStateName)
        }
        return "【烤制算法】$headline，reason=$reason，" +
            "modeType（并发模式）=${modeText(modeType)}，" +
            "transitionMode（并发切换过渡态）=${transitionText(transitionMode)}，" +
            "highConcurrencyResidualDrain（高并发残留消化态）=${residualDrainText(highConcurrencyResidualDrain)}，" +
            "isEnable（烤肠算法开关）=${isEnableText(isEnable)}，" +
            "onlineStatus（设备营业状态）=${onlineStatusText(onlineStatus)}，" +
            "supplyStatus（补货状态）=${supplyStatusText(supplyStatus)}，" +
            "homeQrHidden（首页二维码隐藏）=${homeQrHiddenText(homeQrHidden)}，" +
            "inspectionMode（检修模式）=${inspectionModeText(inspectionMode)}，" +
            "moveStatus（机械动作状态）=${moveStatusText(moveStatus)}，" +
            "errorStatus（设备故障状态）=${errorStatusText(errorStatus)}，" +
            "schedulingEnabled=$schedulingEnabled"
    }

    private fun modeText(value: Int): String = when (value) {
        1 -> "1（低并发）"
        2 -> "2（高并发）"
        else -> "$value（未知）"
    }

    private fun stateChangeReason(currentStateName: String): String = when (currentStateName) {
        "低转高过渡态" -> "低转高触发"
        "高转低过渡态" -> "高转低触发"
        "高并发稳态" -> "进入高并发稳态"
        "低并发稳态" -> "进入低并发稳态"
        "高并发残留消化态" -> "进入高并发残留消化"
        "算法关闭/暂停调度" -> "调度暂停"
        else -> "状态切换"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0（无过渡态）"
        1 -> "1（低转高过渡）"
        2 -> "2（高转低过渡）"
        else -> "$value（未知）"
    }

    private fun residualDrainText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（高并发残留消化中）"
        else -> "$value（未知）"
    }

    private fun isEnableText(value: Int): String = when (value) {
        0 -> "0（关闭）"
        1 -> "1（开启）"
        else -> "$value（未知）"
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0（离线）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    private fun supplyStatusText(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（售罄/待补货）"
        else -> "$value（未知）"
    }

    private fun homeQrHiddenText(value: Int): String = when (value) {
        0 -> "0（显示首页二维码）"
        1 -> "1（隐藏首页二维码）"
        else -> "$value（未知）"
    }

    private fun inspectionModeText(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（检修中）"
        else -> "$value（未知）"
    }

    private fun moveStatusText(value: Int): String = when (value) {
        0 -> "0（机械臂空闲）"
        1 -> "1（补肠中）"
        2 -> "2（夹取/出餐中）"
        3 -> "3（搬运中）"
        4 -> "4（丢弃中）"
        else -> "$value（未知）"
    }

    private fun errorStatusText(value: Int): String = when (value) {
        0 -> "0（正常）"
        1 -> "1（识别/流程错误）"
        2 -> "2（通信/硬件级故障）"
        else -> "$value（未知）"
    }
}
