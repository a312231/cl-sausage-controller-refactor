package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import com.alibaba.fastjson.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultHeatRecoveryJournalTest {

    @Test
    fun recoveryJournal_shouldReplayAbsoluteTargetsIdempotently() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = AppConfigBean().apply {
            faultHeatPauseStartedAtMs = 2_000L
            pendingDiscardPositions.add(3)
        }
        val result = FaultHeatPauseManager.EndPauseResult(
            active = true,
            pauseDurationMs = 600L,
            shiftedPositions = listOf(25),
            holdingPositions = emptyList(),
            pendingDiscardPositions = listOf(26),
            alertCode = null,
            targetStartTimes = mapOf(25 to 1_600L)
        )

        val journal = FaultHeatPauseManager.buildRecoveryJournal(
            config = config,
            result = result,
            preparedAtMs = 2_600L,
            reason = "unit-test"
        )

        FaultHeatPauseManager.applyRecoveryJournal(listOf(pan), config, journal)
        FaultHeatPauseManager.applyRecoveryJournal(listOf(pan), config, journal)

        assertEquals(1_600L, pan.startTime)
        assertEquals(listOf(3, 26), config.pendingDiscardPositions)
        assertEquals(2_000L, journal.pauseStartedAtMs)
        assertEquals(2_600L, journal.preparedAtMs)
        assertEquals(2, journal.schemaVersion)
        assertEquals("ABSOLUTE_START_TIME", journal.semantics)
        assertEquals(listOf(25), journal.affectedPositions)
        assertEquals(2_600L, journal.coveredUntilMs)
    }

    @Test
    fun schema2Journal_shouldReplayTwiceWithoutDoubleShiftAndAdvanceCoveredUntil() {
        val pan = bakingPan(position = 17, startTime = 200_000L)
        val config = pausedConfig(pauseStartedAtMs = 100_000L)
        val raw = schema2JournalJson(
            targetStartTimes = mapOf(17 to 600_000L),
            affectedPositions = listOf(17),
            pauseStartedAtMs = 100_000L,
            coveredUntilMs = 600_000L
        )

        val first = FaultHeatPauseManager.replayRecoveryJournalState(
            raw = raw,
            pans = listOf(pan),
            config = config,
            nowMs = 600_000L
        )
        val second = FaultHeatPauseManager.replayRecoveryJournalState(
            raw = raw,
            pans = listOf(pan),
            config = config,
            nowMs = 600_000L
        )

        assertTrue(first.applied)
        assertTrue(second.applied)
        assertEquals(600_000L, pan.startTime)
        assertEquals(600_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun recoveryJournal_shouldKeepAbsoluteOriginalTargetForLongPausedDiscardPan() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L).apply {
            faultBakingPauseAbortMs = 100L
            faultPauseAnomalyMs = 10_000L
        }
        val result = FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(pan),
            config = config,
            nowMs = 2_600L,
            pauseStartedAtMs = 2_000L,
            reason = "unit-test",
            emitLogs = false
        )

        val journal = FaultHeatPauseManager.buildRecoveryJournal(
            config = config,
            result = result,
            preparedAtMs = 2_600L,
            reason = "unit-test"
        )

        assertEquals(listOf(25), result.pendingDiscardPositions)
        assertEquals(mapOf(25 to 1_000L), journal.targetStartTimes)
        assertEquals(listOf(25), journal.affectedPositions)
        assertTrue(FaultHeatPauseManager.applyRecoveryJournal(listOf(pan), config, journal))
        assertEquals(1_000L, pan.startTime)
    }

    @Test
    fun schema2Journals_shouldCoverOnlyNewTailAcrossMultipleRestarts() {
        val pan = bakingPan(position = 18, startTime = 200_000L)
        val config = pausedConfig(pauseStartedAtMs = 100_000L)
        val firstRaw = schema2JournalJson(
            targetStartTimes = mapOf(18 to 600_000L),
            affectedPositions = listOf(18),
            pauseStartedAtMs = 100_000L,
            coveredUntilMs = 600_000L
        )
        val secondRaw = schema2JournalJson(
            targetStartTimes = mapOf(18 to 800_000L),
            affectedPositions = listOf(18),
            pauseStartedAtMs = 600_000L,
            coveredUntilMs = 800_000L
        )

        assertTrue(FaultHeatPauseManager.replayRecoveryJournalState(firstRaw, listOf(pan), config, 600_000L).applied)
        assertTrue(FaultHeatPauseManager.replayRecoveryJournalState(secondRaw, listOf(pan), config, 800_000L).applied)

        assertEquals(800_000L, pan.startTime)
        assertEquals(800_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldReplayOnlyLegalAbsoluteTargets() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = """{
            "journalId":"legacy","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{"25":1600},
            "pendingDiscardPositions":[]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertTrue(result.applied)
        assertTrue(result.legacy)
        assertEquals(1_600L, pan.startTime)
        assertEquals(2_600L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldReplayPurePendingDiscardBakingPanWithoutTarget() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = """{
            "journalId":"legacy-pending","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{},
            "pendingDiscardPositions":[25]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertTrue(result.applied)
        assertTrue(result.legacy)
        assertEquals(1_000L, pan.startTime)
        assertEquals(listOf(25), config.pendingDiscardPositions)
        assertEquals(2_600L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldReplayMixedTargetAndPendingDiscardBakingPans() {
        val shiftedPan = bakingPan(position = 17, startTime = 1_000L)
        val discardedPan = bakingPan(position = 18, startTime = 1_500L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = """{
            "journalId":"legacy-mixed","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{"17":1600},
            "pendingDiscardPositions":[18]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(
            raw,
            listOf(shiftedPan, discardedPan),
            config,
            2_600L
        )

        assertTrue(result.applied)
        assertEquals(1_600L, shiftedPan.startTime)
        assertEquals(1_500L, discardedPan.startTime)
        assertEquals(listOf(18), config.pendingDiscardPositions)
        assertEquals(2_600L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldAllowSameBakingPanInTargetAndPendingDiscard() {
        val pan = bakingPan(position = 18, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = """{
            "journalId":"legacy-overlap","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{"18":1600},
            "pendingDiscardPositions":[18]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertTrue(result.applied)
        assertTrue(result.legacy)
        assertEquals(1_600L, pan.startTime)
        assertEquals(listOf(18), config.pendingDiscardPositions)
        assertEquals(2_600L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun journal_shouldRejectFutureTargetWithoutMutatingState() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = schema2JournalJson(
            targetStartTimes = mapOf(25 to 3_000L),
            affectedPositions = listOf(25),
            pauseStartedAtMs = 2_000L,
            coveredUntilMs = 2_600L
        )

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertFalse(result.applied)
        assertNotNull(result.error)
        assertEquals(1_000L, pan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldRejectFutureAbsoluteTarget() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = """{
            "journalId":"legacy-future","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{"25":3000},
            "pendingDiscardPositions":[]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertFalse(result.applied)
        assertTrue(result.legacy)
        assertEquals(1_000L, pan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun journal_shouldRejectNonEmptyAffectedPositionsWhenTargetsAreMissing() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = schema2JournalJson(
            targetStartTimes = emptyMap(),
            affectedPositions = listOf(25),
            pauseStartedAtMs = 2_000L,
            coveredUntilMs = 2_600L
        )

        val result = FaultHeatPauseManager.replayRecoveryJournalState(raw, listOf(pan), config, 2_600L)

        assertFalse(result.applied)
        assertEquals(1_000L, pan.startTime)
    }

    @Test
    fun journal_shouldRejectTargetsThatDoNotCoverEveryBakingPan() {
        val firstPan = bakingPan(position = 17, startTime = 1_000L)
        val secondPan = bakingPan(position = 18, startTime = 1_500L)
        val config = pausedConfig(pauseStartedAtMs = 2_000L)
        val raw = schema2JournalJson(
            targetStartTimes = mapOf(17 to 1_600L),
            affectedPositions = listOf(17),
            pauseStartedAtMs = 2_000L,
            coveredUntilMs = 2_600L
        )

        val result = FaultHeatPauseManager.replayRecoveryJournalState(
            raw,
            listOf(firstPan, secondPan),
            config,
            2_600L
        )

        assertFalse(result.applied)
        assertEquals(1_000L, firstPan.startTime)
        assertEquals(1_500L, secondPan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun legacyJournal_shouldAllowEmptyTargetsOnlyWhenThereAreNoBakingPans() {
        val emptyRaw = """{
            "journalId":"legacy-empty","pauseStartedAtMs":2000,"preparedAtMs":2600,
            "pauseDurationMs":600,"targetStartTimes":{},"pendingDiscardPositions":[]
        }""".trimIndent()
        val withBaking = FaultHeatPauseManager.replayRecoveryJournalState(
            emptyRaw,
            listOf(bakingPan(position = 25, startTime = 1_000L)),
            pausedConfig(2_000L),
            2_600L
        )
        val withoutBaking = FaultHeatPauseManager.replayRecoveryJournalState(
            emptyRaw,
            emptyList(),
            pausedConfig(2_000L),
            2_600L
        )

        assertFalse(withBaking.applied)
        assertTrue(withoutBaking.applied)
    }

    @Test
    fun journal_shouldRejectExplicitNullCollectionsAndValuesWithoutThrowing() {
        val config = pausedConfig(2_000L)
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val explicitNullMap = """{
            "schemaVersion":2,"semantics":"ABSOLUTE_START_TIME","journalId":"null-map",
            "pauseStartedAtMs":2000,"preparedAtMs":2600,"coveredUntilMs":2600,
            "affectedPositions":[25],"targetStartTimes":null,"pendingDiscardPositions":[]
        }""".trimIndent()
        val explicitNullList = """{
            "schemaVersion":2,"semantics":"ABSOLUTE_START_TIME","journalId":"null-list",
            "pauseStartedAtMs":2000,"preparedAtMs":2600,"coveredUntilMs":2600,
            "affectedPositions":null,"targetStartTimes":{"25":1600},"pendingDiscardPositions":null
        }""".trimIndent()
        val explicitNullValue = """{
            "schemaVersion":2,"semantics":"ABSOLUTE_START_TIME","journalId":"null-value",
            "pauseStartedAtMs":2000,"preparedAtMs":2600,"coveredUntilMs":2600,
            "affectedPositions":[25],"targetStartTimes":{"25":null},"pendingDiscardPositions":[]
        }""".trimIndent()

        assertFalse(FaultHeatPauseManager.replayRecoveryJournalState(explicitNullMap, listOf(pan), config, 2_600L).applied)
        assertFalse(FaultHeatPauseManager.replayRecoveryJournalState(explicitNullList, listOf(pan), config, 2_600L).applied)
        assertFalse(FaultHeatPauseManager.replayRecoveryJournalState(explicitNullValue, listOf(pan), config, 2_600L).applied)
        assertEquals(1_000L, pan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun schema2Journal_shouldRejectMissingRequiredField() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(2_000L)
        val missingTargets = """{
            "schemaVersion":2,"semantics":"ABSOLUTE_START_TIME","journalId":"missing-targets",
            "pauseStartedAtMs":2000,"preparedAtMs":2600,"coveredUntilMs":2600,
            "pauseDurationMs":600,"affectedPositions":[25],"pendingDiscardPositions":[]
        }""".trimIndent()

        val result = FaultHeatPauseManager.replayRecoveryJournalState(
            missingTargets,
            listOf(pan),
            config,
            2_600L
        )

        assertFalse(result.applied)
        assertNotNull(result.error)
        assertEquals(1_000L, pan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun journal_shouldRejectMalformedJsonWithoutThrowingOrMutatingState() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = pausedConfig(2_000L)

        val result = FaultHeatPauseManager.replayRecoveryJournalState(
            raw = "{not-json",
            pans = listOf(pan),
            config = config,
            nowMs = 2_600L
        )

        assertFalse(result.applied)
        assertNotNull(result.error)
        assertNull(result.journalId)
        assertEquals(1_000L, pan.startTime)
        assertEquals(2_000L, config.faultHeatPauseStartedAtMs)
    }

    @Test
    fun preparation_shouldBlockMalformedExistingJournalWithoutApplyingIt() {
        val pan = bakingPan(position = 25, startTime = 1_000L)

        val result = FaultHeatPauseManager.inspectRecoveryJournalForPreparation(
            raw = "{not-json",
            pans = listOf(pan),
            nowMs = 2_600L
        )

        assertEquals(FaultHeatPauseManager.RecoveryJournalPrepareStatus.BLOCKED, result.status)
        assertNotNull(result.error)
        assertEquals(1_000L, pan.startTime)
    }

    private fun pausedConfig(pauseStartedAtMs: Long): AppConfigBean = AppConfigBean().apply {
        faultHeatPauseActive = 1
        faultHeatPauseStartedAtMs = pauseStartedAtMs
        pendingDiscardPositions.clear()
    }

    private fun schema2JournalJson(
        targetStartTimes: Map<Int, Long>,
        affectedPositions: List<Int>,
        pauseStartedAtMs: Long,
        coveredUntilMs: Long
    ): String = JSON.toJSONString(FaultHeatPauseManager.RecoveryJournal().apply {
        schemaVersion = 2
        semantics = "ABSOLUTE_START_TIME"
        journalId = "schema2"
        this.pauseStartedAtMs = pauseStartedAtMs
        preparedAtMs = coveredUntilMs
        this.coveredUntilMs = coveredUntilMs
        pauseDurationMs = coveredUntilMs - pauseStartedAtMs
        this.affectedPositions = affectedPositions.toMutableList()
        this.targetStartTimes = targetStartTimes.mapValuesTo(linkedMapOf()) { it.value }
        pendingDiscardPositions = mutableListOf()
    })

    private fun bakingPan(position: Int, startTime: Long): KaoPan = KaoPan().apply {
        positionSn = position
        isHasSausage = true
        status = 1
        this.startTime = startTime
        holdingTime = 0L
    }
}
