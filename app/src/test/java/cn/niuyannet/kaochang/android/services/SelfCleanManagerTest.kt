package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCleanManagerTest {

    @Test
    fun maybeRunZone3SelfClean_shouldBlockWhenLiftRiskIsolationActive() {
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
}
