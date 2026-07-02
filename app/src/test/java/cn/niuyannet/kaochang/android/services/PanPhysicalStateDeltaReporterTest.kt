package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import org.junit.Assert.assertEquals
import org.junit.Test

class PanPhysicalStateDeltaReporterTest {

    @Test
    fun reportPanChanged_shouldPublishChangedPanWithCurrentFullState() {
        val pan1 = pan(1, hasSausage = false)
        val pan2 = pan(2, hasSausage = true)
        val box1 = box(1, num = 8)
        var captured: PublishCall? = null

        PanPhysicalStateDeltaReporter.withDependenciesForTest(
            snapshotSource = testSource(pans = listOf(pan1, pan2), boxes = listOf(box1)),
            publisher = { allPans, allStocks, changedPans, changedStocks, reason, source ->
                captured = PublishCall(allPans, allStocks, changedPans, changedStocks, reason, source)
                true
            }
        ) {
            PanPhysicalStateDeltaReporter.reportPanChanged(
                changedPan = pan2,
                reason = "action_physical:in7:placed_on_pan:box=1:pan=2",
                source = "action_physical_in7"
            )
        }

        assertEquals(listOf(1, 2), captured!!.allPans.map { it.positionSn })
        assertEquals(listOf(1), captured!!.allStocks.map { it.positionSn })
        assertEquals(listOf(2), captured!!.changedPans.map { it.positionSn })
        assertEquals(emptyList<Int>(), captured!!.changedStocks.map { it.positionSn })
        assertEquals("action_physical:in7:placed_on_pan:box=1:pan=2", captured!!.reason)
        assertEquals("action_physical_in7", captured!!.source)
    }

    @Test
    fun reportStockChanged_shouldPublishChangedStockOnly() {
        val pan1 = pan(1, hasSausage = false)
        val box1 = box(1, num = 7)
        val box2 = box(2, num = 9)
        var captured: PublishCall? = null

        PanPhysicalStateDeltaReporter.withDependenciesForTest(
            snapshotSource = testSource(pans = listOf(pan1), boxes = listOf(box1, box2)),
            publisher = { allPans, allStocks, changedPans, changedStocks, reason, source ->
                captured = PublishCall(allPans, allStocks, changedPans, changedStocks, reason, source)
                true
            }
        ) {
            PanPhysicalStateDeltaReporter.reportStockChanged(
                changedStock = box1,
                reason = "action_physical:in2:picked_from_box:box=1:pan=2",
                source = "action_physical_in2"
            )
        }

        assertEquals(listOf(1), captured!!.allPans.map { it.positionSn })
        assertEquals(listOf(1, 2), captured!!.allStocks.map { it.positionSn })
        assertEquals(emptyList<Int>(), captured!!.changedPans.map { it.positionSn })
        assertEquals(listOf(1), captured!!.changedStocks.map { it.positionSn })
        assertEquals("action_physical_in2", captured!!.source)
    }

    @Test
    fun reportPanMoveChanged_shouldPublishSourceAndTargetPans() {
        val sourcePan = pan(13, hasSausage = false)
        val targetPan = pan(1, hasSausage = true)
        var captured: PublishCall? = null

        PanPhysicalStateDeltaReporter.withDependenciesForTest(
            snapshotSource = testSource(pans = listOf(sourcePan, targetPan), boxes = emptyList()),
            publisher = { allPans, allStocks, changedPans, changedStocks, reason, source ->
                captured = PublishCall(allPans, allStocks, changedPans, changedStocks, reason, source)
                true
            }
        ) {
            PanPhysicalStateDeltaReporter.reportPansChanged(
                changedPans = listOf(sourcePan, targetPan),
                reason = "pan_move:13:1",
                source = "pan_move_success"
            )
        }

        assertEquals(listOf(13, 1), captured!!.changedPans.map { it.positionSn })
        assertEquals(emptyList<Int>(), captured!!.changedStocks.map { it.positionSn })
        assertEquals("pan_move_success", captured!!.source)
    }

    private data class PublishCall(
        val allPans: List<KaoPan>,
        val allStocks: List<KaoPanBox>,
        val changedPans: List<KaoPan>,
        val changedStocks: List<KaoPanBox>,
        val reason: String,
        val source: String
    )

    private fun testSource(
        pans: List<KaoPan>,
        boxes: List<KaoPanBox>
    ): SnapshotSyncPolicy.SnapshotSource =
        object : SnapshotSyncPolicy.SnapshotSource {
            override fun getKaoPanList(): List<KaoPan> = pans
            override fun getKaoPanBoxList(): List<KaoPanBox> = boxes
        }

    private fun pan(positionSn: Int, hasSausage: Boolean): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
        }

    private fun box(positionSn: Int, num: Int): KaoPanBox =
        KaoPanBox().apply {
            this.positionSn = positionSn
            this.num = num
        }
}
