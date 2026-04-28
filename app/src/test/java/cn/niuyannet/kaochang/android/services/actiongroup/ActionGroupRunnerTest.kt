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

        override suspend fun execute(spec: AtomicActionSpec, infoValue: Int): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
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
