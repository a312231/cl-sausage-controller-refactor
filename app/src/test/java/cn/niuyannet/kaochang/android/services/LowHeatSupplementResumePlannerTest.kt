package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
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

    @Test
    fun findFinishedPans_shouldReturn13WhenOnly13ReachedBakingTime() {
        val nowMs = 40L * 60L * 1000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 1L
                bakingTime = nowMs - 1L
                status = 1
            },
            supplementPan(14, "A", hasSausage = false),
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(13), result.map { it.positionSn })
    }

    @Test
    fun findFinishedPans_shouldReturnOnly13WhenLaterFilled14And15AreStillBaking() {
        val nowMs = 50L * 60L * 1000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L + 1L
                bakingTime = 40L * 60L * 1000L
                status = 1
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 30L * 60L * 1000L
                bakingTime = 40L * 60L * 1000L
                status = 1
            },
            supplementPan(15, "A", hasSausage = true).apply {
                startTime = 30L * 60L * 1000L
                bakingTime = 40L * 60L * 1000L
                status = 1
            }
        )

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(13), result.map { it.positionSn })
    }

    @Test
    fun findFinishedPans_shouldReturn14And15WhenLaterFilledPansReachBakingTime() {
        val nowMs = 70L * 60L * 1000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = false),
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 30L * 60L * 1000L
                bakingTime = 40L * 60L * 1000L
                status = 1
            },
            supplementPan(15, "A", hasSausage = true).apply {
                startTime = 30L * 60L * 1000L
                bakingTime = 40L * 60L * 1000L
                status = 1
            }
        )

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(14, 15), result.map { it.positionSn })
    }

    @Test
    fun findFinishedPans_shouldReturnFullGroupWhenAllPansReachBakingTime() {
        val nowMs = 10_000L
        val group = (13..15).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                startTime = 1_000L
                bakingTime = 8_000L
                status = 1
            }
        }

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(13, 14, 15), result.map { it.positionSn })
    }

    @Test
    fun findFinishedPans_shouldNotFinishDuringFaultHeatPauseEvenWhenWallClockExceeded() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L
        }
        val nowMs = 12L * 60L * 1000L
        val group = (13..15).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                startTime = 1L
                bakingTime = 10L * 60L * 1000L
                status = 1
            }
        }

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, config)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPans_shouldNotFinishDuringFaultHeatPauseEvenWhenAlreadyMaturedAtPauseStart() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 12 * 60 * 1000L
        }
        val nowMs = 15L * 60L * 1000L
        val group = (13..15).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                startTime = 1L
                bakingTime = 10L * 60L * 1000L
                status = 1
            }
        }

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, config)

        assertTrue(result.isEmpty())
    }

    @Test
    fun findKeepWarmPansReadyToMove_shouldReturnEmptyDuringFaultHeatPause() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 12 * 60 * 1000L
        }
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 2
            }
        )

        val result = LowHeatSupplementResumePlanner.findKeepWarmPansReadyToMove(
            supplementGroup = group,
            config = config,
            pendingDiscardPositions = emptyList()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun findFinishedPans_shouldRejectInvalidTiming() {
        val nowMs = 10_000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                bakingTime = 8_000L
                status = 1
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 1_000L
                bakingTime = 0L
                status = 1
            },
            supplementPan(15, "A", hasSausage = true).apply {
                startTime = 7_000L
                bakingTime = 8_000L
                status = 1
            }
        )

        val result = LowHeatSupplementResumePlanner.findFinishedPans(group, nowMs, inactiveFaultConfig())

        assertTrue(result.isEmpty())
    }

    @Test
    fun markFinishedPansKeepWarm_shouldSwitchOnlyFinishedPansToKeepWarm() {
        val nowMs = 50L * 60L * 1000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 1L
                bakingTime = 40L * 60L * 1000L
                status = 1
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 30L * 60L * 1000L
                bakingTime = 40L * 60L * 1000L
                status = 1
            },
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.markFinishedPansKeepWarm(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(13), result.map { it.positionSn })
        assertEquals(0L, group[0].startTime)
        assertEquals(nowMs, group[0].holdingTime)
        assertEquals(2, group[0].status)
        assertEquals(30L * 60L * 1000L, group[1].startTime)
        assertEquals(1, group[1].status)
    }

    @Test
    fun findKeepWarmPansReadyToMove_shouldReturnRetainedFinishedPans() {
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 2
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 5_000L
                holdingTime = 0L
                status = 1
            },
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.findKeepWarmPansReadyToMove(
            supplementGroup = group,
            config = inactiveFaultConfig(),
            pendingDiscardPositions = emptyList()
        )

        assertEquals(listOf(13), result.map { it.positionSn })
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
