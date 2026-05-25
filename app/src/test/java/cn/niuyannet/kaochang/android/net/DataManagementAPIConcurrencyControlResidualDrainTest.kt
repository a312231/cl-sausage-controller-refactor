package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class DataManagementAPIConcurrencyControlResidualDrainTest {

    @Test
    fun resolveConcurrencyResidualDrain_shouldKeepResidualDrainForLowStableWhenHighAreaHasResidual() {
        val residualDrain = DataManagementAPI.resolveConcurrencyResidualDrain(
            resolvedModeType = 1,
            resolvedTransitionMode = 0,
            previousModeType = 2,
            previousTransitionMode = 2,
            previousResidualDrain = 0,
            kaoPanList = highAreaResidualPans()
        )

        assertEquals(1, residualDrain)
    }

    @Test
    fun resolveConcurrencyResidualDrain_shouldClearResidualDrainWhenBackendStartsLowToHigh() {
        val residualDrain = DataManagementAPI.resolveConcurrencyResidualDrain(
            resolvedModeType = 2,
            resolvedTransitionMode = 1,
            previousModeType = 1,
            previousTransitionMode = 0,
            previousResidualDrain = 1,
            kaoPanList = highAreaResidualPans()
        )

        assertEquals(0, residualDrain)
    }

    private fun highAreaResidualPans(): List<KaoPan> =
        (1..33).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position == 25
                status = if (position == 25) 2 else 0
                holdingTime = if (position == 25) 1_000L else 0L
            }
        }
}
