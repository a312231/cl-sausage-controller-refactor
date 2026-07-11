package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveredSupplementStatePolicyTest {
    @Test
    fun decide_shouldNotRewritePanOrDeductBoxWhenPanAlreadyPlaced() {
        val pan = KaoPan().apply {
            positionSn = 13
            isHasSausage = true
            status = 1
            startTime = 123_456L
        }
        val box = KaoPanBox().apply { positionSn = 3; num = 5 }

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.ALREADY_APPLIED,
            RecoveredSupplementStatePolicy.decide(session(lastStage = "in7"), pan, box)
        )
        assertEquals(123_456L, pan.startTime)
        assertEquals(5, box.num)
    }

    @Test
    fun decide_shouldApplyOnlyPanWhenIn2InventoryCheckpointWasPersisted() {
        val pan = KaoPan().apply { positionSn = 13 }
        val box = KaoPanBox().apply { positionSn = 3; num = 4 }

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.APPLY_PAN_ONLY,
            RecoveredSupplementStatePolicy.decide(
                session(lastStage = "in2").copy(
                    stockBefore = 5,
                    expectedStockAfter = 4,
                    boxDeductionApplied = true
                ),
                pan,
                box
            )
        )
    }

    @Test
    fun decide_shouldApplyAndDeductForLegacySessionWithoutPhysicalCheckpoint() {
        val pan = KaoPan().apply { positionSn = 13 }
        val box = KaoPanBox().apply { positionSn = 3; num = 5 }

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.APPLY_PAN_AND_DEDUCT_BOX,
            RecoveredSupplementStatePolicy.decide(session(lastStage = null), pan, box)
        )
    }

    @Test
    fun decide_shouldInferDurableBoxDeductionFromExpectedStockAfterCrashBeforeSessionFlag() {
        val pan = KaoPan().apply { positionSn = 13 }
        val box = KaoPanBox().apply { positionSn = 3; num = 4 }
        val session = session(lastStage = "in2").copy(
            stockBefore = 5,
            expectedStockAfter = 4,
            boxDeductionApplied = false
        )

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.APPLY_PAN_ONLY,
            RecoveredSupplementStatePolicy.decide(session, pan, box)
        )
    }

    @Test
    fun decide_shouldCorrectBoxAndApplyPanWhenCheckpointSaysDeductedButStockIsStillBefore() {
        val pan = KaoPan().apply { positionSn = 13 }
        val box = KaoPanBox().apply { positionSn = 3; num = 5 }
        val session = session(lastStage = "in2").copy(
            stockBefore = 5,
            expectedStockAfter = 4,
            boxDeductionApplied = true
        )

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.APPLY_PAN_AND_CORRECT_BOX,
            RecoveredSupplementStatePolicy.decide(session, pan, box)
        )
    }

    @Test
    fun decide_shouldCorrectOnlyBoxWhenPanWasAlreadyPersisted() {
        val pan = KaoPan().apply {
            positionSn = 13
            isHasSausage = true
            status = 1
            startTime = 123_456L
        }
        val box = KaoPanBox().apply { positionSn = 3; num = 5 }
        val session = session(lastStage = "in7").copy(
            stockBefore = 5,
            expectedStockAfter = 4,
            boxDeductionApplied = true,
            panPlacementApplied = true
        )

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.CORRECT_BOX_ONLY,
            RecoveredSupplementStatePolicy.decide(session, pan, box)
        )
        assertEquals(123_456L, pan.startTime)
    }

    @Test
    fun decide_shouldRejectUnexpectedStockInsteadOfDoubleDeducting() {
        val pan = KaoPan().apply { positionSn = 13 }
        val box = KaoPanBox().apply { positionSn = 3; num = 2 }
        val session = session(lastStage = "in2").copy(stockBefore = 5, expectedStockAfter = 4)

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.INCONSISTENT,
            RecoveredSupplementStatePolicy.decide(session, pan, box)
        )
    }

    @Test
    fun decide_shouldRejectMalformedPlacedPanInsteadOfGuessing() {
        val pan = KaoPan().apply {
            positionSn = 13
            isHasSausage = true
            status = 1
            startTime = 0L
        }
        val box = KaoPanBox().apply { positionSn = 3; num = 5 }

        assertEquals(
            RecoveredSupplementStatePolicy.Decision.INCONSISTENT,
            RecoveredSupplementStatePolicy.decide(session(lastStage = "in7"), pan, box)
        )
    }

    private fun session(lastStage: String?): ActionGroupSession = ActionGroupSession(
        sessionId = "recover-in",
        type = ActionGroupSessionType.IN_SUPPLEMENT,
        sourceNo = 3,
        targetNo = 13,
        lastConfirmedStage = lastStage
    )
}
