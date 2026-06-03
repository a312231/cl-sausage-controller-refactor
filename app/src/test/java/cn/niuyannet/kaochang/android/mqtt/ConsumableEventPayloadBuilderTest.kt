package cn.niuyannet.kaochang.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsumableEventPayloadBuilderTest {

    @Test
    fun nextEvent_shouldUseEpochAndMonotonicSeqInSourceKey() {
        val store = InMemoryConsumableEventSeqStore(epoch = "epoch-a", seq = 41L)

        val event = ConsumableEventPayloadBuilder.nextEvent(
            store = store,
            deviceCode = "device-001",
            consumableType = "STICK",
            eventType = "STICK_CONSUME",
            deltaValue = -1L,
            source = "out2_success",
            detail = "测试穿签"
        )

        assertEquals(42L, event.consumableEventSeq)
        assertEquals("device-001:epoch-a:42", event.sourceEventKey)
        assertEquals(42L, store.seq)
    }

    @Test
    fun buildPayload_shouldIncludeDeviceCodeEventTypeDeltaAndSourceKey() {
        val event = ConsumableEvent(
            deviceCode = "device-001",
            consumableType = "WASTE",
            eventType = "WASTE_DISCARD_BUSINESS_CLOSE",
            deltaValue = 3L,
            consumableEventEpoch = "epoch-a",
            consumableEventSeq = 7L,
            sourceEventKey = "device-001:epoch-a:7",
            source = "business_close_self_clean_success",
            detail = "residualCount=3",
            eventTime = 123456L
        )

        val payload = ConsumableEventPayloadBuilder.buildPayload(event)

        assertEquals(MqttProtocol.TYPE_CONSUMABLE_EVENT, payload.getString("messageType"))
        assertEquals("device-001", payload.getString("deviceCode"))
        assertEquals("WASTE_DISCARD_BUSINESS_CLOSE", payload.getString("eventType"))
        assertEquals(3L, payload.getLongValue("deltaValue"))
        assertEquals("device-001:epoch-a:7", payload.getString("sourceEventKey"))
    }

    @Test
    fun shouldReportBusinessCloseWaste_shouldOnlyReportPositiveResidualOnSuccess() {
        assertTrue(ConsumableEventPayloadBuilder.shouldReportBusinessCloseWaste(3, success = true))
        assertEquals(false, ConsumableEventPayloadBuilder.shouldReportBusinessCloseWaste(0, success = true))
        assertEquals(false, ConsumableEventPayloadBuilder.shouldReportBusinessCloseWaste(3, success = false))
    }

    @Test
    fun nextEvent_shouldNotDuplicateSourceKeyWhenCalledConcurrently() {
        val store = InMemoryConsumableEventSeqStore(epoch = "epoch-a", seq = 0L)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        val sourceKeys = Collections.synchronizedSet(mutableSetOf<String>())

        repeat(100) {
            executor.execute {
                start.await(2, TimeUnit.SECONDS)
                val event = ConsumableEventPayloadBuilder.nextEvent(
                    store = store,
                    deviceCode = "device-001",
                    consumableType = "STICK",
                    eventType = "STICK_CONSUME",
                    deltaValue = -1L,
                    source = "out2_success"
                )
                sourceKeys.add(event.sourceEventKey)
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(100, sourceKeys.size)
        assertEquals(100L, store.seq)
    }

    private class InMemoryConsumableEventSeqStore(
        private val epoch: String,
        var seq: Long
    ) : ConsumableEventSeqStore {
        override fun getOrCreateEpoch(): String = epoch

        override fun nextSeq(): Long {
            Thread.sleep(1)
            seq += 1
            return seq
        }
    }
}
