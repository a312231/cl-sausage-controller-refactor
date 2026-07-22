package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowInitializationMaturityCloseoutPolicyTest {

    @Test
    fun evaluate_shouldCloseRemainingSixToNineAfterAllPlacedPansReachTheirDynamicBakingTimes() {
        listOf(15L, 20L, 30L, 50L).forEach { minutes ->
            val bakingTime = minutes * 60_000L
            val nowMs = bakingTime + 10_000L
            val result = LowInitializationMaturityCloseoutPolicy.evaluate(
                intent = lowInitializationIntent(),
                pans = pans(*(1..5).map { bakingPan(it, 10_000L, bakingTime) }.toTypedArray()),
                config = lowModeConfig(),
                nowMs = nowMs,
                recoveryBlocked = true,
                unfinishedSupplementPlacementEvidence = false
            )

            assertEquals((1..5).toList(), result?.maturedPositions)
            assertEquals((6..9).toList(), result?.remainingPositions)
        }
    }

    @Test
    fun evaluate_shouldWaitUntilLatestPlacedPanReachesItsOwnBakingTime() {
        val result = LowInitializationMaturityCloseoutPolicy.evaluate(
            intent = lowInitializationIntent(),
            pans = pans(
                *(1..4).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray(),
                bakingPan(5, 7_000L, 8_000L)
            ),
            config = lowModeConfig(),
            nowMs = 10_000L,
            recoveryBlocked = true,
            unfinishedSupplementPlacementEvidence = false
        )

        assertNull(result)
    }

    @Test
    fun evaluate_shouldFailClosedForFaultPausePendingDiscardOrUnfinishedPlacement() {
        val pans = pans(*(1..5).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray())

        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), pans, lowModeConfig().apply {
                faultHeatPauseActive = 1
                faultHeatPauseStartedAtMs = 5_000L
            }, 10_000L, recoveryBlocked = true, unfinishedSupplementPlacementEvidence = false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), pans, lowModeConfig().apply { pendingDiscardPositions.add(3) },
            10_000L, recoveryBlocked = true, unfinishedSupplementPlacementEvidence = false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), pans, lowModeConfig(),
            10_000L, recoveryBlocked = true, unfinishedSupplementPlacementEvidence = true
        ))
    }

    @Test
    fun evaluate_shouldRejectWrongBatchTypeBatchOriginOrUnblockedScheduling() {
        val pans = pans(*(1..5).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray())
        val wrongType = lowInitializationIntent().copy(type = SupplementBatchType.HIGH_INITIALIZATION_1_21)
        val wrongOrigin = lowInitializationIntent().also { it.targets[0].originBatchId = "other-batch" }

        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            wrongType, pans, lowModeConfig(), 10_000L, true, false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            wrongOrigin, pans, lowModeConfig(), 10_000L, true, false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), pans, lowModeConfig(), 10_000L, false, false
        ))
    }

    @Test
    fun evaluate_shouldAcceptExactLegacyLowInitializationOriginsAfterMigration() {
        val intent = lowInitializationIntent().apply {
            createdAtMs = 123L
            targets.forEach { target ->
                target.originBatchId = "legacy:123:LOW_INITIALIZATION_1_9:${target.positionSn}"
            }
        }

        val result = LowInitializationMaturityCloseoutPolicy.evaluate(
            intent = intent,
            pans = pans(*(1..5).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray()),
            config = lowModeConfig(),
            nowMs = 10_000L,
            recoveryBlocked = true,
            unfinishedSupplementPlacementEvidence = false
        )

        assertEquals((1..5).toList(), result?.maturedPositions)
        assertTrue(result?.requiresPanSettlement == true)
    }

    @Test
    fun evaluate_shouldResumeCloseoutWhenPansAreAlreadyKeepWarmButIntentWasNotCleared() {
        val intent = lowInitializationIntent().apply {
            maturityCloseoutPreparedAtMs = 8_500L
            maturityCloseoutMaturedPositions = (1..5).toMutableList()
            maturityCloseoutRemainingPositions = (6..9).toMutableList()
        }
        val result = LowInitializationMaturityCloseoutPolicy.evaluate(
            intent = intent,
            pans = pans(*(1..5).map { keepWarmPan(it, 9_000L) }.toTypedArray()),
            config = lowModeConfig(),
            nowMs = 10_000L,
            recoveryBlocked = true,
            unfinishedSupplementPlacementEvidence = false
        )

        assertEquals((1..5).toList(), result?.maturedPositions)
        assertTrue(result?.requiresPanSettlement == false)
    }

    @Test
    fun evaluate_shouldRejectKeepWarmPansWithoutMatchingDurableCloseoutMarker() {
        val pans = pans(*(1..5).map { keepWarmPan(it, 9_000L) }.toTypedArray())
        val missingMarker = lowInitializationIntent()
        val mismatchedMarker = lowInitializationIntent().apply {
            maturityCloseoutPreparedAtMs = 8_500L
            maturityCloseoutMaturedPositions = (1..5).toMutableList()
            maturityCloseoutRemainingPositions = mutableListOf(7, 8, 9)
        }

        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            missingMarker, pans, lowModeConfig(), 10_000L, true, false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            mismatchedMarker, pans, lowModeConfig(), 10_000L, true, false
        ))
    }

    @Test
    fun evaluate_shouldTrustExactPreparedMaturityAcrossRestartEvenIfConfiguredTimeIncreases() {
        val intent = lowInitializationIntent().apply {
            maturityCloseoutPreparedAtMs = 8_500L
            maturityCloseoutMaturedPositions = (1..5).toMutableList()
            maturityCloseoutRemainingPositions = (6..9).toMutableList()
        }
        val result = LowInitializationMaturityCloseoutPolicy.evaluate(
            intent = intent,
            pans = pans(*(1..5).map { bakingPan(it, 1_000L, 50_000L) }.toTypedArray()),
            config = lowModeConfig(),
            nowMs = 10_000L,
            recoveryBlocked = true,
            unfinishedSupplementPlacementEvidence = false
        )

        assertEquals((1..5).toList(), result?.maturedPositions)
        assertTrue(result?.requiresPanSettlement == true)
    }

    @Test
    fun evaluate_shouldRejectRemainingTargetThatIsOccupiedOrReserved() {
        val occupied = pans(
            *(1..5).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray(),
            emptyPan(6).apply { isHasSausage = true }
        )
        val reserved = pans(
            *(1..5).map { bakingPan(it, 1_000L, 8_000L) }.toTypedArray(),
            emptyPan(6).apply { isHasGrilling = true }
        )

        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), occupied, lowModeConfig(), 10_000L, true, false
        ))
        assertNull(LowInitializationMaturityCloseoutPolicy.evaluate(
            lowInitializationIntent(), reserved, lowModeConfig(), 10_000L, true, false
        ))
    }

    private fun lowInitializationIntent(): SupplementBatchIntent {
        val batchId = "low-init"
        return SupplementBatchIntent(
            batchId = batchId,
            type = SupplementBatchType.LOW_INITIALIZATION_1_9,
            targets = (1..9).map { position ->
                SupplementBatchIntentTarget(
                    positionSn = position,
                    tasteCode = "A",
                    completed = position in 1..5,
                    originBatchId = batchId,
                    originBatchType = SupplementBatchType.LOW_INITIALIZATION_1_9
                )
            }.toMutableList()
        )
    }

    private fun lowModeConfig(): AppConfigBean = AppConfigBean().apply {
        modeType = 1
        transitionMode = 0
        highConcurrencyResidualDrain = 0
        pendingDiscardPositions.clear()
    }

    private fun pans(vararg overrides: KaoPan): List<KaoPan> {
        val byPosition = overrides.associateBy { it.positionSn }
        return (1..33).map { byPosition[it] ?: emptyPan(it) }
    }

    private fun emptyPan(position: Int): KaoPan = KaoPan().apply {
        positionSn = position
        isHasSausage = false
        isHasGrilling = false
        status = 0
    }

    private fun bakingPan(position: Int, startAtMs: Long, bakingTimeMs: Long): KaoPan = KaoPan().apply {
        positionSn = position
        isHasSausage = true
        status = 1
        startTime = startAtMs
        holdingTime = 0L
        bakingTime = bakingTimeMs
    }

    private fun keepWarmPan(position: Int, holdingAtMs: Long): KaoPan = KaoPan().apply {
        positionSn = position
        isHasSausage = true
        status = KaoPan.STATUS_SELLABLE_KEEP_WARM
        startTime = 0L
        holdingTime = holdingAtMs
        bakingTime = 8_000L
    }
}
