package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import com.alibaba.fastjson.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeStatusUpdatePayloadBuilderTest {
    @Test
    fun build_shouldSendExactlyOneBoxWithExpectedRevision() {
        val box = KaoPanBox().apply {
            positionSn = 21
            cmdStatusTake = AtomicActionCodes.IR_PICK_FAILED
            takeStatusRevision = 7L
            num = 8
        }

        val request = TakeStatusUpdatePayloadBuilder.build("device-1", "credential", box)
        val content = JSON.parseArray(request.getString("content"))

        assertTrue(request.getBooleanValue("takeStatusOnly"))
        assertEquals(1, content.size)
        assertEquals(21, content.getJSONObject(0).getIntValue("positionSn"))
        assertEquals(AtomicActionCodes.IR_PICK_FAILED, content.getJSONObject(0).getIntValue("cmdStatusTake"))
        assertEquals(7L, content.getJSONObject(0).getLongValue("takeStatusRevision"))
    }
}
