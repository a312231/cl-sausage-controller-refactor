package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPanMovePolicyTest {

    @Test
    fun checkReadiness_shouldAllowMaintenanceStatusWhenIdleAndAlgorithmOff() {
        val decision = ManualPanMovePolicy.checkReadiness(state(onlineStatus = 3))

        assertTrue(decision.ok)
        assertFalse(decision.requiresConfirmation)
        assertEquals("允许搬移", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectMissingSource() {
        val decision = ManualPanMovePolicy.checkReadiness(state(sourcePan = null))

        assertFalse(decision.ok)
        assertEquals("源工位不存在：source=1", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectMissingTarget() {
        val decision = ManualPanMovePolicy.checkReadiness(state(targetPan = null))

        assertFalse(decision.ok)
        assertEquals("目标工位不存在：target=11", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectSameSourceAndTarget() {
        val pan = occupiedPan(1)
        val decision = ManualPanMovePolicy.checkReadiness(
            state(sourcePan = pan, targetPan = pan, targetPositionSn = 1)
        )

        assertFalse(decision.ok)
        assertEquals("源工位和目标工位不能相同：1", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectSourceWithoutSausage() {
        val decision = ManualPanMovePolicy.checkReadiness(state(sourcePan = emptyPan(1)))

        assertFalse(decision.ok)
        assertEquals("源工位无烤肠：source=1", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectOccupiedTarget() {
        val decision = ManualPanMovePolicy.checkReadiness(state(targetPan = occupiedPan(11)))

        assertFalse(decision.ok)
        assertEquals("目标工位已有烤肠：target=11", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectAlgorithmEnabledInConfig() {
        val decision = ManualPanMovePolicy.checkReadiness(state(appConfigIsEnable = 1))

        assertFalse(decision.ok)
        assertEquals("请先关闭烤肠算法：isEnable=1, algorithmIsEnabled=false", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectAlgorithmEnabledInMemory() {
        val decision = ManualPanMovePolicy.checkReadiness(state(algorithmIsEnabled = true))

        assertFalse(decision.ok)
        assertEquals("请先关闭烤肠算法：isEnable=0, algorithmIsEnabled=true", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectMechanicalBusy() {
        val decision = ManualPanMovePolicy.checkReadiness(state(moveStatus = 3))

        assertFalse(decision.ok)
        assertEquals("机械动作状态不是空闲：moveStatus=3", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectDeliveringOnlineStatus() {
        val decision = ManualPanMovePolicy.checkReadiness(state(onlineStatus = 4))

        assertFalse(decision.ok)
        assertEquals("设备正在出餐：onlineStatus=4", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectProcessingOrder() {
        val decision = ManualPanMovePolicy.checkReadiness(state(hasProcessingOrder = true))

        assertFalse(decision.ok)
        assertEquals("存在处理中的订单", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRejectOrderTracking() {
        val decision = ManualPanMovePolicy.checkReadiness(state(hasOrderTracking = true))

        assertFalse(decision.ok)
        assertEquals("订单自清洁跟踪未结束", decision.reason)
    }

    @Test
    fun checkReadiness_shouldRequireConfirmationForGenericFault() {
        val decision = ManualPanMovePolicy.checkReadiness(
            state(
                errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE,
                errorCode = FaultCode.MODBUS_COMM_DROP.name,
                errorReason = "通信曾断开"
            )
        )

        assertTrue(decision.ok)
        assertTrue(decision.requiresConfirmation)
        assertEquals("允许搬移，但需要确认故障/检修状态", decision.reason)
        assertTrue(decision.warning!!.contains("errorCode=${FaultCode.MODBUS_COMM_DROP.name}"))
        assertTrue(decision.warning!!.contains("errorReason=通信曾断开"))
    }

    @Test
    fun checkReadiness_shouldRequireConfirmationForInspectionMode() {
        val decision = ManualPanMovePolicy.checkReadiness(
            state(inspectionMode = AppConfigBean.INSPECTION_MODE_ACTIVE)
        )

        assertTrue(decision.ok)
        assertTrue(decision.requiresConfirmation)
        assertTrue(decision.warning!!.contains("inspectionMode=${AppConfigBean.INSPECTION_MODE_ACTIVE}"))
    }

    @Test
    fun checkReadiness_shouldRejectPanMoveFailedFault() {
        val decision = ManualPanMovePolicy.checkReadiness(
            state(
                errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE,
                errorCode = FaultCode.KAOPAN_MOVE_FAILED.name
            )
        )

        assertFalse(decision.ok)
        assertEquals(
            "当前故障为搬盘机构故障，禁止手动搬盘：errorCode=${FaultCode.KAOPAN_MOVE_FAILED.name}",
            decision.reason
        )
    }

    @Test
    fun checkReadiness_shouldRejectPanMoveTimeoutFault() {
        val decision = ManualPanMovePolicy.checkReadiness(
            state(
                errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE,
                errorCode = FaultCode.KAOPAN_MOVE_TIMEOUT.name
            )
        )

        assertFalse(decision.ok)
        assertEquals(
            "当前故障为搬盘机构故障，禁止手动搬盘：errorCode=${FaultCode.KAOPAN_MOVE_TIMEOUT.name}",
            decision.reason
        )
    }

    private fun state(
        sourcePan: KaoPan? = occupiedPan(1),
        targetPan: KaoPan? = emptyPan(11),
        sourcePositionSn: Int = 1,
        targetPositionSn: Int = 11,
        appConfigIsEnable: Int = 0,
        algorithmIsEnabled: Boolean = false,
        moveStatus: Int = 0,
        onlineStatus: Int = 1,
        errorStatus: Int = AppConfigBean.ERROR_STATUS_NORMAL,
        errorCode: String? = null,
        errorReason: String? = null,
        inspectionMode: Int = AppConfigBean.INSPECTION_MODE_NORMAL,
        hasProcessingOrder: Boolean = false,
        hasOrderTracking: Boolean = false
    ): ManualPanMovePolicy.State {
        return ManualPanMovePolicy.State(
            sourcePan = sourcePan,
            targetPan = targetPan,
            sourcePositionSn = sourcePositionSn,
            targetPositionSn = targetPositionSn,
            appConfigIsEnable = appConfigIsEnable,
            algorithmIsEnabled = algorithmIsEnabled,
            moveStatus = moveStatus,
            onlineStatus = onlineStatus,
            errorStatus = errorStatus,
            errorCode = errorCode,
            errorReason = errorReason,
            inspectionMode = inspectionMode,
            hasProcessingOrder = hasProcessingOrder,
            hasOrderTracking = hasOrderTracking
        )
    }

    private fun occupiedPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = 1000L
        }

    private fun emptyPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = false
            this.status = 0
        }
}
