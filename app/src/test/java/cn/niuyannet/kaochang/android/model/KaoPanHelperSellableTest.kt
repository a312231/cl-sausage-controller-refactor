package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoPanHelperSellableTest {

    private val nowMs = 100_000L

    @Test
    fun isSellable_shouldRejectPanInPendingDiscardPositions() {
        val pan = sellablePan(positionSn = 7)

        assertFalse(KaoPanHelper.isSellable(pan, listOf(7), nowMs))
    }

    @Test
    fun isSellable_shouldAcceptNormalHoldingPan() {
        val pan = sellablePan(positionSn = 8)

        assertTrue(KaoPanHelper.isSellable(pan, emptyList(), nowMs))
    }

    @Test
    fun isSellable_shouldRejectWaitingReturnKeepWarmPan() {
        val pan = sellablePan(positionSn = 13).apply {
            status = 3
        }

        assertFalse(KaoPanHelper.isSellable(pan, emptyList(), nowMs))
    }

    @Test
    fun isSellable_shouldRejectExpiredHoldingPan() {
        val pan = sellablePan(positionSn = 3).apply {
            holdingTime = 30_000L
            closeTime = 60_000L
        }

        assertFalse(KaoPanHelper.isSellable(pan, emptyList(), nowMs))
    }

    @Test
    fun isSellable_shouldAcceptPanAtExpirationBoundary() {
        val pan = sellablePan(positionSn = 4).apply {
            holdingTime = 40_000L
            closeTime = 60_000L
        }

        assertTrue(KaoPanHelper.isSellable(pan, emptyList(), nowMs))
    }

    private fun sellablePan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = 50_000L
            this.closeTime = 60_000L
        }
}
