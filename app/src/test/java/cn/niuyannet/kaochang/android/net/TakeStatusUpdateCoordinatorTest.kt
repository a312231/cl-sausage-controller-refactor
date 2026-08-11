package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.KaoPanBoxTableCommitCoordinator
import cn.niuyannet.kaochang.android.services.TakeStatusRevisionMergePolicy
import cn.niuyannet.kaochang.android.services.TakeStatusIntentRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TakeStatusUpdateCoordinatorTest {

    @Test
    fun explicitRecovery_shouldRetryOnceAfterAutomaticFailureWinsCas() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 7L))
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        val refreshGeneration = registry.observeAutomaticGeneration(21)!!
        var explicitResult: Boolean? = null

        coordinator.updateAutomaticallyIfUnchanged(box(229, 7L), refreshGeneration)
        coordinator.updateExplicitly(box(0, 7L)) { confirmed, _ -> explicitResult = confirmed }
        transport.respond(0, accepted(box(229, 8L)))
        transport.respond(1, conflict(box(229, 8L)))

        assertEquals(3, transport.requests.size)
        assertEquals(0, transport.requests[2].cmdStatusTake)
        assertEquals(8L, transport.requests[2].takeStatusRevision)

        transport.respond(2, accepted(box(0, 9L)))

        assertEquals(true, explicitResult)
        assertEquals(0, store.current.cmdStatusTake)
        assertEquals(9L, store.current.takeStatusRevision)
    }

    @Test
    fun explicitRecovery_shouldStopAfterOneConflictRetry() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 8L))
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        var result: Boolean? = null

        coordinator.updateExplicitly(box(0, 7L)) { confirmed, _ -> result = confirmed }
        transport.respond(0, conflict(box(229, 8L)))
        transport.respond(1, conflict(box(229, 9L)))

        assertEquals(2, transport.requests.size)
        assertEquals(false, result)
        assertNotNull(registry.observeAutomaticGeneration(21))
    }

    @Test
    fun persistenceFailure_shouldReleaseExplicitIntentAndReportFailure() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 7L), failOnSave = true)
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        var callbackCount = 0
        var result: Boolean? = null

        coordinator.updateExplicitly(box(0, 7L)) { confirmed, _ ->
            callbackCount += 1
            result = confirmed
        }
        transport.respond(0, accepted(box(0, 8L)))

        assertEquals(1, callbackCount)
        assertEquals(false, result)
        assertNotNull(registry.observeAutomaticGeneration(21))
    }

    @Test
    fun automaticConflict_shouldNeverRetry() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 7L))
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        val refreshGeneration = registry.observeAutomaticGeneration(21)!!

        coordinator.updateAutomaticallyIfUnchanged(box(229, 7L), refreshGeneration)
        transport.respond(0, conflict(box(0, 8L)))

        assertEquals(1, transport.requests.size)
        assertTrue(registry.isCurrent(21, 1L))
        assertFalse(store.current.cmdStatusTake == 229 && store.current.takeStatusRevision == 8L)
    }

    @Test
    fun duplicateConflictCallback_shouldClaimOnlyOneRetryAndCompleteOnce() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 8L))
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        var callbackCount = 0

        coordinator.updateExplicitly(box(0, 7L)) { _, _ -> callbackCount += 1 }
        val response = conflict(box(229, 8L))
        transport.respond(0, response)
        transport.respond(0, response)

        assertEquals(2, transport.requests.size)

        transport.respond(1, accepted(box(0, 9L)))
        transport.respond(1, accepted(box(0, 9L)))

        assertEquals(1, callbackCount)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun delayedCallback_shouldNotApplyAuthoritativeStateAfterAdmissionCloses() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 7L))
        var admissionAllowed = true
        var result: Boolean? = null
        val coordinator = TakeStatusUpdateCoordinator(
            TakeStatusIntentRegistry(),
            transport,
            store,
            admissionAllowed = { admissionAllowed }
        )

        coordinator.updateExplicitly(box(0, 7L)) { confirmed, _ -> result = confirmed }
        admissionAllowed = false
        transport.respond(0, accepted(box(0, 8L)))

        assertEquals(0, store.saveCount)
        assertEquals(false, result)
    }

    @Test
    fun conflictCallback_shouldNotRetryAfterAdmissionCloses() {
        val transport = FakeTransport()
        val store = FakeStore(box(status = 229, revision = 7L))
        var admissionAllowed = true
        var result: Boolean? = null
        val coordinator = TakeStatusUpdateCoordinator(
            TakeStatusIntentRegistry(),
            transport,
            store,
            admissionAllowed = { admissionAllowed }
        )

        coordinator.updateExplicitly(box(0, 7L)) { confirmed, _ -> result = confirmed }
        admissionAllowed = false
        transport.respond(0, conflict(box(229, 8L)))

        assertEquals(1, transport.requests.size)
        assertEquals(0, store.saveCount)
        assertEquals(false, result)
    }

    @Test
    fun closedAdmission_shouldSkipPreparedMutationAndTransport() {
        val transport = FakeTransport()
        var prepareCalled = false
        var callbackCount = 0
        val coordinator = TakeStatusUpdateCoordinator(
            TakeStatusIntentRegistry(),
            transport,
            FakeStore(box(status = 229, revision = 7L)),
            admissionAllowed = { false }
        )

        try {
            coordinator.updateExplicitlyPrepared(
                positionSn = 21,
                prepare = {
                    prepareCalled = true
                    Unit to box(0, 7L)
                }
            ) { confirmed, _ ->
                callbackCount += 1
                assertFalse(confirmed)
            }
        } catch (_: IllegalStateException) {
        }

        assertFalse(prepareCalled)
        assertEquals(0, transport.requests.size)
        assertEquals(1, callbackCount)
    }

    @Test
    fun closedAdmission_shouldSkipAutomaticTransport() {
        val transport = FakeTransport()
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(
            registry,
            transport,
            FakeStore(box(status = 229, revision = 7L)),
            admissionAllowed = { false }
        )

        val observedGeneration = registry.observeAutomaticGeneration(21)!!
        coordinator.updateAutomaticallyIfUnchanged(box(229, 7L), observedGeneration)

        assertEquals(0, transport.requests.size)
    }

    @Test
    fun currentTokenAction_shouldBlockNewExplicitIntentUntilAtomicStepCompletes() {
        val registry = TakeStatusIntentRegistry()
        val originalToken = registry.beginExplicit(21)
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val currentAction = executor.submit<Boolean> {
                registry.runIfCurrent(21, originalToken) {
                    actionEntered.countDown()
                    releaseAction.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(actionEntered.await(1, TimeUnit.SECONDS))

            val newerIntent = executor.submit<Long> { registry.beginExplicit(21) }
            Thread.sleep(50)
            assertFalse(newerIntent.isDone)

            releaseAction.countDown()

            assertTrue(currentAction.get(1, TimeUnit.SECONDS))
            assertTrue(newerIntent.get(1, TimeUnit.SECONDS) > originalToken)
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun preparedExplicitUpdate_shouldFenceOldCallbackBeforeLocalFailureMutation() {
        val transport = FakeTransport()
        val sharedBox = box(status = 0, revision = 7L)
        val store = InPlaceStore(sharedBox)
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(registry, transport, store)
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            coordinator.updateExplicitly(box(0, 7L))

            val preparedUpdate = executor.submit {
                coordinator.updateExplicitlyPrepared(sharedBox.positionSn, prepare = {
                    sharedBox.cmdStatusTake = 229
                    prepareEntered.countDown()
                    releasePrepare.await(2, TimeUnit.SECONDS)
                    Unit to sharedBox
                })
            }
            assertTrue(prepareEntered.await(1, TimeUnit.SECONDS))

            val oldCallback = executor.submit {
                transport.respond(0, accepted(box(0, 8L)))
            }
            Thread.sleep(50)
            assertFalse(oldCallback.isDone)

            releasePrepare.countDown()
            preparedUpdate.get(1, TimeUnit.SECONDS)
            oldCallback.get(1, TimeUnit.SECONDS)

            assertEquals(229, sharedBox.cmdStatusTake)
            assertEquals(0, store.saveCount)
            assertEquals(2, transport.requests.size)
            assertEquals(229, transport.requests[1].cmdStatusTake)
        } finally {
            releasePrepare.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun preparedExplicitUpdate_shouldSendIndependentTakeStatusSnapshot() {
        var sentBox: KaoPanBox? = null
        val sharedBox = box(status = 0, revision = 7L)
        val coordinator = TakeStatusUpdateCoordinator(
            TakeStatusIntentRegistry(),
            TakeStatusUpdateTransport { request, _ -> sentBox = request },
            TakeStatusUpdateStore { it }
        )

        coordinator.updateExplicitlyPrepared(sharedBox.positionSn, prepare = {
            sharedBox.cmdStatusTake = 229
            Unit to sharedBox
        })
        sharedBox.cmdStatusTake = 0
        sharedBox.takeStatusRevision = 99L

        assertEquals(229, sentBox?.cmdStatusTake)
        assertEquals(7L, sentBox?.takeStatusRevision)
    }

    @Test
    fun preparedExplicitUpdate_shouldReleaseIntentWhenPrepareFails() {
        val registry = TakeStatusIntentRegistry()
        var callbackCount = 0
        var confirmed: Boolean? = null
        val coordinator = TakeStatusUpdateCoordinator(
            registry,
            TakeStatusUpdateTransport { _, _ -> error("transport must not run") },
            TakeStatusUpdateStore { it }
        )

        try {
            coordinator.updateExplicitlyPrepared(
                21,
                prepare = { error("durable save failed") }
            ) { result, _ ->
                callbackCount += 1
                confirmed = result
            }
        } catch (_: IllegalStateException) {
        }

        assertEquals(1, callbackCount)
        assertEquals(false, confirmed)
        assertNotNull(registry.observeAutomaticGeneration(21))
    }

    @Test
    fun conflictRetry_shouldInvokeTransportOutsidePositionMonitor() {
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val transport = object : TakeStatusUpdateTransport {
            private var callback: ((Int, String?) -> Unit)? = null
            private var sendCount = 0

            override fun send(box: KaoPanBox, callback: (Int, String?) -> Unit) {
                sendCount += 1
                if (sendCount == 1) {
                    this.callback = callback
                } else {
                    retryEntered.countDown()
                    releaseRetry.await(2, TimeUnit.SECONDS)
                }
            }

            fun conflict() {
                callback?.invoke(0, conflict(box(229, 8L)))
            }
        }
        val registry = TakeStatusIntentRegistry()
        val coordinator = TakeStatusUpdateCoordinator(
            registry,
            transport,
            FakeStore(box(229, 8L))
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            coordinator.updateExplicitly(box(0, 7L))
            val conflictCallback = executor.submit { transport.conflict() }
            assertTrue(retryEntered.await(1, TimeUnit.SECONDS))

            val newerIntent = executor.submit<Long> { registry.beginExplicit(21) }
            newerIntent.get(1, TimeUnit.SECONDS)

            releaseRetry.countDown()
            conflictCallback.get(1, TimeUnit.SECONDS)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun stockRefresh_shouldPreservePreparedFailureCommittedDuringRefreshRace() {
        val table = KaoPanBoxTableCommitCoordinator()
        val registry = TakeStatusIntentRegistry()
        val sharedBox = box(0, 7L)
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val refreshFinished = CountDownLatch(1)
        val criticalSection = object : TakeStatusCriticalSection {
            override fun <T> run(action: () -> T): T = table.withLock(action)
        }
        val coordinator = TakeStatusUpdateCoordinator(
            registry,
            TakeStatusUpdateTransport { _, _ -> },
            InPlaceStore(sharedBox),
            criticalSection
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val preparedFailure = executor.submit {
                coordinator.updateExplicitlyPrepared(sharedBox.positionSn, prepare = {
                    sharedBox.cmdStatusTake = 229
                    prepareEntered.countDown()
                    releasePrepare.await(2, TimeUnit.SECONDS)
                    Unit to sharedBox
                })
            }
            assertTrue(prepareEntered.await(1, TimeUnit.SECONDS))

            val staleCloudBox = box(0, 7L)
            val refresh = executor.submit {
                table.withLock {
                    val observedGeneration = registry.observeAutomaticGeneration(sharedBox.positionSn)
                    if (observedGeneration == null) {
                        staleCloudBox.cmdStatusTake = sharedBox.cmdStatusTake
                        staleCloudBox.takeStatusRevision = sharedBox.takeStatusRevision
                    } else {
                        TakeStatusRevisionMergePolicy.merge(sharedBox, staleCloudBox)
                    }
                    sharedBox.cmdStatusTake = staleCloudBox.cmdStatusTake
                    sharedBox.takeStatusRevision = staleCloudBox.takeStatusRevision
                }
                refreshFinished.countDown()
            }
            Thread.sleep(50)
            assertEquals(1L, refreshFinished.count)

            releasePrepare.countDown()
            preparedFailure.get(1, TimeUnit.SECONDS)
            refresh.get(1, TimeUnit.SECONDS)

            assertEquals(229, sharedBox.cmdStatusTake)
            assertEquals(7L, sharedBox.takeStatusRevision)
        } finally {
            releasePrepare.countDown()
            executor.shutdownNow()
        }
    }

    private class InPlaceStore(private val current: KaoPanBox) : TakeStatusUpdateStore {
        var saveCount: Int = 0

        override fun applyAuthoritative(authoritative: KaoPanBox): KaoPanBox {
            saveCount += 1
            current.cmdStatusTake = authoritative.cmdStatusTake
            current.takeStatusRevision = authoritative.takeStatusRevision
            return current
        }
    }

    private class FakeTransport : TakeStatusUpdateTransport {
        val requests = mutableListOf<KaoPanBox>()
        private val callbacks = mutableListOf<(Int, String?) -> Unit>()

        override fun send(box: KaoPanBox, callback: (Int, String?) -> Unit) {
            requests += box(box.cmdStatusTake, box.takeStatusRevision)
            callbacks += callback
        }

        fun respond(index: Int, response: String) {
            callbacks[index](0, response)
        }
    }

    private class FakeStore(
        initial: KaoPanBox,
        private val failOnSave: Boolean = false
    ) : TakeStatusUpdateStore {
        var current: KaoPanBox = initial
        var saveCount: Int = 0

        override fun applyAuthoritative(authoritative: KaoPanBox): KaoPanBox? {
            if (failOnSave) error("durable save failed")
            saveCount += 1
            current = box(authoritative.cmdStatusTake, authoritative.takeStatusRevision)
            return current
        }
    }

    companion object {
        private fun box(status: Int, revision: Long): KaoPanBox = KaoPanBox().apply {
            positionSn = 21
            cmdStatusTake = status
            takeStatusRevision = revision
        }

        private fun accepted(authoritative: KaoPanBox): String = response(true, "APPLIED", authoritative)

        private fun conflict(authoritative: KaoPanBox): String = response(false, "CONFLICT", authoritative)

        private fun response(accepted: Boolean, reason: String, authoritative: KaoPanBox): String =
            """{"code":${if (accepted) 200 else 500},"data":{"accepted":$accepted,"reason":"$reason","stocks":[{"positionSn":${authoritative.positionSn},"cmdStatusTake":${authoritative.cmdStatusTake},"takeStatusRevision":${authoritative.takeStatusRevision}}]}}"""
    }
}
