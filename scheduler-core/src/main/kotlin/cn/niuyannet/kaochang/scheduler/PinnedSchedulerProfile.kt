package cn.niuyannet.kaochang.scheduler

sealed class ProfilePinResult {
    data class Pinned(val profile: PinnedSchedulerProfile) : ProfilePinResult()
    data object InvalidId : ProfilePinResult()
    data object NotRegistered : ProfilePinResult()
}

class PinnedSchedulerProfile private constructor(
    val activeProfile: SchedulerProfile
) {
    val id: SchedulerProfileId = activeProfile.id
    val descriptor: SchedulerProfileDescriptor = activeProfile.descriptor

    companion object {
        fun create(
            catalog: SchedulerProfileCatalog,
            rawId: String?
        ): ProfilePinResult {
            val id = parseSchedulerProfileIdStrict(rawId) ?: return ProfilePinResult.InvalidId
            val profile = catalog.find(id) ?: return ProfilePinResult.NotRegistered
            return ProfilePinResult.Pinned(PinnedSchedulerProfile(profile))
        }
    }
}
