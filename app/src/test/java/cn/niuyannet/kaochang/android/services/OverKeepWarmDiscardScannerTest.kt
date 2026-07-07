package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverKeepWarmDiscardScannerTest {

    @Test
    fun collectDiscardCandidates_shouldIncludePendingAndExpiredBeforeMechanicalGate() {
        val nowMs = 8 * 60 * 60 * 1000L
        val pans = listOf(
            holdingPan(positionSn = 1, holdingStartedAtMs = nowMs - 7 * 60 * 60 * 1000L),
            holdingPan(positionSn = 2, holdingStartedAtMs = nowMs - 1 * 60 * 60 * 1000L),
            holdingPan(positionSn = 3, holdingStartedAtMs = nowMs - 1 * 60 * 60 * 1000L),
            emptyPan(positionSn = 4)
        )

        val result = OverKeepWarmDiscardScanner.collectDiscardCandidates(
            pans = pans,
            pendingDiscardPositions = listOf(2, 4),
            nowMs = nowMs
        )

        assertEquals(listOf(2), result.pendingPositions)
        assertEquals(listOf(1), result.expiredPositions)
        assertEquals(listOf(1, 2), result.allPositions)
    }

    @Test
    fun collectDiscardCandidates_shouldIncludeWaitingReturnKeepWarmSourceWhenExpired() {
        val nowMs = 8 * 60 * 60 * 1000L
        val pan = holdingPan(positionSn = 25, holdingStartedAtMs = nowMs - 7 * 60 * 60 * 1000L).apply {
            status = 3
        }

        val result = OverKeepWarmDiscardScanner.collectDiscardCandidates(
            pans = listOf(pan),
            pendingDiscardPositions = emptyList(),
            nowMs = nowMs
        )

        assertEquals(listOf(25), result.expiredPositions)
        assertEquals(listOf(25), result.allPositions)
    }

    @Test
    fun collectDiscardCandidates_shouldNotExpireStaleBakingHoldingTimePan() {
        val nowMs = 8 * 60 * 60 * 1000L
        val staleBakingPan = holdingPan(positionSn = 14, holdingStartedAtMs = 1L).apply {
            status = 1
            startTime = nowMs - 90 * 60 * 1000L
        }

        val result = OverKeepWarmDiscardScanner.collectDiscardCandidates(
            pans = listOf(staleBakingPan),
            pendingDiscardPositions = emptyList(),
            nowMs = nowMs
        )

        assertTrue(result.expiredPositions.isEmpty())
        assertTrue(result.allPositions.isEmpty())
    }

    @Test
    fun evaluateMechanicalGate_shouldBlockWhenAlgorithmDisabledButKeepCandidatesMarkable() {
        val config = AppConfigBean().apply {
            onlineStatus = 1
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            moveStatus = 0
            isEnable = 0
        }

        val gate = OverKeepWarmDiscardScanner.evaluateMechanicalGate(config, allowMechanicalAction = true)

        assertFalse(gate.canStart)
        assertTrue(gate.reason.contains("isEnable"))
    }

    @Test
    fun evaluateMechanicalGate_shouldBlockWhenDeviceFaultedOrMoving() {
        val faulted = AppConfigBean().apply {
            onlineStatus = 1
            errorStatus = AppConfigBean.ERROR_STATUS_HARDWARE
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            moveStatus = 0
            isEnable = 1
        }
        val moving = AppConfigBean().apply {
            onlineStatus = 1
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_NORMAL
            moveStatus = 3
            isEnable = 1
        }

        assertFalse(OverKeepWarmDiscardScanner.evaluateMechanicalGate(faulted, allowMechanicalAction = true).canStart)
        assertFalse(OverKeepWarmDiscardScanner.evaluateMechanicalGate(moving, allowMechanicalAction = true).canStart)
    }

    @Test
    fun evaluateMechanicalGate_shouldBlockWhenInspectionModeActive() {
        val config = AppConfigBean().apply {
            onlineStatus = 1
            errorStatus = AppConfigBean.ERROR_STATUS_NORMAL
            inspectionMode = AppConfigBean.INSPECTION_MODE_ACTIVE
            moveStatus = 0
            isEnable = 1
        }

        val gate = OverKeepWarmDiscardScanner.evaluateMechanicalGate(config, allowMechanicalAction = true)

        assertFalse(gate.canStart)
        assertTrue(gate.reason.contains("inspectionMode"))
    }

    private fun holdingPan(positionSn: Int, holdingStartedAtMs: Long): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = true
            status = 2
            holdingTime = holdingStartedAtMs
            closeTime = 6 * 60 * 60 * 1000L
        }

    private fun emptyPan(positionSn: Int): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            isHasSausage = false
            status = 0
        }
}
