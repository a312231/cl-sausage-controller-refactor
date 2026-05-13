package cn.niuyannet.kaochang.android.adapter

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Test

class KaoPanAdapterTest {

    @Test
    fun formatKaoPanStatusText_shouldFreezeBakingTimerDuringFaultPause() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L + 1_000L
        }
        val pan = KaoPan().apply {
            status = 1
            isHasSausage = true
            startTime = 3 * 60 * 1000L
        }

        val text = formatKaoPanStatusText(
            kaoPan = pan,
            nowMs = 12 * 60 * 1000L,
            config = config
        )

        assertEquals("烤制中:05:01", text)
    }

    @Test
    fun formatKaoPanStatusText_shouldKeepHoldingTimerRunningDuringFaultPause() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L
        }
        val pan = KaoPan().apply {
            status = 2
            isHasSausage = true
            holdingTime = 2 * 60 * 1000L
        }

        val text = formatKaoPanStatusText(
            kaoPan = pan,
            nowMs = 12 * 60 * 1000L + 30_000L,
            config = config
        )

        assertEquals("保温:10:30", text)
    }
}
