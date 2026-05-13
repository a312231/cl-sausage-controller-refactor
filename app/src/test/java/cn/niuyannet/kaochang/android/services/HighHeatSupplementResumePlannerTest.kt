package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
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

    @Test
    fun findFinishedPartialBatch_shouldReturn25To27WhenAllPartialPansReachBakingTime() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 1_000L
                    bakingTime = 8_000L
                    status = 1
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(25, 26, 27), result.map { it.positionSn })
    }

    @Test
    fun findFinishedPartialBatch_shouldRejectWhenAnyPartialPanStillBaking() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                startTime = if (position == 27) 7_000L else 1_000L
                bakingTime = 8_000L
                status = 1
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, inactiveFaultConfig())

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPartialBatch_shouldNotFinishDuringFaultHeatPauseEvenWhenWallClockExceeded() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L
        }
        val nowMs = 12L * 60L * 1000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 1L
                    bakingTime = 10L * 60L * 1000L
                    status = 1
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, config)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPartialBatch_shouldNotFinishDuringFaultHeatPauseEvenWhenAlreadyMaturedAtPauseStart() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 12 * 60 * 1000L
        }
        val nowMs = 15L * 60L * 1000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 1L
                    bakingTime = 10L * 60L * 1000L
                    status = 1
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, config)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findKeepWarmPartialBatchReadyToMove_shouldReturnEmptyDuringFaultHeatPause() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 12 * 60 * 1000L
        }
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 0L
                    holdingTime = 10_000L
                    status = 2
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findKeepWarmPartialBatchReadyToMove(
            supplementGroup = group,
            config = config,
            pendingDiscardPositions = emptyList()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPartialBatch_shouldRejectFullBatch() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                startTime = 1_000L
                bakingTime = 8_000L
                status = 1
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, inactiveFaultConfig())

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPartialBatch_shouldRejectInvalidTiming() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                startTime = if (position == 26) 0L else 1_000L
                bakingTime = if (position == 27) 0L else 8_000L
                status = 1
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, inactiveFaultConfig())

        assertTrue(result.isEmpty())
    }

    @Test
    fun markFinishedPartialBatchKeepWarm_shouldSwitchMaturedPartialPansToKeepWarm() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 1_000L
                    bakingTime = 8_000L
                    status = 1
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.markFinishedPartialBatchKeepWarm(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(25, 26, 27), result.map { it.positionSn })
        result.forEach { pan ->
            assertEquals(0L, pan.startTime)
            assertEquals(nowMs, pan.holdingTime)
            assertEquals(2, pan.status)
        }
        assertEquals(0, group.first { it.positionSn == 28 }.status)
    }

    @Test
    fun findKeepWarmPartialBatchReadyToMove_shouldReturnRetainedPartialPans() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 0L
                    holdingTime = 10_000L
                    status = 2
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findKeepWarmPartialBatchReadyToMove(
            supplementGroup = group,
            config = inactiveFaultConfig(),
            pendingDiscardPositions = emptyList()
        )

        assertEquals(listOf(25, 26, 27), result.map { it.positionSn })
    }

    private fun frontPan(positionSn: Int, tasteCode: String, reserved: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = false
            this.isHasGrilling = reserved
            this.taste = Taste(tasteCode)
        }

    private fun inactiveFaultConfig(): AppConfigBean = AppConfigBean()

    private fun supplementPan(positionSn: Int, tasteCode: String?, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
            this.taste = tasteCode?.let { Taste(it) }
        }
}
