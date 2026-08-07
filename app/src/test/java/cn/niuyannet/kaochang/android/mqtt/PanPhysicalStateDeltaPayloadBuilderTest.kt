package cn.niuyannet.kaochang.android.mqtt

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanPhysicalStateDeltaPayloadBuilderTest {

    @Test
    fun build_shouldUseAction2PhysicalStatePayloadWithFinalPanAndStockState() {
        val changedPan = pan(9, status = 1, hasSausage = true, tasteCode = "A", productId = 101)
        val changedStock = box(8, num = 14, tasteCode = "A", productId = "101")

        val payload = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 200L,
            eventId = "event-200",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(changedPan, pan(1, status = 2, hasSausage = true, tasteCode = "B", productId = 102)),
            allStocks = listOf(changedStock, box(1, num = 12, tasteCode = "B", productId = "102")),
            changedPans = listOf(changedPan),
            changedStocks = listOf(changedStock),
            reason = "in7 已落盘",
            source = "unit-test",
            changedAt = 1779034500000L
        )

        assertEquals(MqttProtocol.TYPE_PAN_STATE_DELTA, payload.getString("messageType"))
        assertEquals("device-001", payload.getString("deviceCode"))
        assertEquals(200L, payload.getLongValue("panDeltaSeq"))
        assertEquals("event-200", payload.getString("eventId"))
        assertEquals(PanPhysicalStateDeltaPayloadBuilder.PROJECTION_TYPE_PHYSICAL_STATE_V1, payload.getString("projectionType"))
        assertEquals(PanPhysicalStateDeltaPayloadBuilder.PROTOCOL_VERSION, payload.getIntValue("protocolVersion"))
        assertEquals(1234L, payload.getLongValue("baseSnapshotSequence"))
        assertEquals("base-hash", payload.getString("baseSnapshotHash"))
        assertEquals("in7 已落盘", payload.getString("reason"))
        assertEquals("unit-test", payload.getString("source"))
        assertNotNull(payload.getString("fullStateHash"))
        assertEquals(listOf<Int>(), payload.getJSONArray("sellablePositions")?.toList())
        assertNotNull(payload.getString("sellableRulesSignature"))

        val changedPans = payload.getJSONArray("changedPans")
        assertEquals(1, changedPans.size)
        val panJson = changedPans.getJSONObject(0)
        assertEquals(9, panJson.getIntValue("positionSn"))
        assertEquals(1, panJson.getIntValue("status"))
        assertEquals(true, panJson.getBooleanValue("hasSausage"))
        assertEquals("A", panJson.getJSONObject("taste").getString("tasteCode"))
        assertEquals(101, panJson.getJSONObject("taste").getIntValue("productId"))

        val changedStocks = payload.getJSONArray("changedStocks")
        assertEquals(1, changedStocks.size)
        val stockJson = changedStocks.getJSONObject(0)
        assertEquals(8, stockJson.getIntValue("positionSn"))
        assertEquals(14, stockJson.getIntValue("num"))
        assertEquals("101", stockJson.getString("productId"))
        assertEquals("A", stockJson.getString("tasteCode"))
        assertEquals(208, stockJson.getIntValue("cmdValueTake"))
        assertFalse(stockJson.containsKey("cmdStatusTake"))
        assertFalse(stockJson.containsKey("takeStatusRevision"))
    }

    @Test
    fun build_shouldCreateStableFullStateHashIndependentOfInputOrder() {
        val pan1 = pan(1, status = 2, hasSausage = true, tasteCode = "A", productId = 101)
        val pan2 = pan(2, status = 0, hasSausage = false, tasteCode = null, productId = null)
        val stock1 = box(1, num = 12, tasteCode = "A", productId = "101")
        val stock2 = box(2, num = 13, tasteCode = "B", productId = "102")

        val payloadA = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 201L,
            eventId = "event-201",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(pan1, pan2),
            allStocks = listOf(stock1, stock2),
            changedPans = listOf(pan1),
            changedStocks = listOf(stock1),
            reason = "hash-test",
            source = "unit-test",
            changedAt = 1779034500000L
        )
        val payloadB = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 202L,
            eventId = "event-202",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(pan2, pan1),
            allStocks = listOf(stock2, stock1),
            changedPans = listOf(pan1),
            changedStocks = listOf(stock1),
            reason = "hash-test",
            source = "unit-test",
            changedAt = 1779034500000L
        )

        assertEquals(payloadA.getString("fullStateHash"), payloadB.getString("fullStateHash"))
        assertEquals(
            "daa1985554400c6f4c4b4b4d93b3f010cc02d48295e88b0be3a1e30b915e472e",
            payloadA.getString("fullStateHash")
        )

        val changedPayload = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 203L,
            eventId = "event-203",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(pan1, pan2),
            allStocks = listOf(stock1, box(2, num = 10, tasteCode = "B", productId = "102")),
            changedPans = listOf(pan1),
            changedStocks = listOf(stock1),
            reason = "hash-test",
            source = "unit-test",
            changedAt = 1779034500000L
        )
        assertNotEquals(payloadA.getString("fullStateHash"), changedPayload.getString("fullStateHash"))
    }

    @Test
    fun build_shouldKeepRawFullStateHashStableWhenSellableProjectionFieldsAreAdded() {
        val now = 1_000_000L
        val pan1 = pan(1, status = 2, hasSausage = true, tasteCode = "A", productId = 101).apply {
            holdingTime = now - 1_000L
            closeTime = 60_000L
        }
        val stock1 = box(1, num = 12, tasteCode = "A", productId = "101")
        val rulesAllowingPan = AppConfigBean().apply {
            modeType = 1
            transitionMode = 1
        }
        val rulesDiscardingPan = AppConfigBean().apply {
            modeType = 1
            transitionMode = 1
            pendingDiscardPositions.add(1)
        }

        val allowingPayload = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 204L,
            eventId = "event-204",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(pan1),
            allStocks = listOf(stock1),
            changedPans = emptyList(),
            changedStocks = emptyList(),
            reason = "rule-only",
            source = "unit-test",
            changedAt = now,
            config = rulesAllowingPan
        )
        val discardingPayload = PanPhysicalStateDeltaPayloadBuilder.build(
            deviceCode = "device-001",
            panDeltaSeq = 205L,
            eventId = "event-205",
            baseSnapshotSequence = 1234L,
            baseSnapshotHash = "base-hash",
            allPans = listOf(pan1),
            allStocks = listOf(stock1),
            changedPans = emptyList(),
            changedStocks = emptyList(),
            reason = "rule-only",
            source = "unit-test",
            changedAt = now,
            config = rulesDiscardingPan
        )

        assertEquals(
            PanPhysicalStateDeltaPayloadBuilder.buildFullStateHash(listOf(pan1), listOf(stock1)),
            allowingPayload.getString("fullStateHash")
        )
        assertEquals(allowingPayload.getString("fullStateHash"), discardingPayload.getString("fullStateHash"))
        assertEquals(listOf(1), allowingPayload.getJSONArray("sellablePositions").toList())
        assertEquals(emptyList<Int>(), discardingPayload.getJSONArray("sellablePositions").toList())
        assertNotEquals(
            allowingPayload.getString("sellableRulesSignature"),
            discardingPayload.getString("sellableRulesSignature")
        )
    }

    private fun pan(
        positionSn: Int,
        status: Int,
        hasSausage: Boolean,
        tasteCode: String?,
        productId: Int?
    ): KaoPan =
        KaoPan().apply {
            this.id = positionSn
            this.positionSn = positionSn
            this.positionRegion = if (positionSn <= 11) 1 else 2
            this.startTime = 1000L
            this.bakingTime = 900_000L
            this.holdingTime = if (status == 2) 2000L else 0L
            this.closeTime = 14_400_000L
            this.temperature = if (status == 1) 160 else if (status == 2) 60 else 0
            this.status = status
            this.isHasSausage = hasSausage
            this.isHasGrilling = status == 1
            this.cmdValueMove = 300 + positionSn
            this.cmdValueTake = 400 + positionSn
            this.cmdStatusMove = 0
            this.cmdStatusTake = 0
            this.cmdValueDiscard = 500 + positionSn
            this.cmdStatusDiscard = 0
            this.taste = tasteCode?.let { Taste(1, it, "口味$it", productId ?: 0, "烤肠") }
        }

    private fun box(positionSn: Int, num: Int, tasteCode: String, productId: String): KaoPanBox =
        KaoPanBox().apply {
            this.id = positionSn
            this.deviceId = "device-001"
            this.productId = productId
            this.skuId = "sku-$positionSn"
            this.positionSn = positionSn
            this.lastPositionSn = positionSn - 1
            this.num = num
            this.tasteCode = tasteCode
            this.tasteName = "口味$tasteCode"
            this.cmdValueTake = 200 + positionSn
            this.cmdStatusTake = 0
            this.checkStatus = true
        }
}
