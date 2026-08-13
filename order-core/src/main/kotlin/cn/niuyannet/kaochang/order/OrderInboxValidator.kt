package cn.niuyannet.kaochang.order

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class OrderInboxValidationCode {
    PROTOCOL_KIND_MISSING,
    ORDER_NO_MISSING,
    ORDER_NO_TOO_LONG,
    DEVICE_ID_MISSING,
    DEVICE_ID_TOO_LONG,
    DEVICE_ID_MISMATCH,
    RECEIVED_AT_MISSING,
    METADATA_TIME_NEGATIVE,
    ITEMS_MISSING,
    ITEMS_EMPTY,
    ITEM_COUNT_EXCEEDS_ORDER_LIMIT,
    ITEM_ID_MISSING,
    ITEM_ID_INVALID_DECIMAL,
    ITEM_ID_NOT_POSITIVE,
    ITEM_ID_OUT_OF_LONG_RANGE,
    DUPLICATE_ITEM_ID,
    PRODUCT_ID_MISSING,
    PRODUCT_ID_INVALID_DECIMAL,
    PRODUCT_ID_NOT_POSITIVE,
    PRODUCT_ID_OUT_OF_LONG_RANGE,
    TASTE_CODE_MISSING,
    TASTE_CODE_TOO_LONG,
    ITEM_QUANTITY_MISSING,
    ITEM_QUANTITY_INVALID_DECIMAL,
    ITEM_QUANTITY_NOT_POSITIVE,
    ITEM_QUANTITY_EXCEEDS_ORDER_LIMIT,
    TOTAL_QUANTITY_OUT_OF_RANGE,
    LEGACY_HAS_V2_METADATA,
    DISPATCH_ID_MISSING,
    DISPATCH_ID_TOO_LONG,
    ORDER_VERSION_MISSING,
    ORDER_VERSION_INVALID_DECIMAL,
    ORDER_VERSION_NOT_POSITIVE,
    ORDER_VERSION_OUT_OF_LONG_RANGE,
    EXPIRES_AT_MISSING,
    EXPIRES_AT_INVALID_DECIMAL,
    EXPIRES_AT_NOT_POSITIVE,
    EXPIRES_AT_OUT_OF_LONG_RANGE
}

data class OrderInboxValidationError(
    val code: OrderInboxValidationCode,
    val itemIndex: Int? = null
)

sealed class OrderInboxValidationResult {
    data class Valid(val record: ActiveOrderRecord) : OrderInboxValidationResult()
    data class Invalid(val errors: List<OrderInboxValidationError>) : OrderInboxValidationResult()
}

object OrderInboxValidator {
    private const val MAX_ORDER_NO_LENGTH = 128
    private const val MAX_DEVICE_ID_LENGTH = 128
    private const val MAX_DISPATCH_ID_LENGTH = 128
    private const val MAX_TASTE_CODE_LENGTH = 64
    private const val MAX_ORDER_QUANTITY = 4
    private const val MAX_ORDER_ITEMS = 4
    private const val MAX_LONG_DECIMAL_TEXT_LENGTH = 20
    private const val MAX_QUANTITY_TEXT_LENGTH = 2
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val ZERO = BigInteger.ZERO

