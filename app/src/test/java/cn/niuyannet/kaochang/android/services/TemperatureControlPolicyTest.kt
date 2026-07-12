package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureControlPolicyTest {

    @Test
    fun projectBatchIntent_shouldSeparateRemainingTargetsAndRetainedReturnBindings() {
        val intent = SupplementBatchIntent(
            batchId = "batch",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 25),
                SupplementBatchIntentTarget(positionSn = 10),
                SupplementBatchIntentTarget(positionSn = 26, skippedBySupplyDegrade = true),
                SupplementBatchIntentTarget(positionSn = 27, cancelledByFaultTimeout = true),
                SupplementBatchIntentTarget(positionSn = 28, returnPositionSn = 3, completed = true)
            )
        )

        val result = TemperatureControlPolicy.projectBatchIntent(intent)

        assertEquals(setOf(25), result.pendingSupplementPositions)
        assertEquals(setOf(3, 28), result.pendingReturnPositions)
    }

    @Test
    fun hasAnySausageInPhysicalPans_shouldIncludeTransitionPositions() {
        val result = TemperatureControlPolicy.hasAnySausageInPhysicalPans(
            pans = pans(
                pan(positionSn = 10, hasSausage = true),
                pan(positionSn = 22, hasSausage = true)
            )
        )

        assertEquals(true, result)
    }

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
    fun decideZoneTemperatures_shouldKeepPendingZoneTwoHeatingAfterConfigChangesTo130() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 3, hasSausage = true, status = 1)),
            keepWarmTemperature = 50,
            heatingTemperature = 130,
            pendingSupplementPositions = setOf(13, 21)
        )

        assertEquals(listOf(130, 130, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldIgnoreTransitionPositionsFromBatchProjection() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(),
            keepWarmTemperature = 50,
            heatingTemperature = 160,
            pendingSupplementPositions = setOf(10, 11, 12, 22, 23, 24),
            pendingReturnPositions = setOf(10, 12, 22, 24)
        )

        assertEquals(listOf(0, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldKeepWarmSourceAndTargetZonesForPendingReturn() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(),
            keepWarmTemperature = 55,
            heatingTemperature = 165,
            pendingReturnPositions = setOf(13, 25)
        )

        assertEquals(listOf(0, 55, 55), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldPreferPendingSupplementOverReturnAndHolding() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 13, hasSausage = true, status = 2, holdingTime = 100L)),
            keepWarmTemperature = 55,
            heatingTemperature = 170,
            heatingOffsetsByArea = mapOf(2 to 4),
            pendingSupplementPositions = setOf(21),
            pendingReturnPositions = setOf(15)
        )

        assertEquals(TemperatureControlPolicy.ZoneMode.HEATING, result[1].mode)
        assertEquals(174, result[1].targetTemperature)
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
    fun decideZoneTemperatures_shouldKeepWarmZoneOneAndStopEmptyZonesAfterHighToLowCloseout() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(
                pan(positionSn = 1, hasSausage = true, status = 2, holdingTime = 100L),
                pan(positionSn = 9, hasSausage = true, status = 2, holdingTime = 100L)
            ),
            keepWarmTemperature = 100,
            heatingTemperature = 180
        )

        assertEquals(listOf(100, 0, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldIgnorePendingDiscardPansWhenRefreshingTemperature() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 8, hasSausage = true, status = 2, holdingTime = 100L)),
            keepWarmTemperature = 100,
            heatingTemperature = 180,
            pendingDiscardPositions = listOf(8)
        )

        assertEquals(listOf(0, 0, 0), result.map { it.targetTemperature })
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
    fun decideZoneTemperatures_shouldApplyHeatingOffsetOnlyToMatchingHeatingZone() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(
                pan(positionSn = 3, hasSausage = true, status = 1),
                pan(positionSn = 15, hasSausage = true, status = 1)
            ),
            keepWarmTemperature = 60,
            heatingTemperature = 150,
            heatingOffsetsByArea = mapOf(1 to 5, 2 to -3, 3 to 8)
        )

        assertEquals(listOf(155, 147, 0), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldAllowTargetTemperatureAbove199AndClampNonPositiveToZero() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(
                pan(positionSn = 3, hasSausage = true, status = 1),
                pan(positionSn = 15, hasSausage = true, status = 1),
                pan(positionSn = 26, hasSausage = true, status = 2, holdingTime = 100L)
            ),
            keepWarmTemperature = 250,
            heatingTemperature = 198,
            heatingOffsetsByArea = mapOf(1 to 5, 2 to -250)
        )

        assertEquals(listOf(203, 0, 250), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldNotApplyHeatingOffsetToKeepWarmZone() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(pan(positionSn = 26, hasSausage = true, status = 2, holdingTime = 100L)),
            keepWarmTemperature = 55,
            heatingTemperature = 160,
            heatingOffsetsByArea = mapOf(3 to 10)
        )

        assertEquals(listOf(0, 0, 55), result.map { it.targetTemperature })
    }

    @Test
    fun decideZoneTemperatures_shouldKeepStopZoneAtZeroEvenWhenHeatingOffsetExists() {
        val result = TemperatureControlPolicy.decideZoneTemperatures(
            pans = pans(),
            keepWarmTemperature = 55,
            heatingTemperature = 160,
            heatingOffsetsByArea = mapOf(1 to 10, 2 to -5, 3 to 20)
        )

        assertEquals(listOf(0, 0, 0), result.map { it.targetTemperature })
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
    fun decideEmptyPanStop_shouldTreatPendingSupplementAsActiveDemand() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            hasPendingSupplementTarget = true,
            previousHasAnySausage = true,
            force = false
        )

        assertEquals(false, result.shouldStopAllHeating)
        assertEquals(true, result.nextHasAnySausage)
    }

    @Test
    fun decideEmptyPanStop_shouldStopAfterPendingSupplementDemandClears() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            hasPendingSupplementTarget = false,
            previousHasAnySausage = true,
            force = false
        )

        assertEquals(true, result.shouldStopAllHeating)
        assertEquals(false, result.nextHasAnySausage)
    }

    @Test
    fun decideEmptyPanStop_shouldHonorForceEvenWhenPendingSupplementExists() {
        val result = TemperatureControlPolicy.decideEmptyPanStop(
            hasAnySausage = false,
            hasPendingSupplementTarget = true,
            previousHasAnySausage = true,
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
