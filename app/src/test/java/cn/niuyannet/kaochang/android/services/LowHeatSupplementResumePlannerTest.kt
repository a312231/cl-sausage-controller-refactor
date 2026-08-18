package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun planResumeTargets_shouldKeepReturnPositionBindingForLowHeatResume() {
        val front = (1..9).map { position ->
            frontPan(position, "A", reserved = position in 1..3)
        }
        val group = listOf(
            supplementPan(16, "A", hasSausage = true),
            supplementPan(17, null, hasSausage = false),
            supplementPan(18, null, hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals(listOf(17, 18), result.map { it.positionSn })
        assertEquals(listOf(2, 3), result.map { it.returnPositionSn })
        assertEquals(listOf("A", "A"), result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldNotRebuildPhysicalGapWhenActiveIntentExists() {
        val front = (1..9).map { position ->
            frontPan(position, "A", reserved = false)
        }
        val group = listOf(
            supplementPan(16, "A", hasSausage = true),
            supplementPan(17, null, hasSausage = false),
            supplementPan(18, null, hasSausage = false)
        )
        val intent = SupplementBatchIntent(
            batchId = "test",
            type = SupplementBatchType.LOW_HEAT_16_18,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 16, tasteCode = "A", returnPositionSn = 1, completed = true),
                SupplementBatchIntentTarget(positionSn = 17, tasteCode = "A", returnPositionSn = 2),
                SupplementBatchIntentTarget(positionSn = 18, tasteCode = "A", returnPositionSn = 3)
            )
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotResumeTasteOnlyPanWithoutReturnBinding() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = false) }
        val group = listOf(
            supplementPan(13, "A", hasSausage = false),
            supplementPan(14, "A", hasSausage = false),
            supplementPan(15, "A", hasSausage = true)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotApplyIntentTasteWhenReturnBindingIsMissing() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = false) }
        val target14 = supplementPan(14, null, hasSausage = false)
        val group = listOf(
            supplementPan(13, "A", hasSausage = true),
            target14,
            supplementPan(15, "A", hasSausage = true)
        )
        val intent = SupplementBatchIntent(
            batchId = "missing-return",
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 14, tasteCode = "B")
            )
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertTrue(result.isEmpty())
        assertEquals(null, target14.taste)
    }

    @Test
    fun planResumeTargets_shouldIgnoreCompletedIntentTarget() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = false) }
        val group = listOf(
            supplementPan(13, "A", hasSausage = true),
            supplementPan(14, null, hasSausage = false),
            supplementPan(15, "A", hasSausage = true)
        )
        val intent = SupplementBatchIntent(
            batchId = "completed-target",
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 14,
                    tasteCode = "A",
                    returnPositionSn = 2,
                    completed = true
                )
            )
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotBorrowReservationFromUnrelatedActiveIntent() {
        val front = (1..9).map { position ->
            frontPan(position, "A", reserved = position in 2..3)
        }
        val group = listOf(
            supplementPan(13, "A", hasSausage = true),
            supplementPan(14, null, hasSausage = false),
            supplementPan(15, "A", hasSausage = true)
        )
        val intent = SupplementBatchIntent(
            batchId = "other-group",
            type = SupplementBatchType.LOW_HEAT_16_18,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 16,
                    tasteCode = "A",
                    returnPositionSn = 1,
                    completed = true
                )
            )
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertTrue(result.isEmpty())
    }

    @Test
    fun planResumeTargets_shouldNotMutateTargetTasteWhenActiveIntentExists() {
        val front = (1..9).map { position -> frontPan(position, "A", reserved = false) }
        val target14 = supplementPan(14, "B", hasSausage = false)
        val group = listOf(
            supplementPan(13, "A", hasSausage = true),
            target14,
            supplementPan(15, "A", hasSausage = true)
        )
        val intent = SupplementBatchIntent(
            batchId = "intent-taste",
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 14,
                    tasteCode = "A",
                    returnPositionSn = 2
                )
            )
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertTrue(result.isEmpty())
        assertEquals("B", target14.taste.tasteCode)
    }

    @Test
    fun planResumeTargets_shouldPreferReservedFrontTasteOverStaleTargetPanTaste() {
        val front = (1..9).map { position ->
            frontPan(position, "A", reserved = position == 2)
        }
        val target13 = supplementPan(13, "B", hasSausage = false)
        val group = listOf(
            target13,
            supplementPan(14, "A", hasSausage = true),
            supplementPan(15, "A", hasSausage = true)
        )

        val result = LowHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals(listOf("A"), result.map { it.tasteCode })
        assertEquals("B", target13.taste.tasteCode)
    }

    @Test
    fun restoreFrontReservationsAfterLowHeatResume_shouldRestoreOnlyActuallyFilledTargets() {
        val front = (1..9).map { position ->
            frontPan(position, "A", reserved = false)
        }
        val target17 = supplementPan(17, "A", hasSausage = true)
        val target18 = supplementPan(18, "A", hasSausage = false)

        val restored = LowCurrentTrafficMode.restoreFrontReservationsAfterLowHeatResume(
            frontPans = front,
            targets = listOf(
                LowHeatSupplementResumePlanner.ResumeTarget(target17, "A", returnPositionSn = 2),
                LowHeatSupplementResumePlanner.ResumeTarget(target18, "A", returnPositionSn = 3)
            )
        )

        assertEquals(listOf(2), restored)
        assertTrue(front.first { it.positionSn == 2 }.isHasGrilling)
        assertFalse(front.first { it.positionSn == 3 }.isHasGrilling)
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
        assertEquals(null, group[2].taste)
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
    fun findFinishedPans_shouldReturnEmptyWhenAnyOccupiedPanStillBaking() {
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

        assertTrue(result.isEmpty())
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
    fun markFinishedPansKeepWarm_shouldWaitWhenOccupiedPanStillBaking() {
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

        assertTrue(result.isEmpty())
        assertEquals(1L, group[0].startTime)
        assertEquals(0L, group[0].holdingTime)
        assertEquals(1, group[0].status)
        assertEquals(30L * 60L * 1000L, group[1].startTime)
        assertEquals(1, group[1].status)
    }

    @Test
    fun markFinishedPansKeepWarm_shouldReturnRetainedWaitingReturnPanWithNewlyMaturedPans() {
        val nowMs = 70L * 60L * 1000L
        val retainedHoldingTime = 10_000L
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = retainedHoldingTime
                status = 3
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

        val result = LowHeatSupplementResumePlanner.markFinishedPansKeepWarm(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(13, 14, 15), result.map { it.positionSn })
        assertEquals(retainedHoldingTime, group[0].holdingTime)
        assertEquals(nowMs, group[1].holdingTime)
        assertEquals(nowMs, group[2].holdingTime)
        assertEquals(listOf(3, 3, 3), group.map { it.status })
    }

    @Test
    fun findKeepWarmPansReadyToMove_shouldWaitWhenOccupiedPanStillBaking() {
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 3
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

        assertTrue(result.isEmpty())
    }

    @Test
    fun findKeepWarmPansReadyToMove_shouldReturnAllOccupiedPansWhenAllAreWaitingReturn() {
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 3
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 12_000L
                status = 3
            },
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.findKeepWarmPansReadyToMove(
            supplementGroup = group,
            config = inactiveFaultConfig(),
            pendingDiscardPositions = emptyList()
        )

        assertEquals(listOf(13, 14), result.map { it.positionSn })
    }

    @Test
    fun findKeepWarmPansReadyToMove_shouldNormalizeLegacySellableSourceToWaitingReturn() {
        val group = listOf(
            supplementPan(13, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 2
            },
            supplementPan(14, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 12_000L
                status = 2
            },
            supplementPan(15, "A", hasSausage = false)
        )

        val result = LowHeatSupplementResumePlanner.findKeepWarmPansReadyToMove(
            supplementGroup = group,
            config = inactiveFaultConfig(),
            pendingDiscardPositions = emptyList()
        )

        assertEquals(listOf(13, 14), result.map { it.positionSn })
        assertEquals(listOf(3, 3), result.map { it.status })
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
