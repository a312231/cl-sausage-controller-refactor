package cn.niuyannet.kaochang.android.services.actiongroup

enum class MechanicalClaimState {
    PREPARED,
    CLAIMED
}

data class MechanicalClaimOwnerSnapshot(
    val owner: String,
    val state: MechanicalClaimState?,
    val revision: Long
)

interface MechanicalClaimOwnerStore {
    fun snapshot(): MechanicalClaimOwnerSnapshot?
    fun savePrepared(owner: String)
    fun saveClaimed(owner: String)
    fun clearIfOwner(owner: String): Boolean
}

interface MechanicalMoveStatusStore {
    fun load(): Int
    fun saveDurable(moveStatus: Int)
}

sealed class MechanicalMoveClaimResult {
    data object Claimed : MechanicalMoveClaimResult()
    data class Rejected(val reason: String) : MechanicalMoveClaimResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : MechanicalMoveClaimResult()
}

sealed class MechanicalMoveReleaseResult {
    data object Released : MechanicalMoveReleaseResult()
    data class Rejected(val reason: String) : MechanicalMoveReleaseResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : MechanicalMoveReleaseResult()
}

class MechanicalMoveClaimCoordinator(
    private val transitionLock: Any,
    private val ownerStore: MechanicalClaimOwnerStore,
    private val moveStore: MechanicalMoveStatusStore
) {
    fun claim(
        owner: String,
        allowedMoveStatuses: Set<Int>,
        claimedMoveStatus: Int,
        canClaim: () -> Boolean = { true }
    ): MechanicalMoveClaimResult = synchronized(transitionLock) {
        if (owner.isBlank()) {
            return@synchronized MechanicalMoveClaimResult.Rejected("owner 不能为空")
        }
        if (!canClaim()) {
            return@synchronized MechanicalMoveClaimResult.Rejected("运行态门禁拒绝认领")
        }
        val beforeMoveStatus = moveStore.load()
        if (beforeMoveStatus !in allowedMoveStatuses) {
            return@synchronized MechanicalMoveClaimResult.Rejected(
                "moveStatus=$beforeMoveStatus 不允许认领"
            )
        }
        val existing = ownerStore.snapshot()
        if (existing != null && existing.owner != owner) {
            return@synchronized MechanicalMoveClaimResult.Rejected(
                "owner 已被 ${existing.owner} 占用"
            )
        }

        try {
            ownerStore.savePrepared(owner)
        } catch (error: Throwable) {
            return@synchronized MechanicalMoveClaimResult.Failed("PREPARED owner 落盘失败", error)
        }
        val prepared = ownerStore.snapshot()
        if (prepared?.owner != owner || prepared.state != MechanicalClaimState.PREPARED) {
            return@synchronized MechanicalMoveClaimResult.Failed("PREPARED owner 落盘后校验失败")
        }

        try {
            if (beforeMoveStatus != claimedMoveStatus) {
                moveStore.saveDurable(claimedMoveStatus)
            }
        } catch (error: Throwable) {
            runCatching { ownerStore.clearIfOwner(owner) }
            return@synchronized MechanicalMoveClaimResult.Failed("moveStatus 耐久认领失败", error)
        }

        try {
            ownerStore.saveClaimed(owner)
            MechanicalMoveClaimResult.Claimed
        } catch (error: Throwable) {
            if (beforeMoveStatus != claimedMoveStatus) {
                try {
                    moveStore.saveDurable(beforeMoveStatus)
                } catch (rollbackError: Throwable) {
                    return@synchronized MechanicalMoveClaimResult.Failed(
                        "CLAIMED owner 落盘失败且 moveStatus 回滚失败，保留 PREPARED owner",
                        rollbackError
                    )
                }
            }
            val ownerCleared = runCatching { ownerStore.clearIfOwner(owner) }.getOrDefault(false)
            if (!ownerCleared && beforeMoveStatus != claimedMoveStatus) {
                runCatching { moveStore.saveDurable(claimedMoveStatus) }
            }
            MechanicalMoveClaimResult.Failed("CLAIMED owner 落盘失败", error)
        }
    }

    fun release(
        owner: String,
        expectedMoveStatus: Int,
        idleMoveStatus: Int
    ): MechanicalMoveReleaseResult = synchronized(transitionLock) {
        val current = ownerStore.snapshot()
        if (current?.owner != owner) {
            return@synchronized MechanicalMoveReleaseResult.Rejected(
                "owner 不匹配：expected=$owner，actual=${current?.owner}"
            )
        }
        val currentMoveStatus = moveStore.load()
        if (currentMoveStatus != expectedMoveStatus) {
            return@synchronized MechanicalMoveReleaseResult.Rejected(
                "moveStatus 不匹配：expected=$expectedMoveStatus，actual=$currentMoveStatus"
            )
        }
        releaseOwned(owner, currentMoveStatus, idleMoveStatus)
    }

    fun abandonPrepared(
        owner: String,
        allowedMoveStatuses: Set<Int>,
        idleMoveStatus: Int
    ): MechanicalMoveReleaseResult = synchronized(transitionLock) {
        val current = ownerStore.snapshot()
        if (current?.owner != owner || current.state != MechanicalClaimState.PREPARED) {
            return@synchronized MechanicalMoveReleaseResult.Rejected(
                "仅允许匹配 owner 的 PREPARED 会话放弃"
            )
        }
        val currentMoveStatus = moveStore.load()
        if (currentMoveStatus !in allowedMoveStatuses) {
            return@synchronized MechanicalMoveReleaseResult.Rejected(
                "moveStatus=$currentMoveStatus 不允许放弃 PREPARED"
            )
        }
        releaseOwned(owner, currentMoveStatus, idleMoveStatus)
    }

    private fun releaseOwned(
        owner: String,
        currentMoveStatus: Int,
        idleMoveStatus: Int
    ): MechanicalMoveReleaseResult {
        try {
            if (currentMoveStatus != idleMoveStatus) {
                moveStore.saveDurable(idleMoveStatus)
            }
        } catch (error: Throwable) {
            return MechanicalMoveReleaseResult.Failed("moveStatus 耐久释放失败", error)
        }
        return try {
            if (!ownerStore.clearIfOwner(owner)) {
                throw IllegalStateException("owner 已变化，拒绝清理")
            }
            MechanicalMoveReleaseResult.Released
        } catch (error: Throwable) {
            if (currentMoveStatus != idleMoveStatus) {
                runCatching { moveStore.saveDurable(currentMoveStatus) }
            }
            MechanicalMoveReleaseResult.Failed("owner 清理失败，已恢复忙态", error)
        }
    }
}
