package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileTestBootstrap
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class KaoPanHelperBakingTimeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun pinSchedulerProfile() = SchedulerProfileTestBootstrap.pinLegacyA()
    }

    @Test
    fun refreshActiveBakingTime_shouldOnlyUpdateBakingPans() {
        val bakingPan = pan(positionSn = 1, status = 1, startTime = 1_000L, bakingTime = 40L * 60L * 1000L)
        val holdingPan = pan(positionSn = 2, status = 2, startTime = 0L, bakingTime = 40L * 60L * 1000L)
        val emptyPan = pan(positionSn = 3, status = 0, startTime = 0L, bakingTime = 40L * 60L * 1000L).apply {
            isHasSausage = false
        }

        val correctedCount = KaoPanHelper.refreshActiveBakingTime(
            pans = listOf(bakingPan, holdingPan, emptyPan),
            bakingTimeMinutes = 30
        )

        assertEquals(1, correctedCount)
        assertEquals(30L * 60L * 1000L, bakingPan.bakingTime)
        assertEquals(40L * 60L * 1000L, holdingPan.bakingTime)
        assertEquals(40L * 60L * 1000L, emptyPan.bakingTime)
    }

    private fun pan(positionSn: Int, status: Int, startTime: Long, bakingTime: Long): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = status
            this.startTime = startTime
            this.bakingTime = bakingTime
        }
}
