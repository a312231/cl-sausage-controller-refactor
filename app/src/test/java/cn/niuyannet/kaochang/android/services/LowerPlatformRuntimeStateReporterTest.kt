package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerPlatformRuntimeStateReporterTest {

    @Test
    fun applyLowerPlatformIsolated_shouldDisableSupplementWithoutBlockingOrdersOrMaintenance() {
        val config = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING
            onlineStatus = 1
            errorStatus = 0
            isEnable = 1
            moveStatus = 1
        }

        LowerPlatformRuntimeStateReporter.applyLowerPlatformIsolated(config)

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, config.supplementEnabled)
        assertEquals("", config.orderBlockReason)
        assertEquals(1, config.onlineStatus)
        assertEquals(0, config.errorStatus)
        assertEquals(1, config.isEnable)
        assertEquals(0, config.moveStatus)
    }

    @Test
    fun confirmLowerPlatformIsolationAfterManualAction_shouldRecoverManualLowerPlatformIsolation() {
        val isolatedLiftRisk = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            lowerPlatformIsolationActionName = "in4"
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        }
        val isolatedLowerPlatform = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            lowerPlatformIsolationActionName = "in3"
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        }
        val isolatedEarlyLowerPlatform = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            lowerPlatformIsolationActionName = "in1"
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        }

        val liftRiskSnapshot = LowerPlatformRuntimeStateReporter.recoverLowerPlatformIsolation(isolatedLiftRisk)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, liftRiskSnapshot.lowerPlatformStatus)
        assertEquals("in4", liftRiskSnapshot.lowerPlatformIsolationActionName)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, liftRiskSnapshot.supplementEnabled)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL, isolatedLiftRisk.lowerPlatformStatus)
        assertEquals("", isolatedLiftRisk.lowerPlatformIsolationActionName)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_YES, isolatedLiftRisk.supplementEnabled)

        assertTrue(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(isolatedLowerPlatform))
        assertTrue(
            LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(isolatedEarlyLowerPlatform)
        )
    }

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
        val isolated = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            orderBlockReason = ""
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

        assertFalse(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualBoxMaintenance(isolated))
        assertFalse(
            LowerPlatformRuntimeStateReporter.shouldClearOrderBlockReasonAfterManualBoxMaintenance(
                isolated
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
