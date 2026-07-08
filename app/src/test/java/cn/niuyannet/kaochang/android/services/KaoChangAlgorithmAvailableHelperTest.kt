package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KaoChangAlgorithmAvailableHelperTest {

    @Test
    fun findNextOpeningSellBatch_shouldPreferFirstHighConcurrencyBatchWhenItWillBecomeSellableSooner() {
        val now = 1_000_000L
        val highOpeningPans = buildList {
            addAll((1..9).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 90_000L,
                    bakingTime = 120_000L
                )
            })
            addAll((10..21).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 30_000L,
                    bakingTime = 120_000L
                )
            })
        }

        val batch = findNextOpeningSellBatch(
            isLowOrTransition = false,
            sellArea = highOpeningPans,
            now = now,
            config = inactiveFaultConfig()
        )

        assertNotNull(batch)
        assertEquals("1~9开盘批次", batch!!.name)
        assertEquals(30_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextOpeningSellBatch_shouldUseWholeFrontAreaWhenLowConcurrency() {
        val now = 1_000_000L
        val lowOpeningPans = (1..9).map { position ->
            heatingPan(
                positionSn = position,
                startTime = now - 75_000L,
                bakingTime = 120_000L
            )
        }

        val batch = findNextOpeningSellBatch(
            isLowOrTransition = true,
            sellArea = lowOpeningPans,
            now = now,
            config = inactiveFaultConfig()
        )

        assertNotNull(batch)
        assertEquals("1~9开盘批次", batch!!.name)
        assertEquals(45_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextSupplementHeatBatch_shouldUseLowHeatGroupsDuringLowToHighTransition() {
        val now = 1_000_000L
        val heatArea = buildList {
            addAll((13..15).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 30_000L,
                    bakingTime = 120_000L
                )
            })
            addAll((16..18).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 80_000L,
                    bakingTime = 120_000L
                )
            })
            addAll((25..33).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 110_000L,
                    bakingTime = 120_000L
                )
            })
        }

        val batch = findNextSupplementHeatBatch(
            isLowOrTransition = true,
            heatArea = heatArea,
            now = now,
            config = inactiveFaultConfig()
        )

        assertNotNull(batch)
        assertEquals("16~18补肠批次", batch!!.name)
        assertEquals(40_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextSupplementHeatBatch_shouldUseHighHeatGroupDuringHighConcurrency() {
        val now = 1_000_000L
        val heatArea = buildList {
            addAll((13..18).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 110_000L,
                    bakingTime = 120_000L
                )
            })
            addAll((25..33).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 60_000L,
                    bakingTime = 120_000L
                )
            })
        }

        val batch = findNextSupplementHeatBatch(
            isLowOrTransition = false,
            heatArea = heatArea,
            now = now,
            config = inactiveFaultConfig()
        )

        assertNotNull(batch)
        assertEquals("25~33补肠批次", batch!!.name)
        assertEquals(60_000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextAvailableHeatBatch_shouldUseOldLowHeatBatchDuringLowToHighTransition() {
        val now = 30L * 60L * 1000L
        val pans = buildList {
            addAll((1..9).map { emptyPan(positionSn = it) })
            add(
                heatingPan(
                    positionSn = 13,
                    startTime = now - 19L * 60L * 1000L,
                    bakingTime = 30L * 60L * 1000L
                )
            )
            add(
                heatingPan(
                    positionSn = 14,
                    startTime = now - 17L * 60L * 1000L,
                    bakingTime = 30L * 60L * 1000L
                )
            )
            add(
                heatingPan(
                    positionSn = 15,
                    startTime = now - 13L * 60L * 1000L,
                    bakingTime = 30L * 60L * 1000L
                )
            )
            addAll((25..33).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now,
                    bakingTime = 30L * 60L * 1000L
                )
            })
        }
        val config = inactiveFaultConfig().apply {
            modeType = 2
            transitionMode = 1
            highConcurrencyResidualDrain = 0
        }

        val batch = findNextAvailableHeatBatch(
            config = config,
            kaoPanList = pans,
            now = now
        )

        assertNotNull(batch)
        assertEquals("13~15补肠批次", batch!!.name)
        assertEquals(17L * 60L * 1000L, batch.remainHeatTimeMs)
    }

    @Test
    fun findNextAvailableHeatBatch_shouldNotUseHighHeatBatchBeforeOldLowHeatClearedDuringLowToHighTransition() {
        val now = 30L * 60L * 1000L
        val pans = buildList {
            addAll((1..9).map { emptyPan(positionSn = it) })
            addAll((13..15).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 5L * 60L * 1000L,
                    bakingTime = 30L * 60L * 1000L
                )
            })
            addAll((25..33).map { position ->
                heatingPan(
                    positionSn = position,
                    startTime = now - 20L * 60L * 1000L,
                    bakingTime = 30L * 60L * 1000L
                )
            })
        }
        val config = inactiveFaultConfig().apply {
            modeType = 2
            transitionMode = 1
            highConcurrencyResidualDrain = 0
        }

        val batch = findNextAvailableHeatBatch(
            config = config,
            kaoPanList = pans,
            now = now
        )

        assertNotNull(batch)
        assertEquals("13~15补肠批次", batch!!.name)
        assertEquals(25L * 60L * 1000L, batch.remainHeatTimeMs)
    }

    @Test
    fun availableProjectionAreas_shouldKeepLowToHighPurchaseAreaInOneToNineAndTrackOldLowHeat() {
        val pans = (1..33).map { position -> emptyPan(positionSn = position) }
        val config = inactiveFaultConfig().apply {
            modeType = 2
            transitionMode = 1
            highConcurrencyResidualDrain = 0
        }

        assertEquals(
            (1..9).toList(),
            findAvailableProjectionSellArea(config, pans).map { it.positionSn }
        )
        assertEquals(
            ((1..9) + (13..18) + (25..33)).toList(),
            findAvailableProjectionTrackedArea(config, pans).map { it.positionSn }
        )
    }

    private fun heatingPan(
        positionSn: Int,
        startTime: Long,
        bakingTime: Long
    ): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setHasSausage(true)
        setStartTime(startTime)
        setBakingTime(bakingTime)
        setHoldingTime(0L)
    }

    private fun emptyPan(positionSn: Int): KaoPan = KaoPan().apply {
        setPositionSn(positionSn)
        setHasSausage(false)
        setStartTime(0L)
        setBakingTime(30L * 60L * 1000L)
        setHoldingTime(0L)
    }

    private fun inactiveFaultConfig(): AppConfigBean = AppConfigBean()
}
