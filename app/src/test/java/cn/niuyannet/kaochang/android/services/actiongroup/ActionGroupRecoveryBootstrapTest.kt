package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupRecoveryBootstrapTest {

    @Test
    fun recover_shouldEnterMaintenanceWhenSessionExistsButAllRegistersIdle() = runBlocking {
        val store = MemorySessionStore(
            ActionGroupSession(
                sessionId = "old",
                type = ActionGroupSessionType.IN_SUPPLEMENT,
                sourceNo = 1,
                targetNo = 2,
                resourceMask = ResourceMask.bottom
            )
        )
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.bottom, "old")

        val result = ActionGroupRecoveryBootstrap(FakeClient(), store, locks).recover()

        assertTrue(result is ActionGroupRecoveryResult.MaintenanceRequired)
        assertTrue((result as ActionGroupRecoveryResult.MaintenanceRequired).reason.contains("疑似下位机复位"))
        assertEquals("old", store.session?.sessionId)
        assertTrue(!locks.isIdle())
    }

    @Test
    fun recover_shouldClearStaleOutSellSessionWhenAllRegistersIdle() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "stale-out",
            type = ActionGroupSessionType.OUT_SELL,
            sourceNo = 1,
            resourceMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift,
            orderContext = ActionGroupOrderContext(
                orderNo = "CL1",
                itemId = 1886,
                productId = 101,
                tasteCode = "B"
            )
        )
        val store = MemorySessionStore(session)
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.above, "stale-out")

        val result = ActionGroupRecoveryBootstrap(FakeClient(), store, locks).recover()

        assertTrue(result is ActionGroupRecoveryResult.StaleSessionCleared)
        assertEquals(session, (result as ActionGroupRecoveryResult.StaleSessionCleared).session)
        assertNull(store.session)
        assertTrue(locks.isIdle())
        assertTrue(result.reason.contains("出餐动作组旧会话"))
    }

    @Test
    fun recover_shouldClearStaleManualOutSessionWhenAllRegistersIdle() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "stale-manual-out",
            type = ActionGroupSessionType.OUT_MANUAL,
            sourceNo = 3,
            resourceMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
        )
        val store = MemorySessionStore(session)
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.gate, "stale-manual-out")

        val result = ActionGroupRecoveryBootstrap(FakeClient(), store, locks).recover()

        assertTrue(result is ActionGroupRecoveryResult.StaleSessionCleared)
        assertEquals(session, (result as ActionGroupRecoveryResult.StaleSessionCleared).session)
        assertNull(store.session)
        assertTrue(locks.isIdle())
        assertTrue(result.reason.contains("出餐动作组旧会话"))
    }

    @Test
    fun recover_shouldReturnIdleWhenAllRegistersIdleAndNoSessionExists() = runBlocking {
        val store = MemorySessionStore(null)
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.bottom, "stale-lock")

        val result = ActionGroupRecoveryBootstrap(FakeClient(), store, locks).recover()

        assertEquals(ActionGroupRecoveryResult.Idle, result)
        assertEquals(null, store.session)
        assertTrue(locks.isIdle())
    }

    @Test
    fun recover_shouldTakeOverRunningActionWhenSessionExistsAndKeepSessionUntilCallerHandlesResult() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "in-session",
            type = ActionGroupSessionType.IN_SUPPLEMENT,
            sourceNo = 17,
            targetNo = 2,
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        )
        val store = MemorySessionStore(session)
        val client = FakeClient(
            states = mutableMapOf("in5" to AtomicActionCodes.RUNNING),
            results = ArrayDeque(
                listOf(
                    AtomicActionResult.Success,
                    AtomicActionResult.Success,
                    AtomicActionResult.Success
                )
            )
        )

        val result = ActionGroupRecoveryBootstrap(client, store, ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.TakeoverFinished)
        assertEquals(ActionGroupResult.Success, (result as ActionGroupRecoveryResult.TakeoverFinished).actionResult)
        assertEquals(listOf("in5:0", "in6:0", "in7:2"), client.calls)
        assertEquals(session, store.session)
    }

    @Test
    fun recover_shouldTreatAllSessionStepsCompletedAsRecoveredSuccess() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "in-completed-session",
            type = ActionGroupSessionType.IN_SUPPLEMENT,
            sourceNo = 17,
            targetNo = 2,
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        )
        val store = MemorySessionStore(session)
        val locks = ResourceLocks()
        locks.tryAcquire(session.resourceMask, session.sessionId)
        val logs = mutableListOf<String>()
        val client = FakeClient(
            states = mutableMapOf(
                "in1" to COMPLETED,
                "in2" to COMPLETED,
                "in3" to COMPLETED,
                "in4" to COMPLETED,
                "in5" to COMPLETED,
                "in6" to COMPLETED,
                "in7" to COMPLETED
            )
        )

        val result = ActionGroupRecoveryBootstrap(
            client = client,
            sessionStore = store,
            resourceLocks = locks,
            logger = { logs += it }
        ).recover()

        assertTrue(result is ActionGroupRecoveryResult.TakeoverFinished)
        assertEquals(ActionGroupResult.Success, (result as ActionGroupRecoveryResult.TakeoverFinished).actionResult)
        assertTrue(client.calls.isEmpty())
        assertTrue(locks.isIdle())
        assertTrue(logs.any { it.contains("按结果寄存器=0x0002（完成）推断本会话已完成") })
        assertEquals(session, store.session)
    }

    @Test
    fun recover_shouldResumeFromCompletedPrefixWhenSessionExists() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "in-prefix-session",
            type = ActionGroupSessionType.IN_SUPPLEMENT,
            sourceNo = 17,
            targetNo = 2,
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        )
        val store = MemorySessionStore(session)
        val client = FakeClient(
            states = mutableMapOf(
                "in1" to COMPLETED,
                "in2" to COMPLETED
            ),
            results = ArrayDeque(
                listOf(
                    AtomicActionResult.Success,
                    AtomicActionResult.Success,
                    AtomicActionResult.Success,
                    AtomicActionResult.Success,
                    AtomicActionResult.Success
                )
            )
        )

        val result = ActionGroupRecoveryBootstrap(client, store, ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.TakeoverFinished)
        assertEquals(ActionGroupResult.Success, (result as ActionGroupRecoveryResult.TakeoverFinished).actionResult)
        assertEquals(listOf("in3:0", "in4:0", "in5:0", "in6:0", "in7:2"), client.calls)
    }

    @Test
    fun recover_shouldEnterMaintenanceWhenCompletedStateIsNotContiguousPrefix() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "in-non-contiguous",
            type = ActionGroupSessionType.IN_SUPPLEMENT,
            sourceNo = 17,
            targetNo = 2,
            resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above
        )
        val client = FakeClient(
            states = mutableMapOf(
                "in1" to COMPLETED,
                "in3" to COMPLETED
            )
        )

        val result = ActionGroupRecoveryBootstrap(
            client,
            MemorySessionStore(session),
            ResourceLocks()
        ).recover()

        assertTrue(result is ActionGroupRecoveryResult.MaintenanceRequired)
        assertTrue((result as ActionGroupRecoveryResult.MaintenanceRequired).reason.contains("非连续完成态"))
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun recover_shouldTakeOverManualOutSessionWithoutOrderContext() = runBlocking {
        val session = ActionGroupSession(
            sessionId = "out-manual-session",
            type = ActionGroupSessionType.OUT_MANUAL,
            sourceNo = 3,
            resourceMask = ResourceMask.above or ResourceMask.gate or ResourceMask.stick or ResourceMask.lift
        )
        val store = MemorySessionStore(session)
        val client = FakeClient(
            states = mutableMapOf("out3" to AtomicActionCodes.RUNNING),
            results = ArrayDeque(
                listOf(
                    AtomicActionResult.Success,
                    AtomicActionResult.Success,
                    AtomicActionResult.Success
                )
            )
        )

        val result = ActionGroupRecoveryBootstrap(client, store, ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.TakeoverFinished)
        assertEquals(ActionGroupResult.Success, (result as ActionGroupRecoveryResult.TakeoverFinished).actionResult)
        assertEquals(listOf("out3:0", "out4:0", "out5:0"), client.calls)
        assertEquals(session, store.session)
    }

    @Test
    fun recover_shouldEnterMaintenanceWhenRunningActionHasNoSession() = runBlocking {
        val client = FakeClient(states = mutableMapOf("out4" to AtomicActionCodes.RUNNING))

        val result = ActionGroupRecoveryBootstrap(client, MemorySessionStore(null), ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.MaintenanceRequired)
        assertTrue((result as ActionGroupRecoveryResult.MaintenanceRequired).reason.contains("没有本地会话"))
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun recover_shouldEnterMaintenanceWhenLegacyErrorCodeExists() = runBlocking {
        val client = FakeClient(states = mutableMapOf("out4" to AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED))

        val result = ActionGroupRecoveryBootstrap(client, MemorySessionStore(null), ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.MaintenanceRequired)
        assertTrue((result as ActionGroupRecoveryResult.MaintenanceRequired).reason.contains("遗留错误"))
    }

    private class MemorySessionStore(var session: ActionGroupSession?) : ActionGroupSessionStore {
        override fun load(): ActionGroupSession? = session

        override fun save(session: ActionGroupSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private class FakeClient(
        private val states: MutableMap<String, Int> = mutableMapOf(),
        private val results: ArrayDeque<AtomicActionResult> = ArrayDeque()
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
    }
}
