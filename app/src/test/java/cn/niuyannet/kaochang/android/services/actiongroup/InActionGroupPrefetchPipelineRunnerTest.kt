package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InActionGroupPrefetchPipelineRunnerTest {

    @Test
    fun pipeline_shouldStartNextPrefetchAfterCurrentIn6DispatchedBeforeCurrentIn7() = runBlocking {
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
                "in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "dispatch:in6",
                "in1:18", "in2:18", "in3:0",
                "in7:13", "in4:0", "in5:0", "in6:0", "dispatch:in6", "in7:14"
            ),
            client.calls
        )
    }

    @Test
    fun pipeline_shouldRunCurrentIn7WithoutWaitingForNextPrefetchToFinish() = runBlocking {
        val client = WaitForCurrentIn7BeforeSecondIn3Client()
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = withTimeout(1_000L) {
            runner.run(
                listOf(
                    InActionGroupPipelineTask(listOf(17), targetPanNo = 13),
                    InActionGroupPipelineTask(listOf(18), targetPanNo = 14)
                )
            )
        }

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertTrue(client.calls.indexOf("in7:13") < client.calls.indexOf("in3:0#2-done"))
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
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17, 18), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Success)
        assertEquals(18, (result as InActionGroupPipelineResult.Success).completed.single().sourceBoxNo)
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
        val runner = InActionGroupPrefetchPipelineRunner(client)

        val result = runner.run(listOf(InActionGroupPipelineTask(listOf(17, 18), targetPanNo = 13)))

        assertTrue(result is InActionGroupPipelineResult.Failed)
        val failed = result as InActionGroupPipelineResult.Failed
        assertEquals("in2", failed.actionName)
        assertEquals(18, failed.sourceBoxNo)
        assertEquals(listOf(17, 18), failed.failedSourceBoxNos)
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
                    "in4 动作未确认启动，疑似下位机复位/命令丢失：" +
                        "resultRegister=341，commandRegister=340，currentCommand=0x0000，elapsedMs=3229"
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
                it.contains("【失败原因】补肠烤盘16 in4（升降台下降）启动失败") &&
                    it.contains("疑似下位机复位/命令丢失") &&
                    it.contains("命令寄存器340当前=0x0000（空闲/已清零）")
            }
        )
    }

    @Test
    fun pipeline_shouldReportPrefetchedNextWhenCurrentDeliveryFailsAfterStartingNextPrefetch() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
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
        assertTrue(failed.hasPrefetchedNext)
        assertTrue(failed.completed.isEmpty())
    }

    private class FakeClient(
        private val results: ArrayDeque<AtomicActionResult> = ArrayDeque()
    ) : AtomicActionClient {
        val calls = mutableListOf<String>()

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (() -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            if (spec.name == "in6" && onCommandDispatched != null) {
                onCommandDispatched()
                calls += "dispatch:in6"
                yield()
            }
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }
    }

    private class WaitForCurrentIn7BeforeSecondIn3Client : AtomicActionClient {
        val calls = mutableListOf<String>()
        private var in3Count = 0

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (() -> Unit)?
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
            return AtomicActionResult.Success
        }
    }

    companion object {
        fun queueOf(vararg values: AtomicActionResult): ArrayDeque<AtomicActionResult> =
            ArrayDeque<AtomicActionResult>().apply { addAll(values.toList()) }
    }
}
