package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementPauseControllerTest {

    @Test
    fun requestPause_shouldReportOnlyNewNonBlankMarker() {
        SupplementPauseController.currentOrderNo()?.let { runCatching { SupplementPauseController.clearOrderPause(it) } }

        assertFalse(SupplementPauseController.requestPauseForOrder(""))
        assertTrue(SupplementPauseController.requestPauseForOrder("order-1"))
        assertFalse(SupplementPauseController.requestPauseForOrder("order-1"))

        runCatching { SupplementPauseController.clearOrderPause("order-1") }
    }

    @Test
    fun newerOrder_shouldOwnTheRemainingSupplementPause() {
        SupplementPauseController.currentOrderNo()?.let { runCatching { SupplementPauseController.clearOrderPause(it) } }

        SupplementPauseController.requestPauseForOrder("order-1")
        SupplementPauseController.requestPauseForOrder("order-2")

        assertTrue(SupplementPauseController.isPausedForDifferentOrder("order-1"))
        assertFalse(SupplementPauseController.isPausedForDifferentOrder("order-2"))

        runCatching { SupplementPauseController.clearOrderPause("order-2") }
    }

    @Test
    fun emergencyReset_shouldClearAnyPreResetPauseOwner() {
        SupplementPauseController.clearForEmergencyReset()
        SupplementPauseController.requestPauseForOrder("order-1")

        SupplementPauseController.clearForEmergencyReset()

        assertFalse(SupplementPauseController.shouldPauseForOrder())
        assertTrue(SupplementPauseController.requestPauseForOrder("order-2"))
        SupplementPauseController.clearForEmergencyReset()
    }
}
