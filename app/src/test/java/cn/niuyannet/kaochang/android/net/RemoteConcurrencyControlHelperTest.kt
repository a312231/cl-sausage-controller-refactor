package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.services.HighConcurrencyResidualDrainEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConcurrencyControlHelperTest {

    @Test
    fun resolveTarget_shouldInferLowToHighTransitionWhenBackendOnlySwitchesMode() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 0,
            requestedModeType = 2,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(1, resolved.transitionMode)
        assertEquals(false, resolved.overrodeConflictingTransition)
    }

    @Test
    fun resolveTarget_shouldPreserveLowToHighWhenBackendRepeatsHighTargetWithoutTransition() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 1,
            requestedModeType = 2,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(1, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldAllowExplicitManualLowStableDuringLowToHigh() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 1,
            requestedModeType = 1,
            requestedTransitionMode = 0
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldInferHighToLowTransitionWhenBackendOnlySwitchesMode() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 0,
            requestedModeType = 1,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(2, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldAllowExplicitManualClearToLowStable() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 0
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldClearInvalidLowHighToLowWhenHighAreaIsIdle() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 2,
            highConcurrencyAreaActive = false
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldKeepHighToLowTransitionWhenHighAreaHasSausage() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = 2,
            highConcurrencyAreaActive = true
        )

        assertEquals(2, resolved.modeType)
        assertEquals(2, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldClearHighToLowWhenBackendRequestsLowAndEmptyResidualCanClear() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = null,
            highConcurrencyAreaActive = false,
            highToLowEmptyResidualCanClear = true
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldKeepHighToLowWhenBackendRequestsLowButRuntimeIsUnsafeToClear() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 2,
            requestedModeType = 1,
            requestedTransitionMode = null,
            highConcurrencyAreaActive = false,
            preserveHighToLowTransition = true
        )

        assertEquals(2, resolved.modeType)
        assertEquals(2, resolved.transitionMode)
    }

    @Test
    fun resolveTarget_shouldUseBackendLatestTargetWhenHighToLowNeedsOverride() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 1,
            currentTransitionMode = 2,
            requestedModeType = 2,
            requestedTransitionMode = 1
        )

        assertEquals(2, resolved.modeType)
        assertEquals(1, resolved.transitionMode)
        assertTrue(resolved.overrodeConflictingTransition)
    }

    @Test
    fun resolveTarget_shouldCancelHighToLowWhenBackendRequestsHighWithoutTransition() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 2,
            requestedModeType = 2,
            requestedTransitionMode = null
        )

        assertEquals(2, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
        assertTrue(resolved.overrodeConflictingTransition)
    }

    @Test
    fun resolveTarget_shouldRouteManualLowStableToResidualDrainWhenHighAreaIsActive() {
        val resolved = RemoteConcurrencyControlHelper.resolveTarget(
            currentModeType = 2,
            currentTransitionMode = 1,
            requestedModeType = 1,
            requestedTransitionMode = 0,
            highConcurrencyAreaActive = true
        )
        val residualDrain = HighConcurrencyResidualDrainEvaluator.evaluate(
            modeType = resolved.modeType,
            kaoPanList = highAreaResidualPans(),
            includeLowHeatOverlapArea = true
        )

        assertEquals(1, resolved.modeType)
        assertEquals(0, resolved.transitionMode)
        assertEquals(1, residualDrain)
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
