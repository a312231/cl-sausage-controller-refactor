package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.scheduler.SchedulerProfileRuntime
import cn.niuyannet.kaochang.scheduler.SupplementPositionInput

internal object HighHeatSupplementResumePlanner {
    private val batchHeatPositionStrategy = SchedulerProfileRuntime.activeProfile.batchHeatPositionStrategy

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
        committedReturnTasteByPosition: Map<Int, String> = emptyMap(),
        frontTasteWritesBeforeTargetCommit: List<DeferredTasteReservationWrite> = emptyList()
    ): List<ResumeTarget> {
        val excludedReturnSnapshot = excludedReturnPositions.toSet()
        val committedFutureReturnSnapshot = committedFutureReturnPositions.toList()
        val committedReturnTasteSnapshot = committedReturnTasteByPosition.toMap()
        val frontTasteWriteSnapshot = frontTasteWritesBeforeTargetCommit.toList()
        val allFrontByPosition = frontPans.associateBy { it.positionSn }
        val currentReturnPans = frontPans
            .filter {
                !it.isHasSausage &&
                    it.positionSn !in excludedReturnSnapshot &&
                    it.positionSn !in committedReturnTasteSnapshot
            }
            .sortedBy { it.positionSn }
            .take(requestedCount)
        val currentReturnPositions = currentReturnPans.map { it.positionSn }.toSet()
        val futureReturnPans = if (currentReturnPans.size < requestedCount) {
            val futureReturnPositions = committedFutureReturnSnapshot
                .filter {
                    it in 1..21 &&
                        it !in excludedReturnSnapshot &&
                        it !in currentReturnPositions &&
                        it !in committedReturnTasteSnapshot
                }
                .distinct()
                .take(requestedCount - currentReturnPans.size)
                .toSet()
            frontPans
                .filter { it.positionSn in futureReturnPositions }
                .sortedBy { committedFutureReturnSnapshot.indexOf(it.positionSn).let { index -> if (index >= 0) index else Int.MAX_VALUE } }
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
        val positionPlan = batchHeatPositionStrategy.planPositions(
            SupplementPositionInput(
                requestedCount = requestedCount,
                targetCandidates = targetPans.map { it.positionSn },
                returnCandidates = returnPans.map { it.positionSn }
            )
        )
        if (positionPlan.pairs.isEmpty()) {
            return emptyList()
        }
        val targetByPosition = targetPans.associateBy { it.positionSn }
        val returnByPosition = returnPans.associateBy { it.positionSn }
        val selectedPairs = positionPlan.pairs.mapNotNull { pair ->
            val targetPan = targetByPosition[pair.targetPosition] ?: return@mapNotNull null
            val returnPan = returnByPosition[pair.returnPosition] ?: return@mapNotNull null
            targetPan to returnPan
        }
        if (selectedPairs.size != positionPlan.pairs.size) {
            return emptyList()
        }

        val occupiedTasteByPosition = frontPans
            .filter { it.positionSn !in futureReturnPositions && it.isHasSausage }
            .mapNotNull { pan ->
                val tasteCode = committedReturnTasteSnapshot[pan.positionSn]
                    ?: pan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                tasteCode?.let { pan.positionSn to it }
            }
            .toMap()
            .toMutableMap()
            .apply {
                committedReturnTasteSnapshot.forEach { (positionSn, tasteCode) ->
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
            assignablePositions = selectedPairs.map { it.second.positionSn }
        )
        val assignmentByPosition = tastePlan.assignments.associateBy { it.positionSn }

        val targets = selectedPairs.mapNotNull { (targetPan, returnPan) ->
            val tasteCode = assignmentByPosition[returnPan.positionSn]?.tasteCode
                ?: return@mapNotNull null
            ResumeTarget(targetPan, tasteCode, returnPan.positionSn)
        }
        if (!isValidInitialPlan(targets) || targets.size != selectedPairs.size) {
            return emptyList()
        }
        val validatedTargets = validateInitialTargetsBeforeTasteWrite(
            targets = targets,
            targetByPosition = targetByPosition,
            returnByPosition = returnByPosition,
            futureReturnPositions = futureReturnPositions,
            excludedReturnPositions = excludedReturnSnapshot,
            committedReturnTasteByPosition = committedReturnTasteSnapshot
        ) ?: return emptyList()
        if (frontTasteWriteSnapshot.map { it.expectedPositionSn }.distinct().size != frontTasteWriteSnapshot.size) {
            return emptyList()
        }
        val validatedFrontTasteWrites = frontTasteWriteSnapshot.map { write ->
            val frontPan = allFrontByPosition[write.expectedPositionSn] ?: return emptyList()
            if (
                frontPan !== write.expectedPan ||
                frontPan.positionSn != write.expectedPositionSn ||
                frontPan.isHasSausage ||
                !frontPan.taste?.tasteCode.isNullOrBlank() ||
                write.tasteCode.isBlank()
            ) {
                return emptyList()
            }
            frontPan to write.tasteCode
        }
        validatedFrontTasteWrites.forEach { (frontPan, tasteCode) ->
            frontPan.taste = Taste(tasteCode)
        }
        validatedTargets.forEach { (target, returnPan) ->
            if (returnPan.positionSn !in futureReturnPositions &&
                returnPan.taste?.tasteCode != target.tasteCode
            ) {
                returnPan.taste = Taste(target.tasteCode)
            }
            target.kaoPan.taste = Taste(target.tasteCode)
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

    internal fun validateInitialTargetsBeforeTasteWrite(
        targets: List<ResumeTarget>,
        targetByPosition: Map<Int, KaoPan>,
        returnByPosition: Map<Int, KaoPan>,
        futureReturnPositions: Set<Int>,
        excludedReturnPositions: Set<Int>,
        committedReturnTasteByPosition: Map<Int, String>
    ): List<Pair<ResumeTarget, KaoPan>>? {
        val validated = targets.map { target ->
            val targetPan = targetByPosition[target.positionSn] ?: return null
            if (targetPan !== target.kaoPan || targetPan.isHasSausage) return null
            val returnPosition = target.returnPositionSn ?: return null
            val returnPan = returnByPosition[returnPosition] ?: return null
            if (returnPan.positionSn != returnPosition) return null
            if (returnPosition in excludedReturnPositions || returnPosition in committedReturnTasteByPosition) {
                return null
            }
            if (returnPosition !in futureReturnPositions && returnPan.isHasSausage) return null
            target to returnPan
        }
        return validated.takeIf { it.size == targets.size }
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
