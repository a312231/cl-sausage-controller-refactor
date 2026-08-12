package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementBatchIntentRepositoryTest {

    @Test
    fun begin_shouldPersistLowHeat13To15IntentBeforeAnyPanIsFilled() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val intent = repository.begin(
            listOf(
                target(13, "B"),
                target(14, "B"),
                target(15, "B")
            )
        )

        assertEquals(SupplementBatchType.LOW_HEAT_13_15, intent?.type)
        assertEquals(listOf(13, 14, 15), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(false, false, false), store.intent?.targets?.map { it.completed })
    }

    @Test
    fun begin_shouldPersistLowHeatReturnPositionsForUiDisplay() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val intent = repository.begin(
            listOf(
                target(13, "A", returnPositionSn = 1),
                target(14, "A", returnPositionSn = 2),
                target(15, "B", returnPositionSn = 6)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )

        assertEquals(SupplementBatchType.LOW_HEAT_13_15, intent?.type)
        assertEquals(listOf(13, 14, 15), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(1, 2, 6), store.intent?.targets?.map { it.returnPositionSn })
    }

    @Test
    fun begin_withoutOverride_shouldKeepLegacyInferenceForFullLowInitialization() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val intent = repository.begin((1..9).map { target(it, "B") })

        assertEquals(SupplementBatchType.LOW_INITIALIZATION_1_9, intent?.type)
        assertEquals((1..9).toList(), store.intent?.targets?.map { it.positionSn })
    }

    @Test
    fun begin_withLowInitializationOverride_shouldPersistPartialLowInitializationAsLowType() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val intent = repository.begin(
            (1..5).map { target(it, "A") },
            typeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )

        assertEquals(SupplementBatchType.LOW_INITIALIZATION_1_9, intent?.type)
        assertEquals((1..5).toList(), store.intent?.targets?.map { it.positionSn })
    }

    @Test
    fun begin_withHighInitializationOverride_shouldPersistFilteredOneToNineAsHighType() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val intent = repository.begin(
            (1..9).map { target(it, "B") },
            typeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21
        )

        assertEquals(SupplementBatchType.HIGH_INITIALIZATION_1_21, intent?.type)
        assertEquals((1..9).toList(), store.intent?.targets?.map { it.positionSn })
    }

    @Test
    fun markFailure_shouldKeepAllTargetsRemainingAndRequireManualConfirmation() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B"), target(15, "B")))!!

        repository.markFailure(
            batchId = intent.batchId,
            targetPanNo = 13,
            actionName = "in3",
            failureMessage = "0x00F1（in3 的 Y 复位失败）",
            requiresManualConfirm = true
        )

        val saved = store.intent!!
        assertTrue(saved.requiresManualConfirm)
        assertEquals("in3", saved.failedActionName)
        assertEquals(13, saved.failedTargetPanNo)
        assertEquals(1000L, saved.failureAtMs)
        assertEquals(listOf(13, 14, 15), saved.remainingTargets().map { it.positionSn })
    }

    @Test
    fun markFailure_shouldRefreshFailureAtForEachManualConfirmFailure() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(target(13, "A"), target(14, "A"), target(15, "A")),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!

        repository.markFailure(intent.batchId, 13, "in3", "第一次故障", true)
        assertEquals(1000L, store.load()!!.failureAtMs)

        now = 2000L
        repository.markFailure(intent.batchId, 14, "in4", "第二次故障", true)

        assertEquals(2000L, store.load()!!.failureAtMs)
        assertEquals("in4", store.load()!!.failedActionName)
    }

    @Test
    fun markCompletedFromPhysicalReconciliation_shouldPreserveUnrelatedManualFailure() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(
                target(25, "A", returnPositionSn = 1),
                target(26, "A", returnPositionSn = 2)
            ),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markFailure(intent.batchId, 26, "in3", "26号盘仍需人工确认", true)

        repository.markCompletedFromPhysicalReconciliation(intent.batchId, 25)

        val saved = store.load()!!
        assertTrue(saved.targets.first { it.positionSn == 25 }.completed)
        assertEquals(listOf(26), saved.remainingTargets().map { it.positionSn })
        assertTrue(saved.requiresManualConfirm)
        assertEquals(26, saved.failedTargetPanNo)
        assertEquals("in3", saved.failedActionName)
        assertEquals("26号盘仍需人工确认", saved.failureMessage)
        assertEquals(1000L, saved.failureAtMs)
    }

    @Test
    fun completedInitializationFromPhysicalReconciliation_shouldRemainActiveUntilManualConfirmation() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(target(1, "A")),
            typeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )!!
        repository.markFailure(intent.batchId, 1, "in3", "最后一根仍需人工确认", true)

        repository.markCompletedFromPhysicalReconciliation(intent.batchId, 1)

        val active = repository.loadActive()!!
        assertTrue(active.isComplete())
        assertTrue(active.requiresManualConfirm)
        assertEquals(1, active.failedTargetPanNo)
        assertEquals("in3", active.failedActionName)
        assertEquals("最后一根仍需人工确认", active.failureMessage)

        repository.confirmManualSafety("人工确认初始化现场安全")
        assertNull(repository.loadActive())
    }

    @Test
    fun confirmManualSafety_shouldCancelUnfinishedHeatTargetsAfterTenMinuteFaultTimeout() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(13, "A", returnPositionSn = 1),
                target(14, "A", returnPositionSn = 2),
                target(15, "A", returnPositionSn = 3)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!

        repository.markCompleted(intent.batchId, 13)
        repository.markCompleted(intent.batchId, 14)
        repository.markFailure(intent.batchId, 15, "in3", "in3 的 Y 复位失败", true)
        now = 1000L + 10 * 60 * 1000L + 1L

        val confirmed = repository.confirmManualSafety("超10分钟确认")!!

        assertFalse(confirmed.requiresManualConfirm)
        assertEquals(listOf<Int>(), confirmed.remainingTargets().map { it.positionSn })
        assertFalse(confirmed.targets.first { it.positionSn == 13 }.cancelledByFaultTimeout)
        assertFalse(confirmed.targets.first { it.positionSn == 14 }.cancelledByFaultTimeout)
        assertTrue(confirmed.targets.first { it.positionSn == 15 }.cancelledByFaultTimeout)
        assertEquals("故障超过10分钟，取消未完成补热目标", confirmed.targets.first { it.positionSn == 15 }.cancelReason)
    }

    @Test
    fun confirmManualSafety_shouldKeepUnfinishedHeatTargetsWithinTenMinuteFaultWindow() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(target(25, "A", returnPositionSn = 1), target(26, "A", returnPositionSn = 2)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!

        repository.markCompleted(intent.batchId, 25)
        repository.markFailure(intent.batchId, 26, "in5", "in5 抛肠失败", true)
        now = 1000L + 10 * 60 * 1000L

        val confirmed = repository.confirmManualSafety("10分钟内确认")!!

        assertFalse(confirmed.requiresManualConfirm)
        assertEquals(listOf(26), confirmed.remainingTargets().map { it.positionSn })
        assertFalse(confirmed.targets.first { it.positionSn == 26 }.cancelledByFaultTimeout)
    }

    @Test
    fun confirmManualSafetyPreservingRemainingTargets_shouldKeepHeatTargetsAfterTenMinuteFaultTimeout() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(13, "A", returnPositionSn = 1),
                target(14, "A", returnPositionSn = 2),
                target(15, "A", returnPositionSn = 3)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!
        repository.markCompleted(intent.batchId, 13)
        repository.markFailure(intent.batchId, 14, "in1", "双 lane in1 下平台故障", true)
        repository.markManualConfirmStrategyPrefetchPreserveRemaining(intent.batchId, "单测预写策略")
        now = 1000L + 20 * 60 * 1000L

        val confirmed = repository.confirmManualSafetyPreservingRemainingTargets("双 lane 人工确认")!!

        assertFalse(confirmed.requiresManualConfirm)
        assertEquals("", confirmed.manualConfirmStrategy)
        assertEquals(listOf(14, 15), confirmed.remainingTargets().map { it.positionSn })
        assertTrue(confirmed.targets.none { it.cancelledByFaultTimeout })
        assertEquals(confirmed.batchId, store.intent!!.batchId)
    }

    @Test
    fun confirmManualSafety_shouldUsePrefetchPreserveStrategyEvenAfterSessionEvidenceWasCleared() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(25, "B", returnPositionSn = 7),
                target(26, "B", returnPositionSn = 8),
                target(27, "B", returnPositionSn = 9)
            ),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(intent.batchId, 25)
        repository.markFailure(intent.batchId, 26, "in1", "双 lane 已清 session 后重试", true)
        repository.markManualConfirmStrategyPrefetchPreserveRemaining(intent.batchId, "单测预写策略")
        now = 1000L + 20 * 60 * 1000L

        val confirmed = repository.confirmManualSafety("session 已清后重试")!!

        assertFalse(confirmed.requiresManualConfirm)
        assertEquals(listOf(26, 27), confirmed.remainingTargets().map { it.positionSn })
        assertTrue(confirmed.targets.none { it.cancelledByFaultTimeout })
    }

    @Test
    fun shouldDelayHeatReturnForBatchCompleteness_shouldOnlyDelayWithinFaultWindow() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(13, "A", returnPositionSn = 1),
                target(14, "A", returnPositionSn = 2),
                target(15, "A", returnPositionSn = 3)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!

        repository.markCompleted(intent.batchId, 13)
        repository.markCompleted(intent.batchId, 14)
        repository.markFailure(intent.batchId, 15, "in3", "in3 的 Y 复位失败", true)

        now = 1000L + 10 * 60 * 1000L
        assertTrue(repository.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.LOW_HEAT_13_15)))
        assertFalse(repository.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.LOW_HEAT_16_18)))

        now = 1000L + 10 * 60 * 1000L + 1L
        assertFalse(repository.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.LOW_HEAT_13_15)))
    }

    @Test
    fun shouldDelayHeatReturnForBatchCompleteness_shouldStopDelayingAfterSafetyConfirmedWindowExpires() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(25, "A", returnPositionSn = 1),
                target(26, "A", returnPositionSn = 2),
                target(27, "A", returnPositionSn = 3)
            ),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!

        repository.markCompleted(intent.batchId, 25)
        repository.markCompleted(intent.batchId, 26)
        repository.markFailure(intent.batchId, 27, "in3", "in3 的 Y 复位失败", true)
        now = 1000L + 5 * 60 * 1000L
        repository.confirmManualSafety("5分钟内确认")
        now = 1000L + 20 * 60 * 1000L

        assertFalse(repository.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.HIGH_HEAT_25_33)))
        assertTrue(store.intent!!.isComplete())
        assertEquals(listOf(25, 26), store.intent!!.targets.filter { it.completed }.map { it.positionSn })
        assertTrue(store.intent!!.targets.first { it.positionSn == 27 }.cancelledByFaultTimeout)
    }

    @Test
    fun shouldDelayHeatReturnForBatchCompleteness_shouldReportCancelledTargetsWhenWindowExpires() {
        val store = MemorySupplementBatchIntentStore()
        var now = 1000L
        val repository = repository(store) { now }
        val intent = repository.begin(
            listOf(
                target(25, "A", returnPositionSn = 1),
                target(26, "A", returnPositionSn = 2)
            ),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(intent.batchId, 25)
        repository.markFailure(intent.batchId, 26, "in3", "in3 的 Y 复位失败", true)
        now = 1000L + 10 * 60 * 1000L + 1L
        var cancelledIntent: SupplementBatchIntent? = null

        assertFalse(
            repository.shouldDelayHeatReturnForBatchCompleteness(
                setOf(SupplementBatchType.HIGH_HEAT_25_33)
            ) {
                cancelledIntent = it
            }
        )

        assertEquals(listOf(26), cancelledIntent!!.targets.filter { it.cancelledByFaultTimeout }.map { it.positionSn })
    }

    @Test
    fun shouldDelayHeatReturnForBatchCompleteness_shouldIgnoreInitializationBatch() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store) { 1000L }
        val intent = repository.begin(
            listOf(target(1, "A"), target(2, "A")),
            typeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )!!

        repository.markCompleted(intent.batchId, 1)
        repository.markFailure(intent.batchId, 2, "in3", "in3 的 Y 复位失败", true)

        assertFalse(repository.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.LOW_INITIALIZATION_1_9)))
    }

    @Test
    fun begin_shouldRejectNewBatchWhenActiveBatchRequiresManualConfirmation() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B"), target(15, "B")))!!

        repository.markFailure(
            batchId = intent.batchId,
            targetPanNo = 13,
            actionName = "in7",
            failureMessage = "in7 取消，可能已落盘",
            requiresManualConfirm = true
        )

        assertNull(repository.begin(listOf(target(16, "B"))))

        val saved = store.intent!!
        assertEquals(intent.batchId, saved.batchId)
        assertTrue(saved.requiresManualConfirm)
        assertEquals(listOf(13, 14, 15), saved.remainingTargets().map { it.positionSn })
    }

    @Test
    fun confirmManualSafety_shouldAllowResumeWithoutDroppingRemainingTargets() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B"), target(15, "B")))!!
        repository.markFailure(intent.batchId, 13, "in3", "0x00F1（in3 的 Y 复位失败）", true)

        val confirmed = repository.confirmManualSafety("单测确认")!!

        assertFalse(confirmed.requiresManualConfirm)
        assertEquals(listOf(13, 14, 15), confirmed.remainingTargets().map { it.positionSn })
    }

    @Test
    fun markCompleted_shouldKeepHeatBatchIntentAfterAllTargetsFilledUntilReturnClearsIt() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(
                target(13, "B", returnPositionSn = 1),
                target(14, "B", returnPositionSn = 2),
                target(15, "B", returnPositionSn = 3)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!

        repository.markCompleted(intent.batchId, 13)

        assertEquals(listOf(14, 15), store.intent?.remainingTargets()?.map { it.positionSn })

        repository.markCompleted(intent.batchId, 14)
        repository.markCompleted(intent.batchId, 15)

        assertEquals(listOf(13, 14, 15), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(1, 2, 3), store.intent?.targets?.map { it.returnPositionSn })
        assertTrue(store.intent!!.isComplete())
    }

    @Test
    fun clearCompletedHeatReturnIfSourceCleared_shouldClearOnlyReturnedSourcesThatAreEmpty() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(
                target(18, "A", returnPositionSn = 3),
                target(17, "A", returnPositionSn = 2)
            ),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(intent.batchId, 17)
        repository.markCompleted(intent.batchId, 18)

        assertTrue(
            repository.clearCompletedHeatReturnIfSourceCleared(
                types = setOf(SupplementBatchType.LOW_HEAT_16_18),
                sourcePositionsWithSausage = setOf(18),
                reason = "18仍有肠"
            )
        )
        assertEquals(listOf(18), store.intent?.targets?.map { it.positionSn })

        assertTrue(
            repository.clearCompletedHeatReturnIfSourceCleared(
                types = setOf(SupplementBatchType.LOW_HEAT_16_18),
                sourcePositionsWithSausage = emptySet(),
                reason = "源盘已空"
            )
        )
        assertNull(store.intent)
    }

    @Test
    fun markCompleted_shouldClearInitializationBatchWhenBatchDone() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(target(1, "B"), target(2, "B")),
            typeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )!!

        repository.markCompleted(intent.batchId, 1)
        repository.markCompleted(intent.batchId, 2)

        assertNull(store.intent)
    }

    @Test
    fun begin_shouldAllowNewBatchWhenOnlyRetainedHeatReturnBindingExists() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val lowIntent = repository.begin(
            listOf(target(18, "A", returnPositionSn = 3)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(lowIntent.batchId, 18)

        val highIntent = repository.begin(
            listOf(target(25, "A", returnPositionSn = 10), target(26, "A", returnPositionSn = 11)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )

        assertEquals(SupplementBatchType.HIGH_HEAT_25_33, highIntent?.type)
        assertEquals(listOf(18, 25, 26), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(true, false, false), store.intent?.targets?.map { it.completed })
        assertEquals(listOf(3, 10, 11), store.intent?.targets?.map { it.returnPositionSn })
        assertEquals(
            listOf(SupplementBatchType.LOW_HEAT_16_18, SupplementBatchType.HIGH_HEAT_25_33, SupplementBatchType.HIGH_HEAT_25_33),
            store.intent?.targets?.map { it.originBatchType }
        )
        assertEquals(
            lowIntent.batchId,
            store.intent?.targets?.first { it.positionSn == 18 }?.originBatchId
        )
        assertEquals(
            highIntent?.batchId,
            store.intent?.targets?.first { it.positionSn == 25 }?.originBatchId
        )
    }

    @Test
    fun markReturned_shouldOnlyRemoveMatchingOriginBinding() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val low = repository.begin(
            listOf(target(18, "A", returnPositionSn = 1)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(low.batchId, 18)
        val high = repository.begin(
            listOf(target(25, "A", returnPositionSn = 1)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(high.batchId, 25)

        assertTrue(
            repository.markReturned(
                originBatchId = low.batchId,
                originBatchType = SupplementBatchType.LOW_HEAT_16_18,
                sourcePositionSn = 18,
                returnPositionSn = 1
            )
        )
        assertEquals(listOf(25), store.intent?.targets?.map { it.positionSn })
        assertFalse(
            repository.markReturned(
                originBatchId = low.batchId,
                originBatchType = SupplementBatchType.LOW_HEAT_16_18,
                sourcePositionSn = 25,
                returnPositionSn = 1
            )
        )
    }

    @Test
    fun reopenSupplyDegradedTarget_shouldPreserveOriginAndReturnBinding() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(
                target(25, "A", returnPositionSn = 3),
                target(26, "A", returnPositionSn = 4)
            ),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markSkippedBySupplyDegrade(intent.batchId, 25, "库存暂不足")

        assertTrue(repository.reopenSupplyDegradedTarget(intent.batchId, 25, "人工补货完成"))
        val target = store.intent!!.targets.first { it.positionSn == 25 }
        assertFalse(target.skippedBySupplyDegrade)
        assertEquals(intent.batchId, target.originBatchId)
        assertEquals(SupplementBatchType.HIGH_HEAT_25_33, target.originBatchType)
        assertEquals(3, target.returnPositionSn)
    }

    @Test
    fun clearPendingTargetsRetainingCompletedHeatReturnBindings_shouldKeepRetainedBinding() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val lowIntent = repository.begin(
            listOf(target(18, "A", returnPositionSn = 3)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(lowIntent.batchId, 18)
        repository.begin(
            listOf(target(25, "A", returnPositionSn = 10), target(26, "A", returnPositionSn = 11)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )

        repository.clearPendingTargetsRetainingCompletedHeatReturnBindings("高转低过渡")

        assertEquals(listOf(18), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(3), store.intent?.targets?.map { it.returnPositionSn })
        assertTrue(store.intent!!.targets.single().completed)
    }

    @Test
    fun clearPendingTargetsRetainingCompletedHeatReturnBindings_shouldKeepRetainedBindingsAfterRemainingTargetCompleted() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val lowIntent = repository.begin(
            listOf(target(18, "A", returnPositionSn = 3)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(lowIntent.batchId, 18)
        val highIntent = repository.begin(
            listOf(target(25, "A", returnPositionSn = 10)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(highIntent.batchId, 25)

        repository.clearPendingTargetsRetainingCompletedHeatReturnBindings("未完成批次已无剩余目标")

        assertEquals(listOf(18, 25), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(3, 10), store.intent?.targets?.map { it.returnPositionSn })
        assertTrue(store.intent!!.targets.all { it.completed })
    }

    @Test
    fun rebindCompletedHeatTargets_shouldPersistNewOriginAndReturnBindings() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val bindings = listOf(
            ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33),
            ManualSupplementReturnBinding(26, "A", 5, SupplementBatchType.HIGH_HEAT_25_33)
        )

        val saved = repository.rebindCompletedHeatTargets(bindings, "运维确认按当前空位回搬")

        assertEquals(SupplementBatchType.UNKNOWN, saved?.type)
        assertEquals(listOf(25, 26), saved?.targets?.map { it.positionSn })
        assertEquals(listOf(2, 5), saved?.targets?.map { it.returnPositionSn })
        assertEquals(listOf(true, true), saved?.targets?.map { it.completed })
        assertTrue(saved!!.targets.all { it.originBatchId?.startsWith("manual-rebind:") == true })
        assertEquals(
            listOf(SupplementBatchType.HIGH_HEAT_25_33, SupplementBatchType.HIGH_HEAT_25_33),
            saved.targets.map { it.originBatchType }
        )
    }

    @Test
    fun rebindCompletedHeatTargets_shouldRejectDuplicateSourceOrReturnPosition() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        assertNull(
            repository.rebindCompletedHeatTargets(
                listOf(
                    ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33),
                    ManualSupplementReturnBinding(25, "A", 3, SupplementBatchType.HIGH_HEAT_25_33)
                ),
                "重复绑定测试"
            )
        )
    }

    @Test
    fun rebindCompletedHeatTargets_shouldPreserveExistingCompletedBinding() {
        val existing = SupplementBatchIntent(
            batchId = "existing",
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 13,
                    tasteCode = "B",
                    returnPositionSn = 1,
                    completed = true,
                    originBatchId = "existing",
                    originBatchType = SupplementBatchType.LOW_HEAT_13_15
                )
            )
        )
        val store = MemorySupplementBatchIntentStore().apply { save(existing) }
        val repository = repository(store)

        val saved = repository.rebindCompletedHeatTargets(
            listOf(ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33)),
            "补充孤儿绑定"
        )

        assertEquals(listOf(13, 25), saved?.targets?.map { it.positionSn })
        assertEquals(listOf(1, 2), saved?.targets?.map { it.returnPositionSn })
    }

    @Test
    fun rebindCompletedHeatTargets_shouldRejectSourceThatDoesNotMatchOriginBatchType() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)

        val saved = repository.rebindCompletedHeatTargets(
            listOf(
                ManualSupplementReturnBinding(
                    sourcePositionSn = 13,
                    tasteCode = "A",
                    returnPositionSn = 10,
                    originBatchType = SupplementBatchType.HIGH_HEAT_25_33
                )
            ),
            "来源类型不匹配测试"
        )

        assertNull(saved)
        assertNull(store.intent)
    }

    @Test
    fun clearCompletedHeatReturnIfSourceCleared_shouldClearOnlyRequestedSourceType() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val lowIntent = repository.begin(
            listOf(target(18, "A", returnPositionSn = 3)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(lowIntent.batchId, 18)
        val highIntent = repository.begin(
            listOf(target(25, "A", returnPositionSn = 10)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(highIntent.batchId, 25)

        assertTrue(
            repository.clearCompletedHeatReturnIfSourceCleared(
                types = setOf(SupplementBatchType.LOW_HEAT_16_18),
                sourcePositionsWithSausage = emptySet(),
                reason = "低并发旧补热已回搬"
            )
        )

        assertEquals(listOf(25), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(10), store.intent?.targets?.map { it.returnPositionSn })
    }

    @Test
    fun clearCompletedHeatReturnIfSourceCleared_shouldClearOnlySourcesThatAreAlreadyEmpty() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val lowIntent = repository.begin(
            listOf(target(18, "A", returnPositionSn = 3)),
            typeOverride = SupplementBatchType.LOW_HEAT_16_18
        )!!
        repository.markCompleted(lowIntent.batchId, 18)
        val highIntent = repository.begin(
            listOf(target(25, "A", returnPositionSn = 10)),
            typeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )!!
        repository.markCompleted(highIntent.batchId, 25)

        assertTrue(
            repository.clearCompletedHeatReturnIfSourceCleared(
                types = setOf(
                    SupplementBatchType.LOW_HEAT_16_18,
                    SupplementBatchType.HIGH_HEAT_25_33
                ),
                sourcePositionsWithSausage = setOf(25),
                reason = "18已空25仍有肠"
            )
        )

        assertEquals(listOf(25), store.intent?.targets?.map { it.positionSn })
        assertEquals(listOf(10), store.intent?.targets?.map { it.returnPositionSn })
    }

    @Test
    fun clearCompletedHeatReturnIfSourceCleared_shouldDropLowBindingWhenSourceWasReusedAsSellablePan() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            listOf(target(13, "A", returnPositionSn = 1)),
            typeOverride = SupplementBatchType.LOW_HEAT_13_15
        )!!
        repository.markCompleted(intent.batchId, 13)

        assertTrue(
            repository.clearCompletedHeatReturnIfSourceCleared(
                types = setOf(SupplementBatchType.LOW_HEAT_13_15),
                sourcePositionsWithSausage = setOf(13),
                sourcePanStatuses = mapOf(13 to KaoPan.STATUS_SELLABLE_KEEP_WARM),
                reason = "旧低并发源盘已被高并发可售盘复用"
            )
        )
        assertNull(store.intent)
    }

    @Test
    fun markSkippedBySupplyDegrade_shouldRemoveTargetFromRemainingWithoutManualConfirmation() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B"), target(15, "B")))!!

        repository.markSkippedBySupplyDegrade(
            batchId = intent.batchId,
            targetPanNo = 13,
            reason = "in2 / IR_PICK_FAILED"
        )

        val saved = store.intent!!
        assertFalse(saved.requiresManualConfirm)
        assertEquals(listOf(14, 15), saved.remainingTargets().map { it.positionSn })
        assertTrue(saved.targets.first { it.positionSn == 13 }.skippedBySupplyDegrade)
        assertEquals("in2 / IR_PICK_FAILED", saved.targets.first { it.positionSn == 13 }.skipReason)
        assertNull(saved.failedActionName)
        assertNull(saved.failedTargetPanNo)
        assertNull(saved.failureMessage)
    }

    @Test
    fun markSkippedBySupplyDegrade_shouldClearWhenAllTargetsCompletedOrSkipped() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B")))!!

        repository.markCompleted(intent.batchId, 13)
        repository.markSkippedBySupplyDegrade(intent.batchId, 14, "无可用供给")

        assertNull(store.intent)
    }

    @Test
    fun clear_shouldDropPendingIntentWhenPanIsManuallyReset() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        repository.begin(listOf(target(25, "A"), target(26, "A"), target(27, "A")))

        repository.clear("清空烤盘")

        assertNull(store.intent)
    }

    @Test
    fun prepareLowInitializationMaturityCloseout_shouldPersistStageAndClearOnlyMatchingBatch() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(
            (1..9).map { target(it, "A") },
            typeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )!!
        (1..5).forEach { repository.markCompleted(intent.batchId, it) }

        assertTrue(repository.prepareLowInitializationMaturityCloseout(
            intent.batchId,
            (1..5).toList(),
            (6..9).toList()
        ))
        assertTrue(store.intent!!.maturityCloseoutPreparedAtMs > 0L)
        assertEquals((1..5).toList(), store.intent!!.maturityCloseoutMaturedPositions)
        assertEquals((6..9).toList(), store.intent!!.maturityCloseoutRemainingPositions)
        assertFalse(repository.prepareLowInitializationMaturityCloseout(
            intent.batchId,
            (1..5).toList(),
            listOf(7, 8, 9)
        ))
        assertFalse(repository.clearPreparedLowInitializationMaturityCloseout(
            "other-batch", (1..5).toList(), (6..9).toList(), "测试旧批次竞争"
        ))
        assertTrue(store.intent != null)
        assertFalse(repository.clearPreparedLowInitializationMaturityCloseout(
            intent.batchId, (1..4).toList(), (6..9).toList(), "测试成熟集合竞争"
        ))
        assertTrue(repository.clearPreparedLowInitializationMaturityCloseout(
            intent.batchId, (1..5).toList(), (6..9).toList(), "测试残缺初始化收口"
        ))
        assertNull(store.intent)
    }

    @Test
    fun loadActive_shouldDurablyMigrateLegacyTargetWithoutUsingContainerBatchId() {
        val store = MemorySupplementBatchIntentStore().apply {
            intent = SupplementBatchIntent(
                batchId = "new-high-container",
                type = SupplementBatchType.HIGH_HEAT_25_33,
                createdAtMs = 123L,
                targets = mutableListOf(
                    SupplementBatchIntentTarget(
                        positionSn = 18,
                        tasteCode = "A",
                        returnPositionSn = 3,
                        completed = true
                    )
                )
            )
        }
        val repository = repository(store)

        val migrated = repository.loadActive()!!.targets.single()

        assertEquals(SupplementBatchType.LOW_HEAT_16_18, migrated.originBatchType)
        assertEquals("legacy:123:LOW_HEAT_16_18:18", migrated.originBatchId)
        assertFalse(migrated.originBatchId == "new-high-container")
        assertEquals(migrated.originBatchId, store.intent!!.targets.single().originBatchId)
    }

    private fun target(
        positionSn: Int,
        tasteCode: String,
        returnPositionSn: Int? = null
    ): KaoChangOperate.SupplementTarget =
        KaoChangOperate.SupplementTarget(
            KaoPan().apply {
                this.positionSn = positionSn
                this.taste = Taste(tasteCode)
            },
            tasteCode,
            returnPositionSn
        )

    private fun repository(
        store: SupplementBatchIntentStore,
        clock: () -> Long = { 1000L }
    ): SupplementBatchIntentRepository =
        SupplementBatchIntentRepository(
            store = store,
            clock = clock,
            infoLogger = {},
            warnLogger = {}
        )

    private class MemorySupplementBatchIntentStore : SupplementBatchIntentStore {
        var intent: SupplementBatchIntent? = null

        override fun load(): SupplementBatchIntent? = intent

        override fun save(intent: SupplementBatchIntent) {
            this.intent = intent.copy(targets = intent.targets.map { it.copy() }.toMutableList())
        }

        override fun clear() {
            intent = null
        }
    }
}
