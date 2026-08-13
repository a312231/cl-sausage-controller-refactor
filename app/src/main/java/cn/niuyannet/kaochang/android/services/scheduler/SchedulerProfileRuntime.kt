package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.LegacySchedulerProfileA
import cn.niuyannet.kaochang.scheduler.PinnedSchedulerProfile
import cn.niuyannet.kaochang.scheduler.ProfilePinResult
import cn.niuyannet.kaochang.scheduler.SchedulerProfile
import cn.niuyannet.kaochang.scheduler.SchedulerProfileCatalog

object SchedulerProfileRuntime {
    private val catalog = SchedulerProfileCatalog(listOf(LegacySchedulerProfileA.create()))
    private val pinnedProfile = when (
        val result = PinnedSchedulerProfile.create(catalog, LegacySchedulerProfileA.ID.value)
    ) {
        is ProfilePinResult.Pinned -> result.profile
        ProfilePinResult.InvalidId -> error("Legacy scheduler profile A id is invalid")
        ProfilePinResult.NotRegistered -> error("Legacy scheduler profile A is not registered")
    }

    val activeProfile: SchedulerProfile = pinnedProfile.activeProfile
}
