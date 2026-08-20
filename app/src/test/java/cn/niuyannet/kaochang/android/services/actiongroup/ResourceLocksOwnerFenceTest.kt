package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceLocksOwnerFenceTest {

    @Test
    fun ownerMasksSnapshot_returnsOneStableCopyWithoutExposingInternalOwners() {
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.bottom, "session-a")
        locks.tryAcquire(ResourceMask.above, "session-b")

        val snapshot = locks.ownerMasksSnapshot()
        locks.release("session-a")

        assertEquals(
            mapOf("session-a" to ResourceMask.bottom, "session-b" to ResourceMask.above),
            snapshot
        )
        assertEquals(mapOf("session-b" to ResourceMask.above), locks.ownerMasksSnapshot())
    }

    @Test
    fun ownerIds_returnsExactOwnersWithoutClearingThem() {
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.bottom, "session-a")
        locks.tryAcquire(ResourceMask.above, "session-b")

        assertEquals(setOf("session-a", "session-b"), locks.ownerIds())
        assertEquals(ResourceMask.bottom, locks.ownedMask("session-a"))
        assertEquals(null, locks.ownedMask("missing"))
        assertEquals(ResourceMask.bottom or ResourceMask.above, locks.heldMask)
    }

    @Test
    fun releaseIfOwned_shouldUseOwnerAndMaskAsOneAtomicFence() {
        val locks = ResourceLocks()
        locks.tryAcquire(ResourceMask.bottom, "session-a")

        assertEquals(
            ResourceReleaseResult.MASK_MISMATCH,
            locks.releaseIfOwned("session-a", ResourceMask.above)
        )
        assertEquals(ResourceMask.bottom, locks.ownedMask("session-a"))
        assertEquals(
            ResourceReleaseResult.RELEASED,
            locks.releaseIfOwned("session-a", ResourceMask.bottom)
        )
        assertEquals(
            ResourceReleaseResult.ALREADY_ABSENT,
            locks.releaseIfOwned("session-a", ResourceMask.bottom)
        )
        assertTrue(locks.isIdle())
    }
}
