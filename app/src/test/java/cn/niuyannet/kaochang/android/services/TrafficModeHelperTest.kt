package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
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

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
        }
    }
}
