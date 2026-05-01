package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureControlPolicyTest {

    @Test
    fun decideZoneTemperatures_shouldStopAllZonesWhenAllPansAreEmpty() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(),
            keepWarmTemperature = 50,
            heatingTemperature = 160
        )

        assertEquals(listOf(0, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldHeatZoneWhenZoneHasBakingSausage() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 3, hasSausage = true, status = 1)),
            keepWarmTemperature = 50,
            heatingTemperature = 160
        )

        assertEquals(listOf(160, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldKeepWarmZoneWhenZoneHasHoldingSausage() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 8, hasSausage = true, status = 2, holdingTime = 100L)),
            keepWarmTemperature = 55,
            heatingTemperature = 165
        )

        assertEquals(listOf(55, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldPreferHeatingWhenZoneHasBakingAndHoldingSausage() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(
                pan(positionSn = 1, hasSausage = true, status = 2, holdingTime = 100L),
                pan(positionSn = 9, hasSausage = true, status = 1)
            ),
            keepWarmTemperature = 55,
            heatingTemperature = 170
        )

        assertEquals(listOf(170, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldUsePhysicalZoneTwoAs13To21Only() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(
                pan(positionSn = 10, hasSausage = true, status = 1),
                pan(positionSn = 13, hasSausage = true, status = 2, holdingTime = 100L),
                pan(positionSn = 21, hasSausage = true, status = 2, holdingTime = 100L)
            ),
            keepWarmTemperature = 60,
            heatingTemperature = 180
        )

        assertEquals(listOf(0, 60, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideEmptyPanStop_shouldStopOnFirstEmptySnapshot() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            previousHasAnySausage = null,
            force = false
        )

        assertEquals(true, result.shouldStopAllHeating)
        assertEquals(false, result.nextHasAnySausage)
    }

    @Test
    fun decideEmptyPanStop_shouldNotStopAgainWhenPansRemainEmpty() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            previousHasAnySausage = false,
            force = false
        )

        assertEquals(false, result.shouldStopAllHeating)
        assertEquals(false, result.nextHasAnySausage)
    }

    @Test
    fun decideEmptyPanStop_shouldStopWhenPansChangeFromOccupiedToEmpty() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            previousHasAnySausage = true,
            force = false
        )

        assertEquals(true, result.shouldStopAllHeating)
        assertEquals(false, result.nextHasAnySausage)
    }

    @Test
    fun decideEmptyPanStop_shouldStopWhenForcedEvenIfPansRemainEmpty() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            previousHasAnySausage = false,
            force = true
        )

        assertEquals(true, result.shouldStopAllHeating)
        assertEquals(false, result.nextHasAnySausage)
    }

    @Test
    fun shouldRestoreHeatingAfterStartup_shouldRestoreOnlyOnceAfterModbusConnected() {
        assertEquals(
            true,
            TemperatureControlPolicy.shouldRestoreHeatingAfterStartup(
                hasRestored = false,
                modbusConnected = true
            )
        )
        assertEquals(
            false,
            TemperatureControlPolicy.shouldRestoreHeatingAfterStartup(
                hasRestored = true,
                modbusConnected = true
            )
        )
        assertEquals(
            false,
            TemperatureControlPolicy.shouldRestoreHeatingAfterStartup(
                hasRestored = false,
                modbusConnected = false
            )
        )
    }

    private fun pans(vararg overrides: KaoPan): List<KaoPan> {
        val byPosition = overrides.associateBy { it.positionSn }
        return (1..33).map { position -> byPosition[position] ?: pan(positionSn = position) }
    }

    private fun pan(
        positionSn: Int,
        hasSausage: Boolean = false,
        status: Int = 0,
        holdingTime: Long = 0L
    ): KaoPan {
        return KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = hasSausage
            this.status = status
            this.holdingTime = holdingTime
        }
    }
}
