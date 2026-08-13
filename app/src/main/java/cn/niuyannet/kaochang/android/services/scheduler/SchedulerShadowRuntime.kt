package cn.niuyannet.kaochang.android.services.scheduler

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.UUID

object SchedulerShadowRuntime {
    private const val TAG = "SchedulerShadow"
    private val runner = SchedulerShadowRunner(
        SchedulerShadowObserver(
            strategy = SchedulerProfileRuntime.activeProfile.sellCandidateStrategy,
            sessionId = UUID.randomUUID().toString(),
            logSink = { message -> LogUtils.dLocalOnly(TAG, message) }
        )
    )

    fun observe(
        legacyCandidate: KaoPan?,
        tasteCode: String,
        productId: Int,
        sellEnd: Int,
        pans: Collection<KaoPan>
    ) {
        runner.observeSafely(legacyCandidate?.positionSn) {
            SchedulerShadowInputMapper.map(
                nowMs = System.currentTimeMillis(),
                tasteCode = tasteCode,
                productId = productId,
                sellRange = 1..sellEnd,
                pendingDiscardPositions = KaoPanHelper.pendingDiscardPositionsOrEmpty(),
                pans = pans
            )
        }
    }
}
