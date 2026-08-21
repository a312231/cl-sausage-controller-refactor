package cn.niuyannet.kaochang.android.utils

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateManagerBeforeAcceptTest {

    @Test
    fun beforeAcceptFalseOrThrow_shouldNotWriteProcessingMemory() {
        listOf<() -> Boolean>({ false }, { error("durable write failed") }).forEachIndexed { index, before ->
            val orderNo = "BEFORE-FAIL-$index-${System.nanoTime()}"

            assertFalse(OrderStateManager.checkAndAcceptOrder(orderNo, before))
            assertTrue(OrderStateManager.checkAndAcceptOrder(orderNo) { true })
            OrderStateManager.releaseProcessing(orderNo)
        }
    }

    @Test
    fun twoThreads_shouldRunBeforeAcceptAndEnterProcessingOnlyOnce() {
        val orderNo = "TWO-THREAD-${System.nanoTime()}"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val beforeCalls = AtomicInteger(0)
        val accepted = AtomicInteger(0)
        val threads = List(2) {
            Thread {
                ready.countDown()
                start.await()
                if (OrderStateManager.checkAndAcceptOrder(orderNo) {
                        beforeCalls.incrementAndGet()
                        true
                    }
                ) accepted.incrementAndGet()
            }
        }
        threads.forEach(Thread::start)
        ready.await()
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, beforeCalls.get())
        assertEquals(1, accepted.get())
        OrderStateManager.releaseProcessing(orderNo)
    }
}
