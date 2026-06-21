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
    fun resolveCurrentSourceBox_shouldUseCurrentCacheObjectWhenPlannedBoxBecameOrphan() {
        val oldPlannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentCacheBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentBoxes = listOf(currentCacheBox)

        val resolved = SupplementPanStateWriter.resolveCurrentSourceBox(
            plannedBox = oldPlannedBox,
            currentBoxes = currentBoxes
        )

        assertSame(currentCacheBox, resolved)
    }

    @Test
    fun resolvedCurrentState_shouldDeductCurrentCacheBoxInsteadOfOrphanPlannedBox() {
        val orphanPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val orphanPlannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentCacheBox = box(positionSn = 21, stock = 6, tasteCode = "B")

        val resolvedState = SupplementPanStateWriter.resolveCurrentSupplementState(
            plannedPan = orphanPlannedPan,
            plannedBox = orphanPlannedBox,
            currentPans = listOf(currentCachePan),
            currentBoxes = listOf(currentCacheBox)
        )
        requireNotNull(resolvedState)

        KaoChangOperate.applySuccessfulSupplementPanState(
            kaoPan = resolvedState.targetPan,
            kaoPanBox = resolvedState.sourceBox,
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
        assertEquals(6, orphanPlannedBox.num)
        assertEquals(5, currentCacheBox.num)
    }

    @Test
    fun consistencyCheck_shouldPassWhenSuccessfulPanAndBoxCountsMatch() {
        val result = SupplementConsistencyChecker.evaluate(
            expectedSuccessCount = 2,
            records = listOf(
                SupplementWriteRecord(targetPanNo = 1, sourceBoxNo = 21, panApplied = true, boxDeducted = true),
                SupplementWriteRecord(targetPanNo = 2, sourceBoxNo = 22, panApplied = true, boxDeducted = true)
            )
        )

        assertFalse(result.requiresMaintenance)
    }

    @Test
    fun consistencyCheck_shouldDetectPanBoxDivergence() {
        val result = SupplementConsistencyChecker.evaluate(
            expectedSuccessCount = 2,
            records = listOf(
                SupplementWriteRecord(targetPanNo = 1, sourceBoxNo = 21, panApplied = true, boxDeducted = true),
                SupplementWriteRecord(targetPanNo = 2, sourceBoxNo = 22, panApplied = true, boxDeducted = false)
            )
        )

        assertTrue(result.requiresMaintenance)
        assertTrue(result.message.contains("落盘成功数=2"))
        assertTrue(result.message.contains("扣箱成功数=1"))
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

    private fun box(positionSn: Int, stock: Int, tasteCode: String): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = stock
            this.tasteCode = tasteCode
            this.tasteName = "测试肠"
        }
}
