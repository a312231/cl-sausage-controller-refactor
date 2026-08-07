package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDeliveryMoveClaimTest {

    @Test
    fun claimOrderDeliveryMove_shouldReuseSameOrderWithoutBumpingRevision() {
        val fixture = fixture()

        val first = claimOrderDeliveryMove(
            orderNo = "CL1",
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore
        ) { true }
        val restartedStore = fixture.newStore()
        val second = claimOrderDeliveryMove(
            orderNo = "CL1",
            transitionLock = fixture.transitionLock,
            claimStore = restartedStore,
            moveStore = fixture.moveStore
        ) { true }

        assertEquals(first, second)
        assertEquals(2, fixture.moveStore.status)
    }

    @Test
    fun claimOrderDeliveryMove_shouldRejectDifferentOrder() {
        val fixture = fixture()
        assertTrue(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true } != null
        )

        val rejected = claimOrderDeliveryMove(
            orderNo = "CL2",
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore
        ) { true }

        assertNull(rejected)
        assertEquals("order:CL1", fixture.claimStore.load()?.owner)
        assertEquals(2, fixture.moveStore.status)
    }

    @Test
    fun releaseOrderDeliveryMove_shouldRequireExactOwnerRevisionAndSafeGate() {
        val fixture = fixture()
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        assertTrue(!releaseOrderDeliveryMove(claim, fixture.transitionLock, fixture.claimStore, fixture.moveStore) { false })
        assertEquals(2, fixture.moveStore.status)
        fixture.claimStore.saveClaimed(claim.owner)
        assertTrue(!releaseOrderDeliveryMove(claim, fixture.transitionLock, fixture.claimStore, fixture.moveStore) { true })
        assertEquals(2, fixture.moveStore.status)
    }

    @Test
    fun restoreClaimedOrderMoveStatus_shouldRecoverCrashWindowAndKeepOwner() {
        val fixture = fixture()
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        // 模拟 releaseOwned 已写 0、尚未来得及清 owner 的进程崩溃。
        fixture.moveStore.status = 0

        val recovery = restoreClaimedOrderMoveStatusIfNeeded(
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore
        )

        assertEquals(OrderDeliveryClaimRecovery.RESTORED, recovery)
        assertEquals(2, fixture.moveStore.status)
        assertEquals(claim.owner, fixture.claimStore.load()?.owner)
        assertEquals(claim.revision, fixture.claimStore.load()?.revision)
    }

    @Test
    fun restoreClaimedOrderMoveStatus_shouldFailClosedWhenDurableRestoreFails() {
        val fixture = fixture()
        claimOrderDeliveryMove(
            orderNo = "CL1",
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore
        ) { true }
        fixture.moveStore.status = 0
        fixture.moveStore.failOnSaveStatus = 2

        val recovery = restoreClaimedOrderMoveStatusIfNeeded(
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore
        )

        assertEquals(OrderDeliveryClaimRecovery.FAILED, recovery)
        assertEquals(0, fixture.moveStore.status)
        assertEquals("order:CL1", fixture.claimStore.load()?.owner)
    }

    private fun fixture(moveStore: FakeMoveStore = FakeMoveStore()): Fixture {
        var raw: String? = null
        val newStore = {
            OrderDeliveryClaimStore(
                readRaw = { raw },
                writeRaw = { value -> raw = value; true },
                nowMs = { 1_000L }
            )
        }
        return Fixture(Any(), newStore(), moveStore, newStore)
    }

    private data class Fixture(
        val transitionLock: Any,
        val claimStore: OrderDeliveryClaimStore,
        val moveStore: FakeMoveStore,
        val newStore: () -> OrderDeliveryClaimStore
    )

    private class FakeMoveStore(var status: Int = 0) : MechanicalMoveStatusStore {
        var failOnSaveStatus: Int? = null

        override fun load(): Int = status

        override fun saveDurable(moveStatus: Int) {
            if (moveStatus == failOnSaveStatus) {
                throw IllegalStateException("move save failed")
            }
            status = moveStatus
        }
    }
}
