package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

object KaoPanCloudSyncReporter {
    private const val LOG_SYNC = "云端同步"

    data class SellableSnapshot internal constructor(
        val sellablePans: List<SellablePan>
    )

    data class SellablePan internal constructor(
        val positionSn: Int,
        val tasteCode: String?,
        val productId: Int?
    )

    @Volatile
    private var deltaPublisher: (SellableSnapshot, SellableSnapshot, String) -> Boolean =
        { before, after, reason -> SendServerHelper.publishPanStateDelta(before, after, reason) }
    @Volatile
    private var fallbackUploader: (String) -> Unit = { reason -> SnapshotSyncPolicy.maybeUploadSnapshot(reason) }
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile
    private var fallbackMinIntervalProvider: () -> Long =
        { AppConfig.getAppConfig().httpFallbackMinIntervalMs.takeIf { it > 0L } ?: 30_000L }
    @Volatile
    private var lastFallbackSnapshotAtMs: Long = 0L
    @Volatile
    private var logger: (String) -> Unit = { message -> LogUtils.i(message) }

    fun captureSellableSnapshot(pans: List<KaoPan>): SellableSnapshot =
        SellableSnapshot(
            pans
                .filter { KaoPanHelper.isSellable(it, KaoPanHelper.pendingDiscardPositionsOrEmpty()) }
                .map { pan ->
                    SellablePan(
                        positionSn = pan.positionSn,
                        tasteCode = pan.taste?.tasteCode,
                        productId = pan.taste?.productId
                    )
                }
                .sortedWith(compareBy<SellablePan> { it.positionSn }.thenBy { it.tasteCode.orEmpty() })
        )

    fun hasSellableStateChanged(before: List<KaoPan>, after: List<KaoPan>): Boolean =
        captureSellableSnapshot(before) != captureSellableSnapshot(after)

    fun uploadIfSellableStateChanged(
        before: List<KaoPan>,
        after: List<KaoPan>,
        reason: String
    ) {
        uploadIfSellableStateChanged(captureSellableSnapshot(before), after, reason)
    }

    fun uploadIfSellableStateChanged(
        before: SellableSnapshot,
        after: List<KaoPan>,
        reason: String
    ) {
        val afterSnapshot = captureSellableSnapshot(after)
        if (before == afterSnapshot) {
            return
        }
        logger.invoke(
            "【$LOG_SYNC】可售烤盘状态变化，上报可售烤盘增量：reason=$reason，" +
                "before=${formatSnapshot(before)}，after=${formatSnapshot(afterSnapshot)}"
        )
        val published = deltaPublisher.invoke(before, afterSnapshot, reason)
        if (!published) {
            triggerFallbackSnapshot("pan_delta_publish_failed:$reason")
        }
    }

    internal fun withUploaderForTest(testUploader: (String) -> Unit, block: () -> Unit) {
        withDependenciesForTest(
            testDeltaPublisher = { _, _, reason ->
                testUploader.invoke(reason)
                true
            },
            testFallbackUploader = testUploader,
            block = block
        )
    }

    internal fun withDependenciesForTest(
        testDeltaPublisher: (SellableSnapshot, SellableSnapshot, String) -> Boolean,
        testFallbackUploader: (String) -> Unit = {},
        testClock: () -> Long = { System.currentTimeMillis() },
        testFallbackMinIntervalMs: () -> Long = { 30_000L },
        block: () -> Unit
    ) {
        val previousDeltaPublisher = deltaPublisher
        val previousFallbackUploader = fallbackUploader
        val previousLogger = logger
        val previousClock = clock
        val previousFallbackMinIntervalProvider = fallbackMinIntervalProvider
        val previousLastFallbackSnapshotAtMs = lastFallbackSnapshotAtMs
        deltaPublisher = testDeltaPublisher
        fallbackUploader = testFallbackUploader
        clock = testClock
        fallbackMinIntervalProvider = testFallbackMinIntervalMs
        lastFallbackSnapshotAtMs = 0L
        logger = {}
        try {
            block()
        } finally {
            deltaPublisher = previousDeltaPublisher
            fallbackUploader = previousFallbackUploader
            clock = previousClock
            fallbackMinIntervalProvider = previousFallbackMinIntervalProvider
            lastFallbackSnapshotAtMs = previousLastFallbackSnapshotAtMs
            logger = previousLogger
        }
    }

    private fun triggerFallbackSnapshot(reason: String) {
        val now = clock.invoke()
        val minIntervalMs = fallbackMinIntervalProvider.invoke()
        if (lastFallbackSnapshotAtMs > 0L && now - lastFallbackSnapshotAtMs < minIntervalMs) {
            logger.invoke("【$LOG_SYNC】可售烤盘增量 HTTP 兜底被限频跳过：reason=$reason，minIntervalMs=$minIntervalMs")
            return
        }
        lastFallbackSnapshotAtMs = now
        fallbackUploader.invoke(reason)
    }

    private fun formatSnapshot(snapshot: SellableSnapshot): String {
        if (snapshot.sellablePans.isEmpty()) {
            return "空"
        }
        return snapshot.sellablePans.joinToString(",") { pan ->
            "${pan.positionSn}:${pan.tasteCode ?: "未知"}"
        }
    }
}
