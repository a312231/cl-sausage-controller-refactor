package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceLocksOwnerFenceTest {

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
}
