package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.annotation.JSONField
import org.junit.Assert.assertFalse
import org.junit.Test

class KaoPanSerializationTest {

    @Test
    fun uiReservationFields_shouldBeExplicitlyExcludedFromFastjsonSerialization() {
        val pan = KaoPan().apply {
            positionSn = 6
            status = 0
            uiReservationState = KaoPan.UI_RESERVATION_WAIT_RETURN
            uiReservationTaste = Taste("B")
        }

        assertJsonFieldExcluded("uiReservationState")
        assertJsonFieldExcluded("uiReservationTaste")

        val json = JSON.toJSONString(pan)

        assertFalse(json.contains("uiReservationState"))
        assertFalse(json.contains("uiReservationTaste"))
    }

    private fun assertJsonFieldExcluded(fieldName: String) {
        val annotation = KaoPan::class.java
            .getDeclaredField(fieldName)
            .getAnnotation(JSONField::class.java)
            ?: error("$fieldName should declare JSONField exclusion")

        assertFalse(annotation.serialize)
        assertFalse(annotation.deserialize)
    }
}
