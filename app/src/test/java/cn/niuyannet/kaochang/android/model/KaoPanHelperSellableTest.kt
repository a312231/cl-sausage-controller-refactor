package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoPanHelperSellableTest {

    @Test
    fun isSellable_shouldRejectPanInPendingDiscardPositions() {
        val pan = sellablePan(positionSn = 7)

        assertFalse(KaoPanHelper.isSellable(pan, listOf(7)))
    }

    @Test
    fun isSellable_shouldAcceptNormalHoldingPan() {
        val pan = sellablePan(positionSn = 8)

        assertTrue(KaoPanHelper.isSellable(pan, emptyList()))
    }

    @Test
    fun isSellable_shouldRejectWaitingReturnKeepWarmPan() {
        val pan = sellablePan(positionSn = 13).apply {
            status = 3
        }

        assertFalse(KaoPanHelper.isSellable(pan, emptyList()))
    }

    private fun sellablePan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = 1000L
        }
}
