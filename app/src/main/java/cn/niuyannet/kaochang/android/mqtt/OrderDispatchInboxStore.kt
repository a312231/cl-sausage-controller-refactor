package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class OrderDispatchInboxState {
    ACCEPTED,
    COMMIT_AUTHORIZED,
    EXECUTION_STARTED,
    REJECTED_NO_EXECUTION,
    TERMINAL
}

data class OrderDispatchInboxRecord(
    val dispatchId: String = "",
    val orderNo: String = "",
    val deviceId: String = "",
    val payloadHash: String = "",
    val rawPayload: String = "",
    val ackExpiresAtMs: Long = 0L,
    val commitExpiresAtMs: Long = 0L,
    val state: OrderDispatchInboxState = OrderDispatchInboxState.ACCEPTED,
    val rejectionReason: String? = null,
    val updatedAtMs: Long = 0L
) {
    fun hasSameImmutableIdentity(other: OrderDispatchInboxRecord): Boolean =
        dispatchId == other.dispatchId &&
            orderNo == other.orderNo &&
            deviceId == other.deviceId &&
            payloadHash == other.payloadHash &&
            rawPayload == other.rawPayload

    fun requireValid(): OrderDispatchInboxRecord {
        require(dispatchId.isNotBlank())
        require(orderNo.isNotBlank())
        require(deviceId.isNotBlank())
        require(payloadHash.isNotBlank())
        require(rawPayload.isNotBlank())
        require(ackExpiresAtMs > 0L)
        require(commitExpiresAtMs >= 0L)
        if (state in setOf(
                OrderDispatchInboxState.COMMIT_AUTHORIZED,
                OrderDispatchInboxState.EXECUTION_STARTED,
                OrderDispatchInboxState.TERMINAL
            )
        ) {
            require(commitExpiresAtMs > 0L)
        }
        return this
    }
}

data class OrderDispatchCommitV2(
    val dispatchId: String,
    val orderNo: String,
    val deviceId: String,
    val payloadHash: String,
    val commitExpiresAtMs: Long
)

data class OrderDispatchOfferAckV2(
    val dispatchId: String,
    val orderNo: String,
    val deviceId: String,
    val payloadHash: String,
    val accepted: Boolean,
    val reason: String,
    val reportedAtMs: Long
)

data class OrderDispatchCommitRejectedV2(
    val dispatchId: String,
    val orderNo: String,
    val deviceId: String,
    val payloadHash: String,
    val accepted: Boolean = false,
    val reason: String,
    val reportedAtMs: Long
)

enum class OrderDispatchOfferStoreResult {
    ACCEPTED,
    ALREADY_ACCEPTED,
    REJECTED_DURABLY,
    ALREADY_REJECTED,
    HASH_MISMATCH,
    ACTIVE_DISPATCH_CONFLICT,
    DURABLE_WRITE_FAILED,
    CORRUPTED
}

enum class OrderDispatchCommitStoreResult {
    AUTHORIZED,
    RESUME_AUTHORIZED,
    ALREADY_STARTED,
    TERMINAL,
    ALREADY_REJECTED,
    NOT_ACCEPTED,
    EXPIRED,
    IDENTITY_MISMATCH,
    DURABLE_WRITE_FAILED,
    CORRUPTED
}

enum class OrderDispatchHandleResult {
    OFFER_ACCEPTED,
    OFFER_REJECTED,
    COMMIT_STARTED,
    COMMIT_REJECTED,
    ALREADY_STARTED,
    TERMINAL_IGNORED,
    START_FAILED_UNKNOWN,
    DURABLE_WRITE_FAILED,
    INVALID_MESSAGE,
    NOT_V2_DISPATCH
}

