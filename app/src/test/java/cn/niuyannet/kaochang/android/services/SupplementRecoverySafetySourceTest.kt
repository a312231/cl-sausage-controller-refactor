package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupplementRecoverySafetySourceTest {
    @Test
    fun successfulSupplementPaths_shouldRecoverTheActualSourceBoxEpisode() {
        val source = operateSource()
        val pipelineStart = source.indexOf("fun applyCompletedPipelineTask(")
        val pipelineEnd = source.indexOf("var actionGroupCompleted", pipelineStart)
        val pipelineBlock = source.substring(pipelineStart, pipelineEnd)
        assertTrue(
            "流水线完整落盘成功后必须按实际来源箱恢复对应供给降级事件",
            pipelineBlock.contains("markSupplyBoxRecoveredAfterSuccessfulTake(") &&
                pipelineBlock.contains("completedTask.sourceBoxNo")
        )

        val singleSuccessStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val singleSuccessEnd = source.indexOf("/**\n     * 烤盘间搬移", singleSuccessStart)
        val singleSuccessBlock = source.substring(singleSuccessStart, singleSuccessEnd)
        assertTrue(
            "单根完整补肠成功后必须恢复同一来源箱事件",
            singleSuccessBlock.contains("markSupplyBoxRecoveredAfterSuccessfulTake(") &&
                singleSuccessBlock.contains("sourceBoxNo = completionSourceBox.positionSn")
        )
    }

    @Test
    fun episodeFailureCalls_shouldCarryCoordinatesBatchAndRawResult() {
        val source = operateSource()
        val pipelineFailureStart = source.indexOf("private suspend fun handlePipelineSupplementFailure(")
        val pipelineFailureEnd = source.indexOf("private fun describeAtomicActionResult", pipelineFailureStart)
        val pipelineFailure = source.substring(pipelineFailureStart, pipelineFailureEnd)
        assertTrue(pipelineFailure.contains("LowerPlatformEpisodeDetails("))
        assertTrue(pipelineFailure.contains("sourceBoxNo = sourceBoxNo"))
        assertTrue(pipelineFailure.contains("targetPanNo = targetPanNo"))
        assertTrue(pipelineFailure.contains("tasteCode = plannedMove?.tasteCode"))
        assertTrue(pipelineFailure.contains("batchId = batchId"))
        assertTrue(pipelineFailure.contains("batchType = batchType"))
        assertTrue(pipelineFailure.contains("rawResult = describeAtomicActionResult(resolvedAtomicResult)"))

        val singleStart = source.indexOf("private suspend fun moveSausageToKaoPanDetailed(")
        val singleEnd = source.indexOf("/**\n     * 烤盘间搬移", singleStart)
        val singleBlock = source.substring(singleStart, singleEnd)
        assertTrue(singleBlock.contains("LowerPlatformEpisodeDetails("))
        assertTrue(singleBlock.contains("sourceBoxNo = kaoPanBox.positionSn"))
        assertTrue(singleBlock.contains("targetPanNo = kaoPan.positionSn"))
        assertTrue(singleBlock.contains("rawResult = actionGroupResultDesc(actionGroupResult)"))
    }

    @Test
    fun inSessionCheckpoint_shouldBeUpdatedBeforeEachPipelineCommand() {
        val source = operateSource()
        val pipelineStart = source.indexOf("InActionGroupPrefetchPipelineRunner(")
        val pipelineEnd = source.indexOf(".run(plannedMoves.map { it.task })", pipelineStart)
        val pipelineBlock = source.substring(pipelineStart, pipelineEnd)
        assertTrue(pipelineBlock.contains("onCurrentActionChanged = { context ->"))
        assertTrue(pipelineBlock.contains("actionGroupSessionStore.save("))
        assertTrue(pipelineBlock.contains("currentAction = context.actionName"))
        assertTrue(pipelineBlock.contains("checkpointVersion ="))
    }

    @Test
    fun recoveredInSuccess_shouldCompleteOnlyPersistedBatchTarget() {
        val source = operateSource()
        val start = source.indexOf("private fun handleRecoveredSuccess(")
        val end = source.indexOf("private fun handleRecoveredDiscarded", start)
        val block = source.substring(start, end)
        assertTrue(block.contains("session.batchId"))
        assertTrue(block.contains("supplementBatchIntentRepository.markCompleted("))
        assertTrue(block.contains("session.targetNo"))
    }

    @Test
    fun mainStartup_shouldRecoverPowerLossBeforeRestoringOrStartingAlgorithm() {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt")
        ).first { it.exists() }
        val source = sourceFile.readText().replace("\r\n", "\n")
        val start = source.indexOf("private fun startRuntimeAfterConfigLoaded(")
        val end = source.indexOf("override fun onResume", start)
        val block = source.substring(start, end)
        val recover = block.indexOf("PowerLossRecoveryGuard.recoverBeforeRuntimeStart(source)")
        val restore = block.indexOf("KaoChangAlgorithm.restoreRoastAlgorithmState()")
        val run = block.indexOf("KaoChangAlgorithm.runServer()")
        assertTrue(recover >= 0 && restore > recover && run > restore)
    }

    private fun operateSource(): String {
        val sourceFile = listOf(
            File("src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt"),
            File("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
        ).first { it.exists() }
        return sourceFile.readText().replace("\r\n", "\n")
    }
}
