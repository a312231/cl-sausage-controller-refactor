package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaoPanHelperRatioTest {

    @Test
    fun createDefaultKaoPanList_shouldBuildThirtyThreeEmptyPansForFreshInstall() {
        val pans = KaoPanHelper.createDefaultKaoPanList(
            bakingTimeMinutes = 40,
            discardTimeHours = 6
        )

        assertEquals(33, pans.size)
        assertEquals((1..33).toSet(), pans.map { it.positionSn }.toSet())
        assertTrue(pans.all { it.bakingTime == 40L * 60L * 1000L })
        assertTrue(pans.all { it.closeTime == 6L * 60L * 60L * 1000L })
        assertTrue(pans.all { !it.isHasSausage && !it.isHasGrilling && it.status == 0 })
    }

    @Test
    fun computeKaoPamRatio_shouldReturnEmptyWhenBoxHasNoAvailableTasteStock() {
        val boxes = listOf(
            box(positionSn = 1, tasteCode = "A", num = 0),
            box(positionSn = 2, tasteCode = "B", num = 0)
        )

        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, 9)

        assertTrue(allocation.isEmpty())
    }

    @Test
    fun computeKaoPamRatio_shouldIgnoreIrPickFailedStock() {
        val boxes = listOf(
            box(positionSn = 1, tasteCode = "A", num = 15, cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED),
            box(positionSn = 2, tasteCode = "B", num = 5)
        )

        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, 4)

        assertFalse(allocation.containsKey("A"))
        assertEquals(4, allocation["B"])
    }

    @Test
    fun computeHighKaoPamRatio_shouldReturnEmptyWhenBoxHasNoAvailableTasteStock() {
        val allocation = KaoPanHelper.computeHighKaoPamRatio(emptyList(), 6)

        assertTrue(allocation.isEmpty())
    }

    @Test
    fun computeHighKaoPamRatio_shouldIgnoreIrPickFailedStock() {
        val boxes = listOf(
            box(positionSn = 1, tasteCode = "A", num = 15, cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED),
            box(positionSn = 2, tasteCode = "B", num = 5)
        )

        val allocation = KaoPanHelper.computeHighKaoPamRatio(boxes, 6)

        assertFalse(allocation.containsKey("A"))
        assertEquals(6, allocation["B"])
    }

    @Test
    fun availableSupplyNumForAutoSupplement_shouldReturnZeroForIrPickFailedBox() {
        val box = box(positionSn = 1, tasteCode = "A", num = 15, cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED)

        assertEquals(0, KaoPanHelper.availableSupplyNumForAutoSupplement(box))
    }

    @Test
    fun isSupplyBoxAvailableForAutoSupplement_shouldRejectReservedSourcePosition() {
        val box = box(positionSn = 22, tasteCode = "A", num = 15)

        assertFalse(KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(box))
    }

    @Test
    fun restoreKaoPanBoxList_shouldReturnMutableEmptyListWhenPreferenceMissing() {
        val restored = KaoPanHelper.restoreKaoPanBoxList(null)

        assertTrue(restored.isEmpty())
        restored.add(KaoPanBox().apply { positionSn = 1 })
        assertEquals(1, restored.size)
    }

    private fun box(positionSn: Int, tasteCode: String, num: Int, cmdStatusTake: Int = 0): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
            this.cmdStatusTake = cmdStatusTake
        }
    }
}
