package cn.niuyannet.kaochang.android.services.actiongroup

class NonParallelSupplementOrderHandoffCoordinator(
    private val transitionLock: Any,
    private val ownerStore: MechanicalClaimOwnerStore,
    private val moveStore: MechanicalMoveStatusStore,
    private val resourceLocks: ResourceLocks,
    private val expectedResourceMask: Int? = null
) {
    fun releaseCompletedRoot(
        owner: String,
        closeout: SupplementPipelineOrderCloseout
    ): Boolean = synchronized(transitionLock) {
        if (closeout != SupplementPipelineOrderCloseout.COMPLETED_ROOT) {
            return@synchronized false
        }
        val current = ownerStore.snapshot()
        if (current?.owner != owner || current.state != MechanicalClaimState.CLAIMED) {
            return@synchronized false
        }
        val resourceMask = expectedResourceMask
            ?: resourceLocks.ownedMask(owner)
            ?: return@synchronized false
        if (resourceLocks.ownedMask(owner) != resourceMask) {
            return@synchronized false
        }
        val release = MechanicalMoveClaimCoordinator(
            transitionLock = transitionLock,
            ownerStore = ownerStore,
            moveStore = moveStore
        ).release(
            owner = owner,
            expectedMoveStatus = 1,
            idleMoveStatus = 0,
            expectedRevision = current.revision
        )
        if (release !is MechanicalMoveReleaseResult.Released) {
            return@synchronized false
        }
        when (resourceLocks.releaseIfOwned(owner, resourceMask)) {
            ResourceReleaseResult.RELEASED,
            ResourceReleaseResult.ALREADY_ABSENT -> true
            ResourceReleaseResult.MASK_MISMATCH -> false
        }
    }
}
