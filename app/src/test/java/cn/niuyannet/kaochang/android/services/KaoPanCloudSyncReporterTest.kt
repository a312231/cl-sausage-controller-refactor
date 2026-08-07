package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoPanCloudSyncReporterTest {

    @Test
    fun hasSellableStateChanged_shouldDetectSellableCountIncrease() {
        val before = listOf(
            sellablePan(7, "A"),
            sellablePan(8, "A"),
            bakingPan(9, "A")
        )
        val after = listOf(
            sellablePan(7, "A"),
            sellablePan(8, "A"),
            sellablePan(9, "A")
        )

        assertTrue(KaoPanCloudSyncReporter.hasSellableStateChanged(before, after))
    }

    @Test
    fun hasSellableStateChanged_shouldDetectSellablePositionChangeWhenCountSame() {
        val before = listOf(
            sellablePan(10, "A"),
            sellablePan(11, "A")
        )
        val after = listOf(
            sellablePan(1, "A"),
            sellablePan(11, "A")
        )

        assertTrue(KaoPanCloudSyncReporter.hasSellableStateChanged(before, after))
    }

    @Test
    fun hasSellableStateChanged_shouldIgnoreNonSellableBakingChanges() {
        val before = listOf(
            sellablePan(7, "A"),
            bakingPan(13, "A")
        )
        val after = listOf(
            sellablePan(7, "A"),
            bakingPan(13, "A", startTime = 2000L)
        )

        assertFalse(KaoPanCloudSyncReporter.hasSellableStateChanged(before, after))
    }

    @Test
    fun uploadIfSellableStateChanged_shouldCallUploaderOnceWhenSellableStateChanged() {
        val before = listOf(sellablePan(7, "A"), bakingPan(9, "A"))
        val after = listOf(sellablePan(7, "A"), sellablePan(9, "A"))
        var uploadCount = 0
        var uploadReason: String? = null

        KaoPanCloudSyncReporter.withUploaderForTest({ reason ->
            uploadCount++
            uploadReason = reason
        }) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(before, after, "测试可售变化")
        }

        assertEquals(1, uploadCount)
        assertEquals("测试可售变化", uploadReason)
    }

    @Test
    fun uploadIfSellableStateChanged_shouldUseSnapshotToAvoidLivePanAlias() {
        val source = sellablePan(1, "A")
        val target = KaoPan().apply {
            positionSn = 11
            isHasSausage = false
            status = 0
            holdingTime = 0L
        }
        val livePans = listOf(source, target)
        val beforeSnapshot = KaoPanCloudSyncReporter.captureSellableSnapshot(livePans)

        target.isHasSausage = true
        target.status = 2
        target.holdingTime = source.holdingTime
        target.closeTime = source.closeTime
        target.taste = source.taste
        source.isHasSausage = false
        source.status = 0
        source.holdingTime = 0L

        var uploadCount = 0
        KaoPanCloudSyncReporter.withUploaderForTest({ uploadCount++ }) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSnapshot,
                livePans,
                "测试活对象别名"
            )
        }

        assertEquals(1, uploadCount)
    }

    @Test
    fun uploadIfSellableStateChanged_shouldFallbackSnapshotPolicyWhenMqttDeltaPublishFails() {
        val before = listOf(sellablePan(7, "A"), bakingPan(9, "A"))
        val after = listOf(sellablePan(7, "A"), sellablePan(9, "A"))
        var publishCount = 0
        var fallbackReason: String? = null

        KaoPanCloudSyncReporter.withDependenciesForTest(
            testDeltaPublisher = { _, _, _ ->
                publishCount++
                false
            },
            testFallbackUploader = { reason -> fallbackReason = reason }
        ) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(before, after, "测试可售变化")
        }

        assertEquals(1, publishCount)
        assertEquals("pan_delta_publish_failed:测试可售变化", fallbackReason)
    }

    @Test
    fun uploadIfSellableStateChanged_shouldThrottleFallbackSnapshotWhenMqttDeltaKeepsFailing() {
        val before = listOf(sellablePan(7, "A"), bakingPan(9, "A"))
        val after = listOf(sellablePan(7, "A"), sellablePan(9, "A"))
        var now = 100_000L
        var fallbackCount = 0

        KaoPanCloudSyncReporter.withDependenciesForTest(
            testDeltaPublisher = { _, _, _ -> false },
            testFallbackUploader = { fallbackCount++ },
            testClock = { now }
        ) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(before, after, "第一次变化")
            now += 1_000L
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(before, after, "第二次变化")
        }

        assertEquals(1, fallbackCount)
    }

    private fun sellablePan(positionSn: Int, tasteCode: String): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = System.currentTimeMillis() - 1_000L
            this.closeTime = 60_000L
            this.taste = Taste(tasteCode)
        }

    private fun bakingPan(positionSn: Int, tasteCode: String, startTime: Long = 1000L): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 1
            this.startTime = startTime
            this.holdingTime = 0L
            this.taste = Taste(tasteCode)
        }
}
