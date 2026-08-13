package cn.niuyannet.kaochang.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProcessLifetimeSchedulerProfilePinTest {
    private val legacyA = LegacySchedulerProfileA.create()
    private val testB = SchedulerProfile.createForTesting(
        id = SchedulerProfileId("test-scheduler-profile-b-v1"),
        sellCandidateStrategy = legacyA.sellCandidateStrategy,
        targetAllocationStrategy = legacyA.targetAllocationStrategy,
        tasteMixStrategy = legacyA.tasteMixStrategy,
        batchHeatPositionStrategy = legacyA.batchHeatPositionStrategy,
        demandTransitionStrategy = legacyA.demandTransitionStrategy
    )
    private val catalog = SchedulerProfileCatalog(listOf(legacyA, testB))

    @Test
    fun requireActiveProfile_beforePin_failsWithStableDiagnostic() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)

        val error = captureIllegalState { pin.requireActiveProfile() }

        assertEquals("Scheduler profile has not been pinned for this process", error.message)
    }

    @Test
    fun pin_rejectsInvalidAndUnregisteredIdsWithoutChangingState() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)

        assertSame(ProcessLifetimeProfilePinResult.InvalidId, pin.pin("INVALID"))
        assertSame(ProcessLifetimeProfilePinResult.NotRegistered, pin.pin("test-missing-profile"))
        captureIllegalState { pin.requireActiveProfile() }
    }

    @Test
    fun pin_firstRequestOwnsProcessAndSameIdIsIdempotent() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)

        val first = pin.pin(legacyA.id.value)
        val repeated = pin.pin(legacyA.id.value)

        assertTrue(first is ProcessLifetimeProfilePinResult.FirstPinned)
        assertTrue(repeated is ProcessLifetimeProfilePinResult.AlreadyPinned)
        assertSame(legacyA, pin.requireActiveProfile())
        assertSame(
            (first as ProcessLifetimeProfilePinResult.FirstPinned).profile,
            (repeated as ProcessLifetimeProfilePinResult.AlreadyPinned).profile
        )
    }

    @Test
    fun pin_differentRegisteredIdIsRejectedWithoutReplacingOwner() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)
        pin.pin(legacyA.id.value)

        val conflict = pin.pin(testB.id.value)

        assertEquals(
            ProcessLifetimeProfilePinResult.Conflict(legacyA.id, testB.id),
            conflict
        )
        assertSame(legacyA, pin.requireActiveProfile())
    }

    @Test
    fun concurrentSameIdPin_hasExactlyOneFirstOwnerAndOneProfileInstance() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..32).map {
                executor.submit<ProcessLifetimeProfilePinResult> {
                    start.await()
                    pin.pin(legacyA.id.value)
                }
            }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is ProcessLifetimeProfilePinResult.FirstPinned })
            assertEquals(31, results.count { it is ProcessLifetimeProfilePinResult.AlreadyPinned })
            val profiles = results.map {
                when (it) {
                    is ProcessLifetimeProfilePinResult.FirstPinned -> it.profile
                    is ProcessLifetimeProfilePinResult.AlreadyPinned -> it.profile
                    else -> fail("Unexpected pin result: $it")
                }
            }
            assertTrue(profiles.all { it === profiles.first() })
            assertSame(legacyA, pin.requireActiveProfile())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentMixedIdPin_hasOneOwnerAndNeverPublishesMixedProfiles() {
        val pin = ProcessLifetimeSchedulerProfilePin(catalog)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val requestedIds = (1..32).map { index ->
                if (index % 2 == 0) legacyA.id else testB.id
            }
            val futures = requestedIds.map { requestedId ->
                executor.submit<Pair<SchedulerProfileId, ProcessLifetimeProfilePinResult>> {
                    start.await()
                    requestedId to pin.pin(requestedId.value)
                }
            }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            val firstResult = results.filter {
                it.second is ProcessLifetimeProfilePinResult.FirstPinned
            }
                .single()
            val first = firstResult.second as ProcessLifetimeProfilePinResult.FirstPinned
            val ownerId = first.profile.id
            val ownerRequests = results.filter { it.first == ownerId }
            val conflictingRequests = results.filter { it.first != ownerId }

            assertEquals(ownerId, firstResult.first)
            assertEquals(16, ownerRequests.size)
            assertEquals(
                15,
                ownerRequests.count {
                    it.second is ProcessLifetimeProfilePinResult.AlreadyPinned
                }
            )
            assertTrue(ownerRequests.all { (_, result) ->
                when (result) {
                    is ProcessLifetimeProfilePinResult.FirstPinned ->
                        result.profile === first.profile
                    is ProcessLifetimeProfilePinResult.AlreadyPinned ->
                        result.profile === first.profile
                    else -> false
                }
            })
            assertEquals(16, conflictingRequests.size)
            assertTrue(conflictingRequests.all { (requestedId, result) ->
                result is ProcessLifetimeProfilePinResult.Conflict &&
                    result.activeProfileId == ownerId &&
                    result.requestedProfileId == requestedId
            })
            assertSame(first.profile, pin.requireActiveProfile())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun captureIllegalState(block: () -> Unit): IllegalStateException {
        var caught: IllegalStateException? = null
        try {
            block()
        } catch (error: IllegalStateException) {
            caught = error
        }
        return caught ?: throw AssertionError("Expected IllegalStateException")
    }
}
