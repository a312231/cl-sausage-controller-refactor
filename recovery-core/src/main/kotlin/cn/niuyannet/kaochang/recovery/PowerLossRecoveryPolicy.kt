package cn.niuyannet.kaochang.recovery

enum class PowerLossColdStartAction {
    INITIALIZE_HEARTBEAT,
    SKIP_ACTIVE_FAULT_PAUSE,
    APPLY_POWER_LOSS_GAP
}

object PowerLossRecoveryPolicy {

    fun coldStartAction(
        lastAliveAtMs: Long,
        faultHeatPauseActive: Int,
        faultHeatPauseStartedAtMs: Long
    ): PowerLossColdStartAction = when {
        lastAliveAtMs <= 0L -> PowerLossColdStartAction.INITIALIZE_HEARTBEAT
        faultHeatPauseActive == 1 && faultHeatPauseStartedAtMs > 0L ->
            PowerLossColdStartAction.SKIP_ACTIVE_FAULT_PAUSE
        else -> PowerLossColdStartAction.APPLY_POWER_LOSS_GAP
    }

    fun shouldMarkAlive(
        lastAliveAtMs: Long,
        nowMs: Long,
        intervalMs: Long = 60_000L
    ): Boolean {
        require(intervalMs > 0L) { "intervalMs must be positive" }
        return lastAliveAtMs <= 0L || nowMs - lastAliveAtMs >= intervalMs
    }
}
