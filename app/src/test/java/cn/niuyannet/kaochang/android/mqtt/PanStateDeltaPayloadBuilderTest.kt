package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.KaoPanCloudSyncReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanStateDeltaPayloadBuilderTest {

    @Test
    fun build_shouldUseAction2PanStateDeltaProjectionPayload() {
        val before = KaoPanCloudSyncReporter.captureSellableSnapshot(listOf(sellablePan(1, "A", 101)))
        val after = KaoPanCloudSyncReporter.captureSellableSnapshot(listOf(sellablePan(2, "A", 101)))

        val payload = PanStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 100L,
            eventId = "event-100",
            before = before,
            after = after,
            reason = "测试可售变化",
            source = "unit-test",
            changedAt = 1779034500000L
        )

        assertEquals(MqttProtocol.TYPE_PAN_STATE_DELTA, payload.getString("messageType"))
        assertEquals("device-001", payload.getString("deviceCode"))
        assertEquals(100L, payload.getLongValue("panDeltaSeq"))
        assertEquals("event-100", payload.getString("eventId"))
        assertEquals(PanStateDeltaPayloadBuilder.PROJECTION_TYPE_SELLABLE_PANS_V1, payload.getString("projectionType"))
        assertEquals(PanStateDeltaPayloadBuilder.PROTOCOL_VERSION, payload.getIntValue("protocolVersion"))
        assertEquals("测试可售变化", payload.getString("reason"))
        assertEquals("unit-test", payload.getString("source"))
        assertEquals(1, payload.getJSONArray("beforeSellablePans").size)
        assertEquals(1, payload.getJSONArray("afterSellablePans").size)
        assertEquals(2, payload.getJSONArray("changedPans").size)
        assertNotNull(payload.getJSONArray("changedPans").getJSONObject(0).get("before"))
    }

    private fun sellablePan(positionSn: Int, tasteCode: String, productId: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = 1000L
            this.taste = Taste(1, tasteCode, "原味", productId, "烤肠")
        }
}
