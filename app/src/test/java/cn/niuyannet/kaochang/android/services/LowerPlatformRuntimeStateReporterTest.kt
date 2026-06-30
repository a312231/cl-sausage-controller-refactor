package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerPlatformRuntimeStateReporterTest {

    @Test
    fun manualBoxMaintenanceRecovery_shouldOnlyApplyToSupplyDegradedOrRecoveryRequiredReason() {
        val degraded = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
            orderBlockReason = ""
        }
        val degradedWithDispatchBlock = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING
        }
        val recoveryRequired = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED
        }
        val dispatchSafetyChecking = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING
        }

        assertTrue(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualBoxMaintenance(degraded))
        assertFalse(LowerPlatformRuntimeStateReporter.shouldClearOrderBlockReasonAfterManualBoxMaintenance(degraded))

        assertTrue(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualBoxMaintenance(degradedWithDispatchBlock))
        assertFalse(
            LowerPlatformRuntimeStateReporter.shouldClearOrderBlockReasonAfterManualBoxMaintenance(
                degradedWithDispatchBlock
            )
        )

        assertTrue(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualBoxMaintenance(recoveryRequired))
        assertTrue(
            LowerPlatformRuntimeStateReporter.shouldClearOrderBlockReasonAfterManualBoxMaintenance(
                recoveryRequired
            )
        )

        assertFalse(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualBoxMaintenance(dispatchSafetyChecking))
        assertFalse(
            LowerPlatformRuntimeStateReporter.shouldClearOrderBlockReasonAfterManualBoxMaintenance(
                dispatchSafetyChecking
            )
        )
    }
}
