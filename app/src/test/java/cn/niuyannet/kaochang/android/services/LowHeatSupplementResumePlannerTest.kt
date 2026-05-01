package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowHeatSupplementResumePlannerTest {

    @Test
    fun planResumeTargets_shouldResumeOnly15When13And14AlreadyFilled() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = position in 1..3) }
        val group = listOf(
            supplementPan(13, "A", hasSausage = true),
            supplementPan(14, "A", hasSausage = true),
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals(listOf(15), result.map { it.positionSn })
        assertEquals(listOf("A"), result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldResume17And18When16AlreadyFilled() {
        val front = (1..9).map { position -> frontPan(position, "B", reserved = position in 4..6) }
        val group = listOf(
            supplementPan(16, "B", hasSausage = true),
            supplementPan(17, "B", hasSausage = false),
            supplementPan(18, "B", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals(listOf(17, 18), result.map { it.positionSn })
        assertEquals(listOf("B", "B"), result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenGroupIsEmpty() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = position in 1..3) }
        val group = (13..15).map { position -> supplementPan(position, "A", hasSausage = false) }

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenGroupIsFull() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = position in 1..3) }
        val group = (13..15).map { position -> supplementPan(position, "A", hasSausage = true) }

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldFillBlankTargetTasteFromReservedFrontPosition() {
        val front = (1..9).map { position -> frontPan(position, "C", reserved = position in 1..3) }
        val group = listOf(
            supplementPan(13, "C", hasSausage = true),
            supplementPan(14, "C", hasSausage = true),
            supplementPan(15, null, hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals(listOf(15), result.map { it.positionSn })
        assertEquals(listOf("C"), result.map { it.tasteCode })
        assertEquals("C", group[2].taste.tasteCode)
    }

    private fun frontPan(positionSn: Int, tasteCode: String, reserved: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = false
            this.isHasGrilling = reserved
            this.taste = Taste(tasteCode)
        }

    private fun supplementPan(positionSn: Int, tasteCode: String?, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
            this.taste = tasteCode?.let { Taste(it) }
        }
}
