package cn.niuyannet.kaochang.android.fragments

object MaintenanceRuntimeStatusDisplayPolicy {
    enum class Level {
        NORMAL,
        WARNING,
        ERROR
    }

    sealed class CloudState {
        object Loading : CloudState()
        object Unavailable : CloudState()
        data class Available(
            val onlineStatus: Int,
            val errorStatus: Int
        ) : CloudState()
    }

    data class Display(
        val faultText: String,
        val onlineText: String,
        val recoveryButtonText: String,
        val faultLevel: Level,
        val onlineLevel: Level
    )

    fun build(
        localOnlineStatus: Int,
        localErrorStatus: Int,
        cloudState: CloudState,
        needsLocalRecovery: Boolean,
        canRetryCloudOnly: Boolean
    ): Display {
        val faultText: String
        val onlineText: String
        val faultLevel: Level
        val onlineLevel: Level

        when (cloudState) {
            is CloudState.Available -> {
                faultText = if (localErrorStatus == cloudState.errorStatus) {
                    "故障状态：${errorStatusLabel(localErrorStatus)}"
                } else {
                    "故障状态不同步：本机${errorStatusLabel(localErrorStatus)} / " +
                        "后台${errorStatusLabel(cloudState.errorStatus)}"
                }
                onlineText = if (localOnlineStatus == cloudState.onlineStatus) {
                    "营业状态：${onlineStatusLabel(localOnlineStatus)}"
                } else {
                    "营业状态不同步：本机${onlineStatusLabel(localOnlineStatus)} / " +
                        "后台${onlineStatusLabel(cloudState.onlineStatus)}"
                }
                faultLevel = if (localErrorStatus == cloudState.errorStatus) {
                    errorLevel(localErrorStatus)
                } else {
                    Level.ERROR
                }
                onlineLevel = if (localOnlineStatus == cloudState.onlineStatus) {
                    onlineLevel(localOnlineStatus)
                } else {
                    Level.WARNING
                }
            }
            CloudState.Loading -> {
                faultText = "故障状态：${errorStatusLabel(localErrorStatus)}（正在同步后台）"
                onlineText = "营业状态：本机${onlineStatusLabel(localOnlineStatus)}（正在同步后台）"
                faultLevel = if (localErrorStatus == 0) Level.WARNING else errorLevel(localErrorStatus)
                onlineLevel = Level.WARNING
            }
            CloudState.Unavailable -> {
                faultText = "故障状态：${errorStatusLabel(localErrorStatus)}（后台状态获取失败）"
                onlineText = "营业状态：本机${onlineStatusLabel(localOnlineStatus)}（后台状态获取失败）"
                faultLevel = if (localErrorStatus == 0) Level.WARNING else errorLevel(localErrorStatus)
                onlineLevel = Level.WARNING
            }
        }

        val shouldRetryCloud = !needsLocalRecovery && canRetryCloudOnly && (
            cloudState !is CloudState.Available ||
                cloudState.onlineStatus != localOnlineStatus ||
                cloudState.errorStatus != localErrorStatus
            )
        val recoveryButtonText = when {
            needsLocalRecovery -> "检查并恢复设备"
            shouldRetryCloud -> "重试同步后台"
            else -> "当前无需恢复"
        }

        return Display(
            faultText = faultText,
            onlineText = onlineText,
            recoveryButtonText = recoveryButtonText,
            faultLevel = faultLevel,
            onlineLevel = onlineLevel
        )
    }

    fun onlineStatusLabel(value: Int): String = when (value) {
        0 -> "待投运"
        1 -> "运营中"
        2 -> "休息中"
        3 -> "维护中"
        4 -> "工作中"
        else -> "未知($value)"
    }

    fun errorStatusLabel(value: Int): String = when (value) {
        0 -> "正常"
        1 -> "告警"
        2 -> "错误"
        else -> "未知($value)"
    }

    private fun errorLevel(value: Int): Level = when (value) {
        0 -> Level.NORMAL
        1 -> Level.WARNING
        else -> Level.ERROR
    }

    private fun onlineLevel(value: Int): Level = when (value) {
        1, 4 -> Level.NORMAL
        else -> Level.WARNING
    }
}
