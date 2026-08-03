package cn.niuyannet.kaochang.android.services.actiongroup

class NonParallelSupplementOrderHandoffCoordinator(
    private val transitionLock: Any,
    private val ownerStore: MechanicalClaimOwnerStore,
    private val moveStore: MechanicalMoveStatusStore,
    private val resourceLocks: ResourceLocks
) {
    fun releaseCompletedRoot(
        owner: String,
        closeout: SupplementPipelineOrderCloseout
    ): Boolean {
        if (closeout != SupplementPipelineOrderCloseout.COMPLETED_ROOT) {
            return false
        }
        val release = MechanicalMoveClaimCoordinator(
            transitionLock = transitionLock,
            ownerStore = ownerStore,
            moveStore = moveStore
        ).release(
            owner = owner,
            expectedMoveStatus = 1,
            idleMoveStatus = 0
        )
        if (release !is MechanicalMoveReleaseResult.Released) {
            return false
        }
        resourceLocks.release(owner)
        return true
    }
}
