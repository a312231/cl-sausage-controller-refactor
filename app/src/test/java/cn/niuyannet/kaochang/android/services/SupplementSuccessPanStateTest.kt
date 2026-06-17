package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementSuccessPanStateTest {

    @Test
    fun applySuccessfulTakePanState_shouldClearLowHeatReservationAfterSold() {
        val pan = KaoPan().apply {
            positionSn = 1
            isHasSausage = true
            isHasGrilling = true
            status = 2
            startTime = 0L
            holdingTime = 12_000L
            taste = Taste("B")
            cmdStatusTake = 0x00E5
        }

        KaoChangOperate.applySuccessfulTakePanState(pan)

        assertFalse(pan.isHasSausage)
        assertFalse(pan.isHasGrilling)
        assertEquals(0, pan.status)
        assertEquals(0L, pan.startTime)
        assertEquals(0L, pan.holdingTime)
        assertEquals(0, pan.cmdStatusTake)
    }

    @Test
    fun applySuccessfulSupplementPanState_shouldClearStaleHoldingTimeAndStartBaking() {
        val pan = KaoPan().apply {
            positionSn = 13
            isHasSausage = false
            isHasGrilling = true
            status = 2
            startTime = 111L
            holdingTime = 1_000L
            taste = Taste("A")
            cmdStatusMove = 0x00E1
        }
        val box = KaoPanBox().apply {
            positionSn = 17
            num = 5
            tasteCode = "C"
            tasteName = "测试肠"
            cmdStatusTake = 0x00E5
        }

        KaoChangOperate.applySuccessfulSupplementPanState(
            kaoPan = pan,
            kaoPanBox = box,
            demandGateReason = "单测",
            nowMs = 12_345L,
            clearPendingDiscard = false,
            config = AppConfigBean().apply {
                bakingTime = 30
                discardTime = 15
            },
            tasteBuilder = { sourceBox, _ ->
                Taste().apply {
                    tasteCode = sourceBox.tasteCode
                    tasteName = sourceBox.tasteName
                    productId = sourceBox.productId?.toIntOrNull() ?: 0
                }
            }
        )

        assertTrue(pan.isHasSausage)
        assertFalse(pan.isHasGrilling)
        assertEquals(1, pan.status)
        assertEquals(12_345L, pan.startTime)
        assertEquals(0L, pan.holdingTime)
        assertEquals("C", pan.taste.tasteCode)
        assertEquals(4, box.num)
        assertEquals(0, pan.cmdStatusMove)
        assertEquals(0, box.cmdStatusTake)
    }
}
