package cn.niuyannet.kaochang.android.services.actiongroup

import cn.niuyannet.kaochang.android.services.SourceBoxPositionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class InActionGroupPrefetchPipelineRunnerTest {

    @Test
    fun parallelPipeline_shouldStartNextPrefetchOnlyAfterCurrentIn6RunningConfirmed() = runBlocking {
        val client = ParallelProbeClient()
        val running = async {
            InActionGroupPrefetchPipelineRunner(client, parallelEnabled = true).run(
                listOf(
                    InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                    InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
                )
            )
        }

        withTimeout(1_000L) {
            while (!client.calls.contains("running:in6:0")) delay(1L)
            while (client.calls.count { it == "in3:0" } < 2) delay(1L)
        }
        assertTrue(client.calls.indexOf("running:in6:0") < client.calls.indexOf("in1:18"))
        assertEquals(1, client.calls.count { it == "in4:0" })

        client.releaseCurrentIn7.complete(Unit)
        assertTrue(running.await() is InActionGroupPipelineResult.Success)
    }

    @Test
    fun parallelPipeline_shouldFallbackToStartingNextPrefetchAfterIn6SuccessWithoutRunningSignal() = runBlocking {
        val client = ParallelProbeClient(
            emitRunningConfirmed = false,
            blockCurrentIn7 = false
        )

        val result = InActionGroupPrefetchPipelineRunner(client, parallelEnabled = true).run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertTrue(client.calls.indexOf("done:in6:0") < client.calls.indexOf("in1:18"))
    }

    @Test
    fun parallelPipeline_shouldNeverDispatchNextIn4BeforeCurrentIn7Completes() = runBlocking {
        val client = ParallelProbeClient()
        val running = async {
            InActionGroupPrefetchPipelineRunner(client, parallelEnabled = true).run(
                listOf(
                    InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                    InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
                )
            )
        }

        withTimeout(1_000L) {
            while (client.calls.count { it == "in3:0" } < 2) delay(1L)
        }
        assertEquals(1, client.calls.count { it == "in4:0" })

        client.releaseCurrentIn7.complete(Unit)
        assertTrue(running.await() is InActionGroupPipelineResult.Success)
        assertTrue(client.calls.indexOf("done:in7:13") < client.calls.lastIndexOf("in4:0"))
    }

    @Test
    fun parallelPipeline_shouldPromotePrefetchLaneBeforeSecondIn4() = runBlocking {
        val client = ParallelProbeClient(blockCurrentIn7 = false)
        val events = mutableListOf<String>()

        val result = InActionGroupPrefetchPipelineRunner(
            client = client,
            parallelEnabled = true,
            onCurrentActionChanged = { events += "action:${it.laneRole}:${it.targetPanNo}:${it.actionName}" },
            onPrefetchPromoted = { events += "promote:${it.sourceBoxNo}->${it.targetPanNo}" }
        ).run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertTrue(events.contains("action:${SupplementLaneRole.PREFETCH}:14:in1"))
        assertTrue(
            events.indexOf("promote:18->14") <
                events.indexOf("action:${SupplementLaneRole.DELIVERY}:14:in4")
        )
    }

    @Test
    fun parallelOrderPause_shouldPromotePrefetchPausedAfterIn1() = runBlocking {
        assertParallelOrderPausePromotesCheckpoint("in1")
    }

    @Test
    fun parallelOrderPause_shouldPromotePrefetchPausedAfterIn2() = runBlocking {
        assertParallelOrderPausePromotesCheckpoint("in2")
    }

    @Test
    fun parallelOrderPause_shouldPromotePrefetchPausedAfterIn3() = runBlocking {
        assertParallelOrderPausePromotesCheckpoint("in3")
    }

    @Test
    fun parallelOrderPause_withFinalPrefetchE5_shouldIsolateOnceAndReturnPrefetchFailure() = runBlocking {
        var orderWaiting = false
        val client = ParallelTerminalClient(
            waitForDeliveryAtPrefetchAction = "in2",
            prefetchIn2Result = AtomicActionResult.Failed(
                AtomicActionCodes.IR_PICK_FAILED,
                "取肠红外未确认有肠"
            ),
            onDeliveryIn7 = { orderWaiting = true }
        )
        val terminalPickFailures = mutableListOf<InActionGroupPipelineActionContext>()

        val result = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            actionClientProvider = client::withContext,
            parallelEnabled = true,
            pauseReasonAfterCurrentTask = { if (orderWaiting) "检测到订单待出餐" else null },
            onTerminalPrefetchPickFailure = { terminalPickFailures += it }
        ).run(twoParallelTasks())

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals(SupplementLaneRole.PREFETCH, failed.laneRole)
        assertEquals("in2", failed.actionName)
        assertEquals(18, failed.sourceBoxNo)
        assertEquals(1, terminalPickFailures.size)
        assertEquals(SupplementLaneRole.PREFETCH, terminalPickFailures.single().laneRole)
        assertEquals("in2", terminalPickFailures.single().actionName)
    }

    @Test
    fun deliveryFailure_withFinalPrefetchE5_shouldIsolateOnceButKeepDeliveryFailurePrimary() = runBlocking {
        val client = ParallelTerminalClient(
            waitForDeliveryAtPrefetchAction = "in2",
            prefetchIn2Result = AtomicActionResult.Failed(
                AtomicActionCodes.IR_PICK_FAILED,
                "取肠红外未确认有肠"
            ),
            deliveryIn7Result = AtomicActionResult.Failed(AtomicActionCodes.LIFT_FAILED, "升降台故障")
        )
        val terminalPickFailures = mutableListOf<InActionGroupPipelineActionContext>()

        val result = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            actionClientProvider = client::withContext,
            parallelEnabled = true,
            onTerminalPrefetchPickFailure = { terminalPickFailures += it }
        ).run(twoParallelTasks())

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals(SupplementLaneRole.DELIVERY, failed.laneRole)
        assertEquals("in7", failed.actionName)
        assertEquals(17, failed.sourceBoxNo)
        assertEquals(1, terminalPickFailures.size)
        assertEquals(18, terminalPickFailures.single().sourceBoxNo)
    }

    @Test
    fun pipeline_shouldExposeCommandRunningAndTerminalLifecycleForSuccessfulActions() = runBlocking {
        val client = FakeClient()
        val commandWritten = mutableListOf<String>()
        val runningConfirmed = mutableListOf<String>()
        val terminalConfirmed = mutableListOf<String>()

        val result = InActionGroupPrefetchPipelineRunner(
            client = client,
            onActionCommandWritten = { commandWritten += it.actionName },
            onActionRunningConfirmed = { runningConfirmed += it.actionName },
            onActionTerminalConfirmed = { context, actionResult ->
                if (actionResult == AtomicActionResult.Success) terminalConfirmed += context.actionName
            }
        ).run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals((1..7).map { "in$it" }, commandWritten)
        assertEquals((1..7).map { "in$it" }, runningConfirmed)
        assertEquals((1..7).map { "in$it" }, terminalConfirmed)
    }

    @Test
    fun pipeline_shouldPauseAtIn3WhenDeliveryBarrierRejectsCommit() = runBlocking {
        val client = FakeClient()

        val result = InActionGroupPrefetchPipelineRunner(
            client = client,
            parallelEnabled = true,
            beforeDeliveryCommit = { false }
        ).run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertEquals("in3", (result as InActionGroupPipelineResult.Paused).pausedAfterAction)
        assertEquals(listOf("in1:17", "in2:17", "in3:0"), client.calls)
    }

    @Test
    fun pipeline_shouldReportTheActualSourceBoxAfterIn2Succeeds() = runBlocking {
        val successfulPickBoxes = mutableListOf<Int>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            onPickSucceeded = { successfulPickBoxes += it }
        )

        val result = runner.run(
            listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13))
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(listOf(17), successfulPickBoxes)
    }

    @Test
    fun pipeline_shouldNotStartNextPrefetchUntilCurrentIn7Completes() = runBlocking {
        val client = FakeClient()
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(
            listOf(
                "in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "dispatch:in6", "in7:13",
                "in1:18", "in2:18", "in3:0", "in4:0", "in5:0", "in6:0", "dispatch:in6", "in7:14"
            ),
            client.calls
        )
    }

    @Test
    fun pipeline_currentActionCallbacks_shouldFinishCurrentTargetBeforeNextTargetStarts() = runBlocking {
        val client = FakeClient()
        val actions = mutableListOf<String>()
        val result = InActionGroupPrefetchPipelineRunner(
            client = client,
            onCurrentActionChanged = { context -> actions += "${context.targetPanNo}:${context.actionName}" }
        ).run(
                listOf(
                    InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                    InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
                )
            )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertTrue(actions.indexOf("13:in7") < actions.indexOf("14:in1"))
    }

    @Test
    fun pipeline_shouldNotifyTaskCompletedImmediatelyAfterEachIn7Success() = runBlocking {
        val client = FakeClient()
        val completedEvents = mutableListOf<String>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            onTaskCompleted = { completedTask ->
                completedEvents += "${completedTask.sourceBoxNo}->${completedTask.targetPanNo}@" +
                    client.calls.joinToString("|")
            }
        )

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(2, completedEvents.size)
        assertTrue(completedEvents[0].startsWith("17->13@"))
        assertTrue(completedEvents[0].contains("in7:13"))
        assertTrue("第一根完成回调不应等第二根 in7", !completedEvents[0].contains("in7:14"))
        assertTrue(completedEvents[1].startsWith("18->14@"))
        assertTrue(completedEvents[1].contains("in7:14"))
    }

    @Test
    fun pipeline_shouldProvideBoxAndPanContextForEveryExecutedAction() = runBlocking {
        val calls = mutableListOf<String>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            actionClientProvider = { context -> ContextCapturingClient(context, calls) }
        )

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(
            listOf(
                "in1:17->13", "in2:17->13", "in3:17->13",
                "in4:17->13", "in5:17->13", "in6:17->13", "dispatch:in6:17->13",
                "in7:17->13", "in1:18->14", "in2:18->14", "in3:18->14",
                "in4:18->14", "in5:18->14", "in6:18->14",
                "dispatch:in6:18->14", "in7:18->14"
            ),
            calls
        )
    }

    @Test
    fun pipeline_shouldProvideAttemptContextForIn2RetryAndCandidateSwitch() = runBlocking {
        val calls = mutableListOf<String>()
        val client = ContextCapturingClient(
            calls = calls,
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            actionClientProvider = { context -> client.withContext(context) }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17, 18), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(
            listOf("in2:17->13", "in2:17->13", "in2:18->13"),
            calls.filter { it.startsWith("in2:") }
        )
        assertTrue(calls.contains("in1:18->13"))
    }

    @Test
    fun pipeline_shouldPauseAfterCurrentTaskWithoutStartingNextPrefetchWhenOrderIsWaiting() = runBlocking {
        val client = FakeClient()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            shouldPauseAfterCurrentTask = { true }
        )

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14),
                InActionGroupPipelineTask(listOf(19), targetPanNo = 15)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Paused)
        val paused = result as InActionGroupPipelineResult.Paused
        assertEquals(listOf(13), paused.completed.map { it.targetPanNo })
        assertEquals(
            listOf(
                "in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "dispatch:in6", "in7:13"
            ),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldRecheckPauseAfterIn7BeforeStartingNextTask() = runBlocking {
        var orderWaiting = false
        val client = OrderArrivesAfterActionClient("in7") { orderWaiting = true }
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            pauseReasonAfterCurrentTask = {
                if (orderWaiting) "检测到订单待出餐" else null
            }
        )

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertEquals(listOf(13), (result as InActionGroupPipelineResult.Paused).completed.map { it.targetPanNo })
        assertFalse(client.calls.contains("in1:18"))
    }

    @Test
    fun pipeline_shouldPauseAfterIn1BeforePickingSausage() = runBlocking {
        var orderWaiting = false
        val client = OrderArrivesAfterActionClient("in1") { orderWaiting = true }
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            pauseReasonBeforePick = {
                if (orderWaiting) "检测到订单待出餐" else null
            }
        )

        val result = runner.run(
            listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13))
        )

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertTrue((result as InActionGroupPipelineResult.Paused).completed.isEmpty())
        assertEquals(listOf("in1:17"), client.calls)
    }

    @Test
    fun pipeline_shouldPauseAfterIn2WhenOrderArrivesBeforeIn3() = runBlocking {
        var orderWaiting = false
        val client = OrderArrivesAfterActionClient("in2") { orderWaiting = true }
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            pauseReasonAfterPrefetchAction = { action ->
                if (action == "in2" && orderWaiting) "检测到订单待出餐" else null
            }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertEquals(listOf("in1:17", "in2:17"), client.calls)
    }

    @Test
    fun pipeline_shouldPauseAfterIn3WhenOrderArrivesBeforeIn4() = runBlocking {
        var orderWaiting = false
        val client = OrderArrivesAfterActionClient("in3") { orderWaiting = true }
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            pauseReasonAfterPrefetchAction = { action ->
                if (action == "in3" && orderWaiting) "检测到订单待出餐" else null
            }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertEquals(listOf("in1:17", "in2:17", "in3:0"), client.calls)
    }

    @Test
    fun pipeline_shouldNotNotifyTaskCompletedWhenIn7Fails() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.LIFT_FAILED, "升降台故障")
            )
        )
        val completedEvents = mutableListOf<CompletedInActionGroupPipelineTask>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            onTaskCompleted = { completedEvents += it }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        assertTrue(completedEvents.isEmpty())
    }

    @Test
    fun pipeline_shouldRetryIn2PickFailureTwoTimesBeforeContinuing() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(
            listOf(
                "in1:17", "in2:17", "in2:17", "in3:0",
                "in4:0", "in5:0", "in6:0", "dispatch:in6", "in7:13"
            ),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldRejectReservedSourceBoxCandidateBeforeWritingAnyAction() = runBlocking {
        val client = FakeClient()
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(22), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in", failed.actionName)
        assertEquals(22, failed.sourceBoxNo)
        assertEquals("源肠箱编号必须在 ${SourceBoxPositionPolicy.validRangeText()}，当前=22", failed.message)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun pipeline_shouldTryNextBoxWhenIn2PickFailureExhaustsCurrentBox() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            )
        )
        val skippedPickFailureBoxes = mutableListOf<Int>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client,
            onPickFailureSkipped = { skippedPickFailureBoxes += it }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17, 18), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(18, (result as InActionGroupPipelineResult.Success).completed.single().sourceBoxNo)
        assertEquals(listOf(17), skippedPickFailureBoxes)
        assertEquals(
            listOf(
                "in1:17", "in2:17", "in2:17",
                "in1:18", "in2:18", "in3:0", "in4:0", "in5:0",
                "in6:0", "dispatch:in6", "in7:13"
            ),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldRequireReplanAfterFallbackSourceCompletesWithoutStartingStaleNextPrefetch() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            pauseReasonAfterFallbackSource = { task, actualSourceBoxNo ->
                "实际来源箱 $actualSourceBoxNo 已偏离主计划箱 ${task.sourceBoxCandidates.first()}，重新规划剩余目标"
            }
        )

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(21, 20), targetPanNo = 1),
                InActionGroupPipelineTask(listOf(20), targetPanNo = 4)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.ReplanRequired)
        val replan = result as InActionGroupPipelineResult.ReplanRequired
        assertEquals(listOf(1), replan.completed.map { it.targetPanNo })
        assertEquals(20, replan.completed.single().sourceBoxNo)
        assertTrue(replan.reason.contains("重新规划剩余目标"))
        assertEquals(
            "备用箱被实际消耗后，不应继续启动旧计划里的下一根预取",
            1,
            client.calls.count { it == "in1:20" }
        )
    }

    @Test
    fun pipeline_shouldReportActualFailedBoxWhenAllCandidateIn2PickFailuresExhaust() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠")
            )
        )
        val skippedPickFailureBoxes = mutableListOf<Int>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client,
            onPickFailureSkipped = { skippedPickFailureBoxes += it }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17, 18), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in2", failed.actionName)
        assertEquals(SupplementLaneRole.DELIVERY, failed.laneRole)
        assertEquals(18, failed.sourceBoxNo)
        assertEquals(listOf(17, 18), failed.failedSourceBoxNos)
        assertEquals(listOf(17), skippedPickFailureBoxes)
        assertEquals(
            listOf("in1:17", "in2:17", "in2:17", "in1:18", "in2:18", "in2:18"),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldRetryIn5ThrowFailureThreeTimesAndStopBeforeIn6() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_THROW_FAILED, "抛肠后仍检测到有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_THROW_FAILED, "抛肠后仍检测到有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_THROW_FAILED, "抛肠后仍检测到有肠")
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in5", failed.actionName)
        assertEquals(SupplementLaneRole.DELIVERY, failed.laneRole)
        assertEquals(false, failed.hasPrefetchedNext)
        assertEquals(
            listOf("in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in5:0", "in5:0"),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldNotRetryIn3YResetFailure() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IN3_Y_RESET_FAILED, "in3 的 Y 复位失败")
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in3", failed.actionName)
        assertEquals(
            listOf("in1:17", "in2:17", "in3:0"),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldExposePossibleControllerResetInStartupFailureLog() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Timeout(
                    "初判责任=疑似下位机复位或命令丢失，失败阶段=启动确认超时，" +
                        "动作=in4，命令寄存器=340，命令值=0x0000（空闲/已清零）"
                )
            )
        )
        val failureLogs = mutableListOf<String>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            failureLogger = { failureLogs += it }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(18), targetPanNo = 16)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        assertTrue(
            failureLogs.any {
                it.contains("【失败原因】补肠烤盘16 in4（升降台下降）失败：初判责任=疑似下位机复位或命令丢失") &&
                    it.contains("命令寄存器=340") &&
                    it.contains("命令值=0x0000（空闲/已清零）")
            }
        )
    }

    @Test
    fun pipeline_shouldNotPrefetchNextWhenCurrentDeliveryFails() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.LIFT_FAILED, "升降台故障")
            )
        )
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(
            listOf(
                InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
            )
        )

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in7", failed.actionName)
        assertFalse(failed.hasPrefetchedNext)
        assertTrue(failed.completed.isEmpty())
    }

    @Test
    fun pipeline_shouldTranslateIn7RuntimeExceptionToFailedWithActualAction() = runBlocking {
        val client = ThrowingClient(throwOnAction = "in7", throwable = IllegalStateException("in7 writeback failed"))
        val completedEvents = mutableListOf<CompletedInActionGroupPipelineTask>()
        val runner = InActionGroupPrefetchPipelineRunner(
            client = client,
            onTaskCompleted = { completedEvents += it }
        )

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in7", failed.actionName)
        assertEquals(17, failed.sourceBoxNo)
        assertTrue(failed.result is AtomicActionResult.ModbusError)
        assertTrue(failed.message.contains("IllegalStateException"))
        assertTrue(completedEvents.isEmpty())
    }

    @Test
    fun pipeline_shouldTranslateIn5RuntimeExceptionToFailedWithoutHardcodingIn7() = runBlocking {
        val client = ThrowingClient(throwOnAction = "in5", throwable = IllegalArgumentException("throw sensor read failed"))
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in5", failed.actionName)
        assertFalse(failed.actionName == "in7")
        assertEquals(17, failed.sourceBoxNo)
        assertTrue(failed.result is AtomicActionResult.ModbusError)
    }

    @Test
    fun pipeline_shouldRethrowIn7CancellationException() = runBlocking {
        val client = ThrowingClient(throwOnAction = "in7", throwable = CancellationException("scope cancelled"))
        val runner = InActionGroupPrefetchPipelineRunner(client)

        try {
            runner.run(listOf(InActionGroupPipelineTask(listOf(17), targetPanNo = 13)))
            fail("CancellationException should be rethrown")
        } catch (e: CancellationException) {
            assertEquals("scope cancelled", e.message)
        }
    }

    private suspend fun assertParallelOrderPausePromotesCheckpoint(actionName: String) {
        var orderWaiting = false
        val client = ParallelTerminalClient(
            waitForDeliveryAtPrefetchAction = actionName,
            onDeliveryIn7 = { orderWaiting = true }
        )
        val promoted = mutableListOf<InActionGroupPipelineActionContext>()

        val result = InActionGroupPrefetchPipelineRunner(
            client = FakeClient(),
            actionClientProvider = client::withContext,
            parallelEnabled = true,
            pauseReasonBeforePick = { if (orderWaiting) "检测到订单待出餐" else null },
            pauseReasonAfterPrefetchAction = {
                if (orderWaiting) "检测到订单待出餐" else null
            },
            pauseReasonAfterCurrentTask = { if (orderWaiting) "检测到订单待出餐" else null },
            onPausedPrefetchPromoted = { promoted += it }
        ).run(twoParallelTasks())

        assertTrue(result is InActionGroupPipelineResult.Paused)
        assertEquals(listOf(13), (result as InActionGroupPipelineResult.Paused).completed.map { it.targetPanNo })
        assertEquals(1, promoted.size)
        assertEquals(actionName, promoted.single().actionName)
        assertEquals(18, promoted.single().sourceBoxNo)
        assertEquals(14, promoted.single().targetPanNo)
    }

    private fun twoParallelTasks(): List<InActionGroupPipelineTask> = listOf(
        InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
        InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
    )

    private class FakeClient(
        private val results: ArrayDeque<AtomicActionResult> = ArrayDeque()
    ) : AtomicActionClient {
        val calls = mutableListOf<String>()

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            onCommandDispatched?.invoke()
            if (spec.name == "in6" && onCommandDispatched != null) {
                calls += "dispatch:in6"
                yield()
            }
            onRunningConfirmed?.invoke()
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }
    }

    private class ParallelProbeClient(
        private val emitRunningConfirmed: Boolean = true,
        private val blockCurrentIn7: Boolean = true
    ) : AtomicActionClient {
        val calls = mutableListOf<String>()
        val releaseCurrentIn7 = CompletableDeferred<Unit>()
        private var in7Count = 0

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            onCommandDispatched?.invoke()
            if (spec.name == "in6" && emitRunningConfirmed) {
                calls += "running:in6:$infoValue"
                onRunningConfirmed?.invoke()
                yield()
            } else if (spec.name != "in6") {
                onRunningConfirmed?.invoke()
            }
            if (spec.name == "in7") {
                in7Count += 1
                if (in7Count == 1 && blockCurrentIn7) {
                    releaseCurrentIn7.await()
                }
            }
            calls += "done:${spec.name}:$infoValue"
            return AtomicActionResult.Success
        }
    }

    private class ThrowingClient(
        private val throwOnAction: String,
        private val throwable: Throwable
    ) : AtomicActionClient {
        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            if (spec.name == throwOnAction) {
                throw throwable
            }
            if (spec.name == "in6" && onCommandDispatched != null) {
                onCommandDispatched()
                yield()
            }
            onRunningConfirmed?.invoke()
            return AtomicActionResult.Success
        }
    }

    private class OrderArrivesAfterActionClient(
        private val actionName: String,
        private val onOrderArrives: () -> Unit
    ) : AtomicActionClient {
        val calls = mutableListOf<String>()

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            if (spec.name == actionName) {
                onOrderArrives()
            }
            if (spec.name == "in6") {
                onCommandDispatched?.invoke()
            }
            onRunningConfirmed?.invoke()
            return AtomicActionResult.Success
        }
    }

    private class ContextCapturingClient private constructor(
        private val context: InActionGroupPipelineActionContext?,
        private val calls: MutableList<String>,
        private val results: ArrayDeque<AtomicActionResult>
    ) : AtomicActionClient {
        constructor(
            context: InActionGroupPipelineActionContext,
            calls: MutableList<String>
        ) : this(context, calls, ArrayDeque())

        constructor(
            calls: MutableList<String>,
            results: ArrayDeque<AtomicActionResult>
        ) : this(null, calls, results)

        fun withContext(context: InActionGroupPipelineActionContext): ContextCapturingClient {
            return ContextCapturingClient(context, calls, results)
        }

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            val currentContext = requireNotNull(context) { "ContextCapturingClient requires action context" }
            calls += "${spec.name}:${currentContext.sourceBoxNo}->${currentContext.targetPanNo}"
            if (spec.name == "in6" && onCommandDispatched != null) {
                onCommandDispatched()
                calls += "dispatch:in6:${currentContext.sourceBoxNo}->${currentContext.targetPanNo}"
                yield()
            }
            onRunningConfirmed?.invoke()
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }
    }

    private class WaitForCurrentIn7BeforeSecondIn3Client : AtomicActionClient {
        val calls = mutableListOf<String>()
        private var in3Count = 0

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?,
            onRunningConfirmed: (suspend () -> Unit)?
        ): AtomicActionResult {
            if (spec.name == "in3") {
                in3Count += 1
                if (in3Count == 2) {
                    calls += "in3:$infoValue#2-wait"
                    while (!calls.contains("in7:13")) {
                        delay(1L)
                    }
                    calls += "in3:$infoValue#2-done"
                    return AtomicActionResult.Success
                }
            }

            calls += "${spec.name}:$infoValue"
            if (spec.name == "in6" && onCommandDispatched != null) {
                onCommandDispatched()
                calls += "dispatch:in6"
                yield()
            }
            onRunningConfirmed?.invoke()
            return AtomicActionResult.Success
        }
    }

    private class ParallelTerminalClient(
        private val waitForDeliveryAtPrefetchAction: String,
        private val prefetchIn2Result: AtomicActionResult = AtomicActionResult.Success,
        private val deliveryIn7Result: AtomicActionResult = AtomicActionResult.Success,
        private val onDeliveryIn7: () -> Unit = {}
    ) {
        private val prefetchTargetReached = CompletableDeferred<Unit>()
        private val deliveryIn7Reached = CompletableDeferred<Unit>()

        fun withContext(context: InActionGroupPipelineActionContext): AtomicActionClient =
            object : AtomicActionClient {
                override suspend fun execute(
                    spec: AtomicActionSpec,
                    infoValue: Int,
                    onCommandDispatched: (suspend () -> Unit)?,
                    onRunningConfirmed: (suspend () -> Unit)?
                ): AtomicActionResult {
                    onCommandDispatched?.invoke()
                    onRunningConfirmed?.invoke()
                    if (context.laneRole == SupplementLaneRole.PREFETCH &&
                        spec.name == waitForDeliveryAtPrefetchAction
                    ) {
                        prefetchTargetReached.complete(Unit)
                        deliveryIn7Reached.await()
                    }
                    if (context.laneRole == SupplementLaneRole.DELIVERY && spec.name == "in7") {
                        prefetchTargetReached.await()
                        onDeliveryIn7()
                        deliveryIn7Reached.complete(Unit)
                        return deliveryIn7Result
                    }
                    return if (context.laneRole == SupplementLaneRole.PREFETCH && spec.name == "in2") {
                        prefetchIn2Result
                    } else {
                        AtomicActionResult.Success
                    }
                }
            }
    }

    companion object {
        fun queueOf(vararg values: AtomicActionResult): ArrayDeque<AtomicActionResult> =
            ArrayDeque<AtomicActionResult>().apply { addAll(values.toList()) }
    }
}
