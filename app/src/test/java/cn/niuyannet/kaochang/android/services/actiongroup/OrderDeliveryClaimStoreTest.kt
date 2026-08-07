package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDeliveryClaimStoreTest {

    @Test
    fun clearIfOwnerAndRevision_shouldRejectNewerClaim() {
        var raw: String? = null
        val store = OrderDeliveryClaimStore(
            readRaw = { raw },
            writeRaw = { value -> raw = value; true },
            nowMs = { 1_000L }
        )

        store.savePrepared("order:CL1")
        store.saveClaimed("order:CL1")
        val claimed = requireNotNull(store.load())
        store.saveClaimed("order:CL1")

        assertFalse(store.clearIfOwnerAndRevision(claimed.owner, claimed.revision))
        assertEquals(claimed.revision + 1L, store.load()?.revision)
    }

    @Test
    fun clearIfOwnerAndRevision_shouldClearOnlyExactClaim() {
        var raw: String? = null
        val store = OrderDeliveryClaimStore(
            readRaw = { raw },
            writeRaw = { value -> raw = value; true },
            nowMs = { 2_000L }
        )

        store.savePrepared("order:CL2")
        store.saveClaimed("order:CL2")
        val claimed = requireNotNull(store.load())

        assertTrue(store.clearIfOwnerAndRevision(claimed.owner, claimed.revision))
        assertNull(store.load())
    }
}
