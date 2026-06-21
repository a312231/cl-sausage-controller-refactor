package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPanStateWriterTest {

    @Test
    fun resolveCurrentTargetPan_shouldUseCurrentCacheObjectWhenPlannedPanBecameOrphan() {
        val oldPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val currentPans = listOf(currentCachePan)

        val resolved = SupplementPanStateWriter.resolveCurrentTargetPan(
            plannedPan = oldPlannedPan,
            currentPans = currentPans
        )

        assertSame(currentCachePan, resolved)
    }

    @Test
    fun resolvedCurrentPan_shouldReceiveUnifiedSupplementWriteInsteadOfOrphanPlannedPan() {
        val orphanPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val box = KaoPanBox().apply {
            positionSn = 21
            num = 6
            tasteCode = "B"
            tasteName = "元气烤肠"
        }

        val resolvedPan = SupplementPanStateWriter.resolveCurrentTargetPan(
            plannedPan = orphanPlannedPan,
            currentPans = listOf(currentCachePan)
        )
        requireNotNull(resolvedPan)

        KaoChangOperate.applySuccessfulSupplementPanState(
            kaoPan = resolvedPan,
            kaoPanBox = box,
            demandGateReason = "单测",
            nowMs = 12_345L,
            clearPendingDiscard = false,
            config = AppConfigBean().apply {
                bakingTime = 40
                discardTime = 6
            },
            tasteBuilder = { sourceBox, _ -> Taste(sourceBox.tasteCode) }
        )

        assertFalse(orphanPlannedPan.isHasSausage)
        assertTrue(currentCachePan.isHasSausage)
        assertEquals(1, currentCachePan.status)
        assertEquals(12_345L, currentCachePan.startTime)
        assertEquals(40 * 60L * 1000L, currentCachePan.bakingTime)
        assertEquals(6 * 60L * 60L * 1000L, currentCachePan.closeTime)
        assertEquals(5, box.num)
    }

    @Test
    fun shouldKeepExistingInMemoryPans_shouldPreserveObjectIdentityWhenCacheAlreadyLoaded() {
        val currentPan = pan(positionSn = 1, tasteCode = "A").apply {
            isHasSausage = true
            status = 1
        }
        val storagePan = pan(positionSn = 1, tasteCode = "A")

        val next = KaoPanCacheInitPolicy.resolveInitialPans(
            currentPans = listOf(currentPan),
            storedPans = listOf(storagePan),
            defaultPans = listOf(storagePan)
        )

        assertSame(currentPan, next.first())
        assertTrue(next.first().isHasSausage)
    }

    @Test
    fun resetAfterManualClear_shouldUseDefaultPansEvenWhenCacheAlreadyLoaded() {
        val currentPan = pan(positionSn = 1, tasteCode = "A").apply {
            isHasSausage = true
            status = 1
        }
        val defaultPan = pan(positionSn = 1, tasteCode = null)

        val next = KaoPanCacheInitPolicy.resolveResetPansAfterManualClear(
            defaultPans = listOf(defaultPan)
        )

        assertSame(defaultPan, next.first())
        assertFalse(next.first().isHasSausage)
        assertTrue(currentPan.isHasSausage)
    }

    @Test
    fun saveGeneration_shouldRejectAsyncSaveCapturedBeforeManualClear() {
        val generation = KaoPanAsyncSaveGeneration()
        val capturedBeforeClear = generation.current()

        generation.advance()

        assertFalse(generation.isCurrent(capturedBeforeClear))
        assertTrue(generation.isCurrent(generation.current()))
    }

    private fun pan(positionSn: Int, tasteCode: String?): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.taste = tasteCode?.let { Taste(it) }
            this.isHasSausage = false
            this.status = 0
        }
}