    fun validate(
        draft: IncomingOrderDraft,
        expectedDeviceId: String,
        createdAtMs: Long
    ): OrderInboxValidationResult {
        require(expectedDeviceId.isNotBlank()) { "expected device id must not be blank" }
        require(createdAtMs >= 0L) { "createdAtMs must be non-negative" }

        val errors = mutableListOf<OrderInboxValidationError>()
        val protocolKind = draft.protocolKind.also {
            if (it == null) errors += error(OrderInboxValidationCode.PROTOCOL_KIND_MISSING)
        }
        val orderNo = requiredText(
            draft.orderNo,
            MAX_ORDER_NO_LENGTH,
            OrderInboxValidationCode.ORDER_NO_MISSING,
            OrderInboxValidationCode.ORDER_NO_TOO_LONG,
            errors
        )
        val deviceId = requiredText(
            draft.deviceId,
            MAX_DEVICE_ID_LENGTH,
            OrderInboxValidationCode.DEVICE_ID_MISSING,
            OrderInboxValidationCode.DEVICE_ID_TOO_LONG,
            errors
        )
        if (deviceId != null && deviceId != expectedDeviceId.trim()) {
            errors += error(OrderInboxValidationCode.DEVICE_ID_MISMATCH)
        }

        val receivedAtMs = draft.receivedAtMs.also {
            if (it == null) errors += error(OrderInboxValidationCode.RECEIVED_AT_MISSING)
            else if (it < 0L) errors += error(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        }
        if (draft.serverDispatchedAtMs != null && draft.serverDispatchedAtMs < 0L) {
            errors += error(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        }
        if (draft.payTimeMs != null && draft.payTimeMs < 0L) {
            errors += error(OrderInboxValidationCode.METADATA_TIME_NEGATIVE)
        }

        val protocolMetadata = validateProtocolMetadata(draft, protocolKind, errors)
        val normalizedItems = validateItems(draft.items, errors)

        if (errors.isNotEmpty()) {
            return OrderInboxValidationResult.Invalid(errors.distinct())
        }

        val validProtocol = requireNotNull(protocolKind)
        val validOrderNo = requireNotNull(orderNo)
        val validDeviceId = requireNotNull(deviceId)
        val validReceivedAt = requireNotNull(receivedAtMs)
        val validItems = requireNotNull(normalizedItems)
        val canonicalHash = OrderCanonicalPayload.sha256(
            protocolKind = validProtocol,
            orderNo = validOrderNo,
            deviceId = validDeviceId,
            dispatchId = protocolMetadata.dispatchId,
            orderVersion = protocolMetadata.orderVersion,
            expiresAtMs = protocolMetadata.expiresAtMs,
            items = validItems
        )

        return OrderInboxValidationResult.Valid(
            ActiveOrderRecord.accepted(
                protocolKind = validProtocol,
                orderNo = validOrderNo,
                deviceId = validDeviceId,
                dispatchId = protocolMetadata.dispatchId,
                orderVersion = protocolMetadata.orderVersion,
                expiresAtMs = protocolMetadata.expiresAtMs,
                receivedAtMs = validReceivedAt,
                serverDispatchedAtMs = draft.serverDispatchedAtMs,
                payTimeMs = draft.payTimeMs,
                canonicalPayloadSha256 = canonicalHash,
                items = validItems,
                createdAtMs = createdAtMs
            )
        )
    }

    private fun validateProtocolMetadata(
        draft: IncomingOrderDraft,
        protocolKind: OrderProtocolKind?,
        errors: MutableList<OrderInboxValidationError>
    ): ProtocolMetadata {
        if (protocolKind == OrderProtocolKind.LEGACY_V1) {
            if (draft.dispatchId != null || draft.orderVersion != null || draft.expiresAtMs != null) {
                errors += error(OrderInboxValidationCode.LEGACY_HAS_V2_METADATA)
            }
            return ProtocolMetadata(null, null, null)
        }
        if (protocolKind != OrderProtocolKind.DISPATCH_V2) {
            return ProtocolMetadata(null, null, null)
        }

        val dispatchId = requiredText(
            draft.dispatchId,
            MAX_DISPATCH_ID_LENGTH,
            OrderInboxValidationCode.DISPATCH_ID_MISSING,
            OrderInboxValidationCode.DISPATCH_ID_TOO_LONG,
            errors
        )
        val orderVersion = positiveLongDecimal(
            draft.orderVersion,
            OrderInboxValidationCode.ORDER_VERSION_MISSING,
            OrderInboxValidationCode.ORDER_VERSION_INVALID_DECIMAL,
            OrderInboxValidationCode.ORDER_VERSION_NOT_POSITIVE,
            OrderInboxValidationCode.ORDER_VERSION_OUT_OF_LONG_RANGE,
            errors
        )
        val expiresAtMs = positiveLongDecimal(
            draft.expiresAtMs,
            OrderInboxValidationCode.EXPIRES_AT_MISSING,
            OrderInboxValidationCode.EXPIRES_AT_INVALID_DECIMAL,
            OrderInboxValidationCode.EXPIRES_AT_NOT_POSITIVE,
            OrderInboxValidationCode.EXPIRES_AT_OUT_OF_LONG_RANGE,
            errors
        )
        return ProtocolMetadata(dispatchId, orderVersion, expiresAtMs)
    }

    private fun validateItems(
        drafts: List<IncomingOrderItemDraft>?,
        errors: MutableList<OrderInboxValidationError>
    ): List<OrderItemSnapshot>? {
        if (drafts == null) {
            errors += error(OrderInboxValidationCode.ITEMS_MISSING)
            return null
        }
        if (drafts.isEmpty()) {
            errors += error(OrderInboxValidationCode.ITEMS_EMPTY)
            return null
        }
        if (drafts.size > MAX_ORDER_ITEMS) {
            errors += error(OrderInboxValidationCode.ITEM_COUNT_EXCEEDS_ORDER_LIMIT)
            return null
        }

        var total = ZERO
        var nextOrdinal = 1
        val normalized = mutableListOf<OrderItemSnapshot>()
        val normalizedItemIds = mutableSetOf<String>()
        drafts.forEachIndexed { index, draft ->
            val before = errors.size
            val itemId = positiveLongDecimal(
                draft.itemId,
                OrderInboxValidationCode.ITEM_ID_MISSING,
                OrderInboxValidationCode.ITEM_ID_INVALID_DECIMAL,
                OrderInboxValidationCode.ITEM_ID_NOT_POSITIVE,
                OrderInboxValidationCode.ITEM_ID_OUT_OF_LONG_RANGE,
                errors,
                index
            )
            if (itemId != null && !normalizedItemIds.add(itemId)) {
                errors += error(OrderInboxValidationCode.DUPLICATE_ITEM_ID, index)
            }
            val productId = positiveLongDecimal(
                draft.productId,
                OrderInboxValidationCode.PRODUCT_ID_MISSING,
                OrderInboxValidationCode.PRODUCT_ID_INVALID_DECIMAL,
                OrderInboxValidationCode.PRODUCT_ID_NOT_POSITIVE,
                OrderInboxValidationCode.PRODUCT_ID_OUT_OF_LONG_RANGE,
                errors,
                index
            )
            val tasteCode = requiredText(
                draft.tasteCode,
                MAX_TASTE_CODE_LENGTH,
                OrderInboxValidationCode.TASTE_CODE_MISSING,
                OrderInboxValidationCode.TASTE_CODE_TOO_LONG,
                errors,
                index
            )
            val quantity = positiveQuantity(draft.quantity, errors, index)
            if (quantity != null) total += quantity

            if (errors.size == before && itemId != null && productId != null &&
                tasteCode != null && quantity != null
            ) {
                val quantityInt = quantity.toInt()
                normalized += OrderItemSnapshot(
                    itemId = itemId,
                    productId = productId,
                    tasteCode = tasteCode,
                    quantity = quantityInt,
                    firstDeliveryOrdinal = nextOrdinal
                )
                nextOrdinal += quantityInt
            }
        }

        if (total <= ZERO || total > BigInteger.valueOf(MAX_ORDER_QUANTITY.toLong())) {
            errors += error(OrderInboxValidationCode.TOTAL_QUANTITY_OUT_OF_RANGE)
        }
        return if (errors.isEmpty()) normalized.toList() else null
    }

    private fun positiveQuantity(
        raw: String?,
        errors: MutableList<OrderInboxValidationError>,
        itemIndex: Int
    ): BigInteger? {
        if (raw.isNullOrBlank()) {
            errors += error(OrderInboxValidationCode.ITEM_QUANTITY_MISSING, itemIndex)
            return null
        }
        val normalized = raw.trim()
        if (normalized.length > MAX_QUANTITY_TEXT_LENGTH) {
            errors += error(OrderInboxValidationCode.ITEM_QUANTITY_EXCEEDS_ORDER_LIMIT, itemIndex)
            return null
        }
        val value = normalized.toBigIntegerOrNull()
        if (value == null) {
            errors += error(OrderInboxValidationCode.ITEM_QUANTITY_INVALID_DECIMAL, itemIndex)
            return null
        }
        if (value <= ZERO) {
            errors += error(OrderInboxValidationCode.ITEM_QUANTITY_NOT_POSITIVE, itemIndex)
            return value
        }
        if (value > BigInteger.valueOf(MAX_ORDER_QUANTITY.toLong())) {
            errors += error(OrderInboxValidationCode.ITEM_QUANTITY_EXCEEDS_ORDER_LIMIT, itemIndex)
        }
        return value
    }

    private fun positiveLongDecimal(
        raw: String?,
        missingCode: OrderInboxValidationCode,
        invalidCode: OrderInboxValidationCode,
        nonPositiveCode: OrderInboxValidationCode,
        outOfRangeCode: OrderInboxValidationCode,
        errors: MutableList<OrderInboxValidationError>,
        itemIndex: Int? = null
    ): String? {
        if (raw.isNullOrBlank()) {
            errors += error(missingCode, itemIndex)
            return null
        }
        val normalized = raw.trim()
        if (normalized.length > MAX_LONG_DECIMAL_TEXT_LENGTH) {
            errors += error(outOfRangeCode, itemIndex)
            return null
        }
        val value = normalized.toBigIntegerOrNull()
        if (value == null) {
            errors += error(invalidCode, itemIndex)
            return null
        }
        if (value <= ZERO) {
            errors += error(nonPositiveCode, itemIndex)
            return null
        }
        if (value > LONG_MAX) {
            errors += error(outOfRangeCode, itemIndex)
            return null
        }
        return value.toString()
    }

    private fun requiredText(
        raw: String?,
        maxLength: Int,
        missingCode: OrderInboxValidationCode,
        tooLongCode: OrderInboxValidationCode,
        errors: MutableList<OrderInboxValidationError>,
        itemIndex: Int? = null
    ): String? {
        val normalized = raw?.trim()
        if (normalized.isNullOrEmpty()) {
            errors += error(missingCode, itemIndex)
            return null
        }
        if (normalized.length > maxLength) {
            errors += error(tooLongCode, itemIndex)
            return null
        }
        return normalized
    }

    private fun error(code: OrderInboxValidationCode, itemIndex: Int? = null) =
        OrderInboxValidationError(code, itemIndex)

    private data class ProtocolMetadata(
        val dispatchId: String?,
        val orderVersion: String?,
        val expiresAtMs: String?
    )
}

object OrderCanonicalPayload {
    fun sha256(
        protocolKind: OrderProtocolKind,
        orderNo: String,
        deviceId: String,
        dispatchId: String?,
        orderVersion: String?,
        expiresAtMs: String?,
        items: List<OrderItemSnapshot>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fields = buildList {
            add("order-inbox-canonical-v1")
            add(protocolKind.name)
            add(orderNo)
            add(deviceId)
            add(dispatchId)
            add(orderVersion)
            add(expiresAtMs)
            add(items.size.toString())
            items.forEach { item ->
                add(item.itemId)
                add(item.productId)
                add(item.tasteCode)
                add(item.quantity.toString())
            }
        }
        fields.forEach { field -> updateLengthPrefixed(digest, field) }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun updateLengthPrefixed(digest: MessageDigest, field: String?) {
        if (field == null) {
            updateInt(digest, -1)
            return
        }
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        updateInt(digest, bytes.size)
        digest.update(bytes)
    }

    private fun updateInt(digest: MessageDigest, value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
    }
}
