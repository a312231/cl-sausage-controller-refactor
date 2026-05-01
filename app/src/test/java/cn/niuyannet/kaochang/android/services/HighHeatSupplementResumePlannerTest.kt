package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighHeatSupplementResumePlannerTest {

    @Test
    fun planResumeTargets_shouldResume26To33WhenOnly25AlreadyFilled() {
        val front = (1..21).map { position -> frontPan(position, "A", reserved = position in 1..9) }
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position == 25)
        }

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals((26..33).toList(), result.map { it.positionSn })
        assertEquals(List(8) { "A" }, result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenGroupIsEmpty() {
        val front = (1..21).map { position -> frontPan(position, "A", reserved = position in 1..9) }
        val group = (25..33).map { position -> supplementPan(position, "A", hasSausage = false) }

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenGroupIsFull() {
        val front = (1..21).map { position -> frontPan(position, "A", reserved = position in 1..9) }
        val group = (25..33).map { position -> supplementPan(position, "A", hasSausage = true) }

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldFillBlankTargetTasteFromReservedFrontPosition() {
        val front = (1..21).map { position -> frontPan(position, "C", reserved = position in 1..9) }
        val group = (25..33).map { position ->
            supplementPan(position, if (position == 26) null else "C", hasSausage = position == 25)
        }

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals((26..33).toList(), result.map { it.positionSn })
        assertEquals("C", result.first { it.positionSn == 26 }.tasteCode)
        assertEquals("C", group[1].taste.tasteCode)
    }

    @Test
    fun isCompleteFinishedBatch_shouldRejectPartialFinishedGroup() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position == 25).apply {
                status = 2
            }
        }

        assertFalse(HighHeatSupplementResumePlanner.isCompleteFinishedBatch(group))
    }

    @Test
    fun hasFullBatchStarted_shouldRejectPartialStartedGroup() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..30)
        }

        assertFalse(HighHeatSupplementResumePlanner.hasFullBatchStarted(group))
    }

    @Test
    fun hasFullBatchStarted_shouldAcceptNineStartedPans() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = true)
        }

        assertTrue(HighHeatSupplementResumePlanner.hasFullBatchStarted(group))
    }

    @Test
    fun isCompleteFinishedBatch_shouldRejectFullGroupBeforeKeepWarmStatus() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                status = 1
            }
        }

        assertFalse(HighHeatSupplementResumePlanner.isCompleteFinishedBatch(group))
    }

    @Test
    fun isCompleteFinishedBatch_shouldAcceptNineFilledAndNoBakingStatus() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                status = 2
            }
        }

        assertTrue(HighHeatSupplementResumePlanner.isCompleteFinishedBatch(group))
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
