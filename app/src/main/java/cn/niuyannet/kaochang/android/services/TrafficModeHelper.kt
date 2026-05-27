package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 并发模式公共工具。
 * 负责烤盘范围筛选，以及按口味从烤肠箱中选择供给来源。
 */
object TrafficModeHelper {
    private const val TAG = "KaoChangAlgorithm"

    /**
     * 可售候选排序：先卖 holdingTime（进入保温时间）最早的烤肠，同时间按烤盘位升序。
     * 这里仅负责排序，不负责过滤生肠或待丢弃烤盘；调用方必须先用 KaoPanHelper.isSellable 做可售判定。
     */
    private val oldestHoldingFirstComparator = compareBy<KaoPan>(
        { if (it.holdingTime > 0L) it.holdingTime else Long.MAX_VALUE },
        { it.positionSn }
    )

    /**
     * 按 positionSn 升序返回指定范围内的烤盘。
     * 例如：
     * - 低并发前区：1~9
     * - 低并发补热区：13~18
     * - 高并发前区：1~21
     * - 高并发补热区：25~33
     */
    fun getTrafficModePositions(start: Int, end: Int, list: List<KaoPan>): List<KaoPan> {
        return list.filter { it.positionSn in start..end }.sortedBy { it.positionSn }
    }

    fun selectSellCandidate(list: List<KaoPan>, tasteCode: String, productId: Int): KaoPan? {
        val pendingDiscardPositions = KaoPanHelper.pendingDiscardPositionsOrEmpty()
        return list
            .filter {
                KaoPanHelper.isSellable(it, pendingDiscardPositions) &&
                    it.taste.tasteCode == tasteCode &&
                    it.taste.productId == productId
            }
            .sortedWith(oldestHoldingFirstComparator)
            .firstOrNull()
    }

    fun shouldUseExpandedSellRange(
        modeType: Int,
        transitionMode: Int,
        highConcurrencyResidualDrain: Int
    ): Boolean {
        return modeType == 2 || transitionMode != 0 || highConcurrencyResidualDrain == 1
    }

    fun shouldUseExpandedSellRangeForPurchase(
        modeType: Int,
        transitionMode: Int,
        highConcurrencyResidualDrain: Int
    ): Boolean {
        if (transitionMode == 1) {
            return false
        }
        return modeType == 2 || transitionMode == 2 || highConcurrencyResidualDrain == 1
    }

    fun selectFrontTargetPan(
        list: List<KaoPan>,
        tasteCode: String?,
        requireReserved: Boolean = false
    ): KaoPan? {
        if (tasteCode.isNullOrBlank()) {
            return null
        }
        return list
            .filter {
                !it.isHasSausage &&
                    it.taste?.tasteCode == tasteCode &&
                    (!requireReserved || it.isHasGrilling)
            }
            .minByOrNull { it.positionSn }
    }

    fun selectFrontTargetPanForHighHeatReturn(
        list: List<KaoPan>,
        tasteCode: String?
    ): KaoPan? {
        if (tasteCode.isNullOrBlank()) {
            return null
        }
        val sameTasteTarget = selectFrontTargetPan(list, tasteCode)
        if (sameTasteTarget != null) {
            return sameTasteTarget
        }
        val blankTasteTarget = list
            .filter {
                !it.isHasSausage &&
                    it.taste?.tasteCode.isNullOrBlank()
            }
            .minByOrNull { it.positionSn }
        if (blankTasteTarget != null) {
            blankTasteTarget.taste = Taste(tasteCode)
        }
        return blankTasteTarget
    }

    fun sortByHoldingAgeThenPosition(list: List<KaoPan>): List<KaoPan> {
        return list.sortedWith(oldestHoldingFirstComparator)
    }

    /**
     * 补齐前区空盘的口味预留。
     *
     * 某些清盘、重置或异常恢复链路会把空盘 taste 清空，但高/低并发补热区补肠又需要
     * 先读取前区空盘的预留口味。这里统一按当前箱位库存比例回填，避免空指针导致算法崩溃。
     */
    fun fillMissingTasteReservations(frontPositions: List<KaoPan>, listBox: List<KaoPanBox>) {
        val blankReservedPans = frontPositions.filter {
            !it.isHasSausage && it.taste?.tasteCode.isNullOrBlank()
        }
        if (blankReservedPans.isEmpty()) {
            return
        }

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, frontPositions.size)
        if (flavorAllocation.isEmpty()) {
            safeLogWarn("【并发补肠】跳过口味预留补齐：当前烤肠箱无可用库存口味")
            return
        }

        val existingAssignedCount = frontPositions
            .mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        var blankIndex = 0
        flavorAllocation.forEach { (flavor, targetCount) ->
            var needCount = targetCount - existingAssignedCount.getOrDefault(flavor, 0)
            while (needCount > 0 && blankIndex < blankReservedPans.size) {
                blankReservedPans[blankIndex].taste = Taste(flavor)
                blankIndex++
                needCount--
            }
        }

        if (blankIndex > 0) {
            safeLogInfo("【并发补肠】已补齐前区空盘口味预留：补齐数量=$blankIndex，总空盘=${blankReservedPans.size}")
        }
    }

    /**
     * 自动补肠时，优先从同口味且仍有库存的烤肠箱中选择编号最大的箱体。
     * 这样可以缩短机械臂整体取肠路径。
     */
    fun findSupplyBoxByTaste(listBox: List<KaoPanBox>, tasteCode: String?): KaoPanBox? {
        return findSupplyBoxesByTaste(listBox, tasteCode).firstOrNull()
    }

    fun findSupplyBoxesByTaste(listBox: List<KaoPanBox>, tasteCode: String?): List<KaoPanBox> {
        if (tasteCode.isNullOrBlank()) {
            return emptyList()
        }
        return listBox
            .filter {
                it.tasteCode == tasteCode &&
                    KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it) &&
                    SourceBoxPositionPolicy.isValidSourceBox(it.positionSn)
            }
            .sortedByDescending { it.positionSn }
    }

    private fun safeLogInfo(message: String) {
        runCatching { LogUtils.i(TAG, message) }
    }

    private fun safeLogWarn(message: String) {
        runCatching { LogUtils.w(TAG, message) }
    }
}
