package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoPanHelperSchedulerSwitchSnapshotTest {

    @Test
    fun absentOrEmptyCache_isUnavailableWithoutCreatingDefaultPans() {
        assertNull(schedulerSwitchPanActivitySnapshot(null, emptySet()))
        assertNull(schedulerSwitchPanActivitySnapshot(emptyList(), emptySet()))
    }

    @Test
    fun initializedCache_distinguishesHoldingFromStillActiveCooking() {
        val nowMs = 1_000L
        val holding = pan(
            status = KaoPan.STATUS_SELLABLE_KEEP_WARM,
            hasSausage = true,
            holdingTime = nowMs,
            closeTime = 100L
        )
        assertFalse(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(holding), emptySet(), nowMs)).hasActiveCookingBatch)

        val baking = pan(status = KaoPan.STATUS_BAKING, hasSausage = true, holdingTime = 0L)
        assertTrue(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(baking), emptySet())).hasActiveCookingBatch)

        val grilling = pan(status = KaoPan.STATUS_EMPTY, hasSausage = false, holdingTime = 0L).also {
            it.isHasGrilling = true
        }
        assertTrue(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(grilling), emptySet())).hasActiveCookingBatch)
    }

    @Test
    fun pendingDiscardHoldingPan_isNotTreatedAsSettled() {
        val nowMs = 1_000L
        val pan = pan(
            status = KaoPan.STATUS_SELLABLE_KEEP_WARM,
            hasSausage = true,
            holdingTime = nowMs,
            closeTime = 100L
        )

        assertTrue(
            requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(pan), setOf(pan.positionSn), nowMs)).hasActiveCookingBatch
        )
    }

    @Test
    fun holdingWindow_usesOneInjectedClockAtExactBoundaries() {
        val pan = pan(
            status = KaoPan.STATUS_SELLABLE_KEEP_WARM,
            hasSausage = true,
            holdingTime = 1_000L,
            closeTime = 100L
        )

        assertTrue(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(pan), emptySet(), 999L)).hasActiveCookingBatch)
        assertFalse(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(pan), emptySet(), 1_000L)).hasActiveCookingBatch)
        assertFalse(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(pan), emptySet(), 1_100L)).hasActiveCookingBatch)
        assertTrue(requireNotNull(schedulerSwitchPanActivitySnapshot(listOf(pan), emptySet(), 1_101L)).hasActiveCookingBatch)
    }

    private fun pan(
        status: Int,
        hasSausage: Boolean,
        holdingTime: Long,
        closeTime: Long = 0L
    ) = KaoPan().also {
        it.positionSn = 1
        it.status = status
        it.isHasSausage = hasSausage
        it.holdingTime = holdingTime
        it.closeTime = closeTime
    }
}
