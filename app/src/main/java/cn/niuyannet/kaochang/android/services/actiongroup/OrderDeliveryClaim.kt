package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON

data class OrderDeliveryClaim(
    val orderNo: String = "",
    val owner: String = "",
    val state: MechanicalClaimState? = null,
    val revision: Long = 0L,
    val createdAtMs: Long = 0L
)

/** 订单 owner 存在时，旧的 moveStatus 自动归零路径必须 fail closed。 */
enum class OrderDeliveryClaimRecovery {
    NO_CLAIM,
    CLAIM_PRESENT,
    RESTORED,
    FAILED
}

class OrderDeliveryClaimStore(
    private val readRaw: () -> String? = {
        PreferenceUtils.getStringPreference(AppConfig.Sp_File_URL, KEY_ORDER_DELIVERY_CLAIM, null)
    },
    private val writeRaw: (String) -> Boolean = { raw ->
        PreferenceUtils.saveStringPreferenceDurable(AppConfig.Sp_File_URL, KEY_ORDER_DELIVERY_CLAIM, raw)
    },
    private val nowMs: () -> Long = System::currentTimeMillis
) : MechanicalClaimOwnerStore {

    fun load(): OrderDeliveryClaim? {
        val raw = readRaw()
        if (raw.isNullOrBlank()) return null
        return try {
            JSON.parseObject(raw, OrderDeliveryClaim::class.java)
        } catch (error: Throwable) {
            throw IllegalStateException("订单出餐 claim 损坏，拒绝按无 owner 处理", error)
        }
    }

    override fun snapshot(): MechanicalClaimOwnerSnapshot? = load()?.let { claim ->
        MechanicalClaimOwnerSnapshot(claim.owner, claim.state, claim.revision)
    }

    override fun savePrepared(owner: String) {
        val current = load()
        check(current == null || current.owner == owner) { "订单出餐 PREPARED owner 已变化" }
        save(
            OrderDeliveryClaim(
                orderNo = current?.orderNo ?: orderNoFromOwner(owner),
                owner = owner,
                state = MechanicalClaimState.PREPARED,
                revision = maxOf(1L, (current?.revision ?: 0L) + 1L),
                createdAtMs = current?.createdAtMs ?: nowMs()
            )
        )
    }

    override fun saveClaimed(owner: String) {
        val current = requireNotNull(load()) { "订单出餐 CLAIMED owner 不存在" }
        check(current.owner == owner) { "订单出餐 CLAIMED owner 已变化" }
        save(
            current.copy(
                state = MechanicalClaimState.CLAIMED,
                revision = current.revision + 1L
            )
        )
    }

    override fun clearIfOwner(owner: String): Boolean {
        if (load()?.owner != owner) return false
        clear()
        return true
    }

    override fun clearIfOwnerAndRevision(owner: String, revision: Long): Boolean {
        val current = load() ?: return false
        if (current.owner != owner || current.revision != revision) return false
        clear()
        return true
    }

    private fun save(claim: OrderDeliveryClaim) {
        check(writeRaw(JSON.toJSONString(claim))) {
            "订单出餐 claim 同步落盘失败：owner=${claim.owner}, revision=${claim.revision}"
        }
    }

    private fun clear() {
        check(writeRaw("")) { "订单出餐 claim 清理同步落盘失败" }
    }

    private fun orderNoFromOwner(owner: String): String {
        check(owner.startsWith(OWNER_PREFIX) && owner.length > OWNER_PREFIX.length) {
            "订单出餐 owner 格式非法"
        }
        return owner.removePrefix(OWNER_PREFIX)
    }

    companion object {
        private const val KEY_ORDER_DELIVERY_CLAIM = "sp_order_delivery_claim_json"
        const val OWNER_PREFIX = "order:"
    }
}

internal fun claimOrderDeliveryMove(
    orderNo: String,
    transitionLock: Any,
    claimStore: OrderDeliveryClaimStore,
    moveStore: MechanicalMoveStatusStore,
    canClaim: () -> Boolean
): OrderDeliveryClaim? = synchronized(transitionLock) {
    if (orderNo.isBlank()) return@synchronized null
    val owner = OrderDeliveryClaimStore.OWNER_PREFIX + orderNo
    val current = try {
        claimStore.load()
    } catch (_: Throwable) {
        return@synchronized null
    }
    if (current != null) {
        return@synchronized current.takeIf {
            it.owner == owner &&
                it.state == MechanicalClaimState.CLAIMED &&
                moveStore.load() == 2
        }
    }
    val result = MechanicalMoveClaimCoordinator(
        transitionLock = transitionLock,
        ownerStore = claimStore,
        moveStore = moveStore
    ).claim(
        owner = owner,
        allowedMoveStatuses = setOf(0),
        claimedMoveStatus = 2,
        canClaim = canClaim
    )
    if (result is MechanicalMoveClaimResult.Claimed) claimStore.load() else null
}

internal fun releaseOrderDeliveryMove(
    claim: OrderDeliveryClaim,
    transitionLock: Any,
    claimStore: OrderDeliveryClaimStore,
    moveStore: MechanicalMoveStatusStore,
    canRelease: () -> Boolean
): Boolean = synchronized(transitionLock) {
    if (!canRelease()) return@synchronized false
    MechanicalMoveClaimCoordinator(
        transitionLock = transitionLock,
        ownerStore = claimStore,
        moveStore = moveStore
    ).release(
        owner = claim.owner,
        expectedMoveStatus = 2,
        idleMoveStatus = 0,
        expectedRevision = claim.revision
    ) is MechanicalMoveReleaseResult.Released
}

/**
 * 修复 release 中“先写 moveStatus=0、后清 owner”的进程崩溃窗口。
 * 仅在 durable owner 已确认 CLAIMED 且 moveStatus 已异常归零时恢复订单忙态；
 * 其他状态不猜测、不覆盖，交给既有恢复链路处理。
 */
internal fun restoreClaimedOrderMoveStatusIfNeeded(
    transitionLock: Any,
    claimStore: OrderDeliveryClaimStore,
    moveStore: MechanicalMoveStatusStore,
    claimedMoveStatus: Int = 2
): OrderDeliveryClaimRecovery = synchronized(transitionLock) {
    val claim = try {
        claimStore.load()
    } catch (_: Throwable) {
        return@synchronized OrderDeliveryClaimRecovery.FAILED
    } ?: return@synchronized OrderDeliveryClaimRecovery.NO_CLAIM

    if (claim.state != MechanicalClaimState.CLAIMED) {
        return@synchronized OrderDeliveryClaimRecovery.CLAIM_PRESENT
    }
    val currentMoveStatus = try {
        moveStore.load()
    } catch (_: Throwable) {
        return@synchronized OrderDeliveryClaimRecovery.FAILED
    }
    if (currentMoveStatus == claimedMoveStatus) {
        return@synchronized OrderDeliveryClaimRecovery.CLAIM_PRESENT
    }
    if (currentMoveStatus != 0) {
        return@synchronized OrderDeliveryClaimRecovery.CLAIM_PRESENT
    }
    try {
        moveStore.saveDurable(claimedMoveStatus)
        OrderDeliveryClaimRecovery.RESTORED
    } catch (_: Throwable) {
        OrderDeliveryClaimRecovery.FAILED
    }
}
