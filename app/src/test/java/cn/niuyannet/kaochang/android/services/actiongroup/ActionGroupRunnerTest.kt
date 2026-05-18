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
        assertEquals("源肠箱编号必须在 1..22，当前=23", (result as ActionGroupResult.Failed).message)
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
    fun outRunner_shouldRetryOut2StickFailureThreeTimesBeforeFailing() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.STICK_FAILED, "穿签失败"),
                AtomicActionResult.Failed(AtomicActionCodes.STICK_FAILED, "穿签失败"),
                AtomicActionResult.Failed(AtomicActionCodes.STICK_FAILED, "穿签失败")
            )
        )
        val runner = OutActionGroupRunner(client)

        val result = runner.run(sourcePanNo = 8)

        assertTrue(result is ActionGroupResult.Failed)
        assertEquals("out2", (result as ActionGroupResult.Failed).actionName)
        assertEquals(listOf("out1:8", "out2:0", "out2:0", "out2:0"), client.calls)
    }

    @Test
    fun outRunner_shouldReturnDiscardedWhenOut5ReportsCustomerTimeoutDiscarded() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Failed(AtomicActionCodes.CLIENT_TAKE_TIMEOUT_DISCARDED, "顾客超时未取，已自动丢弃")
            )
        )
        val runner = OutActionGroupRunner(client)

        val result = runner.run(sourcePanNo = 8)

        assertEquals(ActionGroupResult.Discarded, result)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "out5:0"), client.calls)
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
                    "out3 动作未确认启动，疑似下位机复位/命令丢失：" +
                        "resultRegister=431，commandRegister=430，currentCommand=0x0000，elapsedMs=3229"
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
                it.contains("【失败原因】第2/3根 out3（从穿签位放到预推送位置）启动失败") &&
                    it.contains("疑似下位机复位/命令丢失") &&
                    it.contains("命令寄存器430当前=0x0000（空闲/已清零）")
            }
        )
    }

    @Test
    fun outRunner_shouldCallWindowHookAfterOut4SuccessBeforeOut5() = runBlocking {
        val client = FakeClient()
        val runner = OutActionGroupRunner(client)

        val result = runner.run(
            sourcePanNo = 8,
            onWindowOpened = { client.calls += "window-hook" },
            windowOpenBufferMs = 0L
        )

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "window-hook", "out5:0"), client.calls)
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
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0", "out5:0"), client.calls)
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
        assertEquals(listOf("out1:8", "out2:0", "out3:0", "out4:0"), client.calls)
        assertTrue(!hookCalled)
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
