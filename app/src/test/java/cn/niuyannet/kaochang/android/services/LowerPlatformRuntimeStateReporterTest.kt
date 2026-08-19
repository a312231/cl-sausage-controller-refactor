package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LowerPlatformRuntimeStateReporterTest {

    @Test
    fun deferredIsolationApi_shouldPersistWithoutPublishingUntilExplicitPublish() {
        val source = sourceText()
        val persist = functionBlock(
            source,
            "fun persistIsolated(",
            "fun publishPersistedIsolation("
        )
        val publish = functionBlock(
            source,
            "fun publishPersistedIsolation(",
            "fun markIsolated("
        )

        assertTrue(persist.contains("LowerPlatformEpisodeReducer.transaction("))
        assertFalse(persist.contains("publishOrFallback("))
        assertTrue(publish.contains("publishOrFallback("))
    }

    @Test
    fun applySupplyDegraded_shouldNotDowngradeActiveMechanicalIsolation() {
        val config = AppConfigBean().apply {
            lowerPlatformIsolationActionName = "in4"
        }
        LowerPlatformEpisodeReducer.openIsolated(config, "in4", "升降台异常", 1_000L)

        LowerPlatformRuntimeStateReporter.applySupplyDegraded(
            config = config,
            reason = "3号箱取肠失败",
            failedBoxNos = listOf(3),
            occurredAt = 2_000L
        )

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, config.supplementEnabled)
        assertEquals("in4", config.lowerPlatformIsolationActionName)
    }

    private fun functionBlock(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end > start) { "missing end marker: $endMarker" }
        return source.substring(start, end)
    }

    private fun sourceText(): String = listOf(
        File("app/src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformRuntimeStateReporter.kt"),
        File("src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformRuntimeStateReporter.kt")
    ).first { it.isFile }.readText().replace("\r\n", "\n")

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
        assertEquals(1, config.moveStatus)
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
    fun shouldRecoverAfterManualLowerPlatformAction_shouldAllowUnknownActionIsolation() {
        val isolatedWithoutAction = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            lowerPlatformIsolationActionName = ""
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        }
        val normalWithoutAction = AppConfigBean().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
            lowerPlatformIsolationActionName = ""
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
        }

        assertTrue(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(isolatedWithoutAction))
        assertFalse(LowerPlatformRuntimeStateReporter.shouldRecoverAfterManualLowerPlatformAction(normalWithoutAction))
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

    @Test
    fun manualBoxMaintenance_shouldRecoverOnlySelectedBoxesAndNeverReenableDuringIsolation() {
        val config = AppConfigBean()
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(3, 5), "取肠失败", 1_000L)
        LowerPlatformEpisodeReducer.openIsolated(config, "in4", "升降台异常", 2_000L)

        val recovered = LowerPlatformRuntimeStateReporter.applyManualBoxMaintenanceRecovery(
            config = config,
            sourceBoxNos = listOf(3),
            recoveredAt = 3_000L
        )

        assertTrue(recovered)
        assertEquals("RECOVERED", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 3 }.lifecycle)
        assertEquals("OPEN", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 5 }.lifecycle)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, config.supplementEnabled)
    }
}
