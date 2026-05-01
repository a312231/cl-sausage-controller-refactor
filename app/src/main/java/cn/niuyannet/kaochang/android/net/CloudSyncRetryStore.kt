package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object CloudSyncRetryStore {
    private const val FILE_NAME = "file_cloud_sync_retry"
    private const val KEY_KAO_PAN = "key_pending_kao_pan"
    private const val KEY_STOCK = "key_pending_stock"

    enum class Type(val label: String) {
        KAO_PAN("烤盘状态"),
        STOCK("烤肠箱库存")
    }

    data class PendingPayload(
        val type: Type,
        val payload: String,
        val savedAtMillis: Long,
        val failureCount: Int,
        val lastFailureAtMillis: Long,
        val lastError: String
    )

    interface Storage {
        fun getString(key: String): String?
        fun putString(key: String, value: String?)
    }

    class InMemoryStorage : Storage {
        private val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    private object PreferenceStorage : Storage {
        override fun getString(key: String): String? =
            PreferenceUtils.getStringPreference(FILE_NAME, key, null)

        override fun putString(key: String, value: String?) {
            PreferenceUtils.saveStringPreference(FILE_NAME, key, value ?: "")
        }
    }

    @Volatile
    private var storage: Storage = PreferenceStorage

    fun getPending(type: Type): PendingPayload? {
        val raw = storage.getString(keyFor(type))
        if (raw.isNullOrBlank()) {
            return null
        }
        return parsePendingPayload(raw, type)
    }

    fun recordFailure(
        type: Type,
        payload: String,
        error: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): PendingPayload {
        val previous = getPending(type)
        val pending = PendingPayload(
            type = type,
            payload = payload,
            savedAtMillis = previous?.savedAtMillis ?: nowMillis,
            failureCount = (previous?.failureCount ?: 0) + 1,
            lastFailureAtMillis = nowMillis,
            lastError = error.orEmpty()
        )
        storage.putString(keyFor(type), serialize(pending))
        return pending
    }

    fun markSucceeded(type: Type): PendingPayload? {
        val pending = getPending(type) ?: return null
        storage.putString(keyFor(type), "")
        return pending
    }

    fun withStorageForTest(testStorage: Storage, block: () -> Unit) {
        val previous = storage
        storage = testStorage
        try {
            block()
        } finally {
            storage = previous
        }
    }

    fun typeText(type: Type): String = "${type.name}（${type.label}）"

    private fun keyFor(type: Type): String = when (type) {
        Type.KAO_PAN -> KEY_KAO_PAN
        Type.STOCK -> KEY_STOCK
    }

    private fun serialize(pending: PendingPayload): String {
        val json = JSONObject()
        json["type"] = pending.type.name
        json["payload"] = pending.payload
        json["savedAtMillis"] = pending.savedAtMillis
        json["failureCount"] = pending.failureCount
        json["lastFailureAtMillis"] = pending.lastFailureAtMillis
        json["lastError"] = pending.lastError
        return json.toJSONString()
    }

    private fun parsePendingPayload(raw: String, fallbackType: Type): PendingPayload? {
        return try {
            val json = JSON.parseObject(raw)
            val type = runCatching {
                Type.valueOf(json.getString("type") ?: fallbackType.name)
            }.getOrDefault(fallbackType)
            PendingPayload(
                type = type,
                payload = json.getString("payload").orEmpty(),
                savedAtMillis = json.getLongValue("savedAtMillis"),
                failureCount = json.getIntValue("failureCount"),
                lastFailureAtMillis = json.getLongValue("lastFailureAtMillis"),
                lastError = json.getString("lastError").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }
}
