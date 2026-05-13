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

    private fun inactiveFaultConfig(): AppConfigBean = AppConfigBean()
}
