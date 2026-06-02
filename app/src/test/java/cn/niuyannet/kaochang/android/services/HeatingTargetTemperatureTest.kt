package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatingTargetTemperatureTest {

    @Test
    fun targetHeatingTemperature_shouldApplyAreaOffsets() {
        assertEquals(210, KaoChangOperate.targetHeatingTemperature(1, 180, 30, 0, -30))
        assertEquals(180, KaoChangOperate.targetHeatingTemperature(2, 180, 30, 0, -30))
        assertEquals(150, KaoChangOperate.targetHeatingTemperature(3, 180, 30, 0, -30))
    }

    @Test
    fun targetHeatingTemperature_shouldClampNonPositiveResultToZero() {
        assertEquals(0, KaoChangOperate.targetHeatingTemperature(3, 20, 0, 0, -30))
    }
}
