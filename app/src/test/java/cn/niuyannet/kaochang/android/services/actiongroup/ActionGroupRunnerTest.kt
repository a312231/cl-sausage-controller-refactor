package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupRunnerTest {

    @Test
    fun inRunner_shouldRejectInvalidBoxBeforeWritingAnyAction() = runBlocking {
        val client = FakeClient()
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 23, targetPanNo = 1)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("源肠箱编号必须在 1~10、12~21，当前=23", (result as ActionGroupResult.Failed).message)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun inRunner_shouldRejectReservedSourceBoxBeforeWritingAnyAction() = runBlocking {
        val client = FakeClient()
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 11, targetPanNo = 1)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("源肠箱编号必须在 1~10、12~21，当前=11", (result as ActionGroupResult.Failed).message)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun inRunner_shouldRetryIn2PickFailureTwoTimesWithoutRestartingIn1() = runBlocking {
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
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(
            listOf("in1:17", "in2:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "in7:2"),
            client.calls
        )
    }

    @Test
    fun inRunner_shouldFailIn2AfterTwoPickFailuresWithoutThirdAttempt() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠"),
                AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "取肠红外未确认有肠")
            )
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("in2", (result as ActionGroupResult.Failed).actionName)
        assertEquals(listOf("in1:17", "in2:17", "in2:17"), client.calls)
    }

    @Test
    fun inRunner_shouldRetryIn5ThrowFailureThreeTimesBeforeFailing() = runBlocking {
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
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("in5", (result as ActionGroupResult.Failed).actionName)
        assertEquals(
            listOf("in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in5:0", "in5:0"),
            client.calls
        )
    }

    @Test
    fun outRunner_shouldFailOut2StickFailureWithoutAutomaticRetry() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.STICK_FAILED, "穿签失败")
            )
        )
        val runner = OutActionGroupRunner(client)

        val result = runner.run(sourcePanNo = 8)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("out2", (result as ActionGroupResult.Failed).actionName)
        assertEquals(listOf("out1:8", "out2:0"), client.calls)
    }

    @Test
    fun outRunner_shouldFailWhenOut4ReportsOutExitSausageNotDetectedInsteadOfMappingDiscarded() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(
                    AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED,
                    "售卖口未检测到烤肠（出肠检测失败）"
                )
            )
        )
        val runner = OutActionGroupRunner(client)

        val result = runner.run(sourcePanNo = 8)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("out4", (result as ActionGroupResult.Failed).actionName)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "dispatch:out4"), client.calls)
    }

    @Test
    fun outRunner_shouldEmitReadableOperationStepLogsWithProgress() = runBlocking {
        val client = FakeClient()
        val operationLogs = mutableListOf<String>()
        val failureLogs = mutableListOf<String>()
        val runner = OutActionGroupRunner(
            client = client,
            operationLogContext = ActionGroupOperationLogContext(
                category = "动作组",
                subject = "第2/3根"
            ),
            operationLogger = { operationLogs += it },
            failureLogger = { failureLogs += it }
        )

        val result = runner.run(sourcePanNo = 9)

        assertEquals(ActionGroupResult.Success, result)
        assertTrue(operationLogs.any { it.contains("【动作组】第2/3根 out1（从烤盘取熟肠到穿签位）开始") })
        assertTrue(operationLogs.any { it.contains("【动作组】第2/3根 out5 完成，用时=") })
        assertTrue(failureLogs.isEmpty())
    }

    @Test
    fun outRunner_shouldEmitReadableFailureReasonWithStepAndProgress() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Timeout(
                    "初判责任=疑似下位机复位或命令丢失，失败阶段=启动确认超时，" +
                        "动作=out3，命令寄存器=430，命令值=0x0000（空闲/已清零）"
                )
            )
        )
        val failureLogs = mutableListOf<String>()
        val runner = OutActionGroupRunner(
            client = client,
            operationLogContext = ActionGroupOperationLogContext(
                category = "动作组",
                subject = "第2/3根"
            ),
            failureLogger = { failureLogs += it }
        )

        val result = runner.run(sourcePanNo = 9)

        assertTrue(result is ActionGroupResult.Failed)
        assertTrue(
            failureLogs.any {
                it.contains("【失败原因】第2/3根 out3（从穿签位放到预推送位置）失败：初判责任=疑似下位机复位或命令丢失") &&
                    !it.contains("执行超时：") &&
                    !it.contains("Modbus 读写失败：")
            }
        )
    }

    @Test
    fun outRunner_shouldCallWindowHookAfterOut4CommandDispatchedBeforeOut5() = runBlocking {
        val client = FakeClient()
        val runner = OutActionGroupRunner(client)

        val result = runner.run(
            sourcePanNo = 8,
            onWindowOpened = { client.calls += "window-hook" },
            windowOpenBufferMs = 0L
        )

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "dispatch:out4", "window-hook", "out5:0"), client.calls)
    }

    @Test
    fun outRunner_shouldContinueOut5WhenWindowHookThrows() = runBlocking {
        val client = FakeClient()
        val logs = mutableListOf<String>()
        val runner = OutActionGroupRunner(client, logger = { logs += it })

        val result = runner.run(
            sourcePanNo = 8,
            onWindowOpened = { error("voice hook failed") },
            windowOpenBufferMs = 0L
        )

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "dispatch:out4", "out5:0"), client.calls)
        assertTrue(logs.any { it.contains("【取肠语音】hook 异常") && it.contains("voice hook failed") })
    }

    @Test
    fun outRunner_shouldNotCallWindowHookWhenOut4Fails() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Timeout("out4 开窗并推送超时")
            )
        )
        val runner = OutActionGroupRunner(client)
        var hookCalled = false

        val result = runner.run(
            sourcePanNo = 8,
            onWindowOpened = { hookCalled = true },
            windowOpenBufferMs = 0L
        )

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "dispatch:out4"), client.calls)
        assertTrue("out4 命令寄存器 440=1 写入成功后即触发取肠语音，后续 out4 执行失败不回滚语音", hookCalled)
    }

    @Test
    fun outRunner_shouldNotCallWindowHookWhenRecoveryStartsFromOut5() = runBlocking {
        val client = FakeClient(states = mutableMapOf("out5" to AtomicActionCodes.RUNNING))
        val runner = OutActionGroupRunner(client)
        var hookCalled = false

        val result = runner.run(
            sourcePanNo = 8,
            resumeFromRunningState = true,
            onWindowOpened = { hookCalled = true },
            windowOpenBufferMs = 0L
        )

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("out5:0"), client.calls)
        assertTrue(!hookCalled)
    }

    @Test
    fun outRunner_shouldSkipVoiceHookWhenVoiceReminderDisabled() = runBlocking {
        val client = FakeClient()
        val runner = OutActionGroupRunner(client)
        var hookCalled = false

        val result = runner.run(
            sourcePanNo = 8,
            enableVoiceReminder = false,
            onWindowOpened = { hookCalled = true },
            windowOpenBufferMs = 0L
        )

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "out5:0"), client.calls)
        assertTrue(!hookCalled)
    }

    @Test
    fun inRunner_shouldResumeFromRunningActionWithoutRestartingEarlierActions() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            ),
            states = mutableMapOf("in5" to AtomicActionCodes.RUNNING)
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2, resumeFromRunningState = true)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("in5:0", "in6:0", "in7:2"), client.calls)
    }

    @Test
    fun inRunner_shouldFreshStartAllStepsWhenPreviousOrderLeavesCompletedRegisters() = runBlocking {
        val client = FakeClient(
            states = completedStates(
                "in1",
                "in2",
                "in3",
                "in4",
                "in5",
                "in6",
                "in7"
            )
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(
            listOf("in1:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "in7:2"),
            client.calls
        )
    }

    @Test
    fun inRunner_shouldResumeFromFirstIncompleteStepWhenCompletedPrefixExistsInRecoveryMode() = runBlocking {
        val client = FakeClient(
            states = mutableMapOf(
                "in1" to COMPLETED,
                "in2" to COMPLETED
            )
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2, resumeFromRunningState = true)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("in3:0", "in4:0", "in5:0", "in6:0", "in7:2"), client.calls)
    }

    @Test
    fun resourceLocks_shouldBlockConflictingLegacyAction() {
        val locks = ResourceLocks()

        assertTrue(locks.tryAcquire(ResourceMask.above, owner = "out1"))
        assertTrue(!locks.tryAcquire(ResourceMask.above, owner = "legacy_self_clean"))
        locks.release("out1")
        assertTrue(locks.tryAcquire(ResourceMask.above, owner = "legacy_self_clean"))
    }

    private class FakeClient(
        private val results: ArrayDeque<AtomicActionResult> = ArrayDeque(),
        private val states: MutableMap<String, Int> = mutableMapOf()
    ) : AtomicActionClient, AtomicActionStateReader {
        val calls = mutableListOf<String>()

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (suspend () -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            if (spec.name == "out4" && onCommandDispatched != null) {
                calls += "dispatch:out4"
            }
            onCommandDispatched?.invoke()
            states[spec.name] = COMPLETED
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }

        override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
            return AtomicReadResult.Success(states[spec.name] ?: AtomicActionCodes.IDLE)
        }
    }

    companion object {
        private const val COMPLETED = 0x0002

        fun queueOf(vararg values: AtomicActionResult): ArrayDeque<AtomicActionResult> =
            ArrayDeque<AtomicActionResult>().apply { addAll(values.toList()) }

        fun completedStates(vararg actionNames: String): MutableMap<String, Int> =
            actionNames.associateWith { COMPLETED }.toMutableMap()
    }
}
