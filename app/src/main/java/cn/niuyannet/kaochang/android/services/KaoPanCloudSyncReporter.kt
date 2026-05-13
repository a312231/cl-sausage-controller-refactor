package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.net.DataManagementAPI
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
    private var uploader: () -> Unit = { DataManagementAPI.uploadDataToServer() }
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
            "【$LOG_SYNC】可售烤盘状态变化，立即同步云端：reason=$reason，" +
                "before=${formatSnapshot(before)}，after=${formatSnapshot(afterSnapshot)}"
        )
        uploader.invoke()
    }

    internal fun withUploaderForTest(testUploader: () -> Unit, block: () -> Unit) {
        val previous = uploader
        val previousLogger = logger
        uploader = testUploader
        logger = {}
        try {
            block()
        } finally {
            uploader = previous
            logger = previousLogger
        }
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
