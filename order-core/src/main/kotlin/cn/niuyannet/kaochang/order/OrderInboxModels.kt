package cn.niuyannet.kaochang.order

import java.math.BigInteger
import java.util.Collections

enum class OrderProtocolKind {
    LEGACY_V1,
    DISPATCH_V2
}

data class IncomingOrderItemDraft(
    val itemId: String?,
    val productId: String?,
    val tasteCode: String?,
    val quantity: String?,
    val productName: String? = null,
    val productImage: String? = null
)

data class IncomingOrderDraft(
    val protocolKind: OrderProtocolKind?,
    val orderNo: String?,
    val deviceId: String?,
    val dispatchId: String? = null,
    val orderVersion: String? = null,
    val expiresAtMs: String? = null,
    val receivedAtMs: Long?,
    val serverDispatchedAtMs: Long? = null,
    val payTimeMs: Long? = null,
    val items: List<IncomingOrderItemDraft>?
)

data class OrderItemSnapshot(
    val itemId: String,
    val productId: String,
    val tasteCode: String,
    val quantity: Int,
    val firstDeliveryOrdinal: Int
) {
    init {
        require(isNormalizedPositiveLong(itemId)) { "itemId must be a normalized positive Long" }
        require(isNormalizedPositiveLong(productId)) { "productId must be a normalized positive Long" }
        require(tasteCode.isNotBlank() && tasteCode.length <= MAX_TASTE_CODE_LENGTH) {
            "tasteCode must be non-blank and length-limited"
        }
        require(quantity in 1..MAX_ORDER_QUANTITY) { "item quantity must be in 1..4" }
        require(firstDeliveryOrdinal > 0) { "first delivery ordinal must be positive" }
    }
}

enum class OrderAdmissionStage {
    PERSISTED_PENDING_PREPARATION,
    READY_FOR_EXECUTION,
    HOLD_REQUIRED
}

enum class OrderInboxHoldReason {
    MANUAL_OUT_RESERVATION_FAILED,
    SUPPLEMENT_PAUSE_FAILED,
    SUPPLEMENT_BARRIER_PERSIST_FAILED,
    READY_RECORD_PERSIST_FAILED,
    AUTHORIZATION_REVALIDATION_REQUIRED,
    INBOX_CORRUPTED
}

