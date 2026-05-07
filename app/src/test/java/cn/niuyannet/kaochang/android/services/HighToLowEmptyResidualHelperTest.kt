package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighToLowEmptyResidualHelperTest {

    @Test
    fun evaluate_shouldAllowClearWhenHighToLowResidualIsEmptyAndIdle() {
        val decision = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = 2,
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            moveStatus = 0,
            hasSupplyBoxesAvailable = false,
            kaoPanList = emptyPans()
        )

        assertTrue(decision.canClear)
        assertTrue(decision.highConcurrencyAreaIdle)
        assertFalse(decision.hasFrontSellableSausage)
    }

    @Test
    fun evaluate_shouldKeepTransitionWhenHighConcurrencyAreaHasResidualStatus() {
        val pans = emptyPans().map { pan ->
            if (pan.positionSn == 25) {
                pan.apply { status = 1 }
            } else {
                pan
            }
        }

        val decision = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = 2,
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            moveStatus = 0,
            hasSupplyBoxesAvailable = false,
            kaoPanList = pans
        )

        assertFalse(decision.canClear)
        assertFalse(decision.highConcurrencyAreaIdle)
    }

    @Test
    fun evaluate_shouldKeepTransitionWhenMachineIsNotIdleOrDelivering() {
        val moving = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = 2,
            onlineStatus = 1,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            moveStatus = 3,
            hasSupplyBoxesAvailable = false,
            kaoPanList = emptyPans()
        )
        val delivering = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = 2,
            onlineStatus = 4,
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL,
            moveStatus = 0,
            hasSupplyBoxesAvailable = false,
            kaoPanList = emptyPans()
        )

        assertFalse(moving.canClear)
        assertFalse(delivering.canClear)
    }

    @Test
    fun evaluate_shouldKeepTransitionWhenRuntimeStateIsFaulted() {
        val decision = HighToLowEmptyResidualHelper.evaluate(
            transitionMode = 2,
            onlineStatus = 3,
            errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE,
            moveStatus = 0,
            hasSupplyBoxesAvailable = false,
            kaoPanList = emptyPans()
        )

        assertFalse(decision.canClear)
        assertFalse(decision.runtimeStateReliable)
    }

    private fun emptyPans(): List<KaoPan> =
        (1..33).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = false
                startTime = 0L
                holdingTime = 0L
                status = 0
            }
        }
}
