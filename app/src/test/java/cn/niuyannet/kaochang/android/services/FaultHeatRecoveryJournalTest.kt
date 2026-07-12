package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class FaultHeatRecoveryJournalTest {

    @Test
    fun recoveryJournal_shouldReplayAbsoluteTargetsIdempotently() {
        val pan = bakingPan(position = 25, startTime = 1_000L)
        val config = AppConfigBean().apply {
            faultHeatPauseStartedAtMs = 2_000L
            pendingDiscardPositions.add(3)
        }
        val result = FaultHeatPauseManager.EndPauseResult(
            active = true,
            pauseDurationMs = 600L,
            shiftedPositions = listOf(25),
            holdingPositions = emptyList(),
            pendingDiscardPositions = listOf(26),
            alertCode = null
        )

        val journal = FaultHeatPauseManager.buildRecoveryJournal(
            pans = listOf(pan),
            config = config,
            result = result,
            preparedAtMs = 2_600L,
            reason = "unit-test"
        )

        FaultHeatPauseManager.applyRecoveryJournal(listOf(pan), config, journal)
        FaultHeatPauseManager.applyRecoveryJournal(listOf(pan), config, journal)

        assertEquals(1_600L, pan.startTime)
        assertEquals(listOf(3, 26), config.pendingDiscardPositions)
        assertEquals(2_000L, journal.pauseStartedAtMs)
        assertEquals(2_600L, journal.preparedAtMs)
    }

    private fun bakingPan(position: Int, startTime: Long): KaoPan = KaoPan().apply {
        positionSn = position
        isHasSausage = true
        status = 1
        this.startTime = startTime
        holdingTime = 0L
    }
}
