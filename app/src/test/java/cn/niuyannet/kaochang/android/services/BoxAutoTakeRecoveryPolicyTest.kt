package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxAutoTakeRecoveryPolicyTest {

    @Test
    fun `box should display unavailable label only for ir pick failed`() {
        val unavailable = box(13, AtomicActionCodes.IR_PICK_FAILED)
        val normal = box(14, 0)
        val otherError = box(15, 0x00EF)

        assertTrue(BoxAutoTakeRecoveryPolicy.isAutoTakeUnavailable(unavailable))
        assertEquals("不可自动取用", BoxAutoTakeRecoveryPolicy.statusLabel(unavailable))
        assertEquals("取肠失败", BoxAutoTakeRecoveryPolicy.statusDetail(unavailable))

        assertFalse(BoxAutoTakeRecoveryPolicy.isAutoTakeUnavailable(normal))
        assertEquals("", BoxAutoTakeRecoveryPolicy.statusLabel(normal))
        assertEquals("", BoxAutoTakeRecoveryPolicy.statusDetail(normal))

        assertFalse(BoxAutoTakeRecoveryPolicy.isAutoTakeUnavailable(otherError))
        assertEquals("", BoxAutoTakeRecoveryPolicy.statusLabel(otherError))
    }

    @Test
    fun `restore should only clear selected ir pick failed boxes and preserve stock`() {
        val failed = box(13, AtomicActionCodes.IR_PICK_FAILED).apply {
            num = 8
            tasteCode = "taste-a"
            tasteName = "口味A"
            checkStatus = true
        }
        val normalSelected = box(14, 0).apply {
            num = 7
            tasteCode = "taste-b"
            tasteName = "口味B"
            checkStatus = true
        }
        val failedUnselected = box(15, AtomicActionCodes.IR_PICK_FAILED).apply {
            num = 6
            tasteCode = "taste-c"
            tasteName = "口味C"
            checkStatus = false
        }

        val result = BoxAutoTakeRecoveryPolicy.restoreAutoTakeForSelected(
            listOf(failed, normalSelected, failedUnselected)
        )

        assertEquals(listOf(13), result.recoveredBoxNos)
        assertEquals(0, failed.cmdStatusTake)
        assertEquals(8, failed.num)
        assertEquals("taste-a", failed.tasteCode)
        assertEquals("口味A", failed.tasteName)
        assertFalse(failed.checkStatus)

        assertEquals(0, normalSelected.cmdStatusTake)
        assertEquals(7, normalSelected.num)
        assertTrue(normalSelected.checkStatus)

        assertEquals(AtomicActionCodes.IR_PICK_FAILED, failedUnselected.cmdStatusTake)
        assertEquals(6, failedUnselected.num)
        assertFalse(failedUnselected.checkStatus)
    }

    @Test
    fun `restore should reject when selected boxes do not contain unavailable source`() {
        val result = BoxAutoTakeRecoveryPolicy.restoreAutoTakeForSelected(
            listOf(
                box(13, 0).apply { checkStatus = true },
                box(14, AtomicActionCodes.IR_PICK_FAILED).apply { checkStatus = false }
            )
        )

        assertTrue(result.recoveredBoxNos.isEmpty())
        assertEquals("请先选择不可自动取用的烤肠箱", result.message)
    }

    private fun box(positionSn: Int, cmdStatusTake: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.cmdStatusTake = cmdStatusTake
        }
}
