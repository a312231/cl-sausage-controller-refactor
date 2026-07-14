package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Test

class KaoPanUiReservationStateBuilderTest {

    @Test
    fun apply_shouldMarkPendingSupplementTargetsAndReturnTargets() {
        val pans = mutableListOf(
            pan(1, tasteCode = "A"),
            pan(2, tasteCode = "A"),
            pan(6, tasteCode = "B"),
            pan(13, tasteCode = "A"),
            pan(14, tasteCode = "A").apply {
                status = KaoPan.STATUS_BAKING
                isHasSausage = true
            },
            pan(15, tasteCode = "B")
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(13, "A", returnPositionSn = 1, completed = false),
                SupplementBatchIntentTarget(14, "A", returnPositionSn = 2, completed = true),
                SupplementBatchIntentTarget(15, "B", returnPositionSn = 6, completed = false)
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent)

        assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, pans.first { it.positionSn == 1 }.uiReservationState)
        assertEquals("A", pans.first { it.positionSn == 1 }.uiReservationTaste.tasteCode)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, pans.first { it.positionSn == 2 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, pans.first { it.positionSn == 6 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT, pans.first { it.positionSn == 13 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 14 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT, pans.first { it.positionSn == 15 }.uiReservationState)
    }

    @Test
    fun apply_shouldHideResidualTasteWithoutActiveIntentOrValidLowHeatSource() {
        val pans = mutableListOf(
            pan(1, tasteCode = "A").apply { isHasGrilling = true },
            pan(13, tasteCode = "A")
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 1 }.uiReservationState)
        assertEquals(null, pans.first { it.positionSn == 1 }.uiReservationTaste)
    }

    @Test
    fun apply_shouldNotInferWaitReturnFromLowHeatSourceWithoutBinding() {
        val pans = mutableListOf(
            pan(1, tasteCode = "A").apply { isHasGrilling = true },
            pan(13, tasteCode = "A").apply {
                status = 1
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 1 }.uiReservationState)
    }

    @Test
    fun apply_shouldNotInferWaitReturnFromHighHeatSourceWithoutBinding() {
        val pans = mutableListOf(
            pan(8, tasteCode = "A"),
            pan(9, tasteCode = "B"),
            pan(25, tasteCode = "B").apply {
                status = 1
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 8 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 9 }.uiReservationState)
    }

    @Test
    fun apply_shouldInferHighHeatWaitReturnToBlankFrontTargetWithoutMutatingRealTaste() {
        val pans = mutableListOf(
            blankPan(8),
            pan(25, tasteCode = "B").apply {
                status = 2
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        val target = pans.first { it.positionSn == 8 }
        assertEquals(KaoPan.UI_RESERVATION_NONE, target.uiReservationState)
        assertEquals(null, target.uiReservationTaste)
        assertEquals(null, target.taste)
    }

    @Test
    fun apply_shouldInferHighHeatWaitReturnToDifferentTasteEmptyFrontTargetWithoutMutatingRealTaste() {
        val pans = mutableListOf(
            pan(8, tasteCode = "A"),
            pan(25, tasteCode = "B").apply {
                status = 3
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        val target = pans.first { it.positionSn == 8 }
        assertEquals(KaoPan.UI_RESERVATION_NONE, target.uiReservationState)
        assertEquals(null, target.uiReservationTaste)
        assertEquals("A", target.taste.tasteCode)
    }

    @Test
    fun apply_shouldInferHighHeatWaitReturnFromWaitingReturnKeepWarmSource() {
        val pans = mutableListOf(
            blankPan(8),
            pan(25, tasteCode = "B").apply {
                status = 3
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        val target = pans.first { it.positionSn == 8 }
        assertEquals(KaoPan.UI_RESERVATION_NONE, target.uiReservationState)
        assertEquals(null, target.uiReservationTaste)
    }

    @Test
    fun apply_shouldUseDifferentFrontTargetsForMultipleHighHeatSources() {
        val pans = mutableListOf(
            pan(8, tasteCode = "B"),
            pan(9, tasteCode = "B"),
            pan(25, tasteCode = "B").apply {
                status = 1
                isHasSausage = true
            },
            pan(26, tasteCode = "B").apply {
                status = 1
                isHasSausage = true
            }
        )

        KaoPanUiReservationStateBuilder.apply(pans, activeIntent = null)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 8 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 9 }.uiReservationState)
    }

    @Test
    fun apply_shouldNotShowOldLowBindingAfterSourcePositionWasReusedByHighMove() {
        val pans = mutableListOf(
            blankPan(1),
            pan(13, tasteCode = "B").apply {
                status = KaoPan.STATUS_SELLABLE_KEEP_WARM
                isHasSausage = true
            }
        )
        val intent = SupplementBatchIntent(
            batchId = "high-container",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 13,
                    tasteCode = "A",
                    returnPositionSn = 1,
                    completed = true,
                    originBatchId = "old-low",
                    originBatchType = SupplementBatchType.LOW_HEAT_13_15
                )
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 1 }.uiReservationState)
    }

    @Test
    fun apply_shouldSuppressReservationsWhileMoveRecoveryIsBlocked() {
        val pans = mutableListOf(blankPan(1), blankPan(13))
        val intent = SupplementBatchIntent(
            batchId = "low",
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 13,
                    tasteCode = "A",
                    returnPositionSn = 1,
                    originBatchId = "low",
                    originBatchType = SupplementBatchType.LOW_HEAT_13_15
                )
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent, recoveryBlocked = true)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 1 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 13 }.uiReservationState)
    }

    @Test
    fun apply_shouldMarkHighHeatReturnTargetsFromActiveSupplementIntent() {
        val pans = mutableListOf(
            pan(5, tasteCode = "A"),
            pan(25, tasteCode = "A")
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(25, "A", returnPositionSn = 5, completed = false)
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent)

        assertEquals(KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT, pans.first { it.positionSn == 25 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, pans.first { it.positionSn == 5 }.uiReservationState)
        assertEquals("A", pans.first { it.positionSn == 5 }.uiReservationTaste.tasteCode)
    }

    @Test
    fun apply_shouldShowOldLowHeatReturnTargetFromActiveIntentBeforeSourceIsPlaced() {
        val pans = mutableListOf(
            blankPan(2),
            blankPan(13)
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(13, "A", returnPositionSn = 2, completed = false)
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent)

        val target = pans.first { it.positionSn == 2 }
        assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, target.uiReservationState)
        assertEquals("A", target.uiReservationTaste.tasteCode)
        assertEquals(null, target.taste)
        assertEquals(false, target.isHasGrilling)
        assertEquals(KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT, pans.first { it.positionSn == 13 }.uiReservationState)
    }

    @Test
    fun apply_shouldIgnoreFaultTimeoutCancelledReturnTargets() {
        val pans = mutableListOf(
            blankPan(2),
            blankPan(13)
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 13,
                    tasteCode = "A",
                    returnPositionSn = 2,
                    cancelledByFaultTimeout = true
                )
            )
        )

        KaoPanUiReservationStateBuilder.apply(pans, intent)

        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 2 }.uiReservationState)
        assertEquals(KaoPan.UI_RESERVATION_NONE, pans.first { it.positionSn == 13 }.uiReservationState)
    }

    private fun pan(positionSn: Int, tasteCode: String): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            status = 0
            isHasSausage = false
            taste = Taste(tasteCode)
        }

    private fun blankPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            status = 0
            isHasSausage = false
            taste = null
        }
}
