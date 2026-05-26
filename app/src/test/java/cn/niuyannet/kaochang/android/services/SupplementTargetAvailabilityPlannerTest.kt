package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementTargetAvailabilityPlannerTest {

    @Test
    fun filterExecutableSupplementTargets_shouldKeepOnlyTargetsCoveredByCurrentStock() {
        val targets = (1..21).map { position -> target(position, "A") }
        val boxes = listOf(box(positionSn = 10, tasteCode = "A", num = 15))

        val executableTargets = filterExecutableSupplementTargets(
            targets = targets,
            listBox = boxes
        )

        assertEquals((1..15).toList(), executableTargets.map { it.kaoPan.positionSn })
    }

    @Test
    fun filterExecutableSupplementTargets_shouldNotUseInvalidOrEmptyBoxes() {
        val targets = listOf(target(1, "A"), target(2, "A"), target(3, "A"))
        val boxes = listOf(
            box(positionSn = 11, tasteCode = "A", num = 3),
            box(positionSn = 12, tasteCode = "A", num = 0),
            box(positionSn = 13, tasteCode = "A", num = 1)
        )

        val executableTargets = filterExecutableSupplementTargets(
            targets = targets,
            listBox = boxes
        )

        assertEquals(listOf(1), executableTargets.map { it.kaoPan.positionSn })
    }

    @Test
    fun filterExecutableSupplementTargets_shouldFallbackToPanTasteCode() {
        val target = KaoChangOperate.SupplementTarget(
            kaoPan = KaoPan().apply {
                positionSn = 1
                taste = Taste("A")
            },
            tasteCode = null
        )

        val executableTargets = filterExecutableSupplementTargets(
            targets = listOf(target),
            listBox = listOf(box(positionSn = 10, tasteCode = "A", num = 1))
        )

        assertEquals(listOf(1), executableTargets.map { it.kaoPan.positionSn })
        assertEquals("A", executableTargets.single().tasteCode)
    }

    private fun target(positionSn: Int, tasteCode: String): KaoChangOperate.SupplementTarget =
        KaoChangOperate.SupplementTarget(
            kaoPan = KaoPan().apply {
                this.positionSn = positionSn
                this.taste = Taste(tasteCode)
            },
            tasteCode = tasteCode
        )

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
        }
}
