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
    fun inRunner_shouldRetryIn2PickFailureThreeTimesWithoutRestartingIn1() = runBlocking {
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
                AtomicActionResult.Success
            )
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(
            listOf("in1:17", "in2:17", "in2:17", "in2:17", "in3:0", "in4:0", "in5:0", "in6:0", "in7:2"),
            client.calls
        )
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
                AtomicActionResult.Timeout("out3 动作未确认启动，疑似读取到旧 0x0000（成功/空闲）：resultRegister=431")
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
                    it.contains("结果寄存器431一直为0x0000（旧成功/空闲）")
            }
        )
    }

    @Test
    fun inRunner_shouldResumeFromRunningActionWithoutRestartingEarlierActions() = runBlocking {
        val client = FakeClient(
            results = queueOf(
                AtomicActionResult.Success,
                AtomicActionResult.Success,
                AtomicActionResult.Success
            ),
            runningAction = "in5"
        )
        val runner = InActionGroupRunner(client)

        val result = runner.run(sourceBoxNo = 17, targetPanNo = 2)

        assertEquals(ActionGroupResult.Success, result)
        assertEquals(listOf("in5:0", "in6:0", "in7:2"), client.calls)
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
        private val runningAction: String? = null
    ) : AtomicActionClient, AtomicActionStateReader {
        val calls = mutableListOf<String>()

        override suspend fun execute(
            spec: AtomicActionSpec,
            infoValue: Int,
            onCommandDispatched: (() -> Unit)?
        ): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            onCommandDispatched?.invoke()
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }

        override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
            return AtomicReadResult.Success(
                if (spec.name == runningAction) AtomicActionCodes.RUNNING else AtomicActionCodes.IDLE
            )
        }
    }

    companion object {
        fun queueOf(vararg values: AtomicActionResult): ArrayDeque<AtomicActionResult> =
            ArrayDeque<AtomicActionResult>().apply { addAll(values.toList()) }
    }
}
