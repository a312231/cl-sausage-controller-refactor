package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSupplementCloseoutCoordinatorTest {

    @Test
    fun close_shouldRecoverPersistedSessionAfterRestartWithoutInMemoryProofOrResourceOwner() {
        val key = GuardedSupplementCloseoutKey("in:8:14", 7L, "in2")
        val store = FakeSessionStore(session(key))
        val resources = ResourceLocks()
        var episodeClosed = false
        val coordinator = coordinator(store, resources) { episodeClosed = true; true }

        val result = coordinator.close(key) { expected ->
            store.clear()
            MechanicalMoveReleaseResult.Released
        }

        assertTrue(result is GuardedSupplementCloseoutResult.Closed)
        assertNull(store.load())
        assertTrue(episodeClosed)
    }

    @Test
    fun close_shouldRecoverOrphanEpisodeWhenMatchingSessionWasAlreadyCleared() {
        val key = GuardedSupplementCloseoutKey("in:8:14", 7L, "in2")
        val store = FakeSessionStore(null)
        val resources = ResourceLocks()
        var releaseCalled = false
        val coordinator = coordinator(store, resources) { true }

        val result = coordinator.close(key) {
            releaseCalled = true
            MechanicalMoveReleaseResult.Released
        }

        assertTrue(result is GuardedSupplementCloseoutResult.Closed)
        assertTrue(!releaseCalled)
    }

    @Test
    fun close_shouldKeepResourceOwnerWhenClaimSessionPersistenceFails() {
        val key = GuardedSupplementCloseoutKey("in:8:14", 7L, "in2")
        val store = FakeSessionStore(session(key))
        val resources = ResourceLocks()
        assertTrue(resources.tryAcquire(ResourceMask.bottom or ResourceMask.lift or ResourceMask.above, key.sessionId))
        val coordinator = coordinator(store, resources) { true }

        val result = coordinator.close(key) {
            MechanicalMoveReleaseResult.Failed("session clear failed")
        }

        assertTrue(result is GuardedSupplementCloseoutResult.Rejected)
        assertNotNull(store.load())
        assertEquals(ResourceMask.bottom or ResourceMask.lift or ResourceMask.above, resources.ownedMask(key.sessionId))
    }

    @Test
    fun close_shouldClearSessionThenResourceThenEpisode() {
        val key = GuardedSupplementCloseoutKey("in:8:14", 7L, "in2")
        val store = FakeSessionStore(session(key))
        val resources = ResourceLocks()
        assertTrue(resources.tryAcquire(ResourceMask.bottom or ResourceMask.lift or ResourceMask.above, key.sessionId))
        val events = mutableListOf<String>()
        val coordinator = coordinator(store, resources) {
            assertNull("episode 关闭前必须已释放资源 owner", resources.ownedMask(key.sessionId))
            events += "episode"
            true
        }

        val result = coordinator.close(key) {
            events += "claim"
            store.clear()
            MechanicalMoveReleaseResult.Released
        }

        assertTrue(result is GuardedSupplementCloseoutResult.Closed)
        assertNull(resources.ownedMask(key.sessionId))
        assertEquals(listOf("claim", "episode"), events)
    }

    @Test
    fun close_shouldReturnRejectedWhenEpisodeDurableCloseThrowsSoRetryCanHandleOrphan() {
        val key = GuardedSupplementCloseoutKey("in:8:14", 7L, "in2")
        val store = FakeSessionStore(session(key))
        val resources = ResourceLocks()
        assertTrue(resources.tryAcquire(ResourceMask.bottom or ResourceMask.lift or ResourceMask.above, key.sessionId))
        val coordinator = coordinator(store, resources) { error("disk failed") }

        val result = coordinator.close(key) {
            store.clear()
            MechanicalMoveReleaseResult.Released
        }

        assertTrue(result is GuardedSupplementCloseoutResult.Rejected)
        assertNull(store.load())
        assertNull(resources.ownedMask(key.sessionId))
    }

    private fun coordinator(
        store: ActionGroupSessionStore,
        resources: ResourceLocks,
        closeEpisode: (GuardedSupplementCloseoutKey) -> Boolean
    ) = GuardedSupplementCloseoutCoordinator(
        transitionLock = Any(),
        sessionStore = store,
        resourceLocks = resources,
        moveStatusProvider = { 0 },
        guardedEpisodeExists = { true },
        closeEpisode = closeEpisode
    )

    private fun session(key: GuardedSupplementCloseoutKey) = ActionGroupSession(
        sessionId = key.sessionId,
        type = ActionGroupSessionType.IN_SUPPLEMENT,
        sourceNo = 8,
        targetNo = 14,
        currentAction = key.actionName,
        resourceMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above,
        prefetch = false,
        mechanicalClaimState = MechanicalClaimState.CLAIMED,
        mechanicalClaimRevision = key.claimRevision
    )

    private class FakeSessionStore(private var session: ActionGroupSession?) : ActionGroupSessionStore {
        override fun load(): ActionGroupSession? = session
        override fun save(session: ActionGroupSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }
}
