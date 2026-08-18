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

    fun isValidInitialPlan(targets: List<ResumeTarget>): Boolean {
        if (targets.isEmpty() || targets.size > 9) return false
        val sourcePositions = targets.map { it.positionSn }
        if (sourcePositions.sorted() != (25 until 25 + targets.size).toList()) {
            return false
        }
        val returnPositions = targets.map { it.returnPositionSn }
        return returnPositions.all { it != null && it in 1..21 } &&
            returnPositions.filterNotNull().toSet().size == targets.size
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
        committedFutureReturnPositions: List<Int> = emptyList(),
        committedReturnTasteByPosition: Map<Int, String> = emptyMap()
    ): List<ResumeTarget> {
        val currentReturnPans = frontPans
            .filter {
                !it.isHasSausage &&
                    it.positionSn !in excludedReturnPositions &&
                    it.positionSn !in committedReturnTasteByPosition
            }
            .sortedBy { it.positionSn }
            .take(requestedCount)
        val currentReturnPositions = currentReturnPans.map { it.positionSn }.toSet()
        val futureReturnPans = if (currentReturnPans.size < requestedCount) {
            val futureReturnPositions = committedFutureReturnPositions
                .filter {
                    it in 1..21 &&
                        it !in excludedReturnPositions &&
                        it !in currentReturnPositions &&
                        it !in committedReturnTasteByPosition
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

        val occupiedTasteByPosition = frontPans
            .filter { it.positionSn !in futureReturnPositions && it.isHasSausage }
            .mapNotNull { pan ->
                val tasteCode = committedReturnTasteByPosition[pan.positionSn]
                    ?: pan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                tasteCode?.let { pan.positionSn to it }
            }
            .toMap()
            .toMutableMap()
            .apply {
                committedReturnTasteByPosition.forEach { (positionSn, tasteCode) ->
                    if (positionSn in 1..21 && positionSn !in futureReturnPositions && tasteCode.isNotBlank()) {
                        put(positionSn, tasteCode)
                    }
                }
            }
        val tastePlan = SupplementTasteMixPlanner.plan(
            frontCapacity = 21,
            targetCountByTaste = KaoPanHelper.computeHighKaoPamRatio(listBox, 21),
            availableStockByTaste = buildAvailableStockByTaste(listBox),
            occupiedTasteByPosition = occupiedTasteByPosition,
            assignablePositions = returnPans.take(targetPans.size).map { it.positionSn }
        )
        val assignmentByPosition = tastePlan.assignments.associateBy { it.positionSn }

        val targets = targetPans.mapIndexedNotNull { index, targetPan ->
            val returnPan = returnPans.getOrNull(index) ?: return@mapIndexedNotNull null
            val tasteCode = assignmentByPosition[returnPan.positionSn]?.tasteCode
                ?: return@mapIndexedNotNull null
            if (returnPan.positionSn !in futureReturnPositions &&
                returnPan.taste?.tasteCode != tasteCode
            ) {
                returnPan.taste = Taste(tasteCode)
            }
            targetPan.taste = Taste(tasteCode)
            ResumeTarget(targetPan, tasteCode, returnPan.positionSn)
        }
        if (targets.size == requestedCount) {
            val assignmentText = tastePlan.assignments.joinToString(",") {
                "${it.positionSn}:${it.tasteCode}"
            }
            runCatching {
                cn.niuyannet.kaochang.android.utils.LogUtils.i(
                    "【高并发补肠】新批次口味规划：target=${tastePlan.targetCountByTaste}，" +
                        "occupied=${tastePlan.occupiedCountByTaste}，" +
                        "assignments=$assignmentText，" +
                        "stockLimited=${tastePlan.stockLimited}"
                )
            }
        }
        return targets
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
        if (activeIntent != null) {
            return emptyList()
        }

        val reservedFrontPans = frontPans
            .filter { !it.isHasSausage && !it.taste?.tasteCode.isNullOrBlank() }
            .sortedBy { it.positionSn }
        val groupStart = sortedGroup.firstOrNull()?.positionSn ?: return emptyList()
        return emptyPans.mapNotNull { targetPan ->
            val reservedFrontPan = reservedFrontPanForGroupPosition(targetPan, groupStart, reservedFrontPans)
            val tasteCode = targetPan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                ?: reservedFrontPan?.taste?.tasteCode?.takeIf { it.isNotBlank() }
            if (tasteCode.isNullOrBlank()) {
                return@mapNotNull null
            }
            ResumeTarget(
                kaoPan = targetPan,
                tasteCode = tasteCode,
                returnPositionSn = reservedFrontPan?.positionSn
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
