package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.utils.PreferenceUtils

object PanPhysicalStateBaselineStore {
    private const val FILE_NAME = "file_pan_physical_state_baseline"
    private const val KEY_SEQUENCE = "key_confirmed_snapshot_sequence"
    private const val KEY_HASH = "key_confirmed_snapshot_hash"
    private const val KEY_PAN_DELTA_SEQ = "key_confirmed_pan_delta_seq"

    data class Baseline(
        val sequence: Long,
        val snapshotHash: String,
        val panDeltaSeq: Long
    )

    interface Storage {
        fun getLong(key: String, defaultValue: Long): Long
        fun putLong(key: String, value: Long)
        fun getString(key: String): String?
        fun putString(key: String, value: String?)
    }

    class InMemoryStorage : Storage {
        private val longs = mutableMapOf<String, Long>()
        private val strings = mutableMapOf<String, String?>()

        override fun getLong(key: String, defaultValue: Long): Long = longs[key] ?: defaultValue

        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }

        override fun getString(key: String): String? = strings[key]

        override fun putString(key: String, value: String?) {
            strings[key] = value
        }
    }

    private object PreferenceStorage : Storage {
        override fun getLong(key: String, defaultValue: Long): Long =
            PreferenceUtils.getLongPreference(FILE_NAME, key, defaultValue)

        override fun putLong(key: String, value: Long) {
            PreferenceUtils.saveLongPreference(FILE_NAME, key, value)
        }

        override fun getString(key: String): String? =
            PreferenceUtils.getStringPreference(FILE_NAME, key, null)

        override fun putString(key: String, value: String?) {
            PreferenceUtils.saveStringPreference(FILE_NAME, key, value)
        }
    }

    @Volatile
    private var storage: Storage = PreferenceStorage

    @Synchronized
    fun recordConfirmedSnapshot(sequence: Long, snapshotHash: String?, panDeltaSeq: Long?) {
        if (sequence <= 0L || snapshotHash.isNullOrBlank() || panDeltaSeq == null || panDeltaSeq < 0L) {
            return
        }
        storage.putLong(KEY_SEQUENCE, sequence)
        storage.putString(KEY_HASH, snapshotHash)
        storage.putLong(KEY_PAN_DELTA_SEQ, panDeltaSeq)
    }

    fun currentBaseline(): Baseline? {
        val sequence = storage.getLong(KEY_SEQUENCE, 0L)
        val snapshotHash = storage.getString(KEY_HASH)
        val panDeltaSeq = storage.getLong(KEY_PAN_DELTA_SEQ, -1L)
        if (sequence <= 0L || snapshotHash.isNullOrBlank() || panDeltaSeq < 0L) {
            return null
        }
        return Baseline(sequence, snapshotHash, panDeltaSeq)
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
}
