package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import org.junit.Assert.assertEquals
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
            KaoPanBox().apply {
                positionSn = 1
                num = 0
                tasteCode = "A"
            },
            KaoPanBox().apply {
                positionSn = 2
                num = 0
                tasteCode = "B"
            }
        )

        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, 9)

        assertTrue(allocation.isEmpty())
    }

    @Test
    fun computeHighKaoPamRatio_shouldReturnEmptyWhenBoxHasNoAvailableTasteStock() {
        val allocation = KaoPanHelper.computeHighKaoPamRatio(emptyList(), 6)

        assertTrue(allocation.isEmpty())
    }

    @Test
    fun restoreKaoPanBoxList_shouldReturnMutableEmptyListWhenPreferenceMissing() {
        val restored = KaoPanHelper.restoreKaoPanBoxList(null)

        assertTrue(restored.isEmpty())
        restored.add(KaoPanBox().apply { positionSn = 1 })
        assertEquals(1, restored.size)
    }
}
