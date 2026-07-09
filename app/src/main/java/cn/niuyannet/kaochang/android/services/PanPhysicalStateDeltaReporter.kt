package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

object PanPhysicalStateDeltaReporter {
    private const val LOG_SYNC = "烤盘物理增量"

    @Volatile
    private var snapshotSource: SnapshotSyncPolicy.SnapshotSource = object : SnapshotSyncPolicy.SnapshotSource {
        override fun getKaoPanList(): List<KaoPan> = KaoPanHelper.getKaoPanList()
        override fun getKaoPanBoxList(): List<KaoPanBox> = KaoPanHelper.getKaoPanBoxList()
    }

    @Volatile
    private var publisher: (
        allPans: List<KaoPan>,
        allStocks: List<KaoPanBox>,
        changedPans: List<KaoPan>,
        changedStocks: List<KaoPanBox>,
        reason: String,
        source: String
    ) -> Boolean = { allPans, allStocks, changedPans, changedStocks, reason, source ->
        SendServerHelper.publishPanPhysicalStateDelta(
            allPans = allPans,
            allStocks = allStocks,
            changedPans = changedPans,
            changedStocks = changedStocks,
            reason = reason,
            source = source
        )
    }

    @Volatile
    private var logger: (String) -> Unit = { message -> LogUtils.d(message) }

    fun reportPanChanged(changedPan: KaoPan, reason: String, source: String) {
        reportPansChanged(
            changedPans = listOf(changedPan),
            reason = reason,
            source = source
        )
    }

    fun reportPansChanged(changedPans: List<KaoPan>, reason: String, source: String) {
        reportPanPositionsChanged(
            changedPanPositions = changedPans.map { it.positionSn },
            reason = reason,
            source = source
        )
    }

    fun reportPanPositionsChanged(changedPanPositions: List<Int>, reason: String, source: String) {
        report(
            changedPanNos = changedPanPositions,
            changedStockNos = emptyList(),
            reason = reason,
            source = source
        )
    }

    fun reportStockChanged(changedStock: KaoPanBox, reason: String, source: String) {
        reportStocksChanged(
            changedStocks = listOf(changedStock),
            reason = reason,
            source = source
        )
    }

    fun reportStocksChanged(changedStocks: List<KaoPanBox>, reason: String, source: String) {
        report(
            changedPanNos = emptyList(),
            changedStockNos = changedStocks.map { it.positionSn },
            reason = reason,
            source = source
        )
    }

    fun reportPanAndStockChanged(changedPan: KaoPan, changedStock: KaoPanBox, reason: String, source: String) {
        report(
            changedPanNos = listOf(changedPan.positionSn),
            changedStockNos = listOf(changedStock.positionSn),
            reason = reason,
            source = source
        )
    }

    private fun report(
        changedPanNos: List<Int>,
        changedStockNos: List<Int>,
        reason: String,
        source: String
    ) {
        val allPans = snapshotSource.getKaoPanList()
        val allStocks = snapshotSource.getKaoPanBoxList()
        val changedPans = changedPanNos.mapNotNull { panNo ->
            allPans.firstOrNull { it.positionSn == panNo }
        }
        val changedStocks = changedStockNos.mapNotNull { stockNo ->
            allStocks.firstOrNull { it.positionSn == stockNo }
        }
        if (changedPans.isEmpty() && changedStocks.isEmpty()) {
            logger.invoke(
                "【$LOG_SYNC】跳过物理状态增量：当前快照中找不到变化对象，" +
                    "reason=$reason，source=$source，changedPanNos=$changedPanNos，changedStockNos=$changedStockNos"
            )
            return
        }
        publisher.invoke(
            allPans,
            allStocks,
            changedPans,
            changedStocks,
            reason,
            source
        )
    }

    internal fun withDependenciesForTest(
        snapshotSource: SnapshotSyncPolicy.SnapshotSource,
        publisher: (
            allPans: List<KaoPan>,
            allStocks: List<KaoPanBox>,
            changedPans: List<KaoPan>,
            changedStocks: List<KaoPanBox>,
            reason: String,
            source: String
        ) -> Boolean,
        block: () -> Unit
    ) {
        val previousSnapshotSource = this.snapshotSource
        val previousPublisher = this.publisher
        val previousLogger = this.logger
        this.snapshotSource = snapshotSource
        this.publisher = publisher
        this.logger = {}
        try {
            block()
        } finally {
            this.snapshotSource = previousSnapshotSource
            this.publisher = previousPublisher
            this.logger = previousLogger
        }
    }
}
