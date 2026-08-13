package cn.niuyannet.kaochang.android.services.actiongroup

data class SupplementMechanicalCloseoutRequest(
    val owner: String,
    val revision: Long,
    val resourceMask: Int
)

sealed class SupplementMechanicalCloseoutResult {
    data object ClosedIdle : SupplementMechanicalCloseoutResult()
    data object ClosedKeepingOrder : SupplementMechanicalCloseoutResult()
    data class Rejected(val reason: String) : SupplementMechanicalCloseoutResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : SupplementMechanicalCloseoutResult()
    data class Inconsistent(val reason: String, val cause: Throwable? = null) : SupplementMechanicalCloseoutResult()
}

/**
 * 在统一状态锁内完成订单证据判定、补肠 claim 收口和补肠资源释放。
 * 本类不清订单 claim，也不负责判断具体动作是否处于可释放物理阶段。
 */
class SupplementMechanicalCloseoutCoordinator(
    private val transitionLock: Any,
    private val supplementOwnerStore: MechanicalClaimOwnerStore,
    private val orderOwnerStore: MechanicalClaimOwnerStore,
    private val moveStore: MechanicalMoveStatusStore,
    private val resourceLocks: ResourceLocks
) {
    fun close(request: SupplementMechanicalCloseoutRequest): SupplementMechanicalCloseoutResult =
        synchronized(transitionLock) {
            validateRequest(request)?.let { return@synchronized it }
            val supplementOwner = when (val ownerRead = readOwner(supplementOwnerStore)) {
                is OwnerRead.Failed -> return@synchronized SupplementMechanicalCloseoutResult.Failed(
                    "补肠 owner 读取失败，拒绝收口",
                    ownerRead.cause
                )
                is OwnerRead.Success -> ownerRead.owner
            } ?: return@synchronized SupplementMechanicalCloseoutResult.Rejected("补肠 owner 不存在")
            closeOwned(request, supplementOwner)
        }

    fun closeOrConfirmAlreadyClosed(
        request: SupplementMechanicalCloseoutRequest
    ): SupplementMechanicalCloseoutResult = synchronized(transitionLock) {
        validateRequest(request)?.let { return@synchronized it }
        val supplementOwner = when (val ownerRead = readOwner(supplementOwnerStore)) {
            is OwnerRead.Failed -> return@synchronized SupplementMechanicalCloseoutResult.Failed(
                "补肠 owner 读取失败，拒绝收口",
                ownerRead.cause
            )
            is OwnerRead.Success -> ownerRead.owner
        }
        if (supplementOwner?.state != null) {
            return@synchronized closeOwned(request, supplementOwner)
        }
        if (supplementOwner != null &&
            (supplementOwner.owner != request.owner || supplementOwner.revision != request.revision)
        ) {
            return@synchronized SupplementMechanicalCloseoutResult.Rejected(
                "已清补肠 owner/revision 不匹配，拒绝确认收口"
            )
        }
        if (resourceLocks.ownedMask(request.owner) != null) {
            return@synchronized SupplementMechanicalCloseoutResult.Rejected(
                "补肠 owner 已清但资源仍占用，拒绝确认收口"
            )
        }
        classifyAlreadyClosedState()
    }

    private fun closeOwned(
        request: SupplementMechanicalCloseoutRequest,
        supplementOwner: MechanicalClaimOwnerSnapshot
    ): SupplementMechanicalCloseoutResult {
            if (supplementOwner.owner != request.owner ||
                supplementOwner.revision != request.revision ||
                supplementOwner.state != MechanicalClaimState.CLAIMED
            ) {
                return SupplementMechanicalCloseoutResult.Rejected("补肠 owner/revision/state 不匹配")
            }

            val orderRead = try {
                OwnerRead.Success(orderOwnerStore.snapshot())
            } catch (error: Throwable) {
                OwnerRead.Failed(error)
            }
            val orderOwner = when (orderRead) {
                is OwnerRead.Failed -> return SupplementMechanicalCloseoutResult.Rejected(
                    "订单 claim 读取失败，拒绝收口"
                )
                is OwnerRead.Success -> orderRead.owner
            }
            val moveStatus = try {
                moveStore.load()
            } catch (error: Throwable) {
                return SupplementMechanicalCloseoutResult.Failed(
                    "moveStatus 读取失败，拒绝收口",
                    error
                )
            }
            val keepOrder = when {
                orderOwner == null && moveStatus == 1 -> false
                orderOwner != null &&
                    orderOwner.owner.startsWith(OrderDeliveryClaimStore.OWNER_PREFIX) &&
                    orderOwner.state == MechanicalClaimState.CLAIMED &&
                    orderOwner.revision > 0L &&
                    moveStatus == 2 -> true
                else -> return SupplementMechanicalCloseoutResult.Rejected(
                    "订单 claim 与 moveStatus 不一致，拒绝收口"
                )
            }

            return when (val resourceResult = resourceLocks.releaseIfOwnedAfterCommit(
                owner = request.owner,
                expectedMask = request.resourceMask
            ) {
                if (keepOrder) {
                    clearSupplementOwner(request)
                } else {
                    closeIdleAndClearSupplementOwner(request)
                }
            }) {
                ResourceReleaseCommitResult.Released,
                ResourceReleaseCommitResult.AlreadyAbsent -> if (keepOrder) {
                    SupplementMechanicalCloseoutResult.ClosedKeepingOrder
                } else {
                    SupplementMechanicalCloseoutResult.ClosedIdle
                }
                ResourceReleaseCommitResult.MaskMismatch ->
                    SupplementMechanicalCloseoutResult.Rejected("补肠资源 mask 不匹配")
                is ResourceReleaseCommitResult.CommitFailed ->
                    if (resourceResult.cause is CloseoutInconsistentException) {
                        SupplementMechanicalCloseoutResult.Inconsistent(
                            "补肠 owner 清理失败且 moveStatus 忙态回滚失败",
                            resourceResult.cause
                        )
                    } else {
                        SupplementMechanicalCloseoutResult.Failed(
                            "补肠 claim/moveStatus 耐久收口失败",
                            resourceResult.cause
                        )
                    }
            }
    }

    private fun classifyAlreadyClosedState(): SupplementMechanicalCloseoutResult {
        val orderOwner = when (val orderRead = readOwner(orderOwnerStore)) {
            is OwnerRead.Failed -> return SupplementMechanicalCloseoutResult.Failed(
                "订单 claim 读取失败，拒绝确认收口",
                orderRead.cause
            )
            is OwnerRead.Success -> orderRead.owner
        }
        val moveStatus = try {
            moveStore.load()
        } catch (error: Throwable) {
            return SupplementMechanicalCloseoutResult.Failed(
                "moveStatus 读取失败，拒绝确认收口",
                error
            )
        }
        return when {
            orderOwner == null && moveStatus == 0 -> SupplementMechanicalCloseoutResult.ClosedIdle
            orderOwner != null &&
                orderOwner.owner.startsWith(OrderDeliveryClaimStore.OWNER_PREFIX) &&
                orderOwner.state == MechanicalClaimState.CLAIMED &&
                orderOwner.revision > 0L &&
                moveStatus == 2 -> SupplementMechanicalCloseoutResult.ClosedKeepingOrder
            else -> SupplementMechanicalCloseoutResult.Rejected(
                "已清补肠 owner 与订单 claim/moveStatus 不一致，拒绝确认收口"
            )
        }
    }

    private fun validateRequest(
        request: SupplementMechanicalCloseoutRequest
    ): SupplementMechanicalCloseoutResult.Rejected? =
        if (request.owner.isBlank() || request.revision <= 0L || request.resourceMask == ResourceMask.none) {
            SupplementMechanicalCloseoutResult.Rejected("补肠收口 key 无效")
        } else {
            null
        }

    private fun clearSupplementOwner(request: SupplementMechanicalCloseoutRequest) {
        val cleared = supplementOwnerStore.clearIfOwnerAndRevision(request.owner, request.revision)
        check(cleared) { "补肠 owner/revision 已变化，拒绝清理" }
    }

    private fun closeIdleAndClearSupplementOwner(request: SupplementMechanicalCloseoutRequest) {
        moveStore.saveDurable(0)
        try {
            clearSupplementOwner(request)
        } catch (clearError: Throwable) {
            try {
                moveStore.saveDurable(1)
            } catch (rollbackError: Throwable) {
                throw CloseoutInconsistentException(clearError, rollbackError)
            }
            throw clearError
        }
    }

    private fun readOwner(store: MechanicalClaimOwnerStore): OwnerRead =
        try {
            OwnerRead.Success(store.snapshot())
        } catch (error: Throwable) {
            OwnerRead.Failed(error)
        }

    private sealed class OwnerRead {
        data class Success(val owner: MechanicalClaimOwnerSnapshot?) : OwnerRead()
        data class Failed(val cause: Throwable) : OwnerRead()
    }

    private class CloseoutInconsistentException(
        clearError: Throwable,
        rollbackError: Throwable
    ) : IllegalStateException("owner 清理失败且 moveStatus 忙态回滚失败", clearError) {
        init {
            addSuppressed(rollbackError)
        }
    }
}
