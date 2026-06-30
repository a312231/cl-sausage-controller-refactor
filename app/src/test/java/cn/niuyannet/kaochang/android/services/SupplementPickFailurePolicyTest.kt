package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPickFailurePolicyTest {

    @Test
    fun recordIrPickFailedBox_shouldKeepStockAndExcludeFailedCandidates() {
        val box20 = box(positionSn = 20, num = 3)
        val box19 = box(positionSn = 19, num = 2)
        val box18 = box(positionSn = 18, num = 4).apply {
            cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
        }
        val result = SupplementPickFailurePolicy.recordIrPickFailedBox(
            failedBox = box20,
            allCandidates = listOf(box20, box19, box18)
        )

        assertEquals(3, result.previousNum)
        assertEquals(3, box20.num)
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

    @Test
    fun isSerialIn2IrPickFailure_shouldOnlyAllowIn2IrPickFailed() {
        assertTrue(
            SupplementPickFailurePolicy.isSerialIn2IrPickFailure(
                actionName = "in2",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠")
            )
        )
        assertFalse(
            SupplementPickFailurePolicy.isSerialIn2IrPickFailure(
                actionName = "in5",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "错误码相同但动作不对")
            )
        )
        assertFalse(
            SupplementPickFailurePolicy.isSerialIn2IrPickFailure(
                actionName = "in2",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_THROW_FAILED, "抛肠失败")
            )
        )
    }

    @Test
    fun isPipelineSupplyDegradableFailure_shouldRequireIn2IrPickFailedAndNoPrefetchedNext() {
        assertTrue(
            SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(
                actionName = "in2",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                hasPrefetchedNext = false
            )
        )
        assertFalse(
            SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(
                actionName = "in2",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                hasPrefetchedNext = true
            )
        )
        assertFalse(
            SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(
                actionName = "in5",
                result = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "错误码相同但动作不对"),
                hasPrefetchedNext = false
            )
        )
        assertFalse(
            SupplementPickFailurePolicy.isPipelineSupplyDegradableFailure(
                actionName = "in2",
                result = AtomicActionResult.Timeout("超时"),
                hasPrefetchedNext = false
            )
        )
    }

    private fun box(positionSn: Int, num: Int): KaoPanBox {
        return KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = num
            this.tasteCode = "A"
            this.cmdStatusTake = 0
        }
    }
}
