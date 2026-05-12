package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 低并发转高并发的需求来源门禁。
 *
 * 过保丢弃代表“没人买”，因此这类空位不能作为低转高的需求证据。
 * 该状态只保存在内存中，重启后由真实烤盘状态重新驱动调度。
 */
object LowToHighDemandGate {
    private const val TAG = "LowToHighDemandGate"
    private const val LOW_MODE = 1
    private const val NO_TRANSITION = 0
    private val frontPositions = 1..9
    private val blockedFrontPositions = linkedSetOf<Int>()
    private val defaultInfoLogger: (String) -> Unit = { message -> LogUtils.i(TAG, message) }
    private var infoLogger: (String) -> Unit = defaultInfoLogger

    data class ExpiredDiscardCloseoutResult(
        val changedModeState: Boolean,
        val blockedFrontPositionsChanged: Boolean,
        val blockedFrontPositions: Set<Int>
    )

    @Synchronized
    fun recordExpiredDiscard(positionSn: Int): Boolean {
        if (positionSn !in frontPositions) {
            return false
        }
        return blockedFrontPositions.add(positionSn)
    }

    @Synchronized
    fun clearPosition(positionSn: Int, reason: String): Boolean {
        val removed = blockedFrontPositions.remove(positionSn)
        if (removed) {
            logInfo("【低转高门禁】清除blocked位置：position=$positionSn，reason=$reason，remaining=$blockedFrontPositions")
        }
        return removed
    }

    @Synchronized
    fun clearPositions(positionSnList: Iterable<Int>, reason: String): Boolean {
        val removedPositions = mutableListOf<Int>()
        positionSnList.forEach { positionSn ->
            if (blockedFrontPositions.remove(positionSn)) {
                removedPositions.add(positionSn)
            }
        }
        if (removedPositions.isNotEmpty()) {
            logInfo("【低转高门禁】批量清除blocked位置：removed=$removedPositions，reason=$reason，remaining=$blockedFrontPositions")
        }
        return removedPositions.isNotEmpty()
    }

    @Synchronized
    fun clearAll(reason: String): Boolean {
        if (blockedFrontPositions.isEmpty()) {
            return false
        }
        val removedPositions = blockedFrontPositions.toSet()
        blockedFrontPositions.clear()
        logInfo("【低转高门禁】清空blocked位置：removed=$removedPositions，reason=$reason，remaining=$blockedFrontPositions")
        return true
    }

    @Synchronized
    internal fun replaceInfoLoggerForTest(logger: (String) -> Unit) {
        infoLogger = logger
    }

    @Synchronized
    internal fun resetInfoLoggerForTest() {
        infoLogger = defaultInfoLogger
    }

    private fun logInfo(message: String) {
        infoLogger(message)
    }

    @Synchronized
    fun blockedFrontPositions(): Set<Int> = blockedFrontPositions.toSet()

    @Synchronized
    fun effectiveEmptyFrontPositions(frontPans: List<KaoPan>): Set<Int> {
        val currentEmptyPositions = frontPans
            .filter { it.positionSn in frontPositions && !it.isHasSausage }
            .map { it.positionSn }
            .toSet()
        blockedFrontPositions.retainAll(currentEmptyPositions)
        return currentEmptyPositions - blockedFrontPositions
    }

    @Synchronized
    fun canTriggerLowToHigh(
        frontPans: List<KaoPan>,
        lowHeatEmptyCount: Int
    ): Boolean {
        return effectiveEmptyFrontPositions(frontPans).size >= 7 && lowHeatEmptyCount == 0
    }

    @Synchronized
    fun markExpiredDiscardAndFoldToLow(
        config: AppConfigBean,
        positionSn: Int
    ): ExpiredDiscardCloseoutResult {
        val blockedChanged = recordExpiredDiscard(positionSn)
        val changedModeState = config.modeType != LOW_MODE || config.transitionMode != NO_TRANSITION
        config.modeType = LOW_MODE
        config.transitionMode = NO_TRANSITION
        return ExpiredDiscardCloseoutResult(
            changedModeState = changedModeState,
            blockedFrontPositionsChanged = blockedChanged,
            blockedFrontPositions = blockedFrontPositions.toSet()
        )
    }
}
