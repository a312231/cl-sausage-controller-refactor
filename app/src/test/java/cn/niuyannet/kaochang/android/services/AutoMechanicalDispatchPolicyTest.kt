package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMechanicalDispatchPolicyTest {

    @Test
    fun evaluate_shouldBlockAutoDispatchWhileDeliveringOrder() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 4,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL,
            moveStatus = 0
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("订单出餐中"))
    }

    @Test
    fun evaluate_shouldAllowAutoDispatchOnlyWhenOperatingAndIdle() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL,
            moveStatus = 0
        )

        assertTrue(decision.canStart)
    }

    @Test
    fun evaluate_shouldAllowUpperPlatformAutoDispatchWhenLowerPlatformIsolated() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL,
            moveStatus = 0,
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
            lowerPlatformIsolationActionName = "in5"
        )

        assertTrue(decision.canStart)
    }

    @Test
    fun shouldBlockAutoDispatchForLowerPlatformIsolation_shouldBlockSelfCleanForLiftRiskActions() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL,
            moveStatus = 0,
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
            lowerPlatformIsolationActionName = "in4"
        )

        assertTrue(decision.canStart)
        assertTrue(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in4"
            )
        )
    }

    @Test
    fun shouldBlockAutoDispatchForLowerPlatformIsolation_shouldOnlyBlockLiftRiskActions() {
        assertFalse(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL,
                lowerPlatformIsolationActionName = "in4"
            )
        )
        assertTrue(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in4"
            )
        )
        assertTrue(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in5"
            )
        )
        assertFalse(
            AutoMechanicalDispatchPolicy.shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED,
                lowerPlatformIsolationActionName = "in3"
            )
        )
    }

    @Test
    fun evaluate_shouldBlockAutoDispatchWhenMoveStatusIsBusy() {
        val decision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL,
            moveStatus = 2
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("moveStatus"))
    }

    @Test
    fun shouldPreserveDeliveringMoveStatusDuringRecovery_shouldKeepOrderDeliveringWindowClosed() {
        assertTrue(
            AutoMechanicalDispatchPolicy.shouldPreserveDeliveringMoveStatusDuringRecovery(
                onlineStatus = 4,
                moveStatus = 2
            )
        )
        assertFalse(
            AutoMechanicalDispatchPolicy.shouldPreserveDeliveringMoveStatusDuringRecovery(
                onlineStatus = 1,
                moveStatus = 2
            )
        )
    }
}
