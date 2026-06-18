package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaturedZoneKeepWarmSettlerTest {

    @Test
    fun settleMaturedZones_shouldSettleZoneOneWhenMaturedPansAndRestEmptyEvenIfMachineBusy() {
        val nowMs = 10_000L
        val config = config().apply {
            moveStatus = 2
        }
        val pans = pans(
            *(1..5).map { maturedBakingPan(it) }.toTypedArray()
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config, nowMs) {
            appliedAreas += it
        }

        assertEquals(listOf(1), appliedAreas)
        assertEquals(listOf(1), result.map { it.area })
        assertEquals((1..5).toList(), result.single().positions)
        (1..5).forEach { position ->
            val pan = pans.first { it.positionSn == position }
            assertEquals(0L, pan.startTime)
            assertEquals(nowMs, pan.holdingTime)
            assertEquals(2, pan.status)
        }
    }

    @Test
    fun settleMaturedZones_shouldNotSettleZoneOneWhenSamePhysicalZoneHasUnmaturedPan() {
        val nowMs = 10_000L
        val pans = pans(
            *(1..5).map { maturedBakingPan(it) }.toTypedArray(),
            unmaturedBakingPan(6)
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config(), nowMs) {
            appliedAreas += it
        }

        assertTrue(result.isEmpty())
        assertTrue(appliedAreas.isEmpty())
        assertEquals(1, pans.first { it.positionSn == 1 }.status)
        assertEquals(1L, pans.first { it.positionSn == 1 }.startTime)
    }

    @Test
    fun settleMaturedZones_shouldSettleZoneTwoOnlyWhenThirteenToTwentyOneHaveNoUnmaturedPan() {
        val nowMs = 10_000L
        val pans = pans(
            *(13..18).map { maturedBakingPan(it) }.toTypedArray()
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config(), nowMs) {
            appliedAreas += it
        }

        assertEquals(listOf(2), appliedAreas)
        assertEquals(listOf(2), result.map { it.area })
        assertEquals((13..18).toList(), result.single().positions)
    }

    @Test
    fun settleMaturedZones_shouldNotSettleZoneTwoWhenNineteenIsStillBaking() {
        val nowMs = 10_000L
        val pans = pans(
            *(13..18).map { maturedBakingPan(it) }.toTypedArray(),
            unmaturedBakingPan(19)
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config(), nowMs) {
            appliedAreas += it
        }

        assertTrue(result.isEmpty())
        assertTrue(appliedAreas.isEmpty())
        assertEquals(1, pans.first { it.positionSn == 13 }.status)
        assertEquals(1L, pans.first { it.positionSn == 13 }.startTime)
    }

    @Test
    fun settleMaturedZones_shouldSkipOldLowHeatPansButStillSettleNineteenToTwentyOneDuringLowToHigh() {
        val nowMs = 10_000L
        val config = config().apply {
            modeType = 2
            transitionMode = 1
        }
        val pans = pans(
            maturedBakingPan(13),
            maturedBakingPan(14),
            maturedBakingPan(19),
            maturedBakingPan(20)
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config, nowMs) {
            appliedAreas += it
        }

        assertEquals(listOf(2), appliedAreas)
        assertEquals(listOf(2), result.map { it.area })
        assertEquals(listOf(19, 20), result.single().positions)
        assertEquals(1, pans.first { it.positionSn == 13 }.status)
        assertEquals(1L, pans.first { it.positionSn == 13 }.startTime)
        assertEquals(2, pans.first { it.positionSn == 19 }.status)
        assertEquals(0L, pans.first { it.positionSn == 19 }.startTime)
    }

    @Test
    fun settleMaturedZones_shouldSettlePartialZoneThreeWhenAllExistingZoneThreePansAreMatured() {
        val nowMs = 10_000L
        val pans = pans(
            *(25..29).map { maturedBakingPan(it) }.toTypedArray()
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config(), nowMs) {
            appliedAreas += it
        }

        assertEquals(listOf(3), appliedAreas)
        assertEquals(listOf(3), result.map { it.area })
        assertEquals((25..29).toList(), result.single().positions)
        (25..29).forEach { position ->
            assertEquals(2, pans.first { it.positionSn == position }.status)
        }
    }

    @Test
    fun settleMaturedZones_shouldNotSettleZoneThreeWhenSamePhysicalZoneHasUnmaturedPan() {
        val nowMs = 10_000L
        val pans = pans(
            *(25..30).map { maturedBakingPan(it) }.toTypedArray(),
            unmaturedBakingPan(31)
        )
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config(), nowMs) {
            appliedAreas += it
        }

        assertTrue(result.isEmpty())
        assertTrue(appliedAreas.isEmpty())
        assertEquals(1, pans.first { it.positionSn == 25 }.status)
        assertEquals(1L, pans.first { it.positionSn == 25 }.startTime)
    }

    @Test
    fun settleMaturedZones_shouldSkipDuringFaultHeatPause() {
        val nowMs = 10_000L
        val config = config().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 5_000L
        }
        val pans = pans(maturedBakingPan(1))
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config, nowMs) {
            appliedAreas += it
        }

        assertTrue(result.isEmpty())
        assertTrue(appliedAreas.isEmpty())
        assertEquals(1, pans.first { it.positionSn == 1 }.status)
    }

    @Test
    fun settleMaturedZones_shouldSkipWhenPendingDiscardExists() {
        val nowMs = 10_000L
        val config = config().apply {
            pendingDiscardPositions.add(1)
        }
        val pans = pans(maturedBakingPan(1))
        val appliedAreas = mutableListOf<Int>()

        val result = MaturedZoneKeepWarmSettler.settleMaturedZones(pans, config, nowMs) {
            appliedAreas += it
        }

        assertTrue(result.isEmpty())
        assertTrue(appliedAreas.isEmpty())
        assertEquals(1, pans.first { it.positionSn == 1 }.status)
    }

    private fun config(): AppConfigBean =
        AppConfigBean().apply {
            pendingDiscardPositions.clear()
        }

    private fun pans(vararg overrides: KaoPan): List<KaoPan> {
        val byPosition = overrides.associateBy { it.positionSn }
        return (1..33).map { position -> byPosition[position] ?: emptyPan(position) }
    }

    private fun emptyPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = false
            this.status = 0
        }

    private fun maturedBakingPan(positionSn: Int): KaoPan =
        bakingPan(positionSn = positionSn, startTime = 1L, bakingTime = 8_000L)

    private fun unmaturedBakingPan(positionSn: Int): KaoPan =
        bakingPan(positionSn = positionSn, startTime = 7_000L, bakingTime = 8_000L)

    private fun bakingPan(
        positionSn: Int,
        startTime: Long,
        bakingTime: Long
    ): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 1
            this.startTime = startTime
            this.holdingTime = 0L
            this.bakingTime = bakingTime
        }
}
