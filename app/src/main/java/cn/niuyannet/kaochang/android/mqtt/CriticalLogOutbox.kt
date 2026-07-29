package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

data class CriticalLogEntry(
    val id: String,
    val level: String,
    val tag: String,
    val content: String,
    val reportedAt: Long,
    val enqueuedAt: Long
) {
    fun storageBytes(): Int = storageJson().toJSONString().toByteArray(Charsets.UTF_8).size + 2

    internal fun storageJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("level", level)
        put("tag", tag)
        put("content", content)
        put("reportedAt", reportedAt)
        put("enqueuedAt", enqueuedAt)
    }
}

class CriticalLogOutbox(
    private val storage: Storage,
    private val maxCount: Int = DEFAULT_MAX_COUNT,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    interface Storage {
        fun load(): List<CriticalLogEntry>
        fun save(entries: List<CriticalLogEntry>)
    }

    class InMemoryStorage : Storage {
        private var entries: List<CriticalLogEntry> = emptyList()

        override fun load(): List<CriticalLogEntry> = entries.toList()

        override fun save(entries: List<CriticalLogEntry>) {
            this.entries = entries.toList()
        }
    }

    init {
        require(maxCount > 0)
        require(maxBytes > 0)
        require(maxAgeMs > 0)
    }

    @Synchronized
    fun enqueue(entry: CriticalLogEntry, nowMs: Long): Boolean {
        if (entry.storageBytes() > maxBytes) return false
        val entries = freshEntries(nowMs).toMutableList()
        entries += entry
        trimToBounds(entries)
        storage.save(entries)
        return entries.any { it.id == entry.id }
    }

    @Synchronized
    fun peekBatch(nowMs: Long, limit: Int): List<CriticalLogEntry> {
        require(limit > 0)
        val stored = storage.load()
        val fresh = stored.filter { isFresh(it, nowMs) }
        if (fresh.size != stored.size) {
            storage.save(fresh)
        }
        return fresh.take(limit)
    }

    @Synchronized
    fun remove(id: String, nowMs: Long) {
        val entries = freshEntries(nowMs).filterNot { it.id == id }
        storage.save(entries)
    }

    private fun freshEntries(nowMs: Long): List<CriticalLogEntry> =
        storage.load().filter { isFresh(it, nowMs) }

    private fun isFresh(entry: CriticalLogEntry, nowMs: Long): Boolean =
        nowMs < entry.enqueuedAt || nowMs - entry.enqueuedAt <= maxAgeMs

    private fun trimToBounds(entries: MutableList<CriticalLogEntry>) {
        var totalBytes = entries.sumOf { it.storageBytes() }
        while (entries.size > maxCount || totalBytes > maxBytes) {
            totalBytes -= entries.removeAt(0).storageBytes()
        }
    }

    companion object {
        const val DEFAULT_MAX_COUNT = 200
        const val DEFAULT_MAX_BYTES = 512 * 1024
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

object CriticalLogPreferenceStorage : CriticalLogOutbox.Storage {
    private const val FILE_NAME = "file_critical_log_outbox"
    private const val KEY_ENTRIES = "key_entries"

    override fun load(): List<CriticalLogEntry> {
        val raw = PreferenceUtils.getStringPreference(FILE_NAME, KEY_ENTRIES, null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            JSON.parseArray(raw).mapNotNull { value ->
                val json = value as? JSONObject ?: return@mapNotNull null
                val id = json.getString("id").orEmpty()
                if (id.isBlank()) return@mapNotNull null
                CriticalLogEntry(
                    id = id,
                    level = json.getString("level").orEmpty(),
                    tag = json.getString("tag").orEmpty(),
                    content = json.getString("content").orEmpty(),
                    reportedAt = json.getLongValue("reportedAt"),
                    enqueuedAt = json.getLongValue("enqueuedAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun save(entries: List<CriticalLogEntry>) {
        if (entries.isEmpty()) {
            PreferenceUtils.saveStringPreference(FILE_NAME, KEY_ENTRIES, "")
            return
        }
        val array = JSONArray()
        entries.forEach { entry ->
            array.add(entry.storageJson())
        }
        PreferenceUtils.saveStringPreference(FILE_NAME, KEY_ENTRIES, array.toJSONString())
    }
}
