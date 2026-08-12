package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HighHeatSupplementResumePlannerTest {

    @Test
    fun isValidInitialPlan_shouldAcceptFullAndStockLimitedDistinctTargets() {
        val targets = (25..33).mapIndexed { index, source ->
            HighHeatSupplementResumePlanner.ResumeTarget(
                kaoPan = supplementPan(source, "A", false),
                tasteCode = "A",
                returnPositionSn = index + 1
            )
        }
        (1..9).forEach { stockCount ->
            assertTrue(
                "库存为 $stockCount 根时应允许按实际数量启动",
                HighHeatSupplementResumePlanner.isValidInitialPlan(targets.take(stockCount))
            )
        }
    }

    @Test
    fun isValidInitialPlan_shouldRejectEmptyOrUnsafeTargets() {
        val duplicateReturn = (25..33).map { source ->
            HighHeatSupplementResumePlanner.ResumeTarget(
                supplementPan(source, "A", false), "A", 1
            )
        }
        val duplicateSource = (1..2).map { returnPosition ->
            HighHeatSupplementResumePlanner.ResumeTarget(
                supplementPan(25, "A", false), "A", returnPosition
            )
        }
        val nonContiguousSource = listOf(
            HighHeatSupplementResumePlanner.ResumeTarget(
                supplementPan(25, "A", false), "A", 1
            ),
            HighHeatSupplementResumePlanner.ResumeTarget(
                supplementPan(27, "A", false), "A", 2
            )
        )
        val outOfRange = listOf(
            HighHeatSupplementResumePlanner.ResumeTarget(
                supplementPan(24, "A", false), "A", 1
            )
        )

        assertFalse(HighHeatSupplementResumePlanner.isValidInitialPlan(emptyList()))
        assertFalse(HighHeatSupplementResumePlanner.isValidInitialPlan(duplicateReturn))
        assertFalse(HighHeatSupplementResumePlanner.isValidInitialPlan(duplicateSource))
        assertFalse(HighHeatSupplementResumePlanner.isValidInitialPlan(nonContiguousSource))
        assertFalse(HighHeatSupplementResumePlanner.isValidInitialPlan(outOfRange))
    }

    @Before
    fun setUpAppConfig() {
        setAppConfigInMemory(AppConfigBean())
    }

    @After
    fun resetAppConfig() {
        setAppConfigInMemory(AppConfigBean())
    }

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
    fun planResumeTargets_shouldKeepReturnPositionBindingForHighHeatResume() {
        val front = (1..21).map { position -> frontPan(position, "A", reserved = position in 1..9) }
        val group = (25..33).map { position ->
            supplementPan(position, if (position == 25) "A" else null, hasSausage = position == 25)
        }

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group)

        assertEquals((26..33).toList(), result.map { it.positionSn })
        assertEquals((2..9).toList(), result.map { it.returnPositionSn })
        assertEquals(List(8) { "A" }, result.map { it.tasteCode })
    }

    @Test
    fun planResumeTargets_shouldRestoreReturnPositionFromIntentWhenFrontReservationWasCleared() {
        val front = (1..21).map { position -> frontPan(position, "A", reserved = false) }
        val group = (25..33).map { position ->
            supplementPan(position, if (position == 25) "A" else null, hasSausage = position == 25)
        }
        val intent = SupplementBatchIntent(
            batchId = "test",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = (25..33).map { position ->
                SupplementBatchIntentTarget(
                    positionSn = position,
                    tasteCode = "A",
                    returnPositionSn = position - 24,
                    completed = position == 25
                )
            }.toMutableList()
        )

        val result = HighHeatSupplementResumePlanner.planResumeTargets(front, group, intent)

        assertEquals((26..33).toList(), result.map { it.positionSn })
        assertEquals((2..9).toList(), result.map { it.returnPositionSn })
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
    fun hasBatchStarted_shouldAcceptPartialStartedGroup() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..31)
        }

        assertTrue(HighHeatSupplementResumePlanner.hasBatchStarted(group))
    }

    @Test
    fun isReturnCleared_shouldWaitUntilZone3HasNoSausage() {
        val leftoverGroup = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position == 33)
        }
        val emptyGroup = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = false)
        }

        assertFalse(HighHeatSupplementResumePlanner.isReturnCleared(leftoverGroup))
        assertTrue(HighHeatSupplementResumePlanner.isReturnCleared(emptyGroup))
    }

    @Test
    fun planInitialTargets_shouldOnlyRequestContiguousTargetsCoveredByCurrentStock() {
        val front = (1..21).map { position ->
            frontPan(position, "B", reserved = false).apply {
                isHasSausage = position !in 1..7
            }
        }
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(
            box(positionSn = 18, tasteCode = "B", num = 2),
            box(positionSn = 17, tasteCode = "B", num = 5)
        )

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes
        )

        assertEquals((25..31).toList(), result.map { it.positionSn })
        assertEquals(List(7) { "B" }, result.map { it.tasteCode })
        assertEquals((1..7).toList(), result.map { it.returnPositionSn })
        assertNull(group.first { it.positionSn == 32 }.taste)
        assertNull(group.first { it.positionSn == 33 }.taste)
    }

    @Test
    fun planInitialTargets_shouldFallbackToAvailableTasteWithoutLeavingTargetHoles() {
        val emptyFrontTastes = listOf("A", "A", "B", "B", "B", "A", "A", "A", "A")
        val front = (1..21).map { position ->
            val tasteCode = emptyFrontTastes.getOrNull(position - 1) ?: "A"
            frontPan(position, tasteCode, reserved = false).apply {
                isHasSausage = position !in 1..9
            }
        }
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(
            box(positionSn = 19, tasteCode = "A", num = 8),
            box(positionSn = 15, tasteCode = "B", num = 1)
        )

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals(listOf("B", "A", "A", "A", "A", "A", "A", "A", "A"), result.map { it.tasteCode })
        assertEquals((1..9).toList(), result.map { it.returnPositionSn })
        assertEquals("A", front.first { it.positionSn == 4 }.taste.tasteCode)
        assertEquals("A", front.first { it.positionSn == 5 }.taste.tasteCode)
        assertEquals("A", group.first { it.positionSn == 28 }.taste.tasteCode)
        assertEquals("A", group.first { it.positionSn == 29 }.taste.tasteCode)
    }

    @Test
    fun planInitialTargets_shouldExcludeProtectedOldLowHeatReturnPositions() {
        val front = (1..21).map { position ->
            frontPan(position, "B", reserved = false).apply {
                isHasSausage = position !in 1..9
            }
        }
        front.first { it.positionSn == 1 }.taste = Taste("A")
        front.first { it.positionSn == 2 }.taste = Taste("A")
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(box(positionSn = 18, tasteCode = "B", num = 9))

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes,
            excludedReturnPositions = setOf(1, 2)
        )

        assertEquals((25..31).toList(), result.map { it.positionSn })
        assertEquals((3..9).toList(), result.map { it.returnPositionSn })
        assertEquals("A", front.first { it.positionSn == 1 }.taste.tasteCode)
        assertEquals("A", front.first { it.positionSn == 2 }.taste.tasteCode)
    }

    @Test
    fun planInitialTargets_shouldUseCommittedOldLowHeatSourcePositionsAsFutureReturnSlots() {
        val availableNow = setOf(7, 8, 10, 11, 12, 19, 20, 21)
        val front = (1..21).map { position ->
            frontPan(position, "B", reserved = false).apply {
                isHasSausage = position !in availableNow
            }
        }
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(box(positionSn = 18, tasteCode = "B", num = 9))

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes,
            excludedReturnPositions = setOf(1, 2, 3, 4, 5, 6),
            committedFutureReturnPositions = listOf(13)
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals(listOf(7, 8, 10, 11, 12, 19, 20, 21, 13), result.map { it.returnPositionSn })
        assertTrue(front.first { it.positionSn == 13 }.isHasSausage)
    }

    @Test
    fun planInitialTargets_shouldKeepDefaultHighHeatPlanningWhenNoReturnPositionsAreExcluded() {
        val front = (1..21).map { position ->
            frontPan(position, "B", reserved = false).apply {
                isHasSausage = position !in 1..9
            }
        }
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(box(positionSn = 18, tasteCode = "B", num = 9))

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals((1..9).toList(), result.map { it.returnPositionSn })
    }

    @Test
    fun planInitialTargets_shouldConvergeAcrossTwentyOneFrontPositionsWithoutChangingReturnSlots() {
        val front = (1..21).map { position ->
            frontPan(position, "A", reserved = false).apply {
                isHasSausage = position in 1..12
            }
        }
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(
            box(positionSn = 18, tasteCode = "A", num = 20),
            box(positionSn = 17, tasteCode = "F", num = 10)
        )

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals((13..21).toList(), result.map { it.returnPositionSn })
        assertEquals(mapOf("A" to 2, "F" to 7), result.groupingBy { it.tasteCode }.eachCount())
    }

    @Test
    fun planInitialTargets_shouldNotRewriteActiveReservedReturnPosition() {
        val front = (1..21).map { position ->
            frontPan(position, "A", reserved = position == 13).apply {
                isHasSausage = position in 1..11
            }
        }
        front.first { it.positionSn == 13 }.taste = Taste("F")
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(
            box(positionSn = 18, tasteCode = "A", num = 20),
            box(positionSn = 17, tasteCode = "F", num = 10)
        )

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes,
            committedReturnTasteByPosition = mapOf(13 to "F")
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals(listOf(12) + (14..21).toList(), result.map { it.returnPositionSn })
        assertEquals("F", front.first { it.positionSn == 13 }.taste.tasteCode)
        assertTrue(front.first { it.positionSn == 13 }.isHasGrilling)
    }

    @Test
    fun planInitialTargets_shouldReuseGhostReservedPositionWithoutDurableCommitment() {
        val front = (1..21).map { position ->
            frontPan(position, "A", reserved = position == 13).apply {
                isHasSausage = position in 1..12
            }
        }
        front.first { it.positionSn == 13 }.taste = Taste("F")
        val group = (25..33).map { position -> supplementPan(position, null, hasSausage = false) }
        val boxes = listOf(
            box(positionSn = 18, tasteCode = "A", num = 20),
            box(positionSn = 17, tasteCode = "F", num = 10)
        )

        val result = HighHeatSupplementResumePlanner.planInitialTargets(
            frontPans = front,
            supplementGroup = group,
            listBox = boxes
        )

        assertEquals((25..33).toList(), result.map { it.positionSn })
        assertEquals((13..21).toList(), result.map { it.returnPositionSn })
        assertEquals(9, result.size)
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
                startTime = 0L
                holdingTime = 10_000L
                status = 3
            }
        }

        assertTrue(HighHeatSupplementResumePlanner.isCompleteFinishedBatch(group))
    }

    @Test
    fun isCompleteFinishedBatch_shouldNormalizeLegacySellableSourceToWaitingReturn() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = true).apply {
                startTime = 0L
                holdingTime = 10_000L
                status = 2
            }
        }

        assertTrue(HighHeatSupplementResumePlanner.isCompleteFinishedBatch(group))
        assertEquals(List(9) { 3 }, group.map { it.status })
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
    fun findFinishedPartialBatch_shouldReturnSingleOccupiedPanWhenOtherPansAreEmpty() {
        val nowMs = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position == 25).apply {
                if (isHasSausage) {
                    startTime = 1_000L
                    bakingTime = 8_000L
                    status = 1
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findFinishedPartialBatch(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(25), result.map { it.positionSn })
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
    fun markFinishedPartialBatchKeepWarm_shouldSwitchMaturedPartialPansToWaitingReturnKeepWarm() {
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
            assertEquals(3, pan.status)
        }
        assertEquals(0, group.first { it.positionSn == 28 }.status)
    }

    @Test
    fun markFinishedPartialBatchKeepWarm_shouldReturnRetainedWaitingReturnPanWithNewlyMaturedPans() {
        val nowMs = 70L * 60L * 1000L
        val retainedHoldingTime = 10_000L
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                when (position) {
                    25 -> {
                        startTime = 0L
                        holdingTime = retainedHoldingTime
                        status = 3
                    }

                    26, 27 -> {
                        startTime = 30L * 60L * 1000L
                        bakingTime = 40L * 60L * 1000L
                        status = 1
                    }
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.markFinishedPartialBatchKeepWarm(group, nowMs, inactiveFaultConfig())

        assertEquals(listOf(25, 26, 27), result.map { it.positionSn })
        assertEquals(retainedHoldingTime, group.first { it.positionSn == 25 }.holdingTime)
        assertEquals(nowMs, group.first { it.positionSn == 26 }.holdingTime)
        assertEquals(nowMs, group.first { it.positionSn == 27 }.holdingTime)
        assertEquals(listOf(3, 3, 3), result.map { it.status })
    }

    @Test
    fun findKeepWarmPartialBatchReadyToMove_shouldReturnWaitingReturnPartialPans() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                if (isHasSausage) {
                    startTime = 0L
                    holdingTime = 10_000L
                    status = 3
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

    @Test
    fun findKeepWarmPartialBatchReadyToMove_shouldNormalizeLegacySellablePartialSourceToWaitingReturn() {
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
        assertEquals(listOf(3, 3, 3), result.map { it.status })
    }

    @Test
    fun findKeepWarmPartialBatchReadyToMove_shouldWaitWhenAnyOccupiedPanStillBaking() {
        val group = (25..33).map { position ->
            supplementPan(position, "A", hasSausage = position in 25..27).apply {
                when (position) {
                    25 -> {
                        startTime = 0L
                        holdingTime = 10_000L
                        status = 3
                    }

                    26, 27 -> {
                        startTime = 5_000L
                        holdingTime = 0L
                        status = 1
                    }
                }
            }
        }

        val result = HighHeatSupplementResumePlanner.findKeepWarmPartialBatchReadyToMove(
            supplementGroup = group,
            config = inactiveFaultConfig(),
            pendingDiscardPositions = emptyList()
        )

        assertTrue(result.isEmpty())
    }

    private fun frontPan(positionSn: Int, tasteCode: String, reserved: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = false
            this.isHasGrilling = reserved
            this.taste = Taste(tasteCode)
        }

    private fun inactiveFaultConfig(): AppConfigBean = AppConfigBean()

    private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.tasteCode = tasteCode
            this.num = num
        }

    private fun supplementPan(positionSn: Int, tasteCode: String?, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
            this.taste = tasteCode?.let { Taste(it) }
        }

    private fun setAppConfigInMemory(config: AppConfigBean) {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean")
        field.isAccessible = true
        field.set(AppConfig, config)
    }
}
