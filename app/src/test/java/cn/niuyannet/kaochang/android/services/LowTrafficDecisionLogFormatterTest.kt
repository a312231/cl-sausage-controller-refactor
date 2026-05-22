package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowTrafficDecisionLogFormatterTest {

    @Test
    fun lowInitializationDecision_shouldExplainWhenLocalPanStateIsNotAllEmpty() {
        val message = LowTrafficDecisionLogFormatter.lowInitializationDecision(
            frontEmpty = 9,
            frontCapacity = 9,
            heatEmpty = 5,
            heatCapacity = 6,
            schedulingEnabled = true,
            boxSupplyAvailable = true,
            errorStatus = 0,
            isBeforeCloseWindow = false
        )

        assertTrue(message.startsWith("【低并发调度】本轮未触发低并发初始化"))
        assertTrue(message.contains("原因=本地烤盘未全空"))
        assertTrue(message.contains("1~9空位=9/9"))
        assertTrue(message.contains("13~18空位=5/6"))
        assertTrue(message.contains("总空位=14/15"))
        assertTrue(message.contains("boxSupplyAvailable（烤肠箱可用库存）=true"))
        assertTrue(message.contains("schedulingEnabled（调度允许）=true"))
        assertTrue(message.contains("errorStatus（设备故障状态）=0（正常）"))
        assertTrue(message.contains("isBeforeCloseWindow（停业前收口窗口）=false"))
    }

    @Test
    fun lowInitializationDecision_shouldExplainWhenInitializationWillTrigger() {
        val message = LowTrafficDecisionLogFormatter.lowInitializationDecision(
            frontEmpty = 9,
            frontCapacity = 9,
            heatEmpty = 6,
            heatCapacity = 6,
            schedulingEnabled = true,
            boxSupplyAvailable = true,
            errorStatus = 0,
            isBeforeCloseWindow = false
        )

        assertEquals(
            "【低并发调度】本轮触发低并发初始化：原因=满足低并发初始化条件，1~9空位=9/9，13~18空位=6/6，总空位=15/15，boxSupplyAvailable（烤肠箱可用库存）=true，schedulingEnabled（调度允许）=true，errorStatus（设备故障状态）=0（正常），isBeforeCloseWindow（停业前收口窗口）=false",
            message
        )
    }
}