object OrderDispatchPayloadHash {
    fun sha256Utf8(rawPayload: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawPayload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class OrderDispatchInboxStore(
    private val readRaw: () -> String?,
    private val writeRawDurably: (String) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun load(): OrderDispatchInboxRecord? {
        val raw = readRaw()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            JSON.parseObject(raw, OrderDispatchInboxRecord::class.java).requireValid()
        } catch (error: Exception) {
            throw IllegalStateException("订单派发 inbox 证据损坏", error)
        }
    }

    @Synchronized
    fun acceptOfferDurably(offer: OrderDispatchInboxRecord): OrderDispatchOfferStoreResult {
        val candidate = runCatching { offer.requireValid() }
            .getOrElse { return OrderDispatchOfferStoreResult.CORRUPTED }
        val current = runCatching { load() }
            .getOrElse { return OrderDispatchOfferStoreResult.CORRUPTED }
        if (current != null && current.dispatchId == candidate.dispatchId) {
            if (current.payloadHash != candidate.payloadHash || !current.hasSameImmutableIdentity(candidate)) {
                return OrderDispatchOfferStoreResult.HASH_MISMATCH
            }
            return if (current.state == OrderDispatchInboxState.REJECTED_NO_EXECUTION) {
                OrderDispatchOfferStoreResult.ALREADY_REJECTED
            } else {
                OrderDispatchOfferStoreResult.ALREADY_ACCEPTED
            }
        }
        if (current != null && current.state in ACTIVE_STATES) {
            val expiredAccepted = current.state == OrderDispatchInboxState.ACCEPTED &&
                nowMs() > current.ackExpiresAtMs
            if (!expiredAccepted) {
                return OrderDispatchOfferStoreResult.ACTIVE_DISPATCH_CONFLICT
            }
        }
        val saved = runCatching {
            writeRawDurably(JSON.toJSONString(candidate.copy(
                state = OrderDispatchInboxState.ACCEPTED,
                rejectionReason = null,
                updatedAtMs = nowMs()
            )))
        }.getOrDefault(false)
        return if (saved) OrderDispatchOfferStoreResult.ACCEPTED
        else OrderDispatchOfferStoreResult.DURABLE_WRITE_FAILED
    }

    @Synchronized
    fun rejectNoExecutionDurably(
        offer: OrderDispatchInboxRecord,
        reason: String
    ): OrderDispatchOfferStoreResult {
        val candidate = runCatching { offer.requireValid() }
            .getOrElse { return OrderDispatchOfferStoreResult.CORRUPTED }
        val current = runCatching { load() }
            .getOrElse { return OrderDispatchOfferStoreResult.CORRUPTED }
        if (current != null && current.dispatchId == candidate.dispatchId) {
            if (current.payloadHash != candidate.payloadHash || !current.hasSameImmutableIdentity(candidate)) {
                return OrderDispatchOfferStoreResult.HASH_MISMATCH
            }
            if (current.state == OrderDispatchInboxState.REJECTED_NO_EXECUTION) {
                return OrderDispatchOfferStoreResult.ALREADY_REJECTED
            }
            if (current.state == OrderDispatchInboxState.ACCEPTED && nowMs() > current.ackExpiresAtMs) {
                return if (writeRecord(current.copy(
                        state = OrderDispatchInboxState.REJECTED_NO_EXECUTION,
                        rejectionReason = reason,
                        updatedAtMs = nowMs()
                    ))) OrderDispatchOfferStoreResult.REJECTED_DURABLY
                else OrderDispatchOfferStoreResult.DURABLE_WRITE_FAILED
            }
            if (current.state in ACTIVE_STATES) {
                return OrderDispatchOfferStoreResult.ALREADY_ACCEPTED
            }
        }
        if (current?.state in ACTIVE_STATES) {
            return OrderDispatchOfferStoreResult.ACTIVE_DISPATCH_CONFLICT
        }
        val saved = runCatching {
            writeRawDurably(JSON.toJSONString(candidate.copy(
                state = OrderDispatchInboxState.REJECTED_NO_EXECUTION,
                rejectionReason = reason,
                updatedAtMs = nowMs()
            )))
        }.getOrDefault(false)
        return if (saved) OrderDispatchOfferStoreResult.REJECTED_DURABLY
        else OrderDispatchOfferStoreResult.DURABLE_WRITE_FAILED
    }

    @Synchronized
    fun authorizeCommitDurably(commit: OrderDispatchCommitV2): OrderDispatchCommitStoreResult {
        val current = runCatching { load() }
            .getOrElse { return OrderDispatchCommitStoreResult.CORRUPTED }
            ?: return OrderDispatchCommitStoreResult.NOT_ACCEPTED
        if (current.dispatchId != commit.dispatchId) {
            return OrderDispatchCommitStoreResult.IDENTITY_MISMATCH
        }
        when (current.state) {
            OrderDispatchInboxState.EXECUTION_STARTED ->
                return OrderDispatchCommitStoreResult.ALREADY_STARTED
            OrderDispatchInboxState.TERMINAL ->
                return OrderDispatchCommitStoreResult.TERMINAL
            OrderDispatchInboxState.REJECTED_NO_EXECUTION ->
                return OrderDispatchCommitStoreResult.ALREADY_REJECTED
            else -> Unit
        }
        if (current.orderNo != commit.orderNo ||
            current.deviceId != commit.deviceId ||
            current.payloadHash != commit.payloadHash
        ) return OrderDispatchCommitStoreResult.IDENTITY_MISMATCH
        if (commit.commitExpiresAtMs <= nowMs()) {
            val rejected = writeRecord(current.copy(
                state = OrderDispatchInboxState.REJECTED_NO_EXECUTION,
                rejectionReason = "COMMIT_EXPIRED",
                commitExpiresAtMs = commit.commitExpiresAtMs,
                updatedAtMs = nowMs()
            ))
            return if (rejected) OrderDispatchCommitStoreResult.EXPIRED
            else OrderDispatchCommitStoreResult.DURABLE_WRITE_FAILED
        }
        return when (current.state) {
            OrderDispatchInboxState.ACCEPTED -> {
                if (writeRecord(current.copy(
                        state = OrderDispatchInboxState.COMMIT_AUTHORIZED,
                        commitExpiresAtMs = commit.commitExpiresAtMs,
                        updatedAtMs = nowMs()
                    ))) OrderDispatchCommitStoreResult.AUTHORIZED
                else OrderDispatchCommitStoreResult.DURABLE_WRITE_FAILED
            }
            OrderDispatchInboxState.COMMIT_AUTHORIZED -> {
                if (current.commitExpiresAtMs == commit.commitExpiresAtMs) {
                    OrderDispatchCommitStoreResult.RESUME_AUTHORIZED
                } else {
                    OrderDispatchCommitStoreResult.IDENTITY_MISMATCH
                }
            }
            else -> OrderDispatchCommitStoreResult.NOT_ACCEPTED
        }
    }

    @Synchronized
    fun markExecutionStartedDurably(dispatchId: String): Boolean {
        val current = runCatching { load() }.getOrNull() ?: return false
        if (current.dispatchId != dispatchId || current.state != OrderDispatchInboxState.COMMIT_AUTHORIZED) {
            return false
        }
        return writeRecord(current.copy(
            state = OrderDispatchInboxState.EXECUTION_STARTED,
            updatedAtMs = nowMs()
        ))
    }

    @Synchronized
    fun rejectAuthorizedCommitNoExecutionDurably(dispatchId: String, reason: String): Boolean {
        val current = runCatching { load() }.getOrNull() ?: return false
        if (current.dispatchId != dispatchId) return false
        if (current.state == OrderDispatchInboxState.REJECTED_NO_EXECUTION) return true
        if (current.state != OrderDispatchInboxState.COMMIT_AUTHORIZED) return false
        return writeRecord(current.copy(
            state = OrderDispatchInboxState.REJECTED_NO_EXECUTION,
            rejectionReason = reason,
            updatedAtMs = nowMs()
        ))
    }

    @Synchronized
    fun markTerminalDurably(dispatchId: String): Boolean {
        val current = runCatching { load() }.getOrNull() ?: return false
        if (current.dispatchId != dispatchId) return false
        if (current.state == OrderDispatchInboxState.TERMINAL) return true
        if (current.state != OrderDispatchInboxState.EXECUTION_STARTED) return false
        return writeRecord(current.copy(
            state = OrderDispatchInboxState.TERMINAL,
            updatedAtMs = nowMs()
        ))
    }

    @Synchronized
    fun canPublishOrderEvent(dispatchId: String, orderNo: String): Boolean {
        if (dispatchId.isBlank() || orderNo.isBlank()) return false
        val current = runCatching { load() }.getOrNull() ?: return false
        return current.dispatchId == dispatchId &&
            current.orderNo == orderNo &&
            current.state in setOf(
                OrderDispatchInboxState.EXECUTION_STARTED,
                OrderDispatchInboxState.TERMINAL
            )
    }

    private fun writeRecord(record: OrderDispatchInboxRecord): Boolean =
        runCatching { writeRawDurably(JSON.toJSONString(record.requireValid())) }.getOrDefault(false)

    companion object {
        private const val KEY = "sp_order_dispatch_inbox_v2"
        private val ACTIVE_STATES = setOf(
            OrderDispatchInboxState.ACCEPTED,
            OrderDispatchInboxState.COMMIT_AUTHORIZED,
            OrderDispatchInboxState.EXECUTION_STARTED
        )

        fun sharedPreferences(): OrderDispatchInboxStore = OrderDispatchInboxStore(
            readRaw = { PreferenceUtils.getStringPreference(AppConfig.Sp_File_URL, KEY, null) },
            writeRawDurably = { raw ->
                PreferenceUtils.saveStringPreferenceDurable(AppConfig.Sp_File_URL, KEY, raw)
            }
        )
    }
}

class OrderDispatchV2Handler(
    private val store: OrderDispatchInboxStore,
    private val expectedDeviceId: String,
    private val nowMs: () -> Long,
    private val canAcceptOffer: (OrderDispatchInboxRecord) -> Boolean,
    private val publishOfferAck: (OrderDispatchOfferAckV2) -> Boolean,
    private val admitCommit: (OrderDispatchInboxRecord, () -> Boolean) -> Boolean,
    private val executeAcceptedCommit: (OrderDispatchInboxRecord) -> Unit,
    private val publishCommitRejected: (OrderDispatchCommitRejectedV2) -> Boolean
) {
    fun handleAction2(content: String): OrderDispatchHandleResult {
        val json = runCatching { JSON.parseObject(content) }
            .getOrElse { return OrderDispatchHandleResult.INVALID_MESSAGE }
        if (json.getIntValue("protocolVersion") != MqttProtocol.ORDER_DISPATCH_PROTOCOL_VERSION) {
            return OrderDispatchHandleResult.NOT_V2_DISPATCH
        }
        return when (json.getString("messageType")) {
            MqttProtocol.TYPE_ORDER_DISPATCH_OFFER_V2 -> handleOffer(json)
            MqttProtocol.TYPE_ORDER_DISPATCH_COMMIT_V2 -> handleCommit(json)
            else -> OrderDispatchHandleResult.NOT_V2_DISPATCH
        }
    }

    private fun handleOffer(json: JSONObject): OrderDispatchHandleResult {
        val offer = parseOffer(json) ?: return OrderDispatchHandleResult.INVALID_MESSAGE
        val now = nowMs()
        val existing = runCatching { store.load() }
            .getOrElse { return OrderDispatchHandleResult.DURABLE_WRITE_FAILED }
        if (existing != null &&
            existing.state == OrderDispatchInboxState.ACCEPTED &&
            existing.hasSameImmutableIdentity(offer)
        ) {
            if (now <= existing.ackExpiresAtMs) {
                publishOfferAck(offer.toAck(true, "ACCEPTED", now))
                return OrderDispatchHandleResult.OFFER_ACCEPTED
            }
            val rejected = store.rejectNoExecutionDurably(existing, "OFFER_EXPIRED")
            if (rejected == OrderDispatchOfferStoreResult.DURABLE_WRITE_FAILED ||
                rejected == OrderDispatchOfferStoreResult.CORRUPTED
            ) return OrderDispatchHandleResult.DURABLE_WRITE_FAILED
            publishOfferAck(offer.toAck(false, "OFFER_EXPIRED", now))
            return OrderDispatchHandleResult.OFFER_REJECTED
        }
        val rejection = when {
            now > offer.ackExpiresAtMs -> "OFFER_EXPIRED"
            !canAcceptOffer(offer) -> "DEVICE_BUSY"
            else -> null
        }
        val stored = if (rejection == null) {
            store.acceptOfferDurably(offer)
        } else {
            store.rejectNoExecutionDurably(offer, rejection)
        }
        if (stored == OrderDispatchOfferStoreResult.DURABLE_WRITE_FAILED ||
            stored == OrderDispatchOfferStoreResult.CORRUPTED
        ) return OrderDispatchHandleResult.DURABLE_WRITE_FAILED

        val accepted = rejection == null && stored in setOf(
            OrderDispatchOfferStoreResult.ACCEPTED,
            OrderDispatchOfferStoreResult.ALREADY_ACCEPTED
        )
        val reason = when (stored) {
            OrderDispatchOfferStoreResult.HASH_MISMATCH -> "PAYLOAD_HASH_MISMATCH"
            OrderDispatchOfferStoreResult.ACTIVE_DISPATCH_CONFLICT -> "ACTIVE_DISPATCH_CONFLICT"
            OrderDispatchOfferStoreResult.ALREADY_REJECTED -> store.load()?.rejectionReason ?: "REJECTED_NO_EXECUTION"
            else -> rejection ?: if (accepted) "ACCEPTED" else "REJECTED_NO_EXECUTION"
        }
        publishOfferAck(offer.toAck(accepted, reason, now))
        return if (accepted) OrderDispatchHandleResult.OFFER_ACCEPTED
        else OrderDispatchHandleResult.OFFER_REJECTED
    }

    private fun handleCommit(json: JSONObject): OrderDispatchHandleResult {
        val commit = parseCommit(json) ?: return OrderDispatchHandleResult.INVALID_MESSAGE
        val authorized = store.authorizeCommitDurably(commit)
        when (authorized) {
            OrderDispatchCommitStoreResult.AUTHORIZED,
            OrderDispatchCommitStoreResult.RESUME_AUTHORIZED -> Unit
            OrderDispatchCommitStoreResult.ALREADY_STARTED ->
                return OrderDispatchHandleResult.ALREADY_STARTED
            OrderDispatchCommitStoreResult.TERMINAL ->
                return OrderDispatchHandleResult.TERMINAL_IGNORED
            OrderDispatchCommitStoreResult.ALREADY_REJECTED -> {
                val reason = runCatching { store.load()?.rejectionReason }.getOrNull()
                    ?: "REJECTED_NO_EXECUTION"
                publishRejected(commit, reason)
                return OrderDispatchHandleResult.COMMIT_REJECTED
            }
            OrderDispatchCommitStoreResult.DURABLE_WRITE_FAILED,
            OrderDispatchCommitStoreResult.CORRUPTED ->
                return OrderDispatchHandleResult.DURABLE_WRITE_FAILED
            else -> {
                publishRejected(commit, authorized.name)
                return OrderDispatchHandleResult.COMMIT_REJECTED
            }
        }
        val record = store.load() ?: return OrderDispatchHandleResult.DURABLE_WRITE_FAILED
        val admitted = runCatching {
            admitCommit(record) { store.markExecutionStartedDurably(record.dispatchId) }
        }.getOrDefault(false)
        if (!admitted) {
            val currentState = runCatching { store.load()?.state }.getOrNull()
            return when (currentState) {
                OrderDispatchInboxState.COMMIT_AUTHORIZED -> {
                    if (!store.rejectAuthorizedCommitNoExecutionDurably(
                            record.dispatchId,
                            "COMMIT_REJECTED_NO_EXECUTION"
                        )
                    ) return OrderDispatchHandleResult.DURABLE_WRITE_FAILED
                    publishRejected(commit, "ADMISSION_REJECTED_NO_EXECUTION")
                    OrderDispatchHandleResult.COMMIT_REJECTED
                }
                OrderDispatchInboxState.EXECUTION_STARTED ->
                    OrderDispatchHandleResult.START_FAILED_UNKNOWN
                OrderDispatchInboxState.TERMINAL ->
                    OrderDispatchHandleResult.TERMINAL_IGNORED
                OrderDispatchInboxState.REJECTED_NO_EXECUTION -> {
                    val reason = runCatching { store.load()?.rejectionReason }.getOrNull()
                        ?: "REJECTED_NO_EXECUTION"
                    publishRejected(commit, reason)
                    OrderDispatchHandleResult.COMMIT_REJECTED
                }
                else -> OrderDispatchHandleResult.DURABLE_WRITE_FAILED
            }
        }
        val started = runCatching { store.load()?.state }.getOrNull()
        if (started != OrderDispatchInboxState.EXECUTION_STARTED) {
            if (started == OrderDispatchInboxState.COMMIT_AUTHORIZED &&
                store.rejectAuthorizedCommitNoExecutionDurably(
                    record.dispatchId,
                    "COMMIT_REJECTED_NO_EXECUTION"
                )
            ) {
                publishRejected(commit, "ADMISSION_MISSING_EXECUTION_BOUNDARY")
                return OrderDispatchHandleResult.COMMIT_REJECTED
            }
            return OrderDispatchHandleResult.DURABLE_WRITE_FAILED
        }
        return runCatching {
            executeAcceptedCommit(record)
            OrderDispatchHandleResult.COMMIT_STARTED
        }.getOrDefault(OrderDispatchHandleResult.START_FAILED_UNKNOWN)
    }

    private fun parseOffer(json: JSONObject): OrderDispatchInboxRecord? = runCatching {
        val rawPayload = json.getString("raw_order_payload")
        val orderNo = json.getString("order_no")
        val payload = JSON.parseObject(rawPayload)
        require(payload.getString("order_no") == orderNo)
        require(payload.getJSONArray("goods_items")?.isNotEmpty() == true)
        val hash = json.getString("payload_hash")
        require(OrderDispatchPayloadHash.sha256Utf8(rawPayload) == hash)
        OrderDispatchInboxRecord(
            dispatchId = json.getString("dispatch_id"),
            orderNo = orderNo,
            deviceId = json.getString("device_id"),
            payloadHash = hash,
            rawPayload = rawPayload,
            ackExpiresAtMs = json.getLongValue("ack_expires_at_ms"),
            commitExpiresAtMs = 0L
        ).requireValid().also { require(it.deviceId == expectedDeviceId) }
    }.getOrNull()

    private fun parseCommit(json: JSONObject): OrderDispatchCommitV2? = runCatching {
        OrderDispatchCommitV2(
            dispatchId = json.getString("dispatch_id"),
            orderNo = json.getString("order_no"),
            deviceId = json.getString("device_id"),
            payloadHash = json.getString("payload_hash"),
            commitExpiresAtMs = json.getLongValue("commit_expires_at_ms")
        ).also {
            require(it.dispatchId.isNotBlank() && it.orderNo.isNotBlank())
            require(it.deviceId == expectedDeviceId)
            require(it.payloadHash.isNotBlank() && it.commitExpiresAtMs > 0L)
        }
    }.getOrNull()

    private fun publishRejected(commit: OrderDispatchCommitV2, reason: String) {
        publishCommitRejected(
            OrderDispatchCommitRejectedV2(
                dispatchId = commit.dispatchId,
                orderNo = commit.orderNo,
                deviceId = commit.deviceId,
                payloadHash = commit.payloadHash,
                reason = reason,
                reportedAtMs = nowMs()
            )
        )
    }

    private fun OrderDispatchInboxRecord.toAck(
        accepted: Boolean,
        reason: String,
        reportedAtMs: Long
    ) = OrderDispatchOfferAckV2(
        dispatchId = dispatchId,
        orderNo = orderNo,
        deviceId = deviceId,
        payloadHash = payloadHash,
        accepted = accepted,
        reason = reason,
        reportedAtMs = reportedAtMs
    )
}
