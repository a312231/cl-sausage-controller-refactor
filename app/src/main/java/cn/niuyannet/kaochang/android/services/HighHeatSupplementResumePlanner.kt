package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste

internal object HighHeatSupplementResumePlanner {
    data class ResumeTarget(
        val kaoPan: KaoPan,
        val tasteCode: String,
        val returnPositionSn: Int? = null
    ) {
        val positionSn: Int
            get() = kaoPan.positionSn
    }

    fun hasIncompleteBatch(supplementGroup: List<KaoPan>): Boolean {
        val occupiedCount = supplementGroup.count { it.isHasSausage }
        return occupiedCount in 1 until supplementGroup.size
    }

    fun hasFullBatchStarted(supplementGroup: List<KaoPan>): Boolean {
        return supplementGroup.isNotEmpty() && supplementGroup.all { it.isHasSausage }
    }

    fun hasBatchStarted(supplementGroup: List<KaoPan>): Boolean {
        return supplementGroup.any { it.isHasSausage }
    }

    fun isReturnCleared(supplementGroup: List<KaoPan>): Boolean {
        return supplementGroup.none { it.isHasSausage }
    }

    fun planInitialTargets(
        frontPans: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        listBox: List<KaoPanBox>,
        requestedCount: Int = 9,
        excludedReturnPositions: Set<Int> = emptySet(),
        committedFutureReturnPositions: List<Int> = emptyList()
    ): List<ResumeTarget> {
        val currentReturnPans = frontPans
            .filter { !it.isHasSausage && it.positionSn !in excludedReturnPositions }
            .sortedBy { it.positionSn }
            .take(requestedCount)
        val currentReturnPositions = currentReturnPans.map { it.positionSn }.toSet()
        val futureReturnPans = if (currentReturnPans.size < requestedCount) {
            val futureReturnPositions = committedFutureReturnPositions
                .filter {
                    it in 1..21 &&
                        it !in excludedReturnPositions &&
                        it !in currentReturnPositions
                }
                .distinct()
                .take(requestedCount - currentReturnPans.size)
                .toSet()
            frontPans
                .filter { it.positionSn in futureReturnPositions }
                .sortedBy { committedFutureReturnPositions.indexOf(it.positionSn).let { index -> if (index >= 0) index else Int.MAX_VALUE } }
        } else {
            emptyList()
        }
        val returnPans = (currentReturnPans + futureReturnPans)
            .take(requestedCount)
        val futureReturnPositions = futureReturnPans.map { it.positionSn }.toSet()
        if (returnPans.isEmpty()) {
            return emptyList()
        }
        val targetPans = supplementGroup
            .filter { !it.isHasSausage }
            .sortedBy { it.positionSn }
            .take(minOf(requestedCount, returnPans.size))
        if (targetPans.isEmpty()) {
            return emptyList()
        }

        val reservedTasteCodes = returnPans
            .map { pan ->
                if (pan.positionSn in futureReturnPositions) {
                    null
                } else {
                    pan.taste?.tasteCode?.takeIf { code -> code.isNotBlank() }
                }
            }
        val stockRatioTasteCodes = expandTasteAllocation(
            KaoPanHelper.computeHighKaoPamRatio(listBox, targetPans.size)
        )
        val remainingStockByTaste = buildAvailableStockByTaste(listBox)

        return targetPans.mapIndexedNotNull { index, targetPan ->
            val returnPan = returnPans.getOrNull(index)
            val preferredTasteCode = reservedTasteCodes.getOrNull(index)
                ?: stockRatioTasteCodes.getOrNull(index)
                ?: stockRatioTasteCodes.firstOrNull()
            val tasteCode = selectTasteWithRemainingStock(
                preferredTasteCode = preferredTasteCode,
                remainingStockByTaste = remainingStockByTaste
            )
                ?: return@mapIndexedNotNull null
            remainingStockByTaste[tasteCode] = (remainingStockByTaste[tasteCode] ?: 0) - 1
            if (returnPan != null &&
                returnPan.positionSn !in futureReturnPositions &&
                returnPan.taste?.tasteCode != tasteCode
            ) {
                returnPan.taste = Taste(tasteCode)
            }
            targetPan.taste = Taste(tasteCode)
            ResumeTarget(targetPan, tasteCode, returnPan?.positionSn)
        }
    }

    fun planResumeTargets(
        frontPans: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        activeIntent: SupplementBatchIntent? = null
    ): List<ResumeTarget> {
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedCount = sortedGroup.count { it.isHasSausage }
        val emptyPans = sortedGroup.filter { !it.isHasSausage }
        if (occupiedCount == 0 || emptyPans.isEmpty()) {
            return emptyList()
        }

        val reservedFrontPans = frontPans
            .filter { !it.isHasSausage && !it.taste?.tasteCode.isNullOrBlank() }
            .sortedBy { it.positionSn }
        val groupStart = sortedGroup.firstOrNull()?.positionSn ?: return emptyList()
        val groupPositions = sortedGroup.map { it.positionSn }.toSet()
        val intentTargetsByPosition = activeIntent
            ?.takeIf { it.type == SupplementBatchType.HIGH_HEAT_25_33 }
            ?.targets
            ?.filter {
                !it.cancelledByFaultTimeout &&
                    !it.skippedBySupplyDegrade &&
                    it.positionSn in groupPositions
            }
            ?.associateBy { it.positionSn }
            .orEmpty()

        return emptyPans.mapNotNull { targetPan ->
            val intentTarget = intentTargetsByPosition[targetPan.positionSn]
            val reservedFrontPan = reservedFrontPanForGroupPosition(targetPan, groupStart, reservedFrontPans)
            val tasteCode = targetPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                ?: intentTarget?.tasteCode?.takeIf { it.isNotBlank() }
                ?: reservedFrontPan?.taste?.tasteCode?.takeIf { it.isNotBlank() }
            if (tasteCode.isNullOrBlank()) {
                return@mapNotNull null
            }
            if (targetPan.taste?.tasteCode.isNullOrBlank()) {
                targetPan.taste = Taste(tasteCode)
            }
            ResumeTarget(
                kaoPan = targetPan,
                tasteCode = tasteCode,
                returnPositionSn = intentTarget?.returnPositionSn?.takeIf { it in 1..21 }
                    ?: reservedFrontPan?.positionSn
            )
        }
    }

