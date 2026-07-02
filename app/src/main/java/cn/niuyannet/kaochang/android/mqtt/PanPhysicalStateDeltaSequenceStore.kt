package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.utils.PreferenceUtils

object PanPhysicalStateDeltaSequenceStore {
    private const val FILE_NAME = "file_pan_physical_state_delta_sequence"
    private const val KEY_SEQUENCE = "key_pan_physical_delta_sequence"

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

    @Synchronized
    fun resetToServerSequence(serverSeq: Long?) {
        if (serverSeq == null || serverSeq < 0L) {
            return
        }
        storage.putLong(KEY_SEQUENCE, serverSeq)
    }

    fun withStorageForTest(testStorage: Storage, block: () -> Unit) {
        val previousStorage = storage
        storage = testStorage
        try {
            block()
        } finally {
            storage = previousStorage
        }
    }
}
