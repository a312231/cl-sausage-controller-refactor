package cn.niuyannet.kaochang.android.services.actiongroup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupRecoveryBootstrapTest {

    @Test
    fun recover_shouldClearSessionAndLocksWhenAllRegistersIdle() = runBlocking {
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
    fun recover_shouldEnterMaintenanceWhenRunningActionHasNoSession() = runBlocking {
        val client = FakeClient(states = mutableMapOf("out4" to AtomicActionCodes.RUNNING))

        val result = ActionGroupRecoveryBootstrap(client, MemorySessionStore(null), ResourceLocks()).recover()

        assertTrue(result is ActionGroupRecoveryResult.MaintenanceRequired)
        assertTrue((result as ActionGroupRecoveryResult.MaintenanceRequired).reason.contains("没有本地会话"))
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun recover_shouldEnterMaintenanceWhenLegacyErrorCodeExists() = runBlocking {
        val client = FakeClient(states = mutableMapOf("out5" to AtomicActionCodes.CLIENT_TAKE_TIMEOUT_DISCARDED))

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

        override suspend fun execute(spec: AtomicActionSpec, infoValue: Int): AtomicActionResult {
            calls += "${spec.name}:$infoValue"
            states[spec.name] = AtomicActionCodes.IDLE
            return results.removeFirstOrNull() ?: AtomicActionResult.Success
        }

        override suspend fun readResult(spec: AtomicActionSpec): AtomicReadResult {
            return AtomicReadResult.Success(states[spec.name] ?: AtomicActionCodes.IDLE)
        }
    }
}
