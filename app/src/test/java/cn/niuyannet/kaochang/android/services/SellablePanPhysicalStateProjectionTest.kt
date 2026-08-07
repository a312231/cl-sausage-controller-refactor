package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SellablePanPhysicalStateProjectionTest {

    @Test
    fun sellablePositions_shouldExcludePendingExpiredAndNonFrontPansDuringLowToHigh() {
        val now = 1_000_000L
        val config = config(modeType = 1, transitionMode = 1, residualDrain = 0, pending = listOf(7, 7))
        val positions = SellablePanPhysicalStateProjection.sellablePositions(
            pans = listOf(
                sellablePan(1, now),
                sellablePan(1, now),
                sellablePan(7, now),
                sellablePan(9, now, closeTime = 5_000L, holdingTime = now - 5_001L),
                sellablePan(10, now),
                sellablePan(13, now),
                sellablePan(21, now)
            ),
            config = config,
            nowMs = now
        )

        assertEquals(listOf(1), positions)
    }

    @Test
    fun sellablePositions_shouldIncludeActualFrontPansDuringHighToLow() {
        val now = 1_000_000L
        val config = config(modeType = 1, transitionMode = 2, residualDrain = 0, pending = emptyList())
        val positions = SellablePanPhysicalStateProjection.sellablePositions(
            pans = listOf(
                sellablePan(1, now),
                sellablePan(21, now),
                sellablePan(25, now)
            ),
            config = config,
            nowMs = now
        )

        assertEquals(listOf(1, 21), positions)
    }

    @Test
    fun rulesSignature_shouldNormalizePendingDiscardPositionsAndCoverRuntimeRules() {
        val first = config(modeType = 1, transitionMode = 2, residualDrain = 1, pending = listOf(21, 7, 21))
        val sameRulesDifferentInputOrder = config(modeType = 1, transitionMode = 2, residualDrain = 1, pending = listOf(7, 21))
        val changedMode = config(modeType = 2, transitionMode = 2, residualDrain = 1, pending = listOf(7, 21))

        assertEquals(
            SellablePanPhysicalStateProjection.rulesSignature(first),
            SellablePanPhysicalStateProjection.rulesSignature(sameRulesDifferentInputOrder)
        )
        assertNotEquals(
            SellablePanPhysicalStateProjection.rulesSignature(first),
            SellablePanPhysicalStateProjection.rulesSignature(changedMode)
        )
    }

    private fun config(
        modeType: Int,
        transitionMode: Int,
        residualDrain: Int,
        pending: List<Int>
    ): AppConfigBean = AppConfigBean().apply {
        this.modeType = modeType
        this.transitionMode = transitionMode
        this.highConcurrencyResidualDrain = residualDrain
        this.pendingDiscardPositions.addAll(pending)
    }

    private fun sellablePan(
        positionSn: Int,
        now: Long,
        closeTime: Long = 60_000L,
        holdingTime: Long = now - 1_000L
    ): KaoPan = KaoPan().apply {
        this.positionSn = positionSn
        this.isHasSausage = true
        this.status = KaoPan.STATUS_SELLABLE_KEEP_WARM
        this.holdingTime = holdingTime
        this.closeTime = closeTime
    }
}
