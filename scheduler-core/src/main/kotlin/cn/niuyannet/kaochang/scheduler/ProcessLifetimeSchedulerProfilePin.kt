package cn.niuyannet.kaochang.scheduler

sealed class ProcessLifetimeProfilePinResult {
    data class FirstPinned(val profile: SchedulerProfile) : ProcessLifetimeProfilePinResult()
    data class AlreadyPinned(val profile: SchedulerProfile) : ProcessLifetimeProfilePinResult()
    data object InvalidId : ProcessLifetimeProfilePinResult()
    data object NotRegistered : ProcessLifetimeProfilePinResult()
    data class Conflict(
        val activeProfileId: SchedulerProfileId,
        val requestedProfileId: SchedulerProfileId
    ) : ProcessLifetimeProfilePinResult()
}

/**
 * Owns one complete scheduler profile for one process lifetime.
 *
 * Every read and pin is serialized on a private monitor. Once a profile owns the process,
 * a different registered profile can only be selected by creating a new process instance.
 */
class ProcessLifetimeSchedulerProfilePin(
    private val catalog: SchedulerProfileCatalog
) {
    private val monitor = Any()
    private var pinnedProfile: PinnedSchedulerProfile? = null

    fun pin(rawId: String?): ProcessLifetimeProfilePinResult = synchronized(monitor) {
        val requested = when (val result = PinnedSchedulerProfile.create(catalog, rawId)) {
            is ProfilePinResult.Pinned -> result.profile
            ProfilePinResult.InvalidId -> return@synchronized ProcessLifetimeProfilePinResult.InvalidId
            ProfilePinResult.NotRegistered ->
                return@synchronized ProcessLifetimeProfilePinResult.NotRegistered
        }
        val current = pinnedProfile
        if (current == null) {
            pinnedProfile = requested
            ProcessLifetimeProfilePinResult.FirstPinned(requested.activeProfile)
        } else if (current.id == requested.id) {
            ProcessLifetimeProfilePinResult.AlreadyPinned(current.activeProfile)
        } else {
            ProcessLifetimeProfilePinResult.Conflict(current.id, requested.id)
        }
    }

    fun requireActiveProfile(): SchedulerProfile = synchronized(monitor) {
        checkNotNull(pinnedProfile) {
            "Scheduler profile has not been pinned for this process"
        }.activeProfile
    }
}
