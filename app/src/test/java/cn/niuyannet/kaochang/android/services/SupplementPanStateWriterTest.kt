package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileTestBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class SupplementPanStateWriterTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun pinSchedulerProfile() = SchedulerProfileTestBootstrap.pinLegacyA()
    }

    @Before
    fun setUp() {
        LowToHighDemandGate.replaceStateStoreForTest(InMemoryLowToHighDemandStateStoreForTest())
    }

    @After
    fun tearDown() {
        LowToHighDemandGate.resetStateStoreForTest()
    }

    @Test
    fun applyPhysicalCheckpoint_shouldDeductCurrentBoxOnIn2WithoutWritingPan() {
        val plannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentPan = pan(positionSn = 4, tasteCode = "B")
        val plannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentBox = box(positionSn = 21, stock = 6, tasteCode = "B")

        val applied = SupplementPanStateWriter.applySupplementPickedFromBox(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = listOf(currentPan),
            currentBoxes = listOf(currentBox)
        )

        assertTrue(applied)
        assertEquals(6, plannedBox.num)
        assertEquals(5, currentBox.num)
        assertFalse(plannedPan.isHasSausage)
        assertFalse(currentPan.isHasSausage)
        assertEquals(0, currentPan.status)
    }

    @Test
    fun applyPhysicalCheckpoint_shouldPreserveLatchedPickFailureOnOrdinarySuccess() {
        val plannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentPan = pan(positionSn = 4, tasteCode = "B")
        val plannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentBox = box(positionSn = 21, stock = 6, tasteCode = "B").apply {
            cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
        }

        val applied = SupplementPanStateWriter.applySupplementPickedFromBox(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = listOf(currentPan),
            currentBoxes = listOf(currentBox)
        )

        assertTrue(applied)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, currentBox.cmdStatusTake)
    }

    @Test
    fun applyPhysicalCheckpoint_shouldRejectIn2DeductionWhenCurrentBoxStockAlreadyZero() {
        val plannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentPan = pan(positionSn = 4, tasteCode = "B")
        val plannedBox = box(positionSn = 20, stock = 0, tasteCode = "B")
        val currentBox = box(positionSn = 20, stock = 0, tasteCode = "B")

        val applied = SupplementPanStateWriter.applySupplementPickedFromBox(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = listOf(currentPan),
            currentBoxes = listOf(currentBox)
        )

        assertFalse("0 库存下 in2 成功不能被静默当作已扣库存", applied)
        assertEquals(0, currentBox.num)
        assertFalse(currentPan.isHasSausage)
    }

    @Test
    fun applyPhysicalCheckpoint_shouldPlaceCurrentPanOnIn7WithoutDeductingBoxAgain() {
        val plannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentPan = pan(positionSn = 4, tasteCode = "B")
        val plannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentBox = box(positionSn = 21, stock = 5, tasteCode = "B")

        val applied = SupplementPanStateWriter.applySupplementPlacedOnPan(
            plannedPan = plannedPan,
            plannedBox = plannedBox,
            currentPans = listOf(currentPan),
            currentBoxes = listOf(currentBox),
            nowMs = 12_345L,
            clearPendingDiscard = false,
            config = AppConfigBean().apply {
                bakingTime = 40
                discardTime = 6
            },
            tasteBuilder = { sourceBox, _ -> Taste(sourceBox.tasteCode) }
        )

        assertTrue(applied)
        assertFalse(plannedPan.isHasSausage)
        assertTrue(currentPan.isHasSausage)
        assertEquals(1, currentPan.status)
        assertEquals(12_345L, currentPan.startTime)
        assertEquals(5, currentBox.num)
    }

    @Test
    fun pipelineInterleavedPhysicalCheckpoints_shouldKeepEachTargetBoundToItsOwnBoxAndPan() {
        val plannedPanA = pan(positionSn = 13, tasteCode = "A")
        val plannedPanB = pan(positionSn = 14, tasteCode = "B")
        val currentPanA = pan(positionSn = 13, tasteCode = "A")
        val currentPanB = pan(positionSn = 14, tasteCode = "B")
        val plannedBoxA = box(positionSn = 17, stock = 2, tasteCode = "A")
        val plannedBoxB = box(positionSn = 18, stock = 2, tasteCode = "B")
        val currentBoxA = box(positionSn = 17, stock = 2, tasteCode = "A")
        val currentBoxB = box(positionSn = 18, stock = 2, tasteCode = "B")
        val currentPans = listOf(currentPanA, currentPanB)
        val currentBoxes = listOf(currentBoxA, currentBoxB)
        val config = AppConfigBean().apply {
            bakingTime = 40
            discardTime = 6
        }

        assertTrue(
            applySupplementTelemetryForPlannedMove(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 17, panNo = 13),
                plannedPan = plannedPanA,
                plannedBox = plannedBoxA,
                currentPans = currentPans,
                currentBoxes = currentBoxes,
                config = config
            )
        )
        assertTrue(
            applySupplementTelemetryForPlannedMove(
                telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 18, panNo = 14),
                plannedPan = plannedPanB,
                plannedBox = plannedBoxB,
                currentPans = currentPans,
                currentBoxes = currentBoxes,
                config = config
            )
        )
        assertTrue(
            applySupplementTelemetryForPlannedMove(
                telemetry("in7", "SUPPLEMENT_PLACED_ON_PAN", boxNo = 17, panNo = 13),
                plannedPan = plannedPanA,
                plannedBox = plannedBoxA,
                currentPans = currentPans,
                currentBoxes = currentBoxes,
                config = config
            )
        )
        assertTrue(
            applySupplementTelemetryForPlannedMove(
                telemetry("in7", "SUPPLEMENT_PLACED_ON_PAN", boxNo = 18, panNo = 14),
                plannedPan = plannedPanB,
                plannedBox = plannedBoxB,
                currentPans = currentPans,
                currentBoxes = currentBoxes,
                config = config
            )
        )

        assertEquals(1, currentBoxA.num)
        assertEquals(1, currentBoxB.num)
        assertTrue(currentPanA.isHasSausage)
        assertTrue(currentPanB.isHasSausage)
        assertEquals("A", currentPanA.taste?.tasteCode)
        assertEquals("B", currentPanB.taste?.tasteCode)
    }

    @Test
    fun pipelinePhysicalCheckpoint_shouldNotApplyWhenTelemetryBelongsToAnotherTarget() {
        val plannedPanA = pan(positionSn = 13, tasteCode = "A")
        val currentPanA = pan(positionSn = 13, tasteCode = "A")
        val plannedBoxA = box(positionSn = 17, stock = 2, tasteCode = "A")
        val currentBoxA = box(positionSn = 17, stock = 2, tasteCode = "A")

        val applied = applySupplementTelemetryForPlannedMove(
            telemetry("in2", "SUPPLEMENT_PICKED_FROM_BOX", boxNo = 18, panNo = 14),
            plannedPan = plannedPanA,
            plannedBox = plannedBoxA,
            currentPans = listOf(currentPanA),
            currentBoxes = listOf(currentBoxA),
            config = AppConfigBean()
        )

        assertFalse(applied)
        assertEquals(2, currentBoxA.num)
        assertFalse(currentPanA.isHasSausage)
    }

    @Test
    fun applyPhysicalCheckpoint_shouldClearCurrentPanOnOut1() {
        val plannedPan = pan(positionSn = 4, tasteCode = "B").apply {
            isHasSausage = true
            status = 2
            holdingTime = 10_000L
        }
        val currentPan = pan(positionSn = 4, tasteCode = "B").apply {
            isHasSausage = true
            status = 2
            holdingTime = 10_000L
        }

        val applied = SupplementPanStateWriter.applyOutRemovedFromPan(
            plannedPan = plannedPan,
            currentPans = listOf(currentPan)
        )

        assertTrue(applied)
        assertTrue(plannedPan.isHasSausage)
        assertFalse(currentPan.isHasSausage)
        assertEquals(0, currentPan.status)
        assertEquals(0L, currentPan.holdingTime)
    }

    @Test
    fun resolveCurrentTargetPan_shouldUseCurrentCacheObjectWhenPlannedPanBecameOrphan() {
        val oldPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val currentPans = listOf(currentCachePan)

        val resolved = SupplementPanStateWriter.resolveCurrentTargetPan(
            plannedPan = oldPlannedPan,
            currentPans = currentPans
        )

        assertSame(currentCachePan, resolved)
    }

    @Test
    fun resolvedCurrentPan_shouldReceiveUnifiedSupplementWriteInsteadOfOrphanPlannedPan() {
        val orphanPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val box = KaoPanBox().apply {
            positionSn = 21
            num = 6
            tasteCode = "B"
            tasteName = "元气烤肠"
        }

        val resolvedPan = SupplementPanStateWriter.resolveCurrentTargetPan(
            plannedPan = orphanPlannedPan,
            currentPans = listOf(currentCachePan)
        )
        requireNotNull(resolvedPan)

        KaoChangOperate.applySuccessfulSupplementPanState(
            kaoPan = resolvedPan,
            kaoPanBox = box,
            demandGateReason = "单测",
            nowMs = 12_345L,
            clearPendingDiscard = false,
            config = AppConfigBean().apply {
                bakingTime = 40
                discardTime = 6
            },
            tasteBuilder = { sourceBox, _ -> Taste(sourceBox.tasteCode) }
        )

        assertFalse(orphanPlannedPan.isHasSausage)
        assertTrue(currentCachePan.isHasSausage)
        assertEquals(1, currentCachePan.status)
        assertEquals(12_345L, currentCachePan.startTime)
        assertEquals(40 * 60L * 1000L, currentCachePan.bakingTime)
        assertEquals(6 * 60L * 60L * 1000L, currentCachePan.closeTime)
        assertEquals(5, box.num)
    }

    @Test
    fun resolveCurrentSourceBox_shouldUseCurrentCacheObjectWhenPlannedBoxBecameOrphan() {
        val oldPlannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentCacheBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentBoxes = listOf(currentCacheBox)

        val resolved = SupplementPanStateWriter.resolveCurrentSourceBox(
            plannedBox = oldPlannedBox,
            currentBoxes = currentBoxes
        )

        assertSame(currentCacheBox, resolved)
    }

    @Test
    fun resolvedCurrentState_shouldDeductCurrentCacheBoxInsteadOfOrphanPlannedBox() {
        val orphanPlannedPan = pan(positionSn = 4, tasteCode = "B")
        val currentCachePan = pan(positionSn = 4, tasteCode = "B")
        val orphanPlannedBox = box(positionSn = 21, stock = 6, tasteCode = "B")
        val currentCacheBox = box(positionSn = 21, stock = 6, tasteCode = "B")

        val resolvedState = SupplementPanStateWriter.resolveCurrentSupplementState(
            plannedPan = orphanPlannedPan,
            plannedBox = orphanPlannedBox,
            currentPans = listOf(currentCachePan),
            currentBoxes = listOf(currentCacheBox)
        )
        requireNotNull(resolvedState)

        KaoChangOperate.applySuccessfulSupplementPanState(
            kaoPan = resolvedState.targetPan,
            kaoPanBox = resolvedState.sourceBox,
            demandGateReason = "单测",
            nowMs = 12_345L,
            clearPendingDiscard = false,
            config = AppConfigBean().apply {
                bakingTime = 40
                discardTime = 6
            },
            tasteBuilder = { sourceBox, _ -> Taste(sourceBox.tasteCode) }
        )

        assertFalse(orphanPlannedPan.isHasSausage)
        assertTrue(currentCachePan.isHasSausage)
        assertEquals(6, orphanPlannedBox.num)
        assertEquals(5, currentCacheBox.num)
    }

    @Test
    fun consistencyCheck_shouldPassWhenSuccessfulPanAndBoxCountsMatch() {
        val result = SupplementConsistencyChecker.evaluate(
            expectedSuccessCount = 2,
            records = listOf(
                SupplementWriteRecord(targetPanNo = 1, sourceBoxNo = 21, panApplied = true, boxDeducted = true),
                SupplementWriteRecord(targetPanNo = 2, sourceBoxNo = 22, panApplied = true, boxDeducted = true)
            )
        )

        assertFalse(result.requiresMaintenance)
    }

    @Test
    fun consistencyCheck_shouldDetectPanBoxDivergence() {
        val result = SupplementConsistencyChecker.evaluate(
            expectedSuccessCount = 2,
            records = listOf(
                SupplementWriteRecord(targetPanNo = 1, sourceBoxNo = 21, panApplied = true, boxDeducted = true),
                SupplementWriteRecord(targetPanNo = 2, sourceBoxNo = 22, panApplied = true, boxDeducted = false)
            )
        )

        assertTrue(result.requiresMaintenance)
        assertTrue(result.message.contains("落盘成功数=2"))
        assertTrue(result.message.contains("扣箱成功数=1"))
    }

    @Test
    fun shouldKeepExistingInMemoryPans_shouldPreserveObjectIdentityWhenCacheAlreadyLoaded() {
        val currentPan = pan(positionSn = 1, tasteCode = "A").apply {
            isHasSausage = true
            status = 1
        }
        val storagePan = pan(positionSn = 1, tasteCode = "A")

        val next = KaoPanCacheInitPolicy.resolveInitialPans(
            currentPans = listOf(currentPan),
            storedPans = listOf(storagePan),
            defaultPans = listOf(storagePan)
        )

        assertSame(currentPan, next.first())
        assertTrue(next.first().isHasSausage)
    }

    @Test
    fun resetAfterManualClear_shouldUseDefaultPansEvenWhenCacheAlreadyLoaded() {
        val currentPan = pan(positionSn = 1, tasteCode = "A").apply {
            isHasSausage = true
            status = 1
        }
        val defaultPan = pan(positionSn = 1, tasteCode = null)

        val next = KaoPanCacheInitPolicy.resolveResetPansAfterManualClear(
            defaultPans = listOf(defaultPan)
        )

        assertSame(defaultPan, next.first())
        assertFalse(next.first().isHasSausage)
        assertTrue(currentPan.isHasSausage)
    }

    @Test
    fun saveGeneration_shouldRejectAsyncSaveCapturedBeforeManualClear() {
        val generation = KaoPanAsyncSaveGeneration()
        val capturedBeforeClear = generation.current()

        generation.advance()

        assertFalse(generation.isCurrent(capturedBeforeClear))
        assertTrue(generation.isCurrent(generation.current()))
    }

    private fun pan(positionSn: Int, tasteCode: String?): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.taste = tasteCode?.let { Taste(it) }
            this.isHasSausage = false
            this.status = 0
        }

    private fun box(positionSn: Int, stock: Int, tasteCode: String): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = stock
            this.tasteCode = tasteCode
            this.tasteName = "测试肠"
        }

    private fun applySupplementTelemetryForPlannedMove(
        telemetry: cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry,
        plannedPan: KaoPan,
        plannedBox: KaoPanBox,
        currentPans: List<KaoPan>,
        currentBoxes: List<KaoPanBox>,
        config: AppConfigBean
    ): Boolean {
        if (!ActionPhysicalStateReducerPolicy.isSupplementTelemetryForPlannedMove(
                telemetry = telemetry,
                plannedBoxNo = plannedBox.positionSn,
                plannedPanNo = plannedPan.positionSn
            )
        ) {
            return false
        }
        return when {
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPickedFromBox(telemetry) ->
                SupplementPanStateWriter.applySupplementPickedFromBox(
                    plannedPan = plannedPan,
                    plannedBox = plannedBox,
                    currentPans = currentPans,
                    currentBoxes = currentBoxes
                )
            ActionPhysicalStateReducerPolicy.shouldApplySupplementPlacedOnPan(telemetry) ->
                SupplementPanStateWriter.applySupplementPlacedOnPan(
                    plannedPan = plannedPan,
                    plannedBox = plannedBox,
                    currentPans = currentPans,
                    currentBoxes = currentBoxes,
                    nowMs = 12_345L,
                    clearPendingDiscard = false,
                    config = config,
                    tasteBuilder = { sourceBox, _ -> Taste(sourceBox.tasteCode) }
                )
            else -> false
        }
    }

    private fun telemetry(
        actionName: String,
        checkpoint: String,
        boxNo: Int,
        panNo: Int
    ): cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry =
        cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry(
            eventId = "$actionName:$boxNo->$panNo",
            eventPhase = cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED,
            sessionId = "session",
            actionName = actionName,
            registerAddress = 1,
            resultRegister = 2,
            errorCode = null,
            errorMessage = null,
            boxNo = boxNo,
            panNo = panNo,
            orderId = null,
            resourceLocks = null,
            physicalCheckpoint = checkpoint,
            physicalLocation = null,
            elapsedMs = 10L
        )
}
