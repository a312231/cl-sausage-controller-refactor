package cn.niuyannet.kaochang.android.mqtt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPublishBackpressureQueueTest {

    @Test
    fun offer_shouldSerializePublishes() = runBlocking {
        val activeCount = AtomicInteger(0)
        val maxActiveCount = AtomicInteger(0)
        val completed = CountDownLatch(3)
        val queue = MqttPublishBackpressureQueue(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxPendingCount = 10,
            sender = {
                val active = activeCount.incrementAndGet()
                maxActiveCount.updateAndGet { old -> maxOf(old, active) }
                Thread.sleep(20L)
                activeCount.decrementAndGet()
                completed.countDown()
                true
            }
        )

        assertTrue(queue.offer(request("a")))
        assertTrue(queue.offer(request("b")))
        assertTrue(queue.offer(request("c")))

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(1, maxActiveCount.get())
        queue.shutdown()
    }

    @Test
    fun offer_shouldRejectWhenPendingLimitReached() = runBlocking {
        val releaseSender = CountDownLatch(1)
        val queue = MqttPublishBackpressureQueue(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxPendingCount = 1,
            sender = {
                releaseSender.await(2, TimeUnit.SECONDS)
                true
            }
        )

        assertTrue(queue.offer(request("first")))
        assertFalse(queue.offer(request("second")))

        releaseSender.countDown()
        queue.shutdown()
    }

    @Test
    fun offer_shouldReplacePendingMessageWithSameCoalesceKey() = runBlocking {
        val releaseSender = CountDownLatch(1)
        val completedPayloads = mutableListOf<String>()
        val completed = CountDownLatch(2)
        val queue = MqttPublishBackpressureQueue(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxPendingCount = 10,
            sender = {
                if (it.payload == "blocking") {
                    releaseSender.await(2, TimeUnit.SECONDS)
                }
                synchronized(completedPayloads) {
                    completedPayloads += it.payload
                }
                completed.countDown()
                true
            }
        )

        assertTrue(queue.offer(request("blocking")))
        assertTrue(queue.offer(request("old-runtime", coalesceKey = "runtime_state")))
        assertTrue(queue.offer(request("new-runtime", coalesceKey = "runtime_state")))

        releaseSender.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        synchronized(completedPayloads) {
            assertEquals(listOf("blocking", "new-runtime"), completedPayloads)
        }
        queue.shutdown()
    }

    @Test
    fun offer_shouldNotifyDroppedRequestWhenCoalesced() = runBlocking {
        val releaseSender = CountDownLatch(1)
        val droppedCount = AtomicInteger(0)
        val completedPayloads = mutableListOf<String>()
        val completed = CountDownLatch(2)
        val queue = MqttPublishBackpressureQueue(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            maxPendingCount = 10,
            sender = {
                if (it.payload == "blocking") {
                    releaseSender.await(2, TimeUnit.SECONDS)
                }
                synchronized(completedPayloads) {
                    completedPayloads += it.payload
                }
                completed.countDown()
                true
            }
        )

        assertTrue(queue.offer(request("blocking")))
        assertTrue(
            queue.offer(
                request(
                    payload = "old-runtime",
                    coalesceKey = "runtime_state",
                    onDroppedFromQueue = { droppedCount.incrementAndGet() }
                )
            )
        )
        assertTrue(queue.offer(request("new-runtime", coalesceKey = "runtime_state")))

        assertEquals(1, droppedCount.get())

        releaseSender.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        synchronized(completedPayloads) {
            assertEquals(listOf("blocking", "new-runtime"), completedPayloads)
        }
        queue.shutdown()
    }

    private fun request(payload: String): QueuedMqttPublish =
        QueuedMqttPublish(
            topic = "/test",
            payload = payload,
            qos = 1,
            retained = false,
            description = payload
        )

    private fun request(
        payload: String,
        coalesceKey: String,
        onDroppedFromQueue: (() -> Unit)? = null
    ): QueuedMqttPublish =
        QueuedMqttPublish(
            topic = "/test",
            payload = payload,
            qos = 1,
            retained = false,
            description = payload,
            coalesceKey = coalesceKey,
            onDroppedFromQueue = onDroppedFromQueue
        )
}
