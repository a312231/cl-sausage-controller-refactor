package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendServerHelperSignatureTest {

    @Test
    fun buildRuntimeStateSignature_shouldNormalizePendingDiscardOrder() {
        val first = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(26, 25, 26))
        }
        val second = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26))
        }

        assertEquals(RuntimeStateSignatureBuilder.build(first), RuntimeStateSignatureBuilder.build(second))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenFoodSafetyAlertChanges() {
        val normal = baseConfig()
        val alert = baseConfig().apply {
            foodSafetyAlertCode = FaultAlertCode
            foodSafetyAlertReason = "故障停热暂停超阈值"
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(normal), RuntimeStateSignatureBuilder.build(alert))
    }

    @Test
    fun buildRuntimeStateSignature_shouldIgnoreKeepWarmExpiredAlert() {
        val normal = baseConfig()
        val expiredAlert = baseConfig().apply {
            foodSafetyAlertCode = KeepWarmExpiredAlertCode
            foodSafetyAlertReason = "过保/待丢弃扫描触发：source=高并发轮询"
        }

        assertEquals(RuntimeStateSignatureBuilder.build(normal), RuntimeStateSignatureBuilder.build(expiredAlert))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenPendingDiscardContentChanges() {
        val original = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26))
        }
        val changed = baseConfig().apply {
            pendingDiscardPositions.addAll(listOf(25, 26, 30))
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(original), RuntimeStateSignatureBuilder.build(changed))
    }

    @Test
    fun buildRuntimeStateSignature_shouldIgnoreLocalBasicStatus() {
        val enabled = baseConfig().apply {
            status = 1
        }
        val disabled = baseConfig().apply {
            status = 0
        }

        assertEquals(RuntimeStateSignatureBuilder.build(enabled), RuntimeStateSignatureBuilder.build(disabled))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenOnlineStatusChanges() {
        val operating = baseConfig().apply {
            onlineStatus = 1
        }
        val resting = baseConfig().apply {
            onlineStatus = 2
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(operating), RuntimeStateSignatureBuilder.build(resting))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenLowerPlatformRuntimeFieldsChange() {
        val normal = baseConfig()
        val degraded = baseConfig().apply {
            lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
            supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
            orderBlockReason = AppConfigBean.ORDER_BLOCK_REASON_DISPATCH_SAFETY_CHECKING
        }

        assertNotEquals(RuntimeStateSignatureBuilder.build(normal), RuntimeStateSignatureBuilder.build(degraded))
    }

    @Test
    fun buildRuntimeStateSignature_shouldChangeWhenLowerPlatformGenerationChanges() {
        val oldInstall = baseConfig().apply { lowerPlatformGeneration = "generation-old" }
        val newInstall = baseConfig().apply { lowerPlatformGeneration = "generation-new" }

        assertNotEquals(RuntimeStateSignatureBuilder.build(oldInstall), RuntimeStateSignatureBuilder.build(newInstall))
    }

    @Test
    fun buildRuntimeStatePayload_shouldIncludeLowerPlatformRuntimeFields() {
        val payload = RuntimeStatePayloadBuilder.build(
            deviceCode = "device-001",
            runtimeSeq = 100L,
            config = baseConfig().apply {
                lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                lowerPlatformIsolationActionName = "in4"
                supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
                orderBlockReason = ""
                lowerPlatformAggregateVersion = 6L
                lowerPlatformGeneration = "generation-device-001"
                lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                    eventId = "evt-isolated"
                    revision = 1L
                    faultType = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
                    lifecycle = "OPEN"
                    actionName = "in4"
                    occurrenceCount = 1
                })
            },
            source = "runtime_state:supply_degraded",
            reportedAt = 123L
        )

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, payload.getString("lowerPlatformStatus"))
        assertEquals("in4", payload.getString("lowerPlatformIsolationActionName"))
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, payload.getIntValue("supplementEnabled"))
        assertEquals("", payload.getString("orderBlockReason"))
        assertEquals(6L, payload.getLongValue("lowerPlatformAggregateVersion"))
        assertEquals("generation-device-001", payload.getString("lowerPlatformGeneration"))
        assertEquals("evt-isolated", payload.getJSONArray("lowerPlatformEpisodes").getJSONObject(0).getString("eventId"))
    }

    @Test
    fun frozenLowerSnapshot_shouldKeepSignatureAndPayloadConsistentAfterConfigMutation() {
        val config = baseConfig().apply {
            lowerPlatformGeneration = "generation-frozen"
            lowerPlatformAggregateVersion = 9L
            lowerPlatformIsolationActionName = "in4"
            orderBlockReason = "frozen-block"
        }
        LowerPlatformEpisodeReducer.openIsolated(config, "in4", "frozen", 1L)
        val snapshot = LowerPlatformEpisodeReducer.snapshot(config)

        config.lowerPlatformGeneration = "generation-mutated"
        config.lowerPlatformAggregateVersion = 10L
        config.lowerPlatformIsolationActionName = "in5"
        config.orderBlockReason = "mutated-block"
        config.lowerPlatformEpisodes.clear()

        val signature = RuntimeStateSignatureBuilder.build(config, snapshot)
        val payload = RuntimeStatePayloadBuilder.build(
            deviceCode = "device-001",
            runtimeSeq = 1L,
            config = config,
            source = "test",
            reportedAt = 2L,
            lowerPlatformSnapshot = snapshot
        )

        assertTrue(signature.contains("generation-frozen|${snapshot.aggregateVersion}|"))
        assertTrue(signature.contains("|in4|generation-frozen|"))
        assertTrue(signature.contains("|frozen-block|"))
        assertEquals("generation-frozen", payload.getString("lowerPlatformGeneration"))
        assertEquals(snapshot.aggregateVersion, payload.getLongValue("lowerPlatformAggregateVersion"))
        assertEquals("in4", payload.getString("lowerPlatformIsolationActionName"))
        assertEquals("frozen-block", payload.getString("orderBlockReason"))
        assertEquals(1, payload.getJSONArray("lowerPlatformEpisodes").size)
    }

    private fun baseConfig(): AppConfigBean =
        AppConfigBean().apply {
            status = 1
            onlineStatus = 1
            supplyStatus = AppConfigBean.SUPPLY_STATUS_NORMAL
            scanBlockStatus = AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            isEnable = 1
            modeType = 2
            transitionMode = 0
            highConcurrencyResidualDrain = 0
            moveStatus = 0
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            restStatusSource = AppConfigBean.REST_SOURCE_NONE
        }

    private companion object {
        const val FaultAlertCode = "ANOMALY_TOO_LONG"
        const val KeepWarmExpiredAlertCode = "KEEP_WARM_EXPIRED"
    }
}
