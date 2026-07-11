package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.AppConfig
import java.util.UUID

data class LowerPlatformEpisodeDetails(
    val sourceBoxNo: Int? = null,
    val targetPanNo: Int? = null,
    val tasteCode: String? = null,
    val batchId: String? = null,
    val batchType: String? = null,
    val errorCode: String? = null,
    val rawResult: String? = null,
    val requiresManualConfirm: Boolean = false
)

object LowerPlatformEpisodeReducer {
    const val MAX_EPISODE_HISTORY = 80
    private const val LIFECYCLE_OPEN = "OPEN"
    private const val LIFECYCLE_RECOVERED = "RECOVERED"

    data class EpisodeSnapshot(
        val eventId: String,
        val revision: Long,
        val faultType: String,
        val lifecycle: String,
        val actionName: String,
        val actionLabel: String,
        val errorCode: String,
        val rawResult: String,
        val reason: String,
        val sourceBoxNo: Int?,
        val tasteCode: String,
        val targetPanNo: Int?,
        val batchId: String,
        val batchType: String,
        val firstOccurredAt: Long,
        val lastOccurredAt: Long,
        val occurrenceCount: Int,
        val recoveredAt: Long?,
        val requiresManualConfirm: Boolean
    )

    data class StateSnapshot(
        val generation: String,
        val aggregateVersion: Long,
        val status: String,
        val isolationActionName: String,
        val supplementEnabled: Int,
        val orderBlockReason: String,
        val episodes: List<EpisodeSnapshot>
    )

    data class TransactionResult<T>(val value: T, val snapshot: StateSnapshot)

    @Synchronized
    fun <T> transaction(
        config: AppConfigBean,
        persist: (AppConfigBean) -> Unit,
        mutation: () -> T
    ): TransactionResult<T> {
        val value = mutation()
        normalize(config)
        persist(config)
        return TransactionResult(value, snapshot(config))
    }

    @Synchronized
    fun snapshot(config: AppConfigBean): StateSnapshot {
        val status = reduceStatus(config)
        return StateSnapshot(
            generation = config.lowerPlatformGeneration.orEmpty(),
            aggregateVersion = config.lowerPlatformAggregateVersion,
            status = status,
            isolationActionName = config.lowerPlatformIsolationActionName.orEmpty(),
            supplementEnabled = if (status == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
                AppConfigBean.SUPPLEMENT_ENABLED_NO
            } else {
                AppConfigBean.SUPPLEMENT_ENABLED_YES
            },
            orderBlockReason = config.orderBlockReason.orEmpty(),
            episodes = config.lowerPlatformEpisodes.map { it.toSnapshot() }
        )
    }

