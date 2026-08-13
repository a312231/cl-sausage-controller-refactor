package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.LogUtils

internal data class DeferredTasteReservationWrite(
    val expectedPan: KaoPan,
    val expectedPositionSn: Int,
    val tasteCode: String
)

/**
 * 并发模式公共工具。
 * 负责烤盘范围筛选，以及按口味从烤肠箱中选择供给来源。
 */
object TrafficModeHelper {
    internal data class MissingTasteReservationPlan(
        val hasAvailableAllocation: Boolean,
        val writes: List<DeferredTasteReservationWrite>
    )
    private const val TAG = "KaoChangAlgorithm"

    fun purchaseSellEnd(
        modeType: Int,
        transitionMode: Int,
        highConcurrencyResidualDrain: Int
    ): Int = when {
        transitionMode == 1 -> 9
        modeType == 2 || transitionMode == 2 || highConcurrencyResidualDrain == 1 -> 21
        else -> 9
    }

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

    fun selectSellCandidate(
        list: List<KaoPan>,
        tasteCode: String,
        productId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): KaoPan? {
        val pendingDiscardPositions = KaoPanHelper.pendingDiscardPositionsOrEmpty()
        return list
            .filter {
                KaoPanHelper.isSellable(it, pendingDiscardPositions, nowMs) &&
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
        val emptyPans = list
            .filter {
                !it.isHasSausage &&
                    it.status == KaoPan.STATUS_EMPTY
            }
        val sameTasteTarget = emptyPans
            .filter { it.taste?.tasteCode == tasteCode }
            .minByOrNull { it.positionSn }
        if (sameTasteTarget != null) {
            return sameTasteTarget
        }
        val blankTasteTarget = emptyPans
            .filter {
                it.taste?.tasteCode.isNullOrBlank()
            }
            .minByOrNull { it.positionSn }
        if (blankTasteTarget != null) {
            blankTasteTarget.taste = Taste(tasteCode)
            return blankTasteTarget
        }
        val staleReservedTarget = emptyPans
            .filter { !it.taste?.tasteCode.isNullOrBlank() }
            .minByOrNull { it.positionSn }
        if (staleReservedTarget != null) {
            val oldTasteCode = staleReservedTarget.taste?.tasteCode
            safeLogWarn(
                "【高并发补肠】25~33 熟肠回搬覆盖前区空盘旧口味预留：" +
                    "targetPan=${staleReservedTarget.positionSn}，oldTasteCode=$oldTasteCode，newTasteCode=$tasteCode"
            )
            staleReservedTarget.taste = Taste(tasteCode)
        }
        return staleReservedTarget
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
        val reservationPlan = planMissingTasteReservations(frontPositions, listBox)
        val blankReservedPans = frontPositions.filter {
            !it.isHasSausage && it.taste?.tasteCode.isNullOrBlank()
        }
        if (blankReservedPans.isEmpty()) {
            return
        }

        if (!reservationPlan.hasAvailableAllocation) {
            safeLogWarn("【并发补肠】跳过口味预留补齐：当前烤肠箱无可用库存口味")
            return
        }

        reservationPlan.writes.forEach { write ->
            write.expectedPan.taste = Taste(write.tasteCode)
        }
        if (reservationPlan.writes.isNotEmpty()) {
            safeLogInfo("【并发补肠】已补齐前区空盘口味预留：补齐数量=${reservationPlan.writes.size}，总空盘=${blankReservedPans.size}")
        }
    }

    /**
     * 只计算补齐结果，不修改烤盘。用于需要先完成整份机械计划校验、成功后再提交状态的路径。
     */
    internal fun planMissingTasteReservations(
        frontPositions: List<KaoPan>,
        listBox: List<KaoPanBox>
    ): MissingTasteReservationPlan {
        val blankReservedPans = frontPositions.filter {
            !it.isHasSausage && it.taste?.tasteCode.isNullOrBlank()
        }
        if (blankReservedPans.isEmpty()) {
            return MissingTasteReservationPlan(
                hasAvailableAllocation = true,
                writes = emptyList()
            )
        }

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, frontPositions.size)
        if (flavorAllocation.isEmpty()) {
            return MissingTasteReservationPlan(
                hasAvailableAllocation = false,
                writes = emptyList()
            )
        }

        val existingAssignedCount = frontPositions
            .mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        val plannedWrites = mutableListOf<DeferredTasteReservationWrite>()
        var blankIndex = 0
        flavorAllocation.forEach { (flavor, targetCount) ->
            var needCount = targetCount - existingAssignedCount.getOrDefault(flavor, 0)
            while (needCount > 0 && blankIndex < blankReservedPans.size) {
                val expectedPan = blankReservedPans[blankIndex]
                plannedWrites += DeferredTasteReservationWrite(
                    expectedPan = expectedPan,
                    expectedPositionSn = expectedPan.positionSn,
                    tasteCode = flavor
                )
                blankIndex++
                needCount--
            }
        }
        return MissingTasteReservationPlan(
            hasAvailableAllocation = true,
            writes = plannedWrites.toList()
        )
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
