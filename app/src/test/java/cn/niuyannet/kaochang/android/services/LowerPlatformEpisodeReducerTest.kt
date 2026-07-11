package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.mqtt.RuntimeStatePayloadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerPlatformEpisodeReducerTest {
    @Test
    fun supplyFailures_shouldTrackBoxesIndependentlyAndKeepFirstOccurrence() {
        val config = AppConfigBean()

        LowerPlatformEpisodeReducer.openSupplyDegraded(
            config = config,
            sourceBoxNos = listOf(3, 5),
            reason = "红外未确认",
            occurredAt = 1_000L
        )
        val firstEventId = config.lowerPlatformEpisodes.first { it.sourceBoxNo == 3 }.eventId
        LowerPlatformEpisodeReducer.openSupplyDegraded(
            config = config,
            sourceBoxNos = listOf(3),
            reason = "再次失败",
            occurredAt = 2_000L
        )

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED, config.lowerPlatformStatus)
        assertEquals(2, config.lowerPlatformEpisodes.size)
        val repeated = config.lowerPlatformEpisodes.first { it.sourceBoxNo == 3 }
        assertEquals(firstEventId, repeated.eventId)
        assertEquals(1_000L, repeated.firstOccurredAt)
        assertEquals(2_000L, repeated.lastOccurredAt)
        assertEquals(2, repeated.occurrenceCount)
        assertEquals(2L, repeated.revision)
        assertEquals(2L, config.lowerPlatformAggregateVersion)
    }

    @Test
    fun isolatedEpisode_shouldHavePriorityAndBoxRecoveryShouldNotClearIt() {
        val config = AppConfigBean()
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(7), "取肠失败", 1_000L)
        LowerPlatformEpisodeReducer.openIsolated(config, "in4", "升降台未到底", 2_000L)

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        LowerPlatformEpisodeReducer.recoverSupplyBox(config, 7, 3_000L)

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, config.lowerPlatformStatus)
        assertEquals("RECOVERED", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 7 }.lifecycle)
        assertEquals("OPEN", config.lowerPlatformEpisodes.first { it.faultType == "ISOLATED" }.lifecycle)
    }

    @Test
    fun recoveringOneBox_shouldLeaveOtherOpenBoxDegraded() {
        val config = AppConfigBean()
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(8, 9), "取肠失败", 1_000L)

        LowerPlatformEpisodeReducer.recoverSupplyBox(config, 8, 2_000L)

        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED, config.lowerPlatformStatus)
        assertEquals("RECOVERED", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 8 }.lifecycle)
        assertEquals("OPEN", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 9 }.lifecycle)
        assertNotEquals(0L, config.lowerPlatformAggregateVersion)
    }

    @Test
    fun successfulTake_shouldRecoverOnlyTheMatchingSupplyBox() {
        val config = AppConfigBean()
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(8, 9), "取肠失败", 1_000L)

        val recovered = LowerPlatformRuntimeStateReporter.applySuccessfulSupplyBoxTake(
            config = config,
            sourceBoxNo = 8,
            recoveredAt = 2_000L
        )

        assertTrue(recovered)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED, config.lowerPlatformStatus)
        assertEquals("RECOVERED", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 8 }.lifecycle)
        assertEquals("OPEN", config.lowerPlatformEpisodes.first { it.sourceBoxNo == 9 }.lifecycle)
    }

    @Test
    fun isolatedEpisode_shouldPersistFailureContextWithoutDroppingDiagnostics() {
        val config = AppConfigBean()

        LowerPlatformEpisodeReducer.openIsolated(
            config = config,
            actionName = "in5",
            reason = "抛肠红外未确认",
            occurredAt = 3_000L,
            details = LowerPlatformEpisodeDetails(
                sourceBoxNo = 6,
                targetPanNo = 25,
                tasteCode = "spicy",
                batchId = "batch-25",
                batchType = "HIGH_HEAT_25_33",
                errorCode = "0x00E6",
                rawResult = "FAILED(230): 抛肠红外未确认",
                requiresManualConfirm = true
            )
        )

        val episode = config.lowerPlatformEpisodes.single()
        assertEquals(6, episode.sourceBoxNo)
        assertEquals(25, episode.targetPanNo)
        assertEquals("spicy", episode.tasteCode)
        assertEquals("batch-25", episode.batchId)
        assertEquals("HIGH_HEAT_25_33", episode.batchType)
        assertEquals("0x00E6", episode.errorCode)
        assertEquals("FAILED(230): 抛肠红外未确认", episode.rawResult)
        assertEquals("抛肠红外未确认", episode.reason)
        assertTrue(episode.requiresManualConfirm)
    }

    @Test
    fun normalize_shouldDropInvalidAndBoundRecoveredHistoryWhileKeepingOpenEpisodes() {
        val config = AppConfigBean()
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(3), "active", 1L)
        repeat(150) { index ->
            config.lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                eventId = "old-$index"
                faultType = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                lifecycle = "RECOVERED"
                sourceBoxNo = 4
                lastOccurredAt = index.toLong()
                recoveredAt = index.toLong()
            })
        }
        config.lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
            eventId = "invalid"
            faultType = "UNKNOWN"
            lifecycle = "OPEN"
            sourceBoxNo = 99
        })

        LowerPlatformEpisodeReducer.normalize(config)

        assertTrue(config.lowerPlatformEpisodes.any { it.eventId.isNotBlank() && it.lifecycle == "OPEN" })
        assertTrue(config.lowerPlatformEpisodes.none { it.eventId == "invalid" })
        assertTrue(config.lowerPlatformEpisodes.size <= LowerPlatformEpisodeReducer.MAX_EPISODE_HISTORY)
    }

    @Test
    fun aggregateVersionOverflow_shouldRotateGenerationInsteadOfWrappingNegative() {
        val config = AppConfigBean().apply {
            lowerPlatformGeneration = "old-generation"
            lowerPlatformAggregateVersion = Long.MAX_VALUE
        }

        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(3), "overflow", 1L)

        assertTrue(config.lowerPlatformAggregateVersion > 0L)
        assertTrue(config.lowerPlatformGeneration != "old-generation")
    }

    @Test
    fun recoveringAfterLargeHistory_shouldKeepOpenEpisodesAndBoundRuntimePayload() {
        val config = AppConfigBean()
        repeat(120) { index ->
            config.lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                eventId = "recovered-$index"
                faultType = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                lifecycle = "RECOVERED"
                sourceBoxNo = 4
                recoveredAt = index.toLong()
                lastOccurredAt = index.toLong()
            })
        }
        LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(3, 5), "active", 200L)
        LowerPlatformEpisodeReducer.recoverSupplyBox(config, 3, 300L)

        val payload = RuntimeStatePayloadBuilder.build("device", 1L, config, "test", 400L)
        val episodes = payload.getJSONArray("lowerPlatformEpisodes")

        assertTrue(episodes.size <= LowerPlatformEpisodeReducer.MAX_EPISODE_HISTORY)
        assertTrue(episodes.any { (it as com.alibaba.fastjson.JSONObject).getInteger("sourceBoxNo") == 5 })
    }

    @Test
    fun normalize_shouldDetectAndVersionInPlaceFieldCorrections() {
        val config = AppConfigBean().apply {
            lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                eventId = "normalize-fields"
                faultType = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                lifecycle = "OPEN"
                sourceBoxNo = 3
                revision = 0L
                occurrenceCount = 0
                firstOccurredAt = -1L
            })
        }

        assertTrue(LowerPlatformEpisodeReducer.normalize(config))
        assertEquals(1L, config.lowerPlatformEpisodes.single().revision)
        assertEquals(1, config.lowerPlatformEpisodes.single().occurrenceCount)
        assertTrue(config.lowerPlatformAggregateVersion > 0L)
    }

    @Test
    fun transaction_shouldPersistInsideLockAndReturnImmutableEpisodeSnapshot() {
        val config = AppConfigBean()
        var persistedVersion = 0L

        val tx = LowerPlatformEpisodeReducer.transaction(
            config = config,
            persist = { persistedVersion = it.lowerPlatformAggregateVersion }
        ) {
            LowerPlatformEpisodeReducer.openSupplyDegraded(config, listOf(3), "tx", 1L)
            true
        }
        config.lowerPlatformEpisodes.single().reason = "mutated-after"

        assertEquals(tx.snapshot.aggregateVersion, persistedVersion)
        assertEquals("tx", tx.snapshot.episodes.single().reason)
    }

    @Test
    fun snapshot_shouldFreezeAllLowerPlatformAggregateFields() {
        val config = AppConfigBean().apply {
            lowerPlatformGeneration = "generation-a"
            lowerPlatformAggregateVersion = 7L
            lowerPlatformIsolationActionName = "in4"
            orderBlockReason = "blocked-a"
        }
        LowerPlatformEpisodeReducer.openIsolated(config, "in4", "lift failed", 1L)

        val snapshot = LowerPlatformEpisodeReducer.snapshot(config)
        config.lowerPlatformIsolationActionName = "in5"
        config.orderBlockReason = "blocked-b"

        assertEquals("in4", snapshot.isolationActionName)
        assertEquals("blocked-a", snapshot.orderBlockReason)
        assertEquals(AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED, snapshot.status)
        assertEquals(AppConfigBean.SUPPLEMENT_ENABLED_NO, snapshot.supplementEnabled)
    }
}
