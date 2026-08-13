package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.scheduler.LegacySchedulerProfileA

object SchedulerProfileTestBootstrap {
    fun pinLegacyA() {
        SchedulerProfileRuntime.bootstrapOrThrow(LegacySchedulerProfileA.ID.value)
    }
}
