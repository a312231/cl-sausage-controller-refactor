package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficInitializationResumePlannerTest {

    @Test
    fun planResumeTargets_shouldResumeLowInitRemainingPansWhenFrontPartiallyBakingAndHeatAreaEmpty() {
        val front = (1..9).map { position ->
            pan(position, "A", hasSausage = position in 1..5, status = if (position in 1..5) 1 else 0)
        }
        val heatArea = (13..18).map { position -> pan(position, null, hasSausage = false) }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertEquals((6..9).toList(), result.map { it.positionSn })
        assertEquals(List(4) { "A" }, result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldResumeHighInitRemainingPansWhenFrontPartiallyBakingAndHeatAreaEmpty() {
        val front = (1..21).map { position ->
            pan(position, "B", hasSausage = position in 1..8, status = if (position in 1..8) 1 else 0)
        }
        val heatArea = (25..33).map { position -> pan(position, null, hasSausage = false) }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertEquals((9..21).toList(), result.map { it.positionSn })
        assertEquals(List(13) { "B" }, result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenFrontIsAllEmpty() {
        val front = (1..9).map { position -> pan(position, "A", hasSausage = false) }
        val heatArea = (13..18).map { position -> pan(position, null, hasSausage = false) }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenFrontIsFull() {
        val front = (1..9).map { position -> pan(position, "A", hasSausage = true, status = 1) }
        val heatArea = (13..18).map { position -> pan(position, null, hasSausage = false) }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenExistingFrontSausageIsHoldingForSale() {
        val front = (1..9).map { position ->
            pan(position, "A", hasSausage = position in 1..8, status = if (position in 1..8) 2 else 0)
        }
        val heatArea = (13..18).map { position -> pan(position, null, hasSausage = false) }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeWhenHeatAreaHasActiveState() {
        val front = (1..9).map { position ->
            pan(position, "A", hasSausage = position in 1..5, status = if (position in 1..5) 1 else 0)
        }
        val heatArea = (13..18).map { position ->
            pan(position, "A", hasSausage = position == 13, status = if (position == 13) 1 else 0)
        }

        val result = TrafficInitializationResumePlanner.planFrontInitResumeTargets(front, heatArea)

        assertTrue(result.isEmpty())
    }

    private fun pan(
        positionSn: Int,
        tasteCode: String?,
        hasSausage: Boolean,
        status: Int = 0
    ): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
            this.status = status
            this.startTime = if (status == 1) 1000L else 0L
            this.taste = tasteCode?.let { Taste(it) }
        }
}
