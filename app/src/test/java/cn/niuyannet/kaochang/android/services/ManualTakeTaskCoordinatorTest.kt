package cn.niuyannet.kaochang.android.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTakeTaskCoordinatorTest {

    @Test
    fun cancellingPageObserver_shouldNotCancelApplicationOwnedTask() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val coordinator = ManualTakeTaskCoordinator(scope)

        try {
            val task = requireNotNull(coordinator.start {
                started.complete(Unit)
                finish.await()
                true
            })
            started.await()
            assertTrue(coordinator.hasActiveTask())

            val pageObserver = launch { task.await() }
            pageObserver.cancel()
            pageObserver.join()

            assertFalse(task.isCancelled)
            finish.complete(Unit)
            assertTrue(task.await())
            assertFalse(coordinator.hasActiveTask())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun start_shouldRejectSecondTaskUntilFirstTaskCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finish = CompletableDeferred<Unit>()
        val coordinator = ManualTakeTaskCoordinator(scope)

        try {
            val first = requireNotNull(coordinator.start {
                finish.await()
                true
            })

            assertTrue(coordinator.hasActiveTask())
            assertNull(coordinator.start { true })
            finish.complete(Unit)
            assertTrue(first.await())
            assertFalse(coordinator.hasActiveTask())

            val next = requireNotNull(coordinator.start { true })
            assertTrue(next.await())
        } finally {
            scope.cancel()
        }
    }
}
