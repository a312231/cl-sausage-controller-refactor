package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPickFailurePolicyTest {

    @Test
    fun markIrPickFailedBoxAsEmpty_shouldClearStockAndKeepRemainingCandidates() {
        val box20 = box(positionSn = 20, num = 3)
        val box19 = box(positionSn = 19, num = 2)
        val box18 = box(positionSn = 18, num = 0)
        val result = SupplementPickFailurePolicy.markIrPickFailedBoxAsEmpty(
            failedBox = box20,
            allCandidates = listOf(box20, box19, box18)
        )

        assertEquals(3, result.previousNum)
        assertEquals(0, box20.num)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, box20.cmdStatusTake)
        assertEquals(listOf(19), result.remainingCandidateBoxNos)
    }

    @Test
    fun buildAllCandidatesFailedFaultReason_shouldIncludeTargetTasteBoxesAndErrorCode() {
        val reason = SupplementPickFailurePolicy.buildAllCandidatesFailedFaultReason(
            targetPanNo = 1,
            tasteCode = "A",
            failedBoxNos = listOf(20, 19, 18),
            lastErrorCode = AtomicActionCodes.IR_PICK_FAILED
        )

        assertTrue(reason.contains("targetPan=1"))
        assertTrue(reason.contains("tasteCode=A"))
        assertTrue(reason.contains("failedBoxes=20,19,18"))
        assertTrue(reason.contains("0x00E5"))
    }

    private fun box(positionSn: Int, num: Int): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = num
            this.cmdStatusTake = 0
        }
    }
}
