package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class MechanicalMoveClaimCoordinatorTest {

    @Test
    fun claim_shouldAllowOnlyOneOfConcurrentInAndOutOwners() {
        val owners = FakeOwnerStore()
        val moves = FakeMoveStore(0)
        val coordinator = coordinator(owners, moves)
        val results = Collections.synchronizedList(mutableListOf<MechanicalMoveClaimResult>())

        val inThread = Thread {
            results += coordinator.claim("in-1", setOf(0), 1)
        }
        val outThread = Thread {
            results += coordinator.claim("out-1", setOf(0), 2)
        }
        inThread.start()
        outThread.start()
        inThread.join()
        outThread.join()

        assertEquals(1, results.count { it is MechanicalMoveClaimResult.Claimed })
        assertEquals(1, results.count { it is MechanicalMoveClaimResult.Rejected })
        assertTrue(owners.owner in setOf("in-1", "out-1"))
        assertEquals(if (owners.owner == "in-1") 1 else 2, moves.status)
        assertEquals(MechanicalClaimState.CLAIMED, owners.state)
    }

    @Test
    fun release_shouldNotClearNewerOwnerFromLateCallback() {
        val owners = FakeOwnerStore(owner = "out-new", state = MechanicalClaimState.CLAIMED)
        val moves = FakeMoveStore(2)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.release("in-old", expectedMoveStatus = 1, idleMoveStatus = 0)

        assertTrue(result is MechanicalMoveReleaseResult.Rejected)
        assertEquals("out-new", owners.owner)
        assertEquals(2, moves.status)
    }

    @Test
    fun claim_shouldRollbackPreparedOwnerWhenMoveDurableSaveFails() {
        val owners = FakeOwnerStore()
        val moves = FakeMoveStore(0, failOnSaveStatus = 1)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.claim("in-1", setOf(0), 1)

        assertTrue(result is MechanicalMoveClaimResult.Failed)
        assertNull(owners.owner)
        assertEquals(0, moves.status)
    }

    @Test
    fun claim_shouldEvaluateRuntimeGateInsideTransitionLockBeforePreparingOwner() {
        val owners = FakeOwnerStore()
        val moves = FakeMoveStore(0)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.claim("in-1", setOf(0), 1) { false }

        assertTrue(result is MechanicalMoveClaimResult.Rejected)
        assertNull(owners.owner)
        assertEquals(0, moves.status)
    }

    @Test
    fun release_shouldRestoreBusyWhenOwnerCleanupFails() {
        val owners = FakeOwnerStore(
            owner = "in-1",
            state = MechanicalClaimState.CLAIMED,
            failClear = true
        )
        val moves = FakeMoveStore(1)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.release("in-1", expectedMoveStatus = 1, idleMoveStatus = 0)

        assertTrue(result is MechanicalMoveReleaseResult.Failed)
        assertEquals("in-1", owners.owner)
        assertEquals(1, moves.status)
    }

    @Test
    fun abandonPrepared_shouldClearOwnerAndMoveForIdleColdStart() {
        val owners = FakeOwnerStore(owner = "in-prepared", state = MechanicalClaimState.PREPARED)
        val moves = FakeMoveStore(1)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.abandonPrepared("in-prepared", setOf(0, 1, 2), 0)

        assertTrue(result is MechanicalMoveReleaseResult.Released)
        assertNull(owners.owner)
        assertEquals(0, moves.status)
    }

    @Test
    fun abandonPrepared_shouldRejectClaimedOrDifferentOwner() {
        val owners = FakeOwnerStore(owner = "in-claimed", state = MechanicalClaimState.CLAIMED)
        val moves = FakeMoveStore(1)
        val coordinator = coordinator(owners, moves)

        assertTrue(
            coordinator.abandonPrepared("in-claimed", setOf(0, 1, 2), 0) is
                MechanicalMoveReleaseResult.Rejected
        )
        assertTrue(
            coordinator.abandonPrepared("in-old", setOf(0, 1, 2), 0) is
                MechanicalMoveReleaseResult.Rejected
        )
        assertEquals("in-claimed", owners.owner)
        assertEquals(1, moves.status)
    }

    @Test
    fun claim_shouldLeavePreparedEvidenceWhenClaimedCheckpointSaveFailsAndRollbackCannotClear() {
        val owners = FakeOwnerStore(failMarkClaimed = true, failClear = true)
        val moves = FakeMoveStore(0)
        val coordinator = coordinator(owners, moves)

        val result = coordinator.claim("in-1", setOf(0), 1)

        assertTrue(result is MechanicalMoveClaimResult.Failed)
        assertEquals("in-1", owners.owner)
        assertEquals(MechanicalClaimState.PREPARED, owners.state)
        assertEquals(1, moves.status)
    }

    private fun coordinator(
        owners: FakeOwnerStore,
        moves: FakeMoveStore
    ) = MechanicalMoveClaimCoordinator(
        transitionLock = Any(),
        ownerStore = owners,
        moveStore = moves
    )

    private class FakeOwnerStore(
        var owner: String? = null,
        var state: MechanicalClaimState? = null,
        private val failMarkClaimed: Boolean = false,
        var failClear: Boolean = false
    ) : MechanicalClaimOwnerStore {
        private var revision = 0L

        override fun snapshot(): MechanicalClaimOwnerSnapshot? = owner?.let {
            MechanicalClaimOwnerSnapshot(it, state, revision)
        }

        override fun savePrepared(owner: String) {
            this.owner = owner
            state = MechanicalClaimState.PREPARED
            revision += 1
        }

        override fun saveClaimed(owner: String) {
            check(!failMarkClaimed) { "claimed save failed" }
            check(this.owner == owner)
            state = MechanicalClaimState.CLAIMED
            revision += 1
        }

        override fun clearIfOwner(owner: String): Boolean {
            check(!failClear) { "clear failed" }
            if (this.owner != owner) return false
            this.owner = null
            state = null
            return true
        }
    }

    private class FakeMoveStore(
        var status: Int,
        private val failOnSaveStatus: Int? = null
    ) : MechanicalMoveStatusStore {
        override fun load(): Int = status

        override fun saveDurable(moveStatus: Int) {
            if (moveStatus == failOnSaveStatus) {
                throw IllegalStateException("move save failed")
            }
            status = moveStatus
        }
    }
}
