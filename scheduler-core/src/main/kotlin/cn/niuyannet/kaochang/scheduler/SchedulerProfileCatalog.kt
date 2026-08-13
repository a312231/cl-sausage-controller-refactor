package cn.niuyannet.kaochang.scheduler

import java.util.Collections
import java.util.LinkedHashSet

class SchedulerProfileCatalog(profiles: Collection<SchedulerProfile>) {
    private val profilesById: Map<SchedulerProfileId, SchedulerProfile>

    init {
        val grouped = profiles.groupBy { it.id }
        val duplicateIds = grouped.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Duplicate scheduler profile ids: ${duplicateIds.joinToString { it.value }}"
        }
        profilesById = grouped.mapValues { (_, values) -> values.single() }
    }

    fun find(id: SchedulerProfileId): SchedulerProfile? = profilesById[id]

    fun registeredIds(): Set<SchedulerProfileId> =
        Collections.unmodifiableSet(LinkedHashSet(profilesById.keys))
}
