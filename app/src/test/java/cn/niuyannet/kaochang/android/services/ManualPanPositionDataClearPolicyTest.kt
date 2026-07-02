package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPanPositionDataClearPolicyTest {

    @Test
    fun clearPosition_shouldResetOnlyTargetPanWithResetKaoPanSinglePanSemantics() {
        val target = occupiedPan(positionSn = 1)
        val other = occupiedPan(positionSn = 4)
        val result = ManualPanPositionDataClearPolicy.clearPositionData(
            pans = listOf(target, other),
            positionSn = 1,
            bakingTimeMinutes = 18,
            discardTimeHours = 5
        )

        assertTrue(result.cleared)
        assertEquals(1, result.before?.positionSn)
        assertEquals(1, result.after?.positionSn)

        val cleared = result.pans.first { it.positionSn == 1 }
        assertEquals(0L, cleared.startTime)
        assertEquals(18L * 60L * 1000L, cleared.bakingTime)
        assertEquals(0L, cleared.holdingTime)
        assertEquals(5L * 60L * 60L * 1000L, cleared.closeTime)
        assertNull(cleared.taste)
        assertEquals(0, cleared.temperature)
        assertEquals(0, cleared.status)
        assertFalse(cleared.isHasSausage)
        assertFalse(cleared.isHasGrilling)
        assertEquals(0, cleared.cmdStatusMove)
        assertEquals(0, cleared.cmdStatusTake)
        assertEquals(0, cleared.cmdStatusDiscard)

        val unchanged = result.pans.first { it.positionSn == 4 }
        assertTrue(unchanged.isHasSausage)
        assertEquals(2, unchanged.status)
        assertEquals("B", unchanged.taste?.tasteCode)
        assertEquals(2_000L, unchanged.holdingTime)
    }

    @Test
    fun clearPosition_shouldLeaveListUnchangedWhenTargetDoesNotExist() {
        val original = listOf(occupiedPan(positionSn = 7))
        val result = ManualPanPositionDataClearPolicy.clearPositionData(
            pans = original,
            positionSn = 33,
            bakingTimeMinutes = 20,
            discardTimeHours = 4
        )

        assertFalse(result.cleared)
        assertNull(result.before)
        assertNull(result.after)
        assertEquals(original, result.pans)
    }

    @Test
    fun clearPosition_shouldChangeSnapshotProjectionHash() {
        val before = listOf(occupiedPan(positionSn = 1))
        val beforeHash = SnapshotProjectionHash.hash(before, emptyList())
        val after = ManualPanPositionDataClearPolicy.clearPositionData(
            pans = before,
            positionSn = 1,
            bakingTimeMinutes = 18,
            discardTimeHours = 5
        ).pans

        assertNotEquals(
            beforeHash,
            SnapshotProjectionHash.hash(after, emptyList())
        )
    }

    @Test
    fun checkReadiness_shouldRejectMechanicalBusy() {
        val decision = ManualPanPositionDataClearPolicy.checkReadiness(
            ManualPanPositionDataClearPolicy.State(
                moveStatus = 2,
                onlineStatus = 1,
                hasProcessingOrder = false,
                hasOrderTracking = false
            )
        )

        assertFalse(decision.ok)
        assertEquals("机械动作状态不是空闲：moveStatus=2", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectOnlineStatusWorking() {
        val decision = ManualPanPositionDataClearPolicy.checkReadiness(
            clearState(onlineStatus = 4)
        )

        assertFalse(decision.ok)
        assertEquals("设备正在出餐：onlineStatus=4", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectProcessingOrder() {
        val decision = ManualPanPositionDataClearPolicy.checkReadiness(
            clearState(hasProcessingOrder = true)
        )

        assertFalse(decision.ok)
        assertEquals("存在处理中的订单", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectOrderTracking() {
        val decision = ManualPanPositionDataClearPolicy.checkReadiness(
            clearState(hasOrderTracking = true)
        )

        assertFalse(decision.ok)
        assertEquals("订单自清洁跟踪未结束", decision.reason)
    }

    @Test
    fun checkReadiness_shouldAllowIdleWithoutOrders() {
        val decision = ManualPanPositionDataClearPolicy.checkReadiness(clearState())

        assertTrue(decision.ok)
        assertEquals("允许清除", decision.reason)
    }

    @Test
    fun executor_shouldSaveBeforeMaintenanceSideEffectsAndSnapshotUpload() {
        val events = mutableListOf<String>()
        val result = ManualPanPositionDataClearExecutor.execute(
            positionSn = 1,
            bakingTimeMinutes = 18,
            discardTimeHours = 5,
            dependencies = ManualPanPositionDataClearExecutor.Dependencies(
                getPans = {
                    events.add("get")
                    listOf(occupiedPan(positionSn = 1))
                },
                savePansSync = {
                    events.add("save:${it.first().isHasSausage}")
                },
                reportPhysicalDelta = { pan, reason, source ->
                    events.add("physical:${pan.positionSn}:$reason:$source")
                },
                clearDiscardPosition = {
                    events.add("clearDiscard:$it")
                },
                stopAllHeatingIfNoSausage = {
                    events.add("stopHeat:$it")
                },
                uploadSnapshot = {
                    events.add("upload:$it")
                }
            )
        )

        assertTrue(result.cleared)
        assertEquals(
            listOf(
                "get",
                "save:false",
                "physical:1:maintenance_clear_pan_position:1:maintenance_clear_pan_position",
                "clearDiscard:1",
                "stopHeat:维护页仅清除烤盘位数据:1",
                "upload:maintenance_clear_pan_position:1"
            ),
            events
        )
    }

    private fun clearState(
        moveStatus: Int = 0,
        onlineStatus: Int = 1,
        hasProcessingOrder: Boolean = false,
        hasOrderTracking: Boolean = false
    ): ManualPanPositionDataClearPolicy.State {
        return ManualPanPositionDataClearPolicy.State(
            moveStatus = moveStatus,
            onlineStatus = onlineStatus,
            hasProcessingOrder = hasProcessingOrder,
            hasOrderTracking = hasOrderTracking
        )
    }

    private fun occupiedPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.id = positionSn
            this.positionSn = positionSn
            this.positionRegion = 1
            this.startTime = 1_000L
            this.bakingTime = 20_000L
            this.holdingTime = 2_000L
            this.closeTime = 60_000L
            this.taste = Taste(1, if (positionSn == 4) "B" else "A", "原味", 101, "烤肠")
            this.temperature = 60
            this.status = 2
            this.isHasSausage = true
            this.isHasGrilling = true
            this.cmdValueMove = 101
            this.cmdValueTake = 301
            this.cmdValueDiscard = 401
            this.cmdStatusMove = 9
            this.cmdStatusTake = 8
            this.cmdStatusDiscard = 7
        }
}
