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
            if (request.owner.isBlank() || request.revision <= 0L || request.resourceMask == ResourceMask.none) {
                return@synchronized SupplementMechanicalCloseoutResult.Rejected("补肠收口 key 无效")
            }
            val supplementOwner = readOwner(supplementOwnerStore)
                ?: return@synchronized SupplementMechanicalCloseoutResult.Rejected("补肠 owner 不存在或读取失败")
            if (supplementOwner.owner != request.owner ||
                supplementOwner.revision != request.revision ||
                supplementOwner.state != MechanicalClaimState.CLAIMED
            ) {
                return@synchronized SupplementMechanicalCloseoutResult.Rejected("补肠 owner/revision/state 不匹配")
            }

            val orderRead = try {
                OwnerRead.Success(orderOwnerStore.snapshot())
            } catch (error: Throwable) {
                OwnerRead.Failed(error)
            }
            val orderOwner = when (orderRead) {
                is OwnerRead.Failed -> return@synchronized SupplementMechanicalCloseoutResult.Rejected(
                    "订单 claim 读取失败，拒绝收口"
                )
                is OwnerRead.Success -> orderRead.owner
            }
            val moveStatus = try {
                moveStore.load()
            } catch (error: Throwable) {
                return@synchronized SupplementMechanicalCloseoutResult.Failed(
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
                else -> return@synchronized SupplementMechanicalCloseoutResult.Rejected(
                    "订单 claim 与 moveStatus 不一致，拒绝收口"
                )
            }

            when (val resourceResult = resourceLocks.releaseIfOwnedAfterCommit(
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

    private fun readOwner(store: MechanicalClaimOwnerStore): MechanicalClaimOwnerSnapshot? =
        try {
            store.snapshot()
        } catch (_: Throwable) {
            null
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
