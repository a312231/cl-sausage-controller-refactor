package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmStateLogFormatterTest {

    @Test
    fun stateName_shouldResolveMainTrafficStates() {
        assertEquals("低并发稳态", AlgorithmStateLogFormatter.stateName(1, 0, 0, true))
        assertEquals("低转高过渡态", AlgorithmStateLogFormatter.stateName(2, 1, 0, true))
        assertEquals("高并发稳态", AlgorithmStateLogFormatter.stateName(2, 0, 0, true))
        assertEquals("高转低过渡态", AlgorithmStateLogFormatter.stateName(2, 2, 0, true))
        assertEquals("低并发稳态", AlgorithmStateLogFormatter.stateName(1, 0, 1, true))
    }

    @Test
    fun stateName_shouldShowPausedWhenSchedulingDisabled() {
        assertEquals("算法关闭/暂停调度", AlgorithmStateLogFormatter.stateName(1, 0, 0, false))
    }

    @Test
    fun targetText_shouldFollowTransitionModeOnly() {
        assertEquals("无切换", AlgorithmStateLogFormatter.targetText(0))
        assertEquals("高并发", AlgorithmStateLogFormatter.targetText(1))
        assertEquals("低并发", AlgorithmStateLogFormatter.targetText(2))
    }

    @Test
    fun buildChangeLog_shouldIncludeReadableStateChangeAndRuntimeFields() {
        val log = AlgorithmStateLogFormatter.buildChangeLog(
            previousStateName = "低并发稳态",
            modeType = 2,
            transitionMode = 1,
            highConcurrencyResidualDrain = 0,
            onlineStatus = 1,
            supplyStatus = 0,
            homeQrHidden = 0,
            inspectionMode = 0,
            moveStatus = 0,
            errorStatus = 0,
            isEnable = 1,
            schedulingEnabled = true
        )

        assertTrue(log.contains("【烤制算法】状态变化：低并发稳态 -> 低转高过渡态"))
        assertTrue(log.contains("reason=低转高触发"))
        assertTrue(log.contains("modeType（并发模式）=2（高并发）"))
        assertTrue(log.contains("transitionMode（并发切换过渡态）=1（低转高过渡）"))
        assertTrue(log.contains("targetMode（并发切换目标）=高并发"))
        assertTrue(log.contains("highConcurrencyResidualDrain（高并发残留消化态）=0（关闭）"))
        assertTrue(log.contains("onlineStatus（设备营业状态）=1（运营中）"))
        assertTrue(log.contains("homeQrHidden（首页二维码隐藏）=0（显示首页二维码）"))
        assertTrue(log.contains("inspectionMode（检修模式）=0（正常）"))
        assertTrue(log.contains("moveStatus（机械动作状态）=0（机械臂空闲）"))
        assertTrue(log.contains("schedulingEnabled=true"))
    }
}
