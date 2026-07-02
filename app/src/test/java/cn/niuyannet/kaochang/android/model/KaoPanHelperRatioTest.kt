package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.After

class KaoPanHelperRatioTest {

    @Before
    fun setUpAppConfig() {
        setAppConfigInMemory(AppConfigBean())
    }

    @After
    fun resetAppConfig() {
        setAppConfigInMemory(AppConfigBean())
    }

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
    fun computeKaoPamRatio_shouldKeepMultiTasteInventoryRatio() {
        val boxes = listOf(
            box(positionSn = 17, tasteCode = "A", num = 8),
            box(positionSn = 20, tasteCode = "A", num = 8),
            box(positionSn = 18, tasteCode = "B", num = 8),
            box(positionSn = 19, tasteCode = "B", num = 8),
            box(positionSn = 21, tasteCode = "B", num = 8)
        )

        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, 21)

        assertEquals(8, allocation["A"])
        assertEquals(13, allocation["B"])
    }

    @Test
    fun computeKaoPamRatio_shouldCapAllocationWhenTasteCountExceedsTargetPanCount() {
        val boxes = listOf(
            box(positionSn = 2, tasteCode = "A", num = 10),
            box(positionSn = 3, tasteCode = "B", num = 9),
            box(positionSn = 4, tasteCode = "C", num = 8),
            box(positionSn = 5, tasteCode = "S", num = 7)
        )

        val allocation = KaoPanHelper.computeKaoPamRatio(boxes, 3)

        assertEquals(3, allocation.values.sum())
        assertEquals(setOf("A", "B", "C"), allocation.keys)
        assertFalse(allocation.containsKey("S"))
    }

    @Test
    fun supplyBoxAutoSupplementExclusionReasons_shouldExplainEveryFilterCondition() {
        val emptyBox = box(positionSn = 2, tasteCode = "A", num = 0)
        val blankTasteBox = box(positionSn = 2, tasteCode = "", num = 8)
        val irFailedBox = box(positionSn = 3, tasteCode = "B", num = 8, cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED)
        val reservedBox = box(positionSn = 22, tasteCode = "C", num = 8)

        assertEquals(listOf("num<=0"), KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(emptyBox))
        assertEquals(listOf("tasteCode空"), KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(blankTasteBox))
        assertEquals(listOf("IR_PICK_FAILED"), KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(irFailedBox))
        assertEquals(listOf("非法源箱位"), KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(reservedBox))
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
    fun computeHighKaoPamRatio_shouldCapAllocationWhenTasteCountExceedsTargetPanCount() {
        val boxes = listOf(
            box(positionSn = 2, tasteCode = "A", num = 10),
            box(positionSn = 3, tasteCode = "B", num = 9),
            box(positionSn = 4, tasteCode = "C", num = 8),
            box(positionSn = 5, tasteCode = "S", num = 7)
        )

        val allocation = KaoPanHelper.computeHighKaoPamRatio(boxes, 3)

        assertEquals(3, allocation.values.sum())
        assertEquals(setOf("A", "B", "C"), allocation.keys)
        assertFalse(allocation.containsKey("S"))
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
    fun isSupplyBoxAvailableForAutoSupplement_shouldRejectValidBoxWhenSupplementDisabled() {
        setAppConfigInMemory(AppConfigBean().apply {
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        })
        val box = box(positionSn = 2, tasteCode = "A", num = 15)
        val exclusionReasons = KaoPanHelper.supplyBoxAutoSupplementExclusionReasons(box)

        assertFalse(KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(box))
        assertEquals(listOf("supplementEnabled=0"), exclusionReasons)
        assertEquals(0, KaoPanHelper.availableSupplyNumForAutoSupplement(box))
    }

    @Test
    fun isSupplyBoxPhysicallyAvailableForSupplement_shouldIgnoreSupplementDisabled() {
        setAppConfigInMemory(AppConfigBean().apply {
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        })
        val box = box(positionSn = 2, tasteCode = "A", num = 15)

        assertTrue(KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(box))
    }

    @Test
    fun isSupplyBoxPhysicallyAvailableForSupplement_shouldRejectUnavailablePhysicalBoxes() {
        assertFalse(
            KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(
                box(positionSn = 2, tasteCode = "A", num = 15, cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED)
            )
        )
        assertFalse(KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(box(positionSn = 2, tasteCode = "", num = 15)))
        assertFalse(KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(box(positionSn = 22, tasteCode = "A", num = 15)))
        assertFalse(KaoPanHelper.isSupplyBoxPhysicallyAvailableForSupplement(box(positionSn = 2, tasteCode = "A", num = 0)))
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

    private fun setAppConfigInMemory(config: AppConfigBean) {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean")
        field.isAccessible = true
        field.set(AppConfig, config)
    }
}
