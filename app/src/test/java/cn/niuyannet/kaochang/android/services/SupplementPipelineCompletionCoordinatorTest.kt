package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPipelineCompletionCoordinatorTest {

    @Test
    fun clearFailure_shouldHappenAfterIdlePublishAndStillKeepLock() {
        val events = mutableListOf<String>()

        val result = SupplementPipelineCompletionCoordinator.complete(
            clearSession = { events += "clear"; false },
            persistIdle = { events += "idle" },
            releaseLock = { events += "release" },
            publish = { events += "publish" },
            restoreBusy = { events += "restore-busy" },
            publishRestoredBusy = { events += "republish-busy" }
        )

        assertTrue(result is SupplementPipelineCompletionResult.Failed)
        assertEquals(listOf("idle", "publish", "clear", "restore-busy", "republish-busy"), events)
    }

    @Test
    fun failureAfterIdle_shouldRestoreBusyAndNotContinueCleanup() {
        val events = mutableListOf<String>()

        val result = SupplementPipelineCompletionCoordinator.complete(
            clearSession = { events += "clear"; true },
            persistIdle = { events += "idle"; error("idle durable write failed") },
            releaseLock = { events += "release" },
            publish = { events += "publish" },
            restoreBusy = { events += "restore-busy" }
        )

        assertTrue(result is SupplementPipelineCompletionResult.Failed)
        assertEquals(listOf("idle", "restore-busy"), events)
    }

    @Test
    fun publishFailure_shouldRestoreBusyWithoutClearingSessionOrReleasingLock() {
        val events = mutableListOf<String>()

        val result = SupplementPipelineCompletionCoordinator.complete(
            clearSession = { events += "clear"; true },
            persistIdle = { events += "idle" },
            releaseLock = { events += "release" },
            publish = { events += "publish"; error("publish failed") },
            restoreBusy = { events += "restore-busy" }
        )

        assertTrue(result is SupplementPipelineCompletionResult.Failed)
        assertEquals(listOf("idle", "publish", "restore-busy"), events)
    }

    @Test
    fun success_shouldIdlePublishClearThenRelease() {
        val events = mutableListOf<String>()

        val result = SupplementPipelineCompletionCoordinator.complete(
            clearSession = { events += "clear"; true },
            persistIdle = { events += "idle" },
            releaseLock = { events += "release" },
            publish = { events += "publish" },
            restoreBusy = { events += "restore-busy" }
        )

        assertEquals(SupplementPipelineCompletionResult.Success, result)
        assertEquals(listOf("idle", "publish", "clear", "release"), events)
    }
}
