package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class HighConcurrencyResidualDrainEvaluatorTest {

    @Test
    fun evaluate_shouldReturnOneWhenLowModeHasResidualInTenToTwentyOne() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 10) {
                pan.apply { isHasSausage = true }
            } else {
                pan
            }
        }

        assertEquals(1, HighConcurrencyResidualDrainEvaluator.evaluate(1, pans))
    }

    @Test
    fun evaluate_shouldIgnoreLowHeatOverlapAreaByDefault() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 13) {
                pan.apply {
                    isHasSausage = true
                    startTime = 1000L
                    status = 1
                }
            } else {
                pan
            }
        }

        assertEquals(0, HighConcurrencyResidualDrainEvaluator.evaluate(1, pans))
    }

    @Test
    fun evaluate_shouldIncludeLowHeatOverlapAreaWhenAlreadyDraining() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 13) {
                pan.apply {
                    isHasSausage = true
                    holdingTime = 1000L
                    status = 2
                }
            } else {
                pan
            }
        }

        assertEquals(
            1,
            HighConcurrencyResidualDrainEvaluator.evaluate(
                modeType = 1,
                kaoPanList = pans,
                includeLowHeatOverlapArea = true
            )
        )
    }

    @Test
    fun evaluate_shouldReturnOneWhenLowModeHasResidualInTwentyFiveToThirtyThree() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 25) {
                pan.apply { holdingTime = 1000L }
            } else {
                pan
            }
        }

        assertEquals(1, HighConcurrencyResidualDrainEvaluator.evaluate(1, pans))
    }

    @Test
    fun evaluate_shouldReturnZeroWhenLowModeHighAreasAreIdle() {
        assertEquals(0, HighConcurrencyResidualDrainEvaluator.evaluate(1, emptyPans()))
    }

    @Test
    fun evaluate_shouldReturnZeroInHighModeEvenWhenHighAreaHasResidual() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 25) {
                pan.apply { status = 1 }
            } else {
                pan
            }
        }

        assertEquals(0, HighConcurrencyResidualDrainEvaluator.evaluate(2, pans))
    }

    private fun emptyPans(): List<KaoPan> =
        (1..33).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = false
                startTime = 0L
                holdingTime = 0L
                status = 0
            }
        }
}
