package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupplementMechanicalCloseoutCoordinatorTest {
    private val supplementMask = ResourceMask.bottom or ResourceMask.lift or ResourceMask.above

    @Test
    fun close_shouldReleaseSupplementAndSetIdleWhenNoOrderClaimExists() {
        val fixture = fixture(moveStatus = 1)

        val result = fixture.coordinator.close(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedIdle, result)
        assertNull(fixture.supplementOwner.snapshot())
        assertEquals(0, fixture.moveStore.status)
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldAcceptIdleEvidenceWithoutWritingState() {
        val fixture = fixture(
            moveStatus = 0,
            supplementOwnerPresent = false,
            acquireResource = false,
            failMoveSaveStatuses = setOf(0, 1, 2)
        )

        val result = fixture.coordinator.closeOrConfirmAlreadyClosed(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedIdle, result)
        assertNull(fixture.supplementOwner.snapshot())
        assertEquals(0, fixture.moveStore.status)
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldAcceptRealClearedSessionShapeButRejectRevisionChange() {
        val clearedSession = fixture(
            moveStatus = 0,
            supplementOwnerState = null,
            acquireResource = false
        )

        assertEquals(
            SupplementMechanicalCloseoutResult.ClosedIdle,
            clearedSession.coordinator.closeOrConfirmAlreadyClosed(clearedSession.request)
        )

        val revisionChanged = fixture(
            moveStatus = 0,
            supplementOwnerState = null,
            acquireResource = false
        ).copyRequest(revision = 99L)
        assertTrue(
            revisionChanged.coordinator.closeOrConfirmAlreadyClosed(revisionChanged.request) is
                SupplementMechanicalCloseoutResult.Rejected
        )
        assertEquals(0, revisionChanged.moveStore.status)
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldClearClaimedSessionAfterResourcesAndMoveAlreadySettled() {
        val fixture = fixture(
            moveStatus = 0,
            supplementOwnerState = MechanicalClaimState.CLAIMED,
            acquireResource = false,
            failMoveSaveStatuses = setOf(0, 1, 2)
        )

        val result = fixture.coordinator.closeOrConfirmAlreadyClosed(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedIdle, result)
        assertNull(fixture.supplementOwner.snapshot())
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
        assertEquals(0, fixture.moveStore.status)
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldClearClaimedSessionAndKeepExistingOrderAfterResourcesSettled() {
        val fixture = fixture(
            moveStatus = 2,
            orderState = MechanicalClaimState.CLAIMED,
            supplementOwnerState = MechanicalClaimState.CLAIMED,
            acquireResource = false,
            failMoveSaveStatuses = setOf(0, 1, 2)
        )

        val result = fixture.coordinator.closeOrConfirmAlreadyClosed(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedKeepingOrder, result)
        assertNull(fixture.supplementOwner.snapshot())
        assertEquals("order:existing", fixture.orderOwner.snapshot()?.owner)
        assertEquals(MechanicalClaimState.CLAIMED, fixture.orderOwner.snapshot()?.state)
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
        assertEquals(2, fixture.moveStore.status)
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldKeepExistingOrderEvidence() {
        val fixture = fixture(
            moveStatus = 2,
            orderState = MechanicalClaimState.CLAIMED,
            supplementOwnerPresent = false,
            acquireResource = false,
            failMoveSaveStatuses = setOf(0, 1, 2)
        )

        val result = fixture.coordinator.closeOrConfirmAlreadyClosed(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedKeepingOrder, result)
        assertEquals(2, fixture.moveStore.status)
        assertEquals("order:existing", fixture.orderOwner.snapshot()?.owner)
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldRejectMissingOwnerWhenResourceOrMoveEvidenceRemains() {
        val resourceRemains = fixture(
            moveStatus = 0,
            supplementOwnerPresent = false,
            acquireResource = true
        )
        assertTrue(
            resourceRemains.coordinator.closeOrConfirmAlreadyClosed(resourceRemains.request) is
                SupplementMechanicalCloseoutResult.Rejected
        )
        assertEquals(supplementMask, resourceRemains.resources.ownedMask(resourceRemains.request.owner))

        val busyWithoutOwner = fixture(
            moveStatus = 1,
            supplementOwnerPresent = false,
            acquireResource = false
        )
        assertTrue(
            busyWithoutOwner.coordinator.closeOrConfirmAlreadyClosed(busyWithoutOwner.request) is
                SupplementMechanicalCloseoutResult.Rejected
        )
        assertEquals(1, busyWithoutOwner.moveStore.status)
    }

    @Test
    fun closeOrConfirmAlreadyClosed_shouldReturnStableFailureWhenEvidenceReadFails() {
        val ownerReadFailure = fixture(
            moveStatus = 0,
            supplementOwnerPresent = false,
            acquireResource = false,
            failSupplementRead = true
        )
        assertTrue(
            ownerReadFailure.coordinator.closeOrConfirmAlreadyClosed(ownerReadFailure.request) is
                SupplementMechanicalCloseoutResult.Failed
        )

        val moveReadFailure = fixture(
            moveStatus = 0,
            supplementOwnerPresent = false,
            acquireResource = false,
            failMoveRead = true
        )
        assertTrue(
            moveReadFailure.coordinator.closeOrConfirmAlreadyClosed(moveReadFailure.request) is
                SupplementMechanicalCloseoutResult.Failed
        )
    }

    @Test
    fun close_shouldReleaseOnlySupplementAndKeepMoveStatusWhenOrderAlreadyClaimed() {
        val fixture = fixture(moveStatus = 2, orderState = MechanicalClaimState.CLAIMED)

        val result = fixture.coordinator.close(fixture.request)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedKeepingOrder, result)
        assertNull(fixture.supplementOwner.snapshot())
        assertEquals(2, fixture.moveStore.status)
        assertEquals(MechanicalClaimState.CLAIMED, fixture.orderOwner.snapshot()?.state)
        assertNull(fixture.resources.ownedMask(fixture.request.owner))
    }

    @Test
    fun close_shouldUseOrderClaimCreatedBeforeItObtainsTransitionLock() {
        val fixture = fixture(moveStatus = 1)
        var result: SupplementMechanicalCloseoutResult? = null
        val worker: Thread

        synchronized(fixture.transitionLock) {
            worker = Thread { result = fixture.coordinator.close(fixture.request) }.also { it.start() }
            fixture.orderOwner.set("order:late", MechanicalClaimState.CLAIMED, revision = 7L)
            fixture.moveStore.status = 2
        }
        worker.join(2_000L)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedKeepingOrder, result)
        assertEquals(2, fixture.moveStore.status)
    }

    @Test
    fun orderClaimWaitingForTransitionLock_shouldRunAfterCloseoutWithoutBeingOverwritten() {
        val clearEntered = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        val fixture = fixture(
            moveStatus = 1,
            beforeSupplementClear = {
                clearEntered.countDown()
                check(allowClear.await(2, TimeUnit.SECONDS))
            }
        )
        var closeResult: SupplementMechanicalCloseoutResult? = null
        val closeThread = Thread { closeResult = fixture.coordinator.close(fixture.request) }
        closeThread.start()
        assertTrue(clearEntered.await(2, TimeUnit.SECONDS))

        val orderThread = Thread {
            synchronized(fixture.transitionLock) {
                fixture.orderOwner.set("order:after", MechanicalClaimState.CLAIMED, revision = 9L)
                fixture.moveStore.status = 2
            }
        }
        orderThread.start()
        allowClear.countDown()
        closeThread.join(2_000L)
        orderThread.join(2_000L)

        assertEquals(SupplementMechanicalCloseoutResult.ClosedIdle, closeResult)
        assertEquals(2, fixture.moveStore.status)
        assertEquals("order:after", fixture.orderOwner.snapshot()?.owner)
    }

    @Test
    fun close_shouldRejectPreparedOrCorruptedOrderEvidenceWithoutChangingAnything() {
        val prepared = fixture(moveStatus = 2, orderState = MechanicalClaimState.PREPARED)
        val preparedResult = prepared.coordinator.close(prepared.request)
        assertTrue(preparedResult is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(prepared, expectedMoveStatus = 2)

        val corrupted = fixture(moveStatus = 1, corruptOrderRead = true)
        val corruptedResult = corrupted.coordinator.close(corrupted.request)
        assertTrue(corruptedResult is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(corrupted, expectedMoveStatus = 1)
    }

    @Test
    fun close_shouldRejectOwnerRevisionMaskOrMoveMismatchWithoutChangingAnything() {
        val ownerMismatch = fixture(moveStatus = 1).copyRequest(owner = "in:other")
        assertTrue(ownerMismatch.coordinator.close(ownerMismatch.request) is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(ownerMismatch, 1)

        val revisionMismatch = fixture(moveStatus = 1).copyRequest(revision = 99L)
        assertTrue(revisionMismatch.coordinator.close(revisionMismatch.request) is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(revisionMismatch, 1)

        val maskMismatch = fixture(moveStatus = 1).copyRequest(resourceMask = ResourceMask.bottom)
        assertTrue(maskMismatch.coordinator.close(maskMismatch.request) is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(maskMismatch, 1)

        val moveMismatch = fixture(moveStatus = 0)
        assertTrue(moveMismatch.coordinator.close(moveMismatch.request) is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(moveMismatch, 0)
    }

    @Test
    fun close_shouldKeepEvidenceWhenMoveSaveOrOwnerClearFails() {
        val moveFailure = fixture(moveStatus = 1, failMoveSaveStatus = 0)
        assertTrue(moveFailure.coordinator.close(moveFailure.request) is SupplementMechanicalCloseoutResult.Failed)
        assertUnchanged(moveFailure, 1)

        val ownerFailure = fixture(moveStatus = 1, failSupplementClear = true)
        assertTrue(ownerFailure.coordinator.close(ownerFailure.request) is SupplementMechanicalCloseoutResult.Failed)
        assertUnchanged(ownerFailure, 1)
    }

    @Test
    fun close_shouldReportInconsistentWhenOwnerClearAndBusyRollbackBothFail() {
        val fixture = fixture(
            moveStatus = 1,
            failMoveSaveStatuses = setOf(1),
            failSupplementClear = true
        )

        val result = fixture.coordinator.close(fixture.request)

        assertTrue(result is SupplementMechanicalCloseoutResult.Inconsistent)
        assertEquals("in:session", fixture.supplementOwner.snapshot()?.owner)
        assertEquals(0, fixture.moveStore.status)
        assertEquals(supplementMask, fixture.resources.ownedMask("in:session"))
    }

    @Test
    fun close_shouldReturnFailedWhenMoveStatusReadThrows() {
        val fixture = fixture(moveStatus = 1, failMoveRead = true)

        val result = fixture.coordinator.close(fixture.request)

        assertTrue(result is SupplementMechanicalCloseoutResult.Failed)
        assertEquals("in:session", fixture.supplementOwner.snapshot()?.owner)
        assertEquals(supplementMask, fixture.resources.ownedMask("in:session"))
    }

    @Test
    fun close_shouldAllowAlreadyReleasedResourceButRejectChangedMask() {
        val alreadyAbsent = fixture(moveStatus = 1, acquireResource = false)
        assertEquals(
            SupplementMechanicalCloseoutResult.ClosedIdle,
            alreadyAbsent.coordinator.close(alreadyAbsent.request)
        )
        assertNull(alreadyAbsent.supplementOwner.snapshot())
        assertEquals(0, alreadyAbsent.moveStore.status)

        val changedMask = fixture(moveStatus = 1, acquiredMask = ResourceMask.bottom)
        assertTrue(changedMask.coordinator.close(changedMask.request) is SupplementMechanicalCloseoutResult.Rejected)
        assertUnchanged(changedMask, 1, expectedResourceMask = ResourceMask.bottom)
    }

    private fun fixture(
        moveStatus: Int,
        orderState: MechanicalClaimState? = null,
        corruptOrderRead: Boolean = false,
        failMoveSaveStatus: Int? = null,
        failMoveSaveStatuses: Set<Int> = failMoveSaveStatus?.let(::setOf) ?: emptySet(),
        failMoveRead: Boolean = false,
        failSupplementClear: Boolean = false,
        failSupplementRead: Boolean = false,
        supplementOwnerPresent: Boolean = true,
        supplementOwnerState: MechanicalClaimState? = MechanicalClaimState.CLAIMED,
        acquireResource: Boolean = true,
        acquiredMask: Int = supplementMask,
        beforeSupplementClear: () -> Unit = {}
    ): Fixture {
        val transitionLock = Any()
        val supplementOwner = FakeOwnerStore(
            owner = if (supplementOwnerPresent) "in:session" else null,
            state = if (supplementOwnerPresent) supplementOwnerState else null,
            revision = if (supplementOwnerPresent) 2L else 0L,
            failRead = failSupplementRead,
            failClear = failSupplementClear,
            beforeClear = beforeSupplementClear
        )
        val orderOwner = FakeOwnerStore(
            owner = orderState?.let { "order:existing" },
            state = orderState,
            revision = if (orderState == null) 0L else 4L,
            failRead = corruptOrderRead
        )
        val moveStore = FakeMoveStore(moveStatus, failMoveSaveStatuses, failMoveRead)
        val resources = ResourceLocks().also {
            if (acquireResource) check(it.tryAcquire(acquiredMask, "in:session"))
        }
        val request = SupplementMechanicalCloseoutRequest("in:session", 2L, supplementMask)
        return Fixture(
            transitionLock,
            supplementOwner,
            orderOwner,
            moveStore,
            resources,
            request,
            SupplementMechanicalCloseoutCoordinator(
                transitionLock,
                supplementOwner,
                orderOwner,
                moveStore,
                resources
            )
        )
    }

    private fun Fixture.copyRequest(
        owner: String = request.owner,
        revision: Long = request.revision,
        resourceMask: Int = request.resourceMask
    ): Fixture = copy(request = SupplementMechanicalCloseoutRequest(owner, revision, resourceMask))

    private fun assertUnchanged(
        fixture: Fixture,
        expectedMoveStatus: Int,
        expectedResourceMask: Int = supplementMask
    ) {
        assertEquals("in:session", fixture.supplementOwner.snapshot()?.owner)
        assertEquals(MechanicalClaimState.CLAIMED, fixture.supplementOwner.snapshot()?.state)
        assertEquals(expectedMoveStatus, fixture.moveStore.status)
        assertEquals(expectedResourceMask, fixture.resources.ownedMask("in:session"))
    }

    private data class Fixture(
        val transitionLock: Any,
        val supplementOwner: FakeOwnerStore,
        val orderOwner: FakeOwnerStore,
        val moveStore: FakeMoveStore,
        val resources: ResourceLocks,
        val request: SupplementMechanicalCloseoutRequest,
        val coordinator: SupplementMechanicalCloseoutCoordinator
    )

    private class FakeOwnerStore(
        private var owner: String? = null,
        private var state: MechanicalClaimState? = null,
        private var revision: Long = 0L,
        private val failRead: Boolean = false,
        private val failClear: Boolean = false,
        private val beforeClear: () -> Unit = {}
    ) : MechanicalClaimOwnerStore {
        override fun snapshot(): MechanicalClaimOwnerSnapshot? {
            check(!failRead) { "owner evidence corrupted" }
            return owner?.let { MechanicalClaimOwnerSnapshot(it, state, revision) }
        }

        override fun savePrepared(owner: String) = error("not used")
        override fun saveClaimed(owner: String) = error("not used")

        override fun clearIfOwner(owner: String): Boolean = clearIfOwnerAndRevision(owner, revision)

        override fun clearIfOwnerAndRevision(owner: String, revision: Long): Boolean {
            beforeClear()
            check(!failClear) { "clear failed" }
            if (this.owner != owner || this.revision != revision) return false
            this.owner = null
            state = null
            return true
        }

        fun set(owner: String, state: MechanicalClaimState, revision: Long) {
            this.owner = owner
            this.state = state
            this.revision = revision
        }
    }

    private class FakeMoveStore(
        var status: Int,
        private val failSaveStatuses: Set<Int> = emptySet(),
        private val failRead: Boolean = false
    ) : MechanicalMoveStatusStore {
        override fun load(): Int {
            check(!failRead) { "move read failed" }
            return status
        }

        override fun saveDurable(moveStatus: Int) {
            check(moveStatus !in failSaveStatuses) { "move save failed" }
            status = moveStatus
        }
    }
}
