package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficModeHelperTest {

    @Test
    fun findSupplyBoxesByTaste_shouldReturnSameTasteStockBoxesByDescendingPosition() {
        val boxes = listOf(
            box(positionSn = 1, tasteCode = "A", num = 0),
            box(positionSn = 2, tasteCode = "B", num = 3),
            box(positionSn = 3, tasteCode = "A", num = 2),
            box(positionSn = 8, tasteCode = "A", num = 1),
            box(positionSn = 6, tasteCode = "A", num = 5)
        )

        val result = TrafficModeHelper.findSupplyBoxesByTaste(boxes, "A")

        assertEquals(listOf(8, 6, 3), result.map { it.positionSn })
    }

    @Test
    fun selectSellCandidate_shouldUseOldestHoldingTimestampFirst() {
        val pans = listOf(
            sellablePan(positionSn = 12, holdingTime = 3000L),
            sellablePan(positionSn = 8, holdingTime = 1000L),
            sellablePan(positionSn = 3, holdingTime = 2000L)
        )

        val result = TrafficModeHelper.selectSellCandidate(pans, "A", 104)

        assertEquals(8, result?.positionSn)
    }

    @Test
    fun shouldUseExpandedSellRange_shouldExpandDuringResidualDrain() {
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 1))
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 2, transitionMode = 0, highConcurrencyResidualDrain = 0))
        assertEquals(true, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 2, highConcurrencyResidualDrain = 0))
        assertEquals(false, TrafficModeHelper.shouldUseExpandedSellRange(modeType = 1, transitionMode = 0, highConcurrencyResidualDrain = 0))
    }

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
        }
    }

    private fun sellablePan(positionSn: Int, holdingTime: Long): KaoPan {
        return KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = true
            status = 2
            this.holdingTime = holdingTime
            taste = Taste().apply {
                tasteCode = "A"
                productId = 104
            }
        }
    }
}
