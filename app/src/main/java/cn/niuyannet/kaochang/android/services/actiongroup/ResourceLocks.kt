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

    @Synchronized
    fun forceAcquire(mask: Int, owner: String) {
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
    val resumeAfterAction: String? = null
)

enum class ActionGroupSessionType {
    IN_SUPPLEMENT,
    OUT_SELL,
    OUT_MANUAL
}