class ActiveOrderRecord private constructor(
    val protocolKind: OrderProtocolKind,
    val orderNo: String,
    val deviceId: String,
    val dispatchId: String?,
    val orderVersion: String?,
    val expiresAtMs: String?,
    val receivedAtMs: Long,
    val serverDispatchedAtMs: Long?,
    val payTimeMs: Long?,
    val canonicalPayloadSha256: String,
    items: List<OrderItemSnapshot>,
    val admissionStage: OrderAdmissionStage,
    val holdReason: OrderInboxHoldReason?,
    val lifecycleStage: OrderLifecycleStage,
    val wholeOrderOutcome: WholeOrderOutcome?,
    val claimReleaseEligibility: OrderClaimReleaseEligibility,
    val recordRevision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    val items: List<OrderItemSnapshot> = immutableCopy(items)

    init {
        require(orderNo.isNotBlank() && orderNo.length <= MAX_ORDER_NO_LENGTH) {
            "orderNo must be non-blank and length-limited"
        }
        require(deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH) {
            "deviceId must be non-blank and length-limited"
        }
        require(receivedAtMs >= 0L) { "receivedAtMs must be non-negative" }
        require(serverDispatchedAtMs == null || serverDispatchedAtMs >= 0L) {
            "serverDispatchedAtMs must be non-negative"
        }
        require(payTimeMs == null || payTimeMs >= 0L) { "payTimeMs must be non-negative" }
        require(canonicalPayloadSha256.matches(SHA_256_HEX)) {
            "canonical payload hash must be lowercase SHA-256"
        }
        require(this.items.isNotEmpty()) { "active order must contain items" }
        validateItemSequence(this.items)
        require(recordRevision > 0L) { "record revision must be positive" }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
            "record times must be non-negative and monotonic"
        }
        require(
            (admissionStage == OrderAdmissionStage.HOLD_REQUIRED && holdReason != null) ||
                (admissionStage != OrderAdmissionStage.HOLD_REQUIRED && holdReason == null)
        ) { "hold reason must exist only for HOLD_REQUIRED" }
        require(
            (protocolKind == OrderProtocolKind.LEGACY_V1 &&
                dispatchId == null && orderVersion == null && expiresAtMs == null) ||
                (protocolKind == OrderProtocolKind.DISPATCH_V2 &&
                    !dispatchId.isNullOrBlank() && dispatchId.length <= MAX_DISPATCH_ID_LENGTH &&
                    isNormalizedPositiveLong(orderVersion) && isNormalizedPositiveLong(expiresAtMs))
        ) { "protocol metadata does not match protocol kind" }
        require(
            (lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                wholeOrderOutcome != null &&
                claimReleaseEligibility == OrderClaimReleasePolicy.forWholeOrderOutcome(wholeOrderOutcome)) ||
                (lifecycleStage != OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                    wholeOrderOutcome == null &&
                    claimReleaseEligibility == OrderClaimReleaseEligibility.NOT_APPLICABLE)
        ) { "whole-order outcome and release eligibility must belong to a durable terminal state" }
        require(lifecycleStage != OrderLifecycleStage.UNCONFIRMED) {
            "an unconfirmed order must never be stored as an active inbox record"
        }
        require(canonicalPayloadSha256 == recomputeCanonicalHash()) {
            "canonical payload hash does not match stable execution fields"
        }
    }

    fun stableKey(): String = when (protocolKind) {
        OrderProtocolKind.LEGACY_V1 -> orderNo
        OrderProtocolKind.DISPATCH_V2 -> requireNotNull(dispatchId)
    }

    @JvmSynthetic
    internal fun withAdmissionState(
        admissionStage: OrderAdmissionStage,
        holdReason: OrderInboxHoldReason?,
        recordRevision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord {
        require(this.admissionStage == OrderAdmissionStage.PERSISTED_PENDING_PREPARATION) {
            "only a pending record can transition admission state"
        }
        require(
            admissionStage == OrderAdmissionStage.READY_FOR_EXECUTION ||
                admissionStage == OrderAdmissionStage.HOLD_REQUIRED
        ) { "pending admission can transition only to READY or HOLD" }
        require(lifecycleStage == OrderLifecycleStage.ACCEPTED_DURABLE) {
            "admission state can change only while the whole order is accepted"
        }
        require(recordRevision > this.recordRevision) { "record revision must increase" }
        require(updatedAtMs >= this.updatedAtMs) { "updatedAtMs must not move backwards" }
        return recreate(
            admissionStage = admissionStage,
            holdReason = holdReason,
            lifecycleStage = lifecycleStage,
            wholeOrderOutcome = wholeOrderOutcome,
            claimReleaseEligibility = claimReleaseEligibility,
            recordRevision = recordRevision,
            updatedAtMs = updatedAtMs
        )
    }

    @JvmSynthetic
    internal fun withWholeOrderTerminal(
        outcome: WholeOrderOutcome,
        recordRevision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord {
        require(lifecycleStage == OrderLifecycleStage.ACCEPTED_DURABLE) {
            "only an accepted order can become whole-order terminal"
        }
        require(recordRevision > this.recordRevision) { "record revision must increase" }
        require(updatedAtMs >= this.updatedAtMs) { "updatedAtMs must not move backwards" }
        return recreate(
            admissionStage = admissionStage,
            holdReason = holdReason,
            lifecycleStage = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
            wholeOrderOutcome = outcome,
            claimReleaseEligibility = OrderClaimReleasePolicy.forWholeOrderOutcome(outcome),
            recordRevision = recordRevision,
            updatedAtMs = updatedAtMs
        )
    }

    private fun recreate(
        admissionStage: OrderAdmissionStage,
        holdReason: OrderInboxHoldReason?,
        lifecycleStage: OrderLifecycleStage,
        wholeOrderOutcome: WholeOrderOutcome?,
        claimReleaseEligibility: OrderClaimReleaseEligibility,
        recordRevision: Long,
        updatedAtMs: Long
    ) = ActiveOrderRecord(
        protocolKind = protocolKind,
        orderNo = orderNo,
        deviceId = deviceId,
        dispatchId = dispatchId,
        orderVersion = orderVersion,
        expiresAtMs = expiresAtMs,
        receivedAtMs = receivedAtMs,
        serverDispatchedAtMs = serverDispatchedAtMs,
        payTimeMs = payTimeMs,
        canonicalPayloadSha256 = canonicalPayloadSha256,
        items = items,
        admissionStage = admissionStage,
        holdReason = holdReason,
        lifecycleStage = lifecycleStage,
        wholeOrderOutcome = wholeOrderOutcome,
        claimReleaseEligibility = claimReleaseEligibility,
        recordRevision = recordRevision,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )

    private fun recomputeCanonicalHash(): String = OrderCanonicalPayload.sha256(
        protocolKind = protocolKind,
        orderNo = orderNo,
        deviceId = deviceId,
        dispatchId = dispatchId,
        orderVersion = orderVersion,
        expiresAtMs = expiresAtMs,
        items = items
    )

    override fun equals(other: Any?): Boolean = other is ActiveOrderRecord &&
        protocolKind == other.protocolKind && orderNo == other.orderNo &&
        deviceId == other.deviceId && dispatchId == other.dispatchId &&
        orderVersion == other.orderVersion && expiresAtMs == other.expiresAtMs &&
        receivedAtMs == other.receivedAtMs && serverDispatchedAtMs == other.serverDispatchedAtMs &&
        payTimeMs == other.payTimeMs && canonicalPayloadSha256 == other.canonicalPayloadSha256 &&
        items == other.items && admissionStage == other.admissionStage &&
        holdReason == other.holdReason && lifecycleStage == other.lifecycleStage &&
        wholeOrderOutcome == other.wholeOrderOutcome &&
        claimReleaseEligibility == other.claimReleaseEligibility &&
        recordRevision == other.recordRevision && createdAtMs == other.createdAtMs &&
        updatedAtMs == other.updatedAtMs

    override fun hashCode(): Int = listOf(
        protocolKind, orderNo, deviceId, dispatchId, orderVersion, expiresAtMs, receivedAtMs,
        serverDispatchedAtMs, payTimeMs, canonicalPayloadSha256, items, admissionStage,
        holdReason, lifecycleStage, wholeOrderOutcome, claimReleaseEligibility, recordRevision,
        createdAtMs, updatedAtMs
    ).hashCode()

    override fun toString(): String =
        "ActiveOrderRecord(protocolKind=$protocolKind, orderNo=$orderNo, deviceId=$deviceId, " +
            "admissionStage=$admissionStage, lifecycleStage=$lifecycleStage, recordRevision=$recordRevision)"

    companion object {
        @JvmSynthetic
        internal fun accepted(
            protocolKind: OrderProtocolKind,
            orderNo: String,
            deviceId: String,
            dispatchId: String?,
            orderVersion: String?,
            expiresAtMs: String?,
            receivedAtMs: Long,
            serverDispatchedAtMs: Long?,
            payTimeMs: Long?,
            canonicalPayloadSha256: String,
            items: List<OrderItemSnapshot>,
            createdAtMs: Long
        ): ActiveOrderRecord = ActiveOrderRecord(
            protocolKind = protocolKind,
            orderNo = orderNo,
            deviceId = deviceId,
            dispatchId = dispatchId,
            orderVersion = orderVersion,
            expiresAtMs = expiresAtMs,
            receivedAtMs = receivedAtMs,
            serverDispatchedAtMs = serverDispatchedAtMs,
            payTimeMs = payTimeMs,
            canonicalPayloadSha256 = canonicalPayloadSha256,
            items = items,
            admissionStage = OrderAdmissionStage.PERSISTED_PENDING_PREPARATION,
            holdReason = null,
            lifecycleStage = OrderLifecycleStage.ACCEPTED_DURABLE,
            wholeOrderOutcome = null,
            claimReleaseEligibility = OrderClaimReleaseEligibility.NOT_APPLICABLE,
            recordRevision = 1L,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs
        )
    }
}

class OrderTerminalTombstone private constructor(
    val protocolKind: OrderProtocolKind,
    val stableKey: String,
    val canonicalPayloadSha256: String,
    val terminalAtMs: Long
) {
    init {
        require(stableKey.isNotBlank()) { "terminal stable key must not be blank" }
        require(canonicalPayloadSha256.matches(Regex("[0-9a-f]{64}"))) {
            "terminal canonical payload hash must be lowercase SHA-256"
        }
        require(terminalAtMs >= 0L) { "terminal time must be non-negative" }
    }

    companion object {
        fun fromReleaseAllowedTerminal(record: ActiveOrderRecord): OrderTerminalTombstone {
            require(record.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE) {
                "terminal tombstone requires a durable whole-order terminal record"
            }
            require(record.claimReleaseEligibility == OrderClaimReleaseEligibility.RELEASE_ALLOWED) {
                "terminal tombstone requires release-allowed whole-order evidence"
            }
            return OrderTerminalTombstone(
                protocolKind = record.protocolKind,
                stableKey = record.stableKey(),
                canonicalPayloadSha256 = record.canonicalPayloadSha256,
                terminalAtMs = record.updatedAtMs
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is OrderTerminalTombstone &&
        protocolKind == other.protocolKind && stableKey == other.stableKey &&
        canonicalPayloadSha256 == other.canonicalPayloadSha256 &&
        terminalAtMs == other.terminalAtMs

    override fun hashCode(): Int =
        listOf(protocolKind, stableKey, canonicalPayloadSha256, terminalAtMs).hashCode()

    override fun toString(): String =
        "OrderTerminalTombstone(protocolKind=$protocolKind, stableKey=$stableKey, terminalAtMs=$terminalAtMs)"
}

class OrderInboxSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val snapshotRevision: Long = 0L,
    val activeRecord: ActiveOrderRecord? = null,
    terminalTombstones: List<OrderTerminalTombstone> = emptyList()
) {
    val terminalTombstones: List<OrderTerminalTombstone> = immutableCopy(terminalTombstones)

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported inbox schema version" }
        require(snapshotRevision >= 0L) { "snapshot revision must be non-negative" }
        require(
            (activeRecord == null && this.terminalTombstones.isEmpty()) || snapshotRevision > 0L
        ) { "a non-empty snapshot must have a positive revision" }
        val terminalKeys = this.terminalTombstones.map { it.protocolKind to it.stableKey }
        require(terminalKeys.size == terminalKeys.toSet().size) {
            "terminal tombstone stable keys must be unique"
        }
        val activeKey = activeRecord?.let { it.protocolKind to it.stableKey() }
        require(activeKey == null || activeKey !in terminalKeys) {
            "active order and terminal tombstone must not share a stable key"
        }
    }

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        snapshotRevision: Long = this.snapshotRevision,
        activeRecord: ActiveOrderRecord? = this.activeRecord,
        terminalTombstones: List<OrderTerminalTombstone> = this.terminalTombstones
    ): OrderInboxSnapshot = OrderInboxSnapshot(
        schemaVersion = schemaVersion,
        snapshotRevision = snapshotRevision,
        activeRecord = activeRecord,
        terminalTombstones = terminalTombstones
    )

    override fun equals(other: Any?): Boolean = other is OrderInboxSnapshot &&
        schemaVersion == other.schemaVersion && snapshotRevision == other.snapshotRevision &&
        activeRecord == other.activeRecord && terminalTombstones == other.terminalTombstones

    override fun hashCode(): Int =
        listOf(schemaVersion, snapshotRevision, activeRecord, terminalTombstones).hashCode()

    override fun toString(): String =
        "OrderInboxSnapshot(schemaVersion=$schemaVersion, snapshotRevision=$snapshotRevision, " +
            "activeRecord=$activeRecord, terminalTombstones=${terminalTombstones.size})"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun empty(): OrderInboxSnapshot = OrderInboxSnapshot()
    }
}

private const val MAX_ORDER_NO_LENGTH = 128
private const val MAX_DEVICE_ID_LENGTH = 128
private const val MAX_DISPATCH_ID_LENGTH = 128
private const val MAX_TASTE_CODE_LENGTH = 64
private const val MAX_ORDER_QUANTITY = 4
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
private val SHA_256_HEX = Regex("[0-9a-f]{64}")

private fun isNormalizedPositiveLong(value: String?): Boolean {
    if (value == null || !value.matches(Regex("[1-9][0-9]{0,18}"))) return false
    return value.toBigInteger() <= LONG_MAX
}

private fun validateItemSequence(items: List<OrderItemSnapshot>) {
    var expectedOrdinal = 1
    var total = 0
    val itemIds = mutableSetOf<String>()
    items.forEach { item ->
        require(itemIds.add(item.itemId)) { "itemId must be unique within an order" }
        require(item.firstDeliveryOrdinal == expectedOrdinal) {
            "first delivery ordinals must be contiguous and derived from item quantities"
        }
        total += item.quantity
        require(total <= MAX_ORDER_QUANTITY) { "whole-order quantity must be in 1..4" }
        expectedOrdinal += item.quantity
    }
}

private fun <T> immutableCopy(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
