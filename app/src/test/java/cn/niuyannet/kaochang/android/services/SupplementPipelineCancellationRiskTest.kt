package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineActionContext
import cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPipelineTask
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPipelineCancellationRiskTest {

    @Test
    fun selectRiskAction_shouldMarkIn7CancellationForManualConfirmation() {
        val in7 = context("in7", targetPanNo = 13)

        val selected = KaoChangOperate.selectSupplementPipelineCancellationRiskAction(
            currentAction = in7,
            riskAction = null
        )

        assertEquals(in7, selected)
    }

    @Test
    fun selectRiskAction_shouldNotMarkEarlyCancellationForManualConfirmation() {
        val in5 = context("in5", targetPanNo = 13)

        val selected = KaoChangOperate.selectSupplementPipelineCancellationRiskAction(
            currentAction = in5,
            riskAction = null
        )

        assertNull(selected)
    }

    @Test
    fun selectRiskAction_shouldKeepLatestRiskActionWhenPrefetchOverwritesCurrentAction() {
        val currentPrefetch = context("in1", targetPanNo = 14)
        val previousRisk = context("in7", targetPanNo = 13)

        val selected = KaoChangOperate.selectSupplementPipelineCancellationRiskAction(
            currentAction = currentPrefetch,
            riskAction = previousRisk
        )

        assertEquals(previousRisk, selected)
    }

    @Test
    fun markRiskIfNeeded_shouldWireIn7CancellationToManualConfirmationBatchFailure() {
        val store = MemorySupplementBatchIntentStore()
        val repository = repository(store)
        val intent = repository.begin(listOf(target(13, "A"), target(14, "A")))!!
        val in7 = context("in7", targetPanNo = 13)

        val marked = KaoChangOperate.markSupplementPipelineCancellationRiskIfNeeded(
            batchId = intent.batchId,
            currentAction = in7,
            riskAction = null,
            failureMessage = { action -> "cancel at ${action.actionName}" },
            markFailure = { targetPanNo, actionName, failureMessage ->
                repository.markFailure(
                    batchId = intent.batchId,
                    targetPanNo = targetPanNo,
                    actionName = actionName,
                    failureMessage = failureMessage,
                    requiresManualConfirm = true
                )
            }
        )

        assertTrue(marked)
        val saved = repository.loadActive()!!
        assertTrue(saved.requiresManualConfirm)
        assertEquals(13, saved.failedTargetPanNo)
        assertEquals("in7", saved.failedActionName)
        assertEquals("cancel at in7", saved.failureMessage)
        assertEquals(listOf(13, 14), saved.remainingTargets().map { it.positionSn })
    }

    @Test
    fun markRiskIfNeeded_shouldNotMarkIn5Cancellation() {
        var markFailureCalled = false
        val in5 = context("in5", targetPanNo = 13)

        val marked = KaoChangOperate.markSupplementPipelineCancellationRiskIfNeeded(
            batchId = "batch",
            currentAction = in5,
            riskAction = null,
            failureMessage = { "cancel at ${it.actionName}" },
            markFailure = { _, _, _ -> markFailureCalled = true }
        )

        assertFalse(marked)
        assertFalse(markFailureCalled)
    }

    private fun context(actionName: String, targetPanNo: Int): InActionGroupPipelineActionContext =
        InActionGroupPipelineActionContext(
            task = InActionGroupPipelineTask(listOf(17), targetPanNo),
            sourceBoxNo = 17,
            targetPanNo = targetPanNo,
            actionName = actionName
        )

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
