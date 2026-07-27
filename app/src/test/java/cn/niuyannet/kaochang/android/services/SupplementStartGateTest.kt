package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementStartGateTest {

    @Test
    fun evaluate_shouldAllowOnlyOperatingNormalDevice() {
        val decision = SupplementStartGate.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            isEnable = 1,
            faultHeatPauseActive = 0
        )

        assertTrue(decision.canStart)
        assertTrue(decision.reason.contains("允许启动补肠"))
    }

    @Test
    fun evaluate_shouldRejectMaintenanceStatus() {
        val decision = SupplementStartGate.evaluate(
            onlineStatus = 3,
            errorStatus = 0,
            isEnable = 1,
            faultHeatPauseActive = 0
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("onlineStatus（设备营业状态）=3（维护中）"))
    }

    @Test
    fun evaluate_shouldRejectHardwareErrorStatus() {
        val decision = SupplementStartGate.evaluate(
            onlineStatus = 1,
            errorStatus = 2,
            isEnable = 1,
            faultHeatPauseActive = 0
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("errorStatus（设备故障状态）=2（通信/硬件级故障）"))
    }

    @Test
    fun evaluate_shouldRejectWhenRoastingAlgorithmIsDisabled() {
        val decision = SupplementStartGate.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            isEnable = 0,
            faultHeatPauseActive = 0
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("isEnable（烤肠算法开关）=0（关闭）"))
    }

    @Test
    fun evaluate_shouldRejectWhileFaultHeatPauseIsActive() {
        val decision = SupplementStartGate.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            isEnable = 1,
            faultHeatPauseActive = 1
        )

        assertFalse(decision.canStart)
        assertTrue(decision.reason.contains("faultHeatPauseActive（故障停热暂停）=1（暂停中）"))
    }
}
