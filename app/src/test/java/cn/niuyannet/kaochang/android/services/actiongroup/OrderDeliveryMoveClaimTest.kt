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
    fun restoreClaimedOrderMoveStatus_shouldKeepClaimWithoutResurrectingMoveStatus() {
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
        val saveCountBeforeRecovery = fixture.moveStore.saveCount

        val recovery = restoreClaimedOrderMoveStatusIfNeeded(
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore
        )

        assertEquals(OrderDeliveryClaimRecovery.CLAIM_PRESENT, recovery)
        assertEquals(0, fixture.moveStore.status)
        assertEquals(saveCountBeforeRecovery, fixture.moveStore.saveCount)
        assertEquals(claim.owner, fixture.claimStore.load()?.owner)
        assertEquals(claim.revision, fixture.claimStore.load()?.revision)
    }

    @Test
    fun closeOrderDeliveryMoveForMaintenance_shouldClearOnlyExactClaim() {
        val fixture = fixture()
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        val staleClaim = claim.copy(revision = claim.revision + 1L)
        assertTrue(
            !closeOrderDeliveryMoveForMaintenance(
                claim = staleClaim,
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore,
                commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
            ) { true }
        )
        assertEquals(2, fixture.moveStore.status)

        assertTrue(
            closeOrderDeliveryMoveForMaintenance(
                claim = claim,
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore,
                commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
            ) { true }
        )
        assertEquals(0, fixture.moveStore.status)
        assertNull(fixture.claimStore.load())
        assertEquals(1, fixture.atomicCloseoutCount())
        assertEquals(1, fixture.moveStore.saveCount)
    }

    @Test
    fun closeOrderDeliveryMoveForMaintenance_shouldRejectSameOwnerRevisionWithDifferentCreatedAt() {
        val fixture = fixture()
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )
        val replacedFingerprint = claim.copy(createdAtMs = claim.createdAtMs + 1L)

        val closed = closeOrderDeliveryMoveForMaintenance(
            claim = replacedFingerprint,
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore,
            commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
        ) { true }

        assertTrue(!closed)
        assertEquals(2, fixture.moveStore.status)
        assertEquals(claim, fixture.claimStore.load())
        assertEquals(0, fixture.atomicCloseoutCount())
    }

    @Test
    fun closeOrderDeliveryMoveForMaintenance_shouldRespectSafetyGate() {
        val fixture = fixture()
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        val closed = closeOrderDeliveryMoveForMaintenance(
            claim = claim,
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore,
            commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
        ) { false }

        assertTrue(!closed)
        assertEquals(2, fixture.moveStore.status)
        assertEquals("order:CL1", fixture.claimStore.load()?.owner)
        assertEquals(0, fixture.atomicCloseoutCount())
    }

    @Test
    fun closeOrderDeliveryMoveForMaintenance_shouldKeepClaimAndMoveStatusWhenAtomicCommitFails() {
        val fixture = fixture(atomicCloseoutSucceeds = false)
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        val closed = closeOrderDeliveryMoveForMaintenance(
            claim = claim,
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore,
            commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
        ) { true }

        assertTrue(!closed)
        assertEquals(2, fixture.moveStore.status)
        assertEquals(claim, fixture.claimStore.load())
        assertEquals(1, fixture.atomicCloseoutCount())
    }

    @Test
    fun closeOrderDeliveryMoveForMaintenance_shouldKeepClaimAndMoveStatusWhenAtomicCommitThrows() {
        val fixture = fixture(atomicCloseoutThrows = true)
        val claim = requireNotNull(
            claimOrderDeliveryMove(
                orderNo = "CL1",
                transitionLock = fixture.transitionLock,
                claimStore = fixture.claimStore,
                moveStore = fixture.moveStore
            ) { true }
        )

        val closed = closeOrderDeliveryMoveForMaintenance(
            claim = claim,
            transitionLock = fixture.transitionLock,
            claimStore = fixture.claimStore,
            moveStore = fixture.moveStore,
            commitIdleAndClearClaim = fixture.commitIdleAndClearClaim
        ) { true }

        assertTrue(!closed)
        assertEquals(2, fixture.moveStore.status)
        assertEquals(claim, fixture.claimStore.load())
        assertEquals(1, fixture.atomicCloseoutCount())
    }

    private fun fixture(
        moveStore: FakeMoveStore = FakeMoveStore(),
        atomicCloseoutSucceeds: Boolean = true,
        atomicCloseoutThrows: Boolean = false
    ): Fixture {
        var raw: String? = null
        var atomicCloseoutCount = 0
        val newStore = {
            OrderDeliveryClaimStore(
                readRaw = { raw },
                writeRaw = { value -> raw = value; true },
                nowMs = { 1_000L }
            )
        }
        val store = newStore()
        return Fixture(
            transitionLock = Any(),
            claimStore = store,
            moveStore = moveStore,
            newStore = newStore,
            commitIdleAndClearClaim = {
                atomicCloseoutCount++
                if (atomicCloseoutThrows) {
                    throw IllegalStateException("atomic closeout failed")
                }
                if (atomicCloseoutSucceeds) {
                    moveStore.status = 0
                    raw = ""
                }
                atomicCloseoutSucceeds
            },
            atomicCloseoutCount = { atomicCloseoutCount }
        )
    }

    private data class Fixture(
        val transitionLock: Any,
        val claimStore: OrderDeliveryClaimStore,
        val moveStore: FakeMoveStore,
        val newStore: () -> OrderDeliveryClaimStore,
        val commitIdleAndClearClaim: () -> Boolean,
        val atomicCloseoutCount: () -> Int
    )

    private class FakeMoveStore(var status: Int = 0) : MechanicalMoveStatusStore {
        var failOnSaveStatus: Int? = null
        var saveCount: Int = 0

        override fun load(): Int = status

        override fun saveDurable(moveStatus: Int) {
            saveCount++
            if (moveStatus == failOnSaveStatus) {
                throw IllegalStateException("move save failed")
            }
            status = moveStatus
        }
    }
}