    fun isCompleteFinishedBatch(
        supplementGroup: List<KaoPan>,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): Boolean {
        normalizeLegacySellableKeepWarmSources(supplementGroup)
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        if (!hasFullBatchStarted(sortedGroup)) {
            return false
        }
        return sortedGroup.all { isKeepWarmReady(it, pendingDiscardPositions) }
    }

    fun findFinishedPartialBatch(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allFinished = occupiedPans.all { pan ->
            isKeepWarmReady(pan) || isBakingMatured(pan, config, nowMs)
        }
        return if (allFinished) occupiedPans else emptyList()
    }

    fun markFinishedPartialBatchKeepWarm(
        supplementGroup: List<KaoPan>,
        nowMs: Long,
        config: AppConfigBean = AppConfig.getAppConfig()
    ): List<KaoPan> {
        val finishedPans = findFinishedPartialBatch(supplementGroup, nowMs, config)
        finishedPans.forEach { pan ->
            if (pan.startTime > 0L) {
                pan.startTime = 0L
                pan.holdingTime = nowMs
                pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            }
        }
        return finishedPans
    }

    fun findKeepWarmPartialBatchReadyToMove(
        supplementGroup: List<KaoPan>,
        config: AppConfigBean = AppConfig.getAppConfig(),
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): List<KaoPan> {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }
        normalizeLegacySellableKeepWarmSources(supplementGroup)
        val sortedGroup = supplementGroup.sortedBy { it.positionSn }
        val occupiedPans = sortedGroup.filter { it.isHasSausage }
        if (occupiedPans.size !in 1 until sortedGroup.size) {
            return emptyList()
        }
        val allKeepWarm = occupiedPans.all { pan ->
            isKeepWarmReady(pan, pendingDiscardPositions)
        }
        return if (allKeepWarm) occupiedPans else emptyList()
    }

    fun normalizeLegacySellableKeepWarmSources(supplementGroup: List<KaoPan>): List<Int> {
        val normalizedPositions = mutableListOf<Int>()
        supplementGroup
            .filter { pan ->
                pan.positionSn in 25..33 &&
                    pan.isHasSausage &&
                    pan.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                    pan.startTime <= 0L &&
                    pan.holdingTime > 0L
            }
            .forEach { pan ->
                pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                normalizedPositions += pan.positionSn
            }
        return normalizedPositions.sorted()
    }

    private fun reservedFrontPanForGroupPosition(
        targetPan: KaoPan,
        groupStart: Int,
        reservedFrontPans: List<KaoPan>
    ): KaoPan? {
        val offset = targetPan.positionSn - groupStart
        if (offset < 0) {
            return null
        }
        return reservedFrontPans.getOrNull(offset)
    }

    private fun expandTasteAllocation(allocation: Map<String, Int>): List<String> {
        return allocation.flatMap { (tasteCode, count) ->
            List(count.coerceAtLeast(0)) { tasteCode }
        }
    }

    private fun buildAvailableStockByTaste(listBox: List<KaoPanBox>): MutableMap<String, Int> {
        return listBox
            .filter {
                KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it) &&
                    SourceBoxPositionPolicy.isValidSourceBox(it.positionSn) &&
                    !it.tasteCode.isNullOrBlank()
            }
            .groupBy { it.tasteCode.orEmpty() }
            .mapValues { (_, boxes) -> boxes.sumOf { it.num } }
            .filterValues { it > 0 }
            .toMutableMap()
    }

    private fun selectTasteWithRemainingStock(
        preferredTasteCode: String?,
        remainingStockByTaste: Map<String, Int>
    ): String? {
        val preferred = preferredTasteCode?.takeIf { it.isNotBlank() }
        if (preferred != null && (remainingStockByTaste[preferred] ?: 0) > 0) {
            return preferred
        }
        return remainingStockByTaste
            .filterValues { it > 0 }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
    }

    private fun isKeepWarmReady(
        pan: KaoPan,
        pendingDiscardPositions: Collection<Int> = KaoPanHelper.pendingDiscardPositionsOrEmpty()
    ): Boolean {
        return pan.isHasSausage &&
            pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM &&
            pan.startTime == 0L &&
            pan.holdingTime > 0L &&
            pan.positionSn !in pendingDiscardPositions
    }

    private fun isBakingMatured(
        pan: KaoPan,
        config: AppConfigBean,
        nowMs: Long
    ): Boolean {
        return pan.startTime > 0L &&
            pan.bakingTime > 0L &&
            FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs) >= pan.bakingTime
    }
}