    @Synchronized
    fun openSupplyDegraded(
        config: AppConfigBean,
        sourceBoxNos: List<Int>,
        reason: String,
        occurredAt: Long = System.currentTimeMillis(),
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails()
    ) {
        sourceBoxNos.distinct().forEach { boxNo ->
            val existing = config.lowerPlatformEpisodes.firstOrNull {
                it.lifecycle == LIFECYCLE_OPEN &&
                    it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED &&
                    it.sourceBoxNo == boxNo
            }
            if (existing == null) {
                config.lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                    eventId = UUID.randomUUID().toString()
                    faultType = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
                    lifecycle = LIFECYCLE_OPEN
                    actionName = "in2"
                    actionLabel = "取肠并分料"
                    errorCode = details.errorCode?.takeIf { it.isNotBlank() } ?: "IR_PICK_FAILED"
                    rawResult = details.rawResult.orEmpty()
                    this.reason = reason
                    sourceBoxNo = boxNo
                    targetPanNo = details.targetPanNo
                    tasteCode = details.tasteCode.orEmpty()
                    batchId = details.batchId.orEmpty()
                    batchType = details.batchType.orEmpty()
                    firstOccurredAt = occurredAt
                    lastOccurredAt = occurredAt
                    occurrenceCount = 1
                    requiresManualConfirm = details.requiresManualConfirm
                })
            } else {
                existing.revision += 1L
                existing.lastOccurredAt = occurredAt
                existing.occurrenceCount += 1
                existing.reason = reason
                existing.rawResult = details.rawResult.orEmpty()
                existing.targetPanNo = details.targetPanNo
                existing.tasteCode = details.tasteCode.orEmpty()
                existing.batchId = details.batchId.orEmpty()
                existing.batchType = details.batchType.orEmpty()
                existing.requiresManualConfirm = details.requiresManualConfirm
            }
        }
        changed(config)
    }

    @Synchronized
    fun openIsolated(
        config: AppConfigBean,
        actionName: String,
        reason: String,
        occurredAt: Long = System.currentTimeMillis(),
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails(requiresManualConfirm = true)
    ) {
        val existing = config.lowerPlatformEpisodes.firstOrNull {
            it.lifecycle == LIFECYCLE_OPEN && it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
        }
        if (existing == null) {
            config.lowerPlatformEpisodes.add(AppConfigBean.LowerPlatformEpisodeBean().apply {
                eventId = UUID.randomUUID().toString()
                faultType = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
                lifecycle = LIFECYCLE_OPEN
                this.actionName = actionName
                actionLabel = actionLabel(actionName)
                errorCode = details.errorCode.orEmpty()
                rawResult = details.rawResult.orEmpty()
                this.reason = reason
                sourceBoxNo = details.sourceBoxNo
                targetPanNo = details.targetPanNo
                tasteCode = details.tasteCode.orEmpty()
                batchId = details.batchId.orEmpty()
                batchType = details.batchType.orEmpty()
                firstOccurredAt = occurredAt
                lastOccurredAt = occurredAt
                occurrenceCount = 1
                requiresManualConfirm = details.requiresManualConfirm
            })
        } else {
            existing.revision += 1L
            existing.lastOccurredAt = occurredAt
            existing.occurrenceCount += 1
            existing.actionName = actionName
            existing.actionLabel = actionLabel(actionName)
            existing.reason = reason
            existing.errorCode = details.errorCode.orEmpty()
            existing.rawResult = details.rawResult.orEmpty()
            existing.sourceBoxNo = details.sourceBoxNo
            existing.targetPanNo = details.targetPanNo
            existing.tasteCode = details.tasteCode.orEmpty()
            existing.batchId = details.batchId.orEmpty()
            existing.batchType = details.batchType.orEmpty()
            existing.requiresManualConfirm = details.requiresManualConfirm
        }
        changed(config)
    }

    @Synchronized
    fun recoverSupplyBox(config: AppConfigBean, sourceBoxNo: Int, recoveredAt: Long = System.currentTimeMillis()): Boolean {
        val episodes = config.lowerPlatformEpisodes.filter {
            it.lifecycle == LIFECYCLE_OPEN &&
                it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED &&
                it.sourceBoxNo == sourceBoxNo
        }
        if (episodes.isEmpty()) return false
        episodes.forEach {
            it.lifecycle = LIFECYCLE_RECOVERED
            it.revision += 1L
            it.recoveredAt = recoveredAt
            it.lastOccurredAt = recoveredAt
        }
        changed(config)
        return true
    }

    @Synchronized
    fun recoverSupplyBoxes(
        config: AppConfigBean,
        sourceBoxNos: Collection<Int>,
        recoveredAt: Long = System.currentTimeMillis()
    ): Boolean {
        val selected = sourceBoxNos.toSet()
        val episodes = config.lowerPlatformEpisodes.filter {
            it.lifecycle == LIFECYCLE_OPEN &&
                it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED &&
                it.sourceBoxNo in selected
        }
        if (episodes.isEmpty()) return false
        episodes.forEach {
            it.lifecycle = LIFECYCLE_RECOVERED
            it.revision += 1L
            it.recoveredAt = recoveredAt
            it.lastOccurredAt = recoveredAt
        }
        changed(config)
        return true
    }

    @Synchronized
    fun recoverAllSupplyBoxes(config: AppConfigBean, recoveredAt: Long = System.currentTimeMillis()): Boolean {
        val openBoxNos = config.lowerPlatformEpisodes.filter {
            it.lifecycle == LIFECYCLE_OPEN && it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
        }.mapNotNull { it.sourceBoxNo }.distinct()
        if (openBoxNos.isEmpty()) return false
        return recoverSupplyBoxes(config, openBoxNos, recoveredAt)
    }

    @Synchronized
    fun recoverIsolation(config: AppConfigBean, recoveredAt: Long = System.currentTimeMillis()): Boolean {
        val episodes = config.lowerPlatformEpisodes.filter {
            it.lifecycle == LIFECYCLE_OPEN && it.faultType == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
        }
        if (episodes.isEmpty()) return false
        episodes.forEach {
            it.lifecycle = LIFECYCLE_RECOVERED
            it.revision += 1L
            it.recoveredAt = recoveredAt
            it.lastOccurredAt = recoveredAt
        }
        changed(config)
        return true
    }

    @Synchronized
    fun reduceStatus(config: AppConfigBean): String {
        val activeTypes = config.lowerPlatformEpisodes.asSequence()
            .filter { it.lifecycle == LIFECYCLE_OPEN }
            .map { it.faultType }
            .toSet()
        return when {
            AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED in activeTypes -> AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
            AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED in activeTypes ->
                AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
            else -> AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        }
    }

    @Synchronized
    fun normalize(config: AppConfigBean): Boolean {
        val changed = normalizeInternal(config)
        if (changed) {
            incrementAggregateVersion(config)
        }
        return changed
    }

    private fun changed(config: AppConfigBean) {
        AppConfig.ensureLowerPlatformGeneration(config)
        normalizeInternal(config)
        incrementAggregateVersion(config)
    }

    private fun normalizeInternal(config: AppConfigBean): Boolean {
        val before = config.lowerPlatformEpisodes.map { it.toSnapshot() }
        val valid = config.lowerPlatformEpisodes.filterNotNull().filter { episode ->
            episode.eventId.isNotBlank() &&
                episode.faultType in setOf(
                    AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED,
                    AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
                ) &&
                episode.lifecycle in setOf(LIFECYCLE_OPEN, LIFECYCLE_RECOVERED) &&
                (episode.sourceBoxNo == null || episode.sourceBoxNo in 1..22) &&
                (episode.targetPanNo == null || episode.targetPanNo in 1..33)
        }.onEach { episode ->
            episode.revision = episode.revision.coerceAtLeast(1L)
            episode.occurrenceCount = episode.occurrenceCount.coerceAtLeast(1)
            episode.firstOccurredAt = episode.firstOccurredAt.coerceAtLeast(0L)
            episode.lastOccurredAt = episode.lastOccurredAt.coerceAtLeast(episode.firstOccurredAt)
        }
        val open = valid.filter { it.lifecycle == LIFECYCLE_OPEN }
        val recoveredBudget = (MAX_EPISODE_HISTORY - open.size).coerceAtLeast(0)
        val recovered = valid.filter { it.lifecycle == LIFECYCLE_RECOVERED }
            .sortedByDescending { it.recoveredAt ?: it.lastOccurredAt }
            .take(recoveredBudget)
        val normalized = (open + recovered).toCollection(ArrayList())
        val listChanged = before != normalized.map { it.toSnapshot() }
        if (listChanged) {
            config.lowerPlatformEpisodes = normalized
        }
        val reduced = reduceStatus(config)
        val projectionChanged = config.lowerPlatformStatus != reduced ||
            config.supplementEnabled != if (reduced == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
                AppConfigBean.SUPPLEMENT_ENABLED_NO
            } else {
                AppConfigBean.SUPPLEMENT_ENABLED_YES
            }
        config.lowerPlatformStatus = reduced
        config.supplementEnabled = if (reduced == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            AppConfigBean.SUPPLEMENT_ENABLED_NO
        } else {
            AppConfigBean.SUPPLEMENT_ENABLED_YES
        }
        return listChanged || projectionChanged
    }

    private fun AppConfigBean.LowerPlatformEpisodeBean.toSnapshot(): EpisodeSnapshot = EpisodeSnapshot(
        eventId, revision, faultType, lifecycle, actionName, actionLabel, errorCode, rawResult, reason,
        sourceBoxNo, tasteCode, targetPanNo, batchId, batchType, firstOccurredAt, lastOccurredAt,
        occurrenceCount, recoveredAt, requiresManualConfirm
    )

    private fun incrementAggregateVersion(config: AppConfigBean) {
        if (config.lowerPlatformAggregateVersion == Long.MAX_VALUE) {
            config.lowerPlatformGeneration = UUID.randomUUID().toString()
            config.lowerPlatformAggregateVersion = 1L
        } else {
            config.lowerPlatformAggregateVersion = (config.lowerPlatformAggregateVersion + 1L).coerceAtLeast(1L)
        }
    }

    private fun actionLabel(actionName: String): String = when (actionName) {
        "in1" -> "移动到烤肠箱预推送位置"
        "in2" -> "取肠并分料"
        "in3" -> "回到抛肠点"
        "in4" -> "升降台下降"
        "in5" -> "抛肠并检测"
        else -> actionName
    }
}
