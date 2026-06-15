package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class KaoPanHelperTasteMetadataTest {

    @After
    fun tearDown() {
        replaceTasteCatalog(mutableListOf())
    }

    @Test
    fun buildTasteFromBox_shouldNotFallbackToDifferentProductWhenProductIdIsPresent() {
        replaceTasteCatalog(
            mutableListOf(
                Taste(2, "A", "原味", 102, "其他商品")
            )
        )
        val box = box(productId = "101", tasteCode = "A", tasteName = "旧商品(原味)")

        val resolvedTaste = KaoPanHelper.buildTasteFromBox(box)

        assertEquals(101, resolvedTaste.productId)
        assertEquals("A", resolvedTaste.tasteCode)
        assertEquals("旧商品(原味)", resolvedTaste.tasteName)
        assertEquals("", resolvedTaste.productName)
    }

    @Test
    fun normalizeKaoPanBoxTasteMetadata_shouldUpdateOnlyStrictProductTasteMatches() {
        val boxes = mutableListOf(
            box(productId = "101", tasteCode = "A", tasteName = "旧商品(原味)"),
            box(productId = "102", tasteCode = "A", tasteName = "另一个旧名")
        )
        val catalog = listOf(
            Taste(1, "A", "原味", 101, "新商品"),
            Taste(2, "A", "原味", 103, "串味商品")
        )

        val changedCount = KaoPanHelper.normalizeKaoPanBoxTasteMetadata(boxes, catalog)

        assertEquals(1, changedCount)
        assertEquals("新商品(原味)", boxes[0].tasteName)
        assertEquals("另一个旧名", boxes[1].tasteName)
    }

    private fun box(productId: String, tasteCode: String, tasteName: String): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = 1
            this.productId = productId
            this.tasteCode = tasteCode
            this.tasteName = tasteName
            this.num = 10
        }

    private fun replaceTasteCatalog(tastes: MutableList<Taste>) {
        val field = KaoPanHelper::class.java.getDeclaredField("tasteList")
        field.isAccessible = true
        field.set(KaoPanHelper, tastes)
    }
}
