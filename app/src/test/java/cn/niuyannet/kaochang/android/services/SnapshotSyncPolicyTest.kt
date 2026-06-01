package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSyncPolicyTest {

    @Test
    fun decideUpload_shouldSkipWhenHashUnchangedAndFallbackNotDue() {
        val decision = SnapshotSyncPolicy.decideUpload(
            currentHash = "same",
            lastSuccessfulSnapshotHash = "same",
            lastSuccessfulForceFullSnapshotAtMillis = 1_000L,
            nowMillis = 1_000L + SnapshotSyncPolicy.FORCE_FULL_INTERVAL_MS - 1L,
            reason = "poll_loop"
        )

        assertFalse(decision.shouldUpload)
        assertFalse(decision.forceFullSnapshot)
    }

    @Test
    fun decideUpload_shouldUploadWhenHashChanged() {
        val decision = SnapshotSyncPolicy.decideUpload(
            currentHash = "new",
            lastSuccessfulSnapshotHash = "old",
            lastSuccessfulForceFullSnapshotAtMillis = 1_000L,
            nowMillis = 2_000L,
            reason = "poll_loop"
        )

        assertTrue(decision.shouldUpload)
        assertFalse(decision.forceFullSnapshot)
        assertEquals("poll_loop", decision.snapshotReason)
    }

    @Test
    fun decideUpload_shouldForceFullSnapshotWhenFallbackDueEvenIfHashUnchanged() {
        val decision = SnapshotSyncPolicy.decideUpload(
            currentHash = "same",
            lastSuccessfulSnapshotHash = "same",
            lastSuccessfulForceFullSnapshotAtMillis = 1_000L,
            nowMillis = 1_000L + SnapshotSyncPolicy.FORCE_FULL_INTERVAL_MS,
            reason = "poll_loop"
        )

        assertTrue(decision.shouldUpload)
        assertTrue(decision.forceFullSnapshot)
        assertEquals("poll_loop:fallback_full", decision.snapshotReason)
    }

    @Test
    fun maybeUploadSnapshot_shouldNotUpdateSuccessfulStateWhenUploadFails() {
        val state = SnapshotSyncPolicy.InMemoryState().apply {
            lastSuccessfulSnapshotHash = "old"
            lastSuccessfulForceFullSnapshotAtMillis = 123L
        }
        var uploadCount = 0

        SnapshotSyncPolicy.withDependenciesForTest(
            snapshotSource = source(pans = listOf(pan("A")), boxes = emptyList()),
            uploader = { _, _, _, callback ->
                uploadCount += 1
                callback(false)
            },
            clock = { 1_000L },
            state = state
        ) {
            SnapshotSyncPolicy.maybeUploadSnapshot("poll_loop")
        }

        assertEquals(1, uploadCount)
        assertEquals("old", state.lastSuccessfulSnapshotHash)
        assertEquals(123L, state.lastSuccessfulForceFullSnapshotAtMillis)
    }

    @Test
    fun maybeUploadSnapshot_shouldUpdateFallbackTimeOnlyAfterSuccessfulForceUpload() {
        val pan = pan("A")
        val hash = SnapshotProjectionHash.hash(listOf(pan), emptyList())
        val state = SnapshotSyncPolicy.InMemoryState().apply {
            lastSuccessfulSnapshotHash = hash
            lastSuccessfulForceFullSnapshotAtMillis = 1_000L
        }

        SnapshotSyncPolicy.withDependenciesForTest(
            snapshotSource = source(pans = listOf(pan), boxes = emptyList()),
            uploader = { snapshotHash, _, forceFullSnapshot, callback ->
                assertEquals(hash, snapshotHash)
                assertTrue(forceFullSnapshot)
                callback(true)
            },
            clock = { 1_000L + SnapshotSyncPolicy.FORCE_FULL_INTERVAL_MS },
            state = state
        ) {
            SnapshotSyncPolicy.maybeUploadSnapshot("poll_loop")
        }

        assertEquals(hash, state.lastSuccessfulSnapshotHash)
        assertEquals(1_000L + SnapshotSyncPolicy.FORCE_FULL_INTERVAL_MS, state.lastSuccessfulForceFullSnapshotAtMillis)
    }

    @Test
    fun maybeUploadSnapshot_shouldSkipUploaderWhenHashUnchangedAndFallbackNotDue() {
        val pan = pan("A")
        val hash = SnapshotProjectionHash.hash(listOf(pan), emptyList())
        val state = SnapshotSyncPolicy.InMemoryState().apply {
            lastSuccessfulSnapshotHash = hash
            lastSuccessfulForceFullSnapshotAtMillis = 1_000L
        }
        var uploadedHash: String? = null

        SnapshotSyncPolicy.withDependenciesForTest(
            snapshotSource = source(pans = listOf(pan), boxes = emptyList()),
            uploader = { snapshotHash, _, _, callback ->
                uploadedHash = snapshotHash
                callback(true)
            },
            clock = { 1_000L + SnapshotSyncPolicy.FORCE_FULL_INTERVAL_MS - 1L },
            state = state
        ) {
            SnapshotSyncPolicy.maybeUploadSnapshot("poll_loop")
        }

        assertNull(uploadedHash)
    }

    private fun pan(tasteCode: String): KaoPan =
        KaoPan().apply {
            positionSn = 1
            isHasSausage = true
            status = 2
            holdingTime = 5_000L
            taste = Taste(1, tasteCode, "原味", 101, "烤肠")
        }

    private fun source(
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ): SnapshotSyncPolicy.SnapshotSource =
        object : SnapshotSyncPolicy.SnapshotSource {
            override fun getKaoPanList(): List<KaoPan> = pans
            override fun getKaoPanBoxList(): List<KaoPanBox> = boxes
        }
}
