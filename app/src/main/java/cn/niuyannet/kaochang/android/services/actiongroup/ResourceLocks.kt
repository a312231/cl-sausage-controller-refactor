package cn.niuyannet.kaochang.android.services.actiongroup

object ResourceMask {
    const val none = 0
    const val bottom = 1 shl 0
    const val lift = 1 shl 1
    const val above = 1 shl 2
    const val gate = 1 shl 3
    const val stick = 1 shl 4
}

class ResourceLocks {
    private val owners = linkedMapOf<String, Int>()

    val heldMask: Int
        get() = owners.values.fold(ResourceMask.none) { acc, mask -> acc or mask }

    @Synchronized
    fun tryAcquire(mask: Int, owner: String): Boolean {
        owners[owner]?.let { ownedMask ->
            return ownedMask == mask
        }
        if ((heldMask and mask) != 0) {
            return false
        }
        owners[owner] = mask
        return true
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
    }

    /**
     * 仅当 owner 仍持有期望的资源集合时释放，检查和删除在同一同步范围内完成。
     * owner 已不存在视为幂等成功，mask 不匹配则拒绝触碰任何资源。
     */
    @Synchronized
    fun releaseIfOwned(owner: String, expectedMask: Int): ResourceReleaseResult {
        val currentMask = owners[owner] ?: return ResourceReleaseResult.ALREADY_ABSENT
        if (currentMask != expectedMask) {
            return ResourceReleaseResult.MASK_MISMATCH
        }
        owners.remove(owner)
        return ResourceReleaseResult.RELEASED
    }

    @Synchronized
    fun forceAcquire(mask: Int, owner: String) {
        owners[owner]?.let { ownedMask ->
            check(ownedMask == mask) {
                "资源 owner 已持有不同 mask：owner=$owner，owned=${describe(ownedMask)}，requested=${describe(mask)}"
            }
            return
        }
        val conflictingOwner = owners.entries.firstOrNull { (_, ownedMask) ->
            (ownedMask and mask) != 0
        }
        check(conflictingOwner == null) {
            "资源已被其他 owner 持有：requestedOwner=$owner，" +
                "conflictingOwner=${conflictingOwner?.key}，mask=${describe(mask)}"
        }
        owners[owner] = mask
    }

    @Synchronized
    fun clear() {
        owners.clear()
    }

    @Synchronized
    fun isIdle(): Boolean = owners.isEmpty()

    @Synchronized
    fun snapshot(): String {
        return owners.entries.joinToString(prefix = "[", postfix = "]") { "${it.key}:${describe(it.value)}" }
    }

    @Synchronized
    fun ownerIds(): Set<String> = owners.keys.toSet()

    @Synchronized
    fun ownedMask(owner: String): Int? = owners[owner]

    companion object {
        fun describe(mask: Int): String {
            if (mask == ResourceMask.none) {
                return "NONE（无资源）"
            }
            return buildList {
                if ((mask and ResourceMask.bottom) != 0) add("BOTTOM（下平台）")
                if ((mask and ResourceMask.lift) != 0) add("LIFT（升降台）")
                if ((mask and ResourceMask.above) != 0) add("ABOVE（上平台）")
                if ((mask and ResourceMask.gate) != 0) add("GATE（闸门）")
                if ((mask and ResourceMask.stick) != 0) add("STICK（穿签机构）")
            }.joinToString("|")
        }
    }
}

enum class ResourceReleaseResult {
    RELEASED,
    ALREADY_ABSENT,
    MASK_MISMATCH
}

data class ActionGroupSession(
    val sessionId: String,
    val type: ActionGroupSessionType,
    val sourceNo: Int,
    val targetNo: Int? = null,
    val orderId: String? = null,
    val orderContext: ActionGroupOrderContext? = null,
    val currentAction: String? = null,
    val resourceMask: Int = ResourceMask.none,
    val startedAtMs: Long = System.currentTimeMillis(),
    val batchId: String? = null,
    val taste: String? = null,
    val returnPosition: Int? = null,
    val prefetch: Boolean = false,
    val checkpointVersion: Long = 0L,
    val lastConfirmedStage: String? = null,
    val stockBefore: Int? = null,
    val expectedStockAfter: Int? = null,
    val boxDeductionApplied: Boolean = false,
    val panPlacementApplied: Boolean = false,
    val pausedForOrder: Boolean = false,
    val resumeAfterAction: String? = null,
    val out1DispatchEvidence: Out1DispatchEvidence? = null,
    val mechanicalClaimState: MechanicalClaimState? = null,
    val mechanicalClaimRevision: Long = 0L
)

enum class Out1DispatchEvidence {
    PREPARED,
    NOT_DISPATCHED_CONFIRMED,
    DISPATCHED
}

enum class ActionGroupSessionType {
    IN_SUPPLEMENT,
    OUT_SELL,
    OUT_MANUAL
}
