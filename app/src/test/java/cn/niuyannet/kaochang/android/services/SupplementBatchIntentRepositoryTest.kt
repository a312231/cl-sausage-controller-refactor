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
    fun clear_shouldDropPendingIntentWhenPanIsManuallyReset() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        repository.begin(listOf(target(25, "A"), target(26, "A"), target(27, "A")))

        repository.clear("清空烤盘")

        assertNull(store.intent)
    }

    private fun target(positionSn: Int, tasteCode: String): KaoChangOperate.SupplementTarget =
        KaoChangOperate.SupplementTarget(
            KaoPan().apply {
                this.positionSn = positionSn
                this.taste = Taste(tasteCode)
            },
            tasteCode
        )

    private fun repository(store: SupplementBatchIntentStore): SupplementBatchIntentRepository =
        SupplementBatchIntentRepository(
            store = store,
            clock = { 1000L },
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
