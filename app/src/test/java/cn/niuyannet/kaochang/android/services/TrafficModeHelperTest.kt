package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficModeHelperTest {

    @Before
    fun setUpAppConfig() {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean")
        field.isAccessible = true
        field.set(AppConfig, AppConfigBean())
    }

    @Test
    fun purchaseSellEnd_shouldMatchBusinessMatrix() {
        assertEquals(9, TrafficModeHelper.purchaseSellEnd(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 0))
        assertEquals(9, TrafficModeHelper.purchaseSellEnd(modeType = 2, transitionMode = 1, highConcurrencyResidualDrain = 0))
        assertEquals(21, TrafficModeHelper.purchaseSellEnd(modeType = 2, transitionMode = 0, highConcurrencyResidualDrain = 0))
        assertEquals(21, TrafficModeHelper.purchaseSellEnd(modeType = 2, transitionMode = 2, highConcurrencyResidualDrain = 0))
        assertEquals(21, TrafficModeHelper.purchaseSellEnd(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 1))
    }

    @Test
    fun findSupplyBoxesByTaste_shouldReturnSameTasteStockBoxesByDescendingPosition() {
        val boxes = listOf(
            box(positionSn = 1, tasteCode = "A", num = 0),
            box(positionSn = 2, tasteCode = "B", num = 3),
            box(positionSn = 3, tasteCode = "A", num = 2),
            box(positionSn = 11, tasteCode = "A", num = 9),
            box(positionSn = 22, tasteCode = "A", num = 9),
            box(positionSn = 8, tasteCode = "A", num = 1),
            box(positionSn = 6, tasteCode = "A", num = 5)
        )

        val result = TrafficModeHelper.findSupplyBoxesByTaste(boxes, "A")

        assertEquals("4F 源肠箱保留位 11/22 不能作为自动补肠候选", listOf(8, 6, 3), result.map { it.positionSn })
    }

    @Test
    fun findSupplyBoxesByTaste_shouldSkipReservedSlotsAndAllowAdjacentValidBoxes() {
        val boxes = listOf(
            box(positionSn = 10, tasteCode = "A", num = 1),
            box(positionSn = 11, tasteCode = "A", num = 1),
            box(positionSn = 12, tasteCode = "A", num = 1),
            box(positionSn = 21, tasteCode = "A", num = 1),
            box(positionSn = 22, tasteCode = "A", num = 1)
        )

        val result = TrafficModeHelper.findSupplyBoxesByTaste(boxes, "A")

        assertEquals(listOf(21, 10), result.map { it.positionSn })
    }

    @Test
    fun findSupplyBoxesByTaste_shouldSkipBoxesMarkedWithIrPickFailure() {
        val boxes = listOf(
            box(positionSn = 3, tasteCode = "A", num = 2),
            box(positionSn = 8, tasteCode = "A", num = 1, cmdStatusTake = 0x00E5),
            box(positionSn = 6, tasteCode = "A", num = 5)
        )

        val result = TrafficModeHelper.findSupplyBoxesByTaste(boxes, "A")

        assertEquals(listOf(6, 3), result.map { it.positionSn })
    }

    @Test
    fun selectSellCandidate_shouldUseOldestHoldingTimestampFirst() {
        val nowMs = 100_000L
        val pans = listOf(
            sellablePan(positionSn = 12, holdingTime = 3000L),
            sellablePan(positionSn = 8, holdingTime = 1000L),
            sellablePan(positionSn = 3, holdingTime = 2000L)
        )

        val result = TrafficModeHelper.selectSellCandidate(pans, "A", 104, nowMs)

        assertEquals(8, result?.positionSn)
    }

    @Test
    fun selectSellCandidate_shouldUsePositionAsTieBreaker() {
        val nowMs = 100_000L
        val pans = listOf(
            sellablePan(positionSn = 8, holdingTime = 1_000L),
            sellablePan(positionSn = 3, holdingTime = 1_000L)
        )

        val result = TrafficModeHelper.selectSellCandidate(pans, "A", 104, nowMs)

        assertEquals(3, result?.positionSn)
    }

    @Test
    fun selectSellCandidate_shouldIncludeBothHoldingWindowEndpoints() {
        val startsHoldingNow = sellablePan(positionSn = 1, holdingTime = 10_000L, closeTime = 5_000L)
        val reachesCloseTimeNow = sellablePan(positionSn = 2, holdingTime = 10_000L, closeTime = 5_000L)

        assertEquals(
            1,
            TrafficModeHelper.selectSellCandidate(listOf(startsHoldingNow), "A", 104, nowMs = 10_000L)?.positionSn
        )
        assertEquals(
            2,
            TrafficModeHelper.selectSellCandidate(listOf(reachesCloseTimeNow), "A", 104, nowMs = 15_000L)?.positionSn
        )
        assertNull(
            TrafficModeHelper.selectSellCandidate(listOf(reachesCloseTimeNow), "A", 104, nowMs = 15_001L)
        )
    }

    @Test
    fun selectSellCandidate_shouldExcludePendingDiscardAndFutureHoldingTime() {
        AppConfig.getAppConfig().pendingDiscardPositions.add(2)
        val pans = listOf(
            sellablePan(positionSn = 1, holdingTime = 100_001L),
            sellablePan(positionSn = 2, holdingTime = 1_000L)
        )

        val result = TrafficModeHelper.selectSellCandidate(pans, "A", 104, nowMs = 100_000L)

        assertNull(result)
    }

    @Test
    fun shouldUseExpandedSellRange_shouldExpandDuringResidualDrain() {
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 1))
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 2, transitionMode = 0, highConcurrencyResidualDrain = 0))
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 2, highConcurrencyResidualDrain = 0))
        assertEquals(false, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 0))
    }

    @Test
    fun shouldUseExpandedSellRangeForPurchase_shouldKeepLowToHighInLowSellArea() {
        assertEquals(
            false,
            TrafficModeHelper.shouldUseExpandedSellRangeForPurchase(
                modeType = 2,
                transitionMode = 1,
                highConcurrencyResidualDrain = 0
            )
        )
    }

    @Test
    fun shouldUseExpandedSellRangeForPurchase_shouldExpandDuringHighToLowAndResidualDrain() {
        assertEquals(
            true,
            TrafficModeHelper.shouldUseExpandedSellRangeForPurchase(
                modeType = 2,
                transitionMode = 2,
                highConcurrencyResidualDrain = 0
            )
        )
        assertEquals(
            true,
            TrafficModeHelper.shouldUseExpandedSellRangeForPurchase(
                modeType = 1,
                transitionMode = 0,
                highConcurrencyResidualDrain = 1
            )
        )
    }

    @Test
    fun selectFrontTargetPanForHighHeatReturn_shouldUseBlankTastePanWhenSameTasteIsUnavailable() {
        val pans = listOf(
            emptyPan(positionSn = 10, tasteCode = null),
            emptyPan(positionSn = 11, tasteCode = "C"),
            emptyPan(positionSn = 12, tasteCode = "A").apply { isHasSausage = true }
        )

        val result = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(pans, "A")

        assertEquals(10, result?.positionSn)
        assertEquals("A", result?.taste?.tasteCode)
        assertEquals("C", pans.first { it.positionSn == 11 }.taste.tasteCode)
    }

    @Test
    fun selectFrontTargetPanForHighHeatReturn_shouldOverwriteStaleEmptyPanTasteWhenNoPreferredTarget() {
        val pans = listOf(
            emptyPan(positionSn = 10, tasteCode = "C"),
            emptyPan(positionSn = 11, tasteCode = "D")
        )

        val result = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(pans, "A")

        assertEquals(10, result?.positionSn)
        assertEquals("A", pans.first { it.positionSn == 10 }.taste.tasteCode)
        assertEquals("D", pans.first { it.positionSn == 11 }.taste.tasteCode)
    }

    @Test
    fun selectFrontTargetPanForHighHeatReturn_shouldNeverSelectOccupiedPanWhenOverridingStaleTaste() {
        val pans = listOf(
            emptyPan(positionSn = 10, tasteCode = "C").apply { isHasSausage = true },
            emptyPan(positionSn = 11, tasteCode = "D")
        )

        val result = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(pans, "A")

        assertEquals(11, result?.positionSn)
        assertEquals("C", pans.first { it.positionSn == 10 }.taste.tasteCode)
        assertEquals("A", pans.first { it.positionSn == 11 }.taste.tasteCode)
    }

    @Test
    fun selectFrontTargetPanForHighHeatReturn_shouldNeverSelectNonEmptyStatusPanWhenOverridingStaleTaste() {
        val pans = listOf(
            emptyPan(positionSn = 10, tasteCode = "C").apply { status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM },
            emptyPan(positionSn = 11, tasteCode = "D")
        )

        val result = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(pans, "A")

        assertEquals(11, result?.positionSn)
        assertEquals("C", pans.first { it.positionSn == 10 }.taste.tasteCode)
        assertEquals("A", pans.first { it.positionSn == 11 }.taste.tasteCode)
    }

    private fun box(positionSn: Int, tasteCode: String, num: Int, cmdStatusTake: Int = 0): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
            this.cmdStatusTake = cmdStatusTake
        }
    }

    private fun sellablePan(positionSn: Int, holdingTime: Long, closeTime: Long = 200_000L): KaoPan {
        return KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = true
            status = 2
            this.holdingTime = holdingTime
            this.closeTime = closeTime
            taste = Taste().apply {
                tasteCode = "A"
                productId = 104
            }
        }
    }

    private fun emptyPan(positionSn: Int, tasteCode: String?): KaoPan {
        return KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = false
            if (tasteCode != null) {
                taste = Taste().apply {
                    this.tasteCode = tasteCode
                }
            }
        }
    }
}
