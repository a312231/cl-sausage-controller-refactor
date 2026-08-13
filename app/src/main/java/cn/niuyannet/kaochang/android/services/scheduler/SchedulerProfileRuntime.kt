package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.LegacySchedulerProfileA
import cn.niuyannet.kaochang.scheduler.ProcessLifetimeProfilePinResult
import cn.niuyannet.kaochang.scheduler.ProcessLifetimeSchedulerProfilePin
import cn.niuyannet.kaochang.scheduler.SchedulerProfile
import cn.niuyannet.kaochang.scheduler.SchedulerProfileCatalog

object SchedulerProfileRuntime {
    private val catalog = SchedulerProfileCatalog(listOf(LegacySchedulerProfileA.create()))
    private val processPin = ProcessLifetimeSchedulerProfilePin(catalog)

    fun bootstrapOrThrow(rawId: String?) {
        when (val result = processPin.pin(rawId)) {
            is ProcessLifetimeProfilePinResult.FirstPinned,
            is ProcessLifetimeProfilePinResult.AlreadyPinned -> Unit
            ProcessLifetimeProfilePinResult.InvalidId ->
                error("Scheduler profile id is invalid")
            ProcessLifetimeProfilePinResult.NotRegistered ->
                error("Scheduler profile is not registered: $rawId")
            is ProcessLifetimeProfilePinResult.Conflict -> error(
                "Scheduler profile is already pinned: " +
                    "active=${result.activeProfileId.value}, " +
                    "requested=${result.requestedProfileId.value}"
            )
        }
    }

    val activeProfile: SchedulerProfile
        get() = processPin.requireActiveProfile()
}
