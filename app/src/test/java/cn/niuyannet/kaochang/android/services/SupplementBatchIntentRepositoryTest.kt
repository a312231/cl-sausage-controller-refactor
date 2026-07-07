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
        assertNull(store.intent)
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
    fun markCompleted_shouldNotRepeatFinishedPanAndClearWhenBatchDone() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "B"), target(14, "B"), target(15, "B")))!!

        repository.markCompleted(intent.batchId, 13)

        assertEquals(listOf(14, 15), store.intent?.remainingTargets()?.map { it.positionSn })

        repository.markCompleted(intent.batchId, 14)
        repository.markCompleted(intent.batchId, 15)

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
