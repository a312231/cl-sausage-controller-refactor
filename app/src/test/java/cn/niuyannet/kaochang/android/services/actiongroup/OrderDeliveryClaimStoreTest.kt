package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDeliveryClaimStoreTest {

    @Test
    fun load_shouldRejectStructurallyInvalidClaim() {
        val invalidClaims = listOf(
            """{"orderNo":"","owner":"order:CL1","state":"CLAIMED","revision":2,"createdAtMs":1000}""",
            """{"orderNo":"CL1","owner":"order:OTHER","state":"CLAIMED","revision":2,"createdAtMs":1000}""",
            """{"orderNo":"CL1","owner":"order:CL1","revision":2,"createdAtMs":1000}""",
            """{"orderNo":"CL1","owner":"order:CL1","state":"CLAIMED","revision":0,"createdAtMs":1000}""",
            """{"orderNo":"CL1","owner":"order:CL1","state":"CLAIMED","revision":2,"createdAtMs":0}"""
        )

        invalidClaims.forEach { raw ->
            val store = OrderDeliveryClaimStore(readRaw = { raw }, writeRaw = { true })
            try {
                store.load()
                fail("结构损坏的订单 claim 必须 fail closed：$raw")
            } catch (_: IllegalStateException) {
                // expected
            }
        }
    }

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
