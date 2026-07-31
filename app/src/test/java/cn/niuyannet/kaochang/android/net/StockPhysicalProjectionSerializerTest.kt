package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import com.alibaba.fastjson.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockPhysicalProjectionSerializerTest {

    @Test
    fun serialize_shouldKeepInventoryButExcludeTakeStatusAuthorityFields() {
        val box = KaoPanBox().apply {
            positionSn = 21
            num = 8
            tasteCode = "A"
            cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
            takeStatusRevision = 9L
        }

        val payload = JSON.parseArray(StockPhysicalProjectionSerializer.serialize(listOf(box)))
        val item = payload.getJSONObject(0)

        assertEquals(21, item.getIntValue("positionSn"))
        assertEquals(8, item.getIntValue("num"))
        assertFalse(item.containsKey("cmdStatusTake"))
        assertFalse(item.containsKey("takeStatusRevision"))
    }

    @Test
    fun applyInventoryFields_shouldKeepConcurrentTakeFailureAndRevision() {
        val current = KaoPanBox().apply {
            positionSn = 21
            num = 3
            tasteCode = "A"
            cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
            takeStatusRevision = 10L
        }
        val proposed = KaoPanBox().apply {
            positionSn = 21
            num = 15
            tasteCode = "B"
            cmdStatusTake = 0
            takeStatusRevision = 9L
        }

        val changed = StockPhysicalProjectionSerializer.applyInventoryFields(
            current = listOf(current),
            proposed = listOf(proposed),
            targetPositions = setOf(21)
        )

        assertTrue(changed)
        assertEquals(15, current.num)
        assertEquals("B", current.tasteCode)
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, current.cmdStatusTake)
        assertEquals(10L, current.takeStatusRevision)
    }

    @Test
    fun projectForTargets_shouldExcludeUnselectedBoxesFromInventoryWrite() {
        val boxes = listOf(
            KaoPanBox().apply { positionSn = 1; num = 7 },
            KaoPanBox().apply { positionSn = 2; num = 9 }
        )

        val projected = StockPhysicalProjectionSerializer.projectForTargets(boxes, setOf(2))

        assertEquals(listOf(2), projected.map { it.positionSn })
    }
}
