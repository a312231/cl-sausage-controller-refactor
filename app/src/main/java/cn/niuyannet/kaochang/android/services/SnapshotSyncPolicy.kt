package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils

object SnapshotSyncPolicy {
    const val FORCE_FULL_INTERVAL_MS = 15L * 60L * 1000L

    private const val FILE_NAME = "file_snapshot_sync_policy"
    private const val KEY_LAST_HASH = "key_last_successful_snapshot_hash"
    private const val KEY_LAST_FORCE_AT = "key_last_successful_force_full_snapshot_at"
    private const val LOG_SYNC = "云端同步"

    data class UploadDecision(
        val shouldUpload: Boolean,
        val forceFullSnapshot: Boolean,
        val snapshotReason: String
    )

    interface SnapshotSource {
        fun getKaoPanList(): List<KaoPan>
        fun getKaoPanBoxList(): List<KaoPanBox>
    }

    interface State {
        var lastSuccessfulSnapshotHash: String?
        var lastSuccessfulForceFullSnapshotAtMillis: Long
    }

    class InMemoryState : State {
        override var lastSuccessfulSnapshotHash: String? = null
        override var lastSuccessfulForceFullSnapshotAtMillis: Long = 0L
    }

    private object KaoPanSnapshotSource : SnapshotSource {
        override fun getKaoPanList(): List<KaoPan> = KaoPanHelper.getKaoPanList()
        override fun getKaoPanBoxList(): List<KaoPanBox> = KaoPanHelper.getKaoPanBoxList()
    }

    private object PreferenceState : State {
        override var lastSuccessfulSnapshotHash: String?
            get() = PreferenceUtils.getStringPreference(FILE_NAME, KEY_LAST_HASH, null)
            set(value) {
                PreferenceUtils.saveStringPreference(FILE_NAME, KEY_LAST_HASH, value)
            }

        override var lastSuccessfulForceFullSnapshotAtMillis: Long
            get() = PreferenceUtils.getLongPreference(FILE_NAME, KEY_LAST_FORCE_AT, 0L)
            set(value) {
                PreferenceUtils.saveLongPreference(FILE_NAME, KEY_LAST_FORCE_AT, value)
            }
    }

    @Volatile
    private var snapshotSource: SnapshotSource = KaoPanSnapshotSource
    @Volatile
    private var uploader: (String, String, Boolean, (Boolean) -> Unit) -> Unit =
        { snapshotHash, snapshotReason, forceFullSnapshot, callback ->
            DataManagementAPI.uploadDataToServer(
                snapshotHash = snapshotHash,
                snapshotReason = snapshotReason,
                forceFullSnapshot = forceFullSnapshot,
                onResult = callback
            )
        }
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile
    private var state: State = PreferenceState
    @Volatile
    private var logger: (String) -> Unit = { message -> LogUtils.dLocalOnly(message) }
    @Volatile
    private var skippedSnapshotCount: Long = 0L

    fun maybeUploadSnapshot(reason: String = "poll_loop") {
        uploadSnapshot(reason = reason, forceFullSnapshotNow = false)
    }

    fun forceUploadSnapshot(reason: String) {
        uploadSnapshot(reason = reason, forceFullSnapshotNow = true)
    }

    private fun uploadSnapshot(reason: String, forceFullSnapshotNow: Boolean) {
        if (snapshotSource === KaoPanSnapshotSource) {
            KaoPanHelper.normalizeKaoPanTasteMetadata()
            KaoPanHelper.normalizeKaoPanBoxTasteMetadata()
        }
        val pans = snapshotSource.getKaoPanList()
        val boxes = snapshotSource.getKaoPanBoxList()
        val currentHash = SnapshotProjectionHash.hash(pans, boxes)
        val now = clock.invoke()
        val decision = decideUpload(
            currentHash = currentHash,
            lastSuccessfulSnapshotHash = state.lastSuccessfulSnapshotHash,
            lastSuccessfulForceFullSnapshotAtMillis = state.lastSuccessfulForceFullSnapshotAtMillis,
            nowMillis = now,
            reason = reason,
            forceFullSnapshotNow = forceFullSnapshotNow
        )
        if (!decision.shouldUpload) {
            skippedSnapshotCount += 1
            if (skippedSnapshotCount == 1L || skippedSnapshotCount % 60L == 0L) {
                logger.invoke("【$LOG_SYNC】设备快照投影未变化，跳过HTTP同步：reason=$reason，hash=$currentHash，skipCount=$skippedSnapshotCount")
            }
            return
        }
        skippedSnapshotCount = 0L

        uploader.invoke(currentHash, decision.snapshotReason, decision.forceFullSnapshot) { success ->
            if (!success) {
                return@invoke
            }
            state.lastSuccessfulSnapshotHash = currentHash
            if (decision.forceFullSnapshot) {
                state.lastSuccessfulForceFullSnapshotAtMillis = now
            }
        }
    }

    internal fun decideUpload(
        currentHash: String,
        lastSuccessfulSnapshotHash: String?,
        lastSuccessfulForceFullSnapshotAtMillis: Long,
        nowMillis: Long,
        reason: String,
        forceFullSnapshotNow: Boolean = false
    ): UploadDecision {
        if (forceFullSnapshotNow) {
            return UploadDecision(
                shouldUpload = true,
                forceFullSnapshot = true,
                snapshotReason = "$reason:force_full"
            )
        }
        val forceDue = nowMillis - lastSuccessfulForceFullSnapshotAtMillis >= FORCE_FULL_INTERVAL_MS
        if (forceDue) {
            return UploadDecision(
                shouldUpload = true,
                forceFullSnapshot = true,
                snapshotReason = "$reason:fallback_full"
            )
        }
        if (currentHash != lastSuccessfulSnapshotHash) {
            return UploadDecision(
                shouldUpload = true,
                forceFullSnapshot = false,
                snapshotReason = reason
            )
        }
        return UploadDecision(
            shouldUpload = false,
            forceFullSnapshot = false,
            snapshotReason = reason
        )
    }

    internal fun withDependenciesForTest(
        snapshotSource: SnapshotSource,
        uploader: (String, String, Boolean, (Boolean) -> Unit) -> Unit,
        clock: () -> Long,
        state: State,
        block: () -> Unit
    ) {
        val previousSource = this.snapshotSource
        val previousUploader = this.uploader
        val previousClock = this.clock
        val previousState = this.state
        val previousLogger = this.logger
        val previousSkippedSnapshotCount = this.skippedSnapshotCount
        this.snapshotSource = snapshotSource
        this.uploader = uploader
        this.clock = clock
        this.state = state
        this.logger = {}
        this.skippedSnapshotCount = 0L
        try {
            block()
        } finally {
            this.snapshotSource = previousSource
            this.uploader = previousUploader
            this.clock = previousClock
            this.state = previousState
            this.logger = previousLogger
            this.skippedSnapshotCount = previousSkippedSnapshotCount
        }
    }
}
