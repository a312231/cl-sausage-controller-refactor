package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.TrafficModeHelper
import cn.niuyannet.kaochang.scheduler.PanLifecycle
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SchedulerShadowAdapterTest {

    @Before
    fun setUpAppConfig() {
        val field = AppConfig::class.java.getDeclaredField("appConfigBean")
        field.isAccessible = true
        field.set(AppConfig, AppConfigBean())
    }

    @Test
    fun mapper_shouldHandleJavaPlatformNullsUnknownStatusAndDefensiveCollections() {
        val pending = mutableSetOf(2)
        val pans = mutableListOf(
            KaoPan().apply {
                positionSn = 1
                status = KaoPan.STATUS_SELLABLE_KEEP_WARM
                isHasSausage = true
                holdingTime = 100L
                closeTime = 200L
                taste = null
            },
            KaoPan().apply {
                positionSn = 2
                status = 99
                taste = Taste().apply { tasteCode = null }
            }
        )

        val input = SchedulerShadowInputMapper.map(
            nowMs = 150L,
            tasteCode = "A",
            productId = 104,
            sellRange = 1..9,
            pendingDiscardPositions = pending,
            pans = pans
        )
        pending.add(3)
        pans.clear()

        assertEquals(setOf(2), input.pendingDiscardPositions)
        assertEquals(2, input.pans.size)
        assertNull(input.pans[0].tasteCode)
        assertNull(input.pans[0].productId)
        assertNull(input.pans[1].tasteCode)
        assertEquals(PanLifecycle.UNKNOWN, input.pans[1].lifecycle)
    }

    @Test
    fun oracle_shouldMatchRealLegacySelectorForTenThousandValidSamples() {
        val random = Random(2_504_200L)
        val config = AppConfig.getAppConfig()

        repeat(10_000) { sample ->
            val nowMs = 1_000_000L + sample
            val sellEnd = if (random.nextBoolean()) 9 else 21
            val pending = (1..sellEnd).filter { random.nextInt(12) == 0 }.toSet()
            config.pendingDiscardPositions.clear()
            config.pendingDiscardPositions.addAll(pending)
            val pans = (1..33).map { position -> validRandomPan(random, position, nowMs) }
            val rangePans = TrafficModeHelper.getTrafficModePositions(1, sellEnd, pans)
            val legacy = TrafficModeHelper.selectSellCandidate(rangePans, "A", 104, nowMs)?.positionSn
            val input = SchedulerShadowInputMapper.map(
                nowMs = nowMs,
                tasteCode = "A",
                productId = 104,
                sellRange = 1..sellEnd,
                pendingDiscardPositions = pending,
                pans = pans
            )

            assertEquals("sample=$sample", legacy, LegacySellCandidateOracle.selectPosition(input))
        }
    }

    private fun validRandomPan(random: Random, position: Int, nowMs: Long): KaoPan {
        val holdingTime = nowMs + random.nextInt(2_001) - 1_000L
        return KaoPan().apply {
            positionSn = position
            isHasSausage = random.nextBoolean()
            status = random.nextInt(4)
            this.holdingTime = if (random.nextInt(8) == 0) 0L else holdingTime
            closeTime = listOf(0L, 500L, 1_000L, 2_000L)[random.nextInt(4)]
            taste = Taste().apply {
                tasteCode = if (random.nextBoolean()) "A" else "B"
                productId = if (random.nextBoolean()) 104 else 205
            }
        }
    }
}
