package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.utils.PreferenceUtils

object CloudSnapshotSequenceStore {
    private const val FILE_NAME = "file_cloud_snapshot_sequence"
    private const val KEY_SEQUENCE = "key_snapshot_sequence"

    interface Storage {
        fun getLong(key: String): Long
        fun putLong(key: String, value: Long)
    }

    class InMemoryStorage : Storage {
        private val values = mutableMapOf<String, Long>()

        override fun getLong(key: String): Long = values[key] ?: 0L

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }
    }

    private object PreferenceStorage : Storage {
        override fun getLong(key: String): Long =
            PreferenceUtils.getLongPreference(FILE_NAME, key, 0L)

        override fun putLong(key: String, value: Long) {
            PreferenceUtils.saveLongPreference(FILE_NAME, key, value)
        }
    }

    @Volatile
    private var storage: Storage = PreferenceStorage

    @Synchronized
    fun nextSequence(): Long {
        val next = currentSequence() + 1L
        storage.putLong(KEY_SEQUENCE, next)
        return next
    }

    fun currentSequence(): Long = storage.getLong(KEY_SEQUENCE).coerceAtLeast(0L)

    fun withStorageForTest(testStorage: Storage, block: () -> Unit) {
        val previous = storage
        storage = testStorage
        try {
            block()
        } finally {
            storage = previous
        }
    }
}
