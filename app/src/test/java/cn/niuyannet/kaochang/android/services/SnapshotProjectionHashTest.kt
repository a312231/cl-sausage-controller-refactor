package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SnapshotProjectionHashTest {

    @Test
    fun hash_shouldIgnoreRawTimingFieldsWhenSellableProjectionIsUnchanged() {
        val before = listOf(sellablePan(positionSn = 7, holdingTime = 5_000L, startTime = 1_000L))
        val after = listOf(sellablePan(positionSn = 7, holdingTime = 1_000L, startTime = 9_000L))

        assertEquals(
            SnapshotProjectionHash.hash(before, emptyList()),
            SnapshotProjectionHash.hash(after, emptyList())
        )
    }

    @Test
    fun hash_shouldChangeWhenHoldingAvailabilityChangesToExpired() {
        val before = listOf(sellablePan(positionSn = 7, holdingTime = 1L))
        val after = listOf(sellablePan(positionSn = 7, holdingTime = 0L))

        assertNotEquals(
            SnapshotProjectionHash.hash(before, emptyList()),
            SnapshotProjectionHash.hash(after, emptyList())
        )
    }

    @Test
    fun hash_shouldChangeWhenStatusOrSausagePresenceChanges() {
        val sellable = listOf(sellablePan(positionSn = 7))
        val notSellableStatus = listOf(sellablePan(positionSn = 7).apply { status = 1 })
        val emptyPan = listOf(sellablePan(positionSn = 7).apply { isHasSausage = false })

        val baseHash = SnapshotProjectionHash.hash(sellable, emptyList())

        assertNotEquals(baseHash, SnapshotProjectionHash.hash(notSellableStatus, emptyList()))
        assertNotEquals(baseHash, SnapshotProjectionHash.hash(emptyPan, emptyList()))
    }

    @Test
    fun hash_shouldChangeWhenStockProjectionChanges() {
        val base = listOf(stock(positionSn = 1, num = 3, tasteCode = "A", productId = "101"))
        val numChanged = listOf(stock(positionSn = 1, num = 2, tasteCode = "A", productId = "101"))
        val tasteChanged = listOf(stock(positionSn = 1, num = 3, tasteCode = "B", productId = "101"))
        val productChanged = listOf(stock(positionSn = 1, num = 3, tasteCode = "A", productId = "102"))

        val baseHash = SnapshotProjectionHash.hash(emptyList(), base)

        assertNotEquals(baseHash, SnapshotProjectionHash.hash(emptyList(), numChanged))
        assertNotEquals(baseHash, SnapshotProjectionHash.hash(emptyList(), tasteChanged))
        assertNotEquals(baseHash, SnapshotProjectionHash.hash(emptyList(), productChanged))
    }

    private fun sellablePan(
        positionSn: Int,
        holdingTime: Long = 5_000L,
        startTime: Long = 1_000L
    ): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.holdingTime = holdingTime
            this.startTime = startTime
            this.bakingTime = 20_000L
            this.closeTime = 60_000L
            this.temperature = 160
            this.taste = Taste(1, "A", "原味", 101, "烤肠")
        }

    private fun stock(positionSn: Int, num: Int, tasteCode: String, productId: String): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = num
            this.tasteCode = tasteCode
            this.productId = productId
        }
}
