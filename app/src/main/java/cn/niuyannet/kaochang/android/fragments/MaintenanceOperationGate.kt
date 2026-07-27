package cn.niuyannet.kaochang.android.fragments

enum class MaintenanceOperationKind {
    FAULT_RECOVERY,
    ALGORITHM_PREFLIGHT
}

data class MaintenanceOperationToken(
    val generation: Long,
    val kind: MaintenanceOperationKind
)

class MaintenanceOperationGate {
    private var generation = 0L
    private var active: MaintenanceOperationToken? = null

    @Synchronized
    fun begin(kind: MaintenanceOperationKind): MaintenanceOperationToken {
        generation += 1L
        return MaintenanceOperationToken(generation, kind).also { active = it }
    }

    @Synchronized
    fun isCurrent(token: MaintenanceOperationToken): Boolean = active == token

    @Synchronized
    fun finish(token: MaintenanceOperationToken): Boolean {
        if (active != token) return false
        active = null
        return true
    }

    @Synchronized
    fun invalidateAll() {
        generation += 1L
        active = null
    }
}
