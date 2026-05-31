package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean

object ManualPanResetRuntimeStateHelper {

    data class Result(
        val changed: Boolean,
        val oldAvailable: Long,
        val oldAvailableCountdownLatched: Int,
        val oldTransitionMode: Int,
        val oldHighConcurrencyResidualDrain: Int
    )

    fun apply(config: AppConfigBean): Result {
        val oldAvailable = config.available
        val oldAvailableCountdownLatched = config.availableCountdownLatched
        val oldTransitionMode = config.transitionMode
        val oldHighConcurrencyResidualDrain = config.highConcurrencyResidualDrain
        val changed = oldAvailable != 0L ||
            oldAvailableCountdownLatched != 0 ||
            oldTransitionMode != 0 ||
            oldHighConcurrencyResidualDrain != 0

        if (changed) {
            config.available = 0L
            config.availableCountdownLatched = 0
            config.transitionMode = 0
            config.highConcurrencyResidualDrain = 0
        }

        return Result(
            changed = changed,
            oldAvailable = oldAvailable,
            oldAvailableCountdownLatched = oldAvailableCountdownLatched,
            oldTransitionMode = oldTransitionMode,
            oldHighConcurrencyResidualDrain = oldHighConcurrencyResidualDrain
        )
    }
}
