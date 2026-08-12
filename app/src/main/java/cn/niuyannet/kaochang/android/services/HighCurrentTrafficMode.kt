package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 高并发模式控制器。
 *
 * 负责高并发前区 1~21 与补热区 25~33 的初始化、补肠、回补、过保丢弃，
 * 同时根据营业结束时间和补肠节奏判断是否进入高转低过渡态。
 */
object HighCurrentTrafficMode {
    private const val TAG = "KaoChangAlgorithm"

    /** 已触发的高并发补肠坑位计数，仅用于状态观察。 */
    private var pitCount = 0

    /** 最近一次高并发补肠成功时间，用于判断高转低。 */
    private var lastFillPitTime = 0L

    private var lastHighTrafficSnapshot: String? = null
    private var lastLowToHighPartialWaitSnapshot: LowToHighPartialWaitLogSnapshot? = null

    private fun modeText(value: Int): String = when (value) {
        1 -> "1(低并发)"
        2 -> "2(高并发)"
        else -> "$value(未知)"
    }

    private fun transitionText(value: Int): String = when (value) {
        0 -> "0(无过渡态)"
        1 -> "1(低转高过渡)"
        2 -> "2(高转低过渡)"
        else -> "$value(未知)"
    }

    private fun currentSupplementTrackingState(): HighTrafficSupplementTrackingState =
        HighTrafficSupplementTrackingState(
            pitCount = pitCount,
            lastFillPitTime = lastFillPitTime
        )

    private fun applySupplementTrackingState(
        nextState: HighTrafficSupplementTrackingState,
        reason: String
    ) {
        val previous = currentSupplementTrackingState()
        if (previous == nextState) {
            return
        }
        pitCount = nextState.pitCount
        lastFillPitTime = nextState.lastFillPitTime
        LogUtils.d(
            TAG,
            "【高并发补肠】更新补肠节奏跟踪：reason=$reason，pitCount=${previous.pitCount}->${nextState.pitCount}，lastFillPitTime=${previous.lastFillPitTime}->${nextState.lastFillPitTime}"
        )
    }

    private fun resetSupplementTracking(reason: String) {
        clearLowToHighPartialWaitSnapshot()
        applySupplementTrackingState(
            nextState = resetHighTrafficSupplementTracking(),
            reason = reason
        )
    }

    private fun recordSupplementLaunch(
        filledCount: Int,
        now: Long,
        reason: String
    ) {
        applySupplementTrackingState(
            nextState = recordHighTrafficSupplementLaunch(
                previous = currentSupplementTrackingState(),
                filledCount = filledCount,
                now = now
            ),
            reason = reason
        )
    }

    private fun recordSupplementLaunchOutcome(
        requestedTargetCount: Int,
        movedCount: Int,
        now: Long,
        reason: String
    ) {
        applySupplementTrackingState(
            nextState = recordHighTrafficSupplementLaunchOutcome(
                previous = currentSupplementTrackingState(),
                requestedTargetCount = requestedTargetCount,
                movedCount = movedCount,
                now = now
            ),
            reason = reason
        )
    }

    private fun clearPitCountAfterBatch(reason: String) {
        applySupplementTrackingState(
            nextState = currentSupplementTrackingState().copy(pitCount = 0),
            reason = reason
        )
    }

    private fun clearLowToHighPartialWaitSnapshot() {
        lastLowToHighPartialWaitSnapshot = null
    }

    private fun logLowToHighPartialWaitIfChanged(
        list13_18: List<KaoPan>,
        list25_33: List<KaoPan>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean,
        errorStatus: Int
    ) {
        val currentSnapshot = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = list13_18,
            partialHighHeatPans = list25_33,
            boxSupplyAvailable = boxSupplyAvailable,
            schedulingEnabled = schedulingEnabled,
            errorStatus = errorStatus
        )
        if (!shouldLogLowToHighPartialWait(lastLowToHighPartialWaitSnapshot, currentSnapshot)) {
            return
        }
        lastLowToHighPartialWaitSnapshot = currentSnapshot
        LogUtils.i(
            TAG,
            "【并发切换】低转高等待旧补热区清空：13~18剩余=${currentSnapshot.oldLowHeatPositions}，" +
                "25~33残缺批次=${currentSnapshot.partialHighHeatPositions}(${currentSnapshot.partialHighHeatPositions.size}/9)，" +
                "boxSupplyAvailable=${currentSnapshot.boxSupplyAvailable}，" +
                "schedulingEnabled=${currentSnapshot.schedulingEnabled}，" +
                "errorStatus=${currentSnapshot.errorStatus}"
        )
    }

    private fun selfHealStaleLowToHighTransition(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>
    ): Boolean {
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList)
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val hasRealLowHeatLegacyBatch = hasRealOldLowHeatLegacyBatch(
            oldLowHeatPans = list13_18,
            frontPans = list1_9
        )
        val hasHighHeatSupplyInProgress =
            HighHeatSupplementResumePlanner.hasBatchStarted(list25_33) || pitCount > 0
        val resolvedState = resolveStaleLowToHighTransitionState(
            currentModeType = config.modeType,
            currentTransitionMode = config.transitionMode,
            hasRealLowHeatLegacyBatch = hasRealLowHeatLegacyBatch,
            hasHighHeatSupplyInProgress = hasHighHeatSupplyInProgress
        ) ?: return false

        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        config.modeType = resolvedState.modeType
        config.transitionMode = resolvedState.transitionMode
        clearLowToHighPartialWaitSnapshot()
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus("runtime_state:stale_low_to_high_transition_healed")
        LogUtils.w(
            TAG,
            "【并发切换自愈】禁止高并发稳态反向进入低转高，已自动纠正且本轮不启动25~33：" +
                "source=HighCurrentTrafficMode.selfHealStaleLowToHighTransition，" +
                "modeType（并发模式）=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "hasRealLowHeatLegacyBatch（13~18真实旧低并发补热批次）=$hasRealLowHeatLegacyBatch，" +
                "hasHighHeatSupplyInProgress（25~33补热批次仍在处理）=$hasHighHeatSupplyInProgress，" +
                "1~21空位=${TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList).filter { !it.isHasSausage }.map { it.positionSn }}，" +
                "13~18状态=${panStatusSummary(list13_18)}，" +
                "25~33状态=${panStatusSummary(list25_33)}"
        )
        return true
    }

    private fun panStatusSummary(pans: List<KaoPan>): String {
        return pans.joinToString(prefix = "[", postfix = "]") { pan ->
            val state = when {
                pan.isHasSausage && pan.startTime > 0L -> "烤制中"
                pan.isHasSausage && pan.holdingTime > 0L -> "保温"
                pan.isHasSausage -> "有肠"
                pan.isHasGrilling -> "预留"
                else -> "空"
            }
            "${pan.positionSn}:$state"
        }
    }

    /**
     * 高并发主处理器。
     *
     * transitionMode 含义：
     * - 0：正常高并发
     * - 1：低转高过渡
     * - 2：高转低过渡
     */
    suspend fun highTrafficMode(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean = true
    ) {
        when (config.transitionMode) {
            0 -> {
                val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
                val p1_21 = list1_21.count { !it.isHasSausage }
                val p25_33 = list25_33.count { !it.isHasSausage }
                val totalEmpty = p1_21 + p25_33

                val stateSnapshot =
                    "front=$p1_21|25_33=$p25_33|total=$totalEmpty|pit=$pitCount|mode=${config.modeType}|transition=${config.transitionMode}|error=${config.errorStatus}"
                if (stateSnapshot != lastHighTrafficSnapshot) {
                    LogUtils.d(
                        TAG,
                        "【高并发状态】状态变化：1~21 空位=$p1_21，25~33 空位=$p25_33，总空位=$totalEmpty，pitCount=$pitCount，modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，errorStatus=${config.errorStatus}"
                    )
                    lastHighTrafficSnapshot = stateSnapshot
                }

                val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
                val isBeforeCloseWindow =
                    KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)

                val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
                checkHighHeatEnd(list1_9, 1)
                checkHighHeatEnd(list10_21, 2)
                checkHighHeatEnd(list25_33, 3)
                val preCleanupIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
                val preCleanupBatchState = classifyPersistedHighHeatBatch(
                    activeIntent = preCleanupIntent,
                    highHeatPans = list25_33
                )
                val blockUntrustedHighHeatRecovery =
                    shouldBlockPendingHighHeatRecovery(preCleanupIntent, preCleanupBatchState)
                if (blockUntrustedHighHeatRecovery) {
                    LogUtils.w(
                        TAG,
                        "【高并发补肠】25~33 持久化批次证据不可信，保留 completed binding 并阻断本轮自动续补：" +
                            "persistedBatchState=$preCleanupBatchState，batchId=${preCleanupIntent?.batchId}"
                    )
                } else {
                    KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
                        types = setOf(
                            SupplementBatchType.LOW_HEAT_13_15,
                            SupplementBatchType.LOW_HEAT_16_18,
                            SupplementBatchType.HIGH_HEAT_25_33
                        ),
                        sourcePositionsWithSausage = list
                            .filter { it.positionSn in 13..18 || it.positionSn in 25..33 }
                            .filter { it.isHasSausage }
                            .map { it.positionSn }
                            .toSet(),
                        sourcePanStatuses = list.associate { it.positionSn to it.status },
                        reason = "高并发调度入口检测补热源盘已清空"
                    )
                    KaoChangOperate.reconcilePendingSupplementTargetsAlreadyOnPans(
                        allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                        expectedBatchId = preCleanupIntent?.batchId,
                        reason = "高并发正常态成熟检查前对账25~33已上盘目标",
                        pans = list
                    )
                }
                if (schedulingEnabled) {
                    checkHighHeatSausage(list)
                }

                val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
                val persistedBatchState = classifyPersistedHighHeatBatch(
                    activeIntent = activeIntent,
                    highHeatPans = list25_33
                )
                val pendingBatchHandling = if (
                    shouldBlockPendingHighHeatRecovery(activeIntent, persistedBatchState)
                ) {
                    LogUtils.w(
                        TAG,
                        "【高并发补肠】25~33 持久化批次证据不可信，阻断 pending 自动续补：" +
                            "persistedBatchState=$persistedBatchState，batchId=${activeIntent?.batchId}"
                    )
                    KaoChangOperate.PendingSupplementBatchHandling.BLOCKED
                } else {
                    KaoChangOperate.handlePendingSupplementBatchIfAny(
                        allowedTypes = setOf(
                            SupplementBatchType.HIGH_INITIALIZATION_1_21,
                            SupplementBatchType.HIGH_HEAT_25_33
                        ),
                        reason = "高并发正常态调度入口",
                        boxSupplyAvailable = boxSupplyAvailable,
                        schedulingEnabled = schedulingEnabled,
                        isBeforeCloseWindow = isBeforeCloseWindow
                    )
                }
                if (pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return
                }

                if (
                    schedulingEnabled &&
                    boxSupplyAvailable &&
                    config.errorStatus == 0 &&
                    !isBeforeCloseWindow &&
                    resumeHighInitialization(listBox, list1_21, list25_33)
                ) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return
                } else if (HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)) {
                    val shouldResumeIncompleteBatch = shouldResumeIncompleteHighHeatBatch(
                        pitCount = pitCount,
                        hasIncompleteBatch = true,
                        persistedBatchState = persistedBatchState
                    )
                    val handledFinishedPartialBatch = if (schedulingEnabled) {
                        checkHighHeatSausage(list)
                    } else {
                        false
                    }
                    if (handledFinishedPartialBatch) {
                        // 已处理成熟残缺批次，本轮不再续补，避免新肠混入同一补热批次。
                    } else if (shouldResumeIncompleteBatch && schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                        resumeIncompleteHighHeatSupplement(
                            list = list,
                            listBox = listBox,
                            reason = "高并发正常态恢复25~33未完成批次"
                        )
                    } else {
                        LogUtils.i(
                            TAG,
                            "【高并发补肠】25~33 补热区存在未满批次，本轮不续补：pitCount=$pitCount，" +
                                "persistedBatchState=$persistedBatchState，" +
                                "原因=${if (shouldResumeIncompleteBatch) "等待库存/调度条件满足" else "已记录实际批次大小，按当前批次整批烤熟后回搬"}，" +
                                "boxSupplyAvailable=$boxSupplyAvailable，schedulingEnabled=$schedulingEnabled，errorStatus=${config.errorStatus}，处于停业前收口窗口=$isBeforeCloseWindow"
                        )
                    }
                } else if (HighHeatSupplementResumePlanner.hasBatchStarted(list25_33)) {
                    checkHighHeatEnd(list25_33, 3)
                    if (schedulingEnabled) {
                        checkHighHeatSausage(list)
                    }
                } else if (totalEmpty == 30) {
                    if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                        initHighTrafficMode(list, listBox)
                        resetSupplementTracking("高并发初始化重新开盘")
                    } else {
                        LogUtils.w(
                            TAG,
                            "【高并发初始化】跳过初始化：boxSupplyAvailable=$boxSupplyAvailable，errorStatus=${config.errorStatus}，处于停业前收口窗口=$isBeforeCloseWindow"
                        )
                    }
                } else if (p1_21 >= 9) {
                    val blockedEmptyFrontPositions = LowToHighDemandGate.blockedEmptyFrontPositions(list1_21)
                    if (blockedEmptyFrontPositions.isNotEmpty()) {
                        switchHighToLow(
                            reason = "高并发前区达到补肠门槛但包含过保丢弃空位，blockedEmptyFrontPositions=$blockedEmptyFrontPositions",
                            kaoPanList = list
                        )
                        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                        return
                    }
                    if (p25_33 == 9) {
                        if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                            val movedCount = addHighHeatSausage(list, listBox)
                            if (movedCount > 0) {
                                LogUtils.i(
                                    TAG,
                                    "【高并发补肠】已向 25~33 补热区批量补肠：movedCount=$movedCount，lastFillPitTime=$lastFillPitTime"
                                )
                            }
                        } else if (isBeforeCloseWindow) {
                            LogUtils.i(TAG, "【高并发补肠】接近营业结束，停止向 25~33 补热区继续补肠")
                        }
                    } else {
                        checkHighHeatEnd(list25_33, 3)
                        if (schedulingEnabled) {
                            checkHighHeatSausage(list)
                        }
                    }
                }

                checkHighHeatEnd(list1_9, 1)
                checkHighHeatEnd(list10_21, 2)
                if (schedulingEnabled) {
                    checkHighHeatDiscard()
                }
                // 高转低收口不依赖烤肠箱库存；箱空时更应进入消化收口。
                if (schedulingEnabled) {
                    checkSwitchToLow(list, boxSupplyAvailable)
                }
                // 烤盘保存由 KaoPanHelper 内部后台作用域统一处理，这里直接提交最新状态。
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }

            1 -> {
                if (selfHealStaleLowToHighTransition(config, list)) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return
                }
                lowTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }

            2 -> {
                highTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }
        }
    }

    /**
     * 低并发内部高并发残留收口专用收尾。
     *
     * 不向 25~33 补新肠；25~33 已有批次按实际存在数量整体烤熟后回搬 1~21，
     * 当 25~33 清空且 1~21 剩余不超过 9 根时，收拢回真正低并发。
     */
    suspend fun drainHighConcurrencyResidual(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        schedulingEnabled: Boolean,
        reason: String
    ) {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)

        LogUtils.i(TAG, "【并发残留】执行高并发残留消化收尾：reason=$reason，不补新肠，25~33 已熟后回搬 1~21")
        checkHighHeatEnd(list1_9, 1)
        checkHighHeatEnd(list10_21, 2)
        checkHighHeatEnd(list25_33, 3, requireCompleteZone3Batch = false)
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        val persistedBatchState = classifyPersistedHighHeatBatch(
            activeIntent = activeIntent,
            highHeatPans = list25_33
        )
        val blockUntrustedHighHeatRecovery =
            shouldBlockPendingHighHeatRecovery(activeIntent, persistedBatchState)
        if (activeIntent?.type == SupplementBatchType.HIGH_HEAT_25_33 && !blockUntrustedHighHeatRecovery) {
            KaoChangOperate.reconcilePendingSupplementTargetsAlreadyOnPans(
                allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                expectedBatchId = activeIntent.batchId,
                reason = "高并发残留消化成熟检查前对账25~33已上盘目标",
                pans = list
            )
        }
        if (!schedulingEnabled) {
            LogUtils.i(TAG, "【并发残留】残留消化暂停自动搬盘：schedulingEnabled=false")
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return
        }

        checkHighHeatSausage(list)
        val frontSausageCount = TrafficModeHelper.getTrafficModePositions(1, 21, list).count { it.isHasSausage }
        val zone3SausageCount = list25_33.count { it.isHasSausage }
        val hasAnyUnreadySausage = hasUnreadySausage(list)
        if (canFinishHighToLowDrain(frontSausageCount, zone3SausageCount, hasUnreadySausage = hasAnyUnreadySausage)) {
            finishHighToLowCloseout(
                config = AppConfig.getAppConfig(),
                list = list,
                listBox = listBox,
                reason = "高并发残留消化完成"
            )
        }
        checkHighHeatDiscard()
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
    }

    private fun reportPlannedPanState(
        pans: Collection<KaoPan>,
        reason: String,
        source: String
    ) {
        val changedPositions = pans.map { it.positionSn }.distinct().sorted()
        if (changedPositions.isEmpty()) {
            return
        }
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                changedPanPositions = changedPositions,
                reason = reason,
                source = source
            )
        }.onFailure { error ->
            LogUtils.w(TAG, "【云端同步】$reason 物理增量上报异常，等待快照补偿：${error.message}")
        }
    }

    private suspend fun resumeHighInitialization(
        listBox: List<KaoPanBox>,
        list1_21: List<KaoPan>,
        list25_33: List<KaoPan>
    ): Boolean {
        TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
        val resumeTargets = TrafficInitializationResumePlanner.planFrontInitResumeTargets(list1_21, list25_33)
        if (resumeTargets.isEmpty()) {
            return false
        }

        reportPlannedPanState(
            pans = list1_21,
            reason = "高并发1~21初始化续补规划已确定",
            source = "high_initialization_resume_planned"
        )

        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)
        LogUtils.i(
            TAG,
            "【高并发初始化】检测到 1~21 初始化未完成，续补剩余空位：目标烤盘=${resumeTargets.joinToString(",") { it.positionSn.toString() }}"
        )
        val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
            resumeTargets.map { KaoChangOperate.SupplementTarget(it.kaoPan, it.tasteCode) },
            batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21
        )
        LogUtils.i(
            TAG,
            "【高并发初始化】1~21 初始化续补结束：目标数=${resumeTargets.size}，完成数=$movedCount"
        )
        return true
    }

    /**
     * 低并发 -> 高并发过渡。
     * 13~15 原补热区需要先完成加热，再进入过渡保温；
     * 25~33 启动新一轮高并发补肠。
     *
     * 注意：
     * 13~15 在过渡期间不会提前写入 holdingTime，避免尚未完成交接就被当成可售。
     */
    private suspend fun lowTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
        val isBeforeCloseWindow =
            KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)

        val hasStartedHighHeatSupply =
            HighHeatSupplementResumePlanner.hasBatchStarted(list25_33) || pitCount > 0
        val hadStartedHighHeatSupplyBefore = hasStartedHighHeatSupply
        val activeIntentBeforePending = KaoChangOperate.activeSupplementBatchIntentForUi()
        val persistedBatchStateBeforePending = classifyPersistedHighHeatBatch(
            activeIntent = activeIntentBeforePending,
            highHeatPans = list25_33
        )
        val pendingBatchHandling = if (
            shouldBlockPendingHighHeatRecovery(activeIntentBeforePending, persistedBatchStateBeforePending)
        ) {
            LogUtils.w(
                TAG,
                "【并发切换】低转高过渡检测到不可信25~33批次证据，阻断 pending 自动续补：" +
                    "persistedBatchState=$persistedBatchStateBeforePending，batchId=${activeIntentBeforePending?.batchId}"
            )
            KaoChangOperate.PendingSupplementBatchHandling.BLOCKED
        } else {
            KaoChangOperate.handlePendingSupplementBatchIfAny(
                allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                reason = "低转高过渡调度入口",
                boxSupplyAvailable = boxSupplyAvailable,
                schedulingEnabled = schedulingEnabled,
                isBeforeCloseWindow = isBeforeCloseWindow
            )
        }
        if (pendingBatchHandling == KaoChangOperate.PendingSupplementBatchHandling.RESUMED) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return
        }
        val pendingHighHeatBatchBlocksSupplement =
            pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        val persistedBatchState = classifyPersistedHighHeatBatch(
            activeIntent = activeIntent,
            highHeatPans = list25_33
        )

        val protectedOldLowHeatReturnPositions = collectProtectedOldLowHeatReturnPositions(
            frontPans = TrafficModeHelper.getTrafficModePositions(1, 9, list),
            oldLowHeatPans = list13_18,
            activeIntent = activeIntent
        )
        val committedOldLowHeatSourcePositions = collectCommittedOldLowHeatSourcePositionsForHighHeatReturn(
            frontPans = TrafficModeHelper.getTrafficModePositions(1, 9, list),
            oldLowHeatPans = list13_18,
            activeIntent = activeIntent
        )
        val hasIncompleteHighHeatBatch = HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)
        if (
            !pendingHighHeatBatchBlocksSupplement &&
            shouldResumeIncompleteHighHeatBatch(
                pitCount = pitCount,
                hasIncompleteBatch = hasIncompleteHighHeatBatch,
                persistedBatchState = persistedBatchState
            ) &&
            boxSupplyAvailable &&
            schedulingEnabled &&
            !isBeforeCloseWindow
        ) {
            resumeIncompleteHighHeatSupplement(
                list = list,
                listBox = listBox,
                reason = "低转高过渡恢复25~33未完成批次"
            )
        } else if (!pendingHighHeatBatchBlocksSupplement && !hasStartedHighHeatSupply && boxSupplyAvailable && schedulingEnabled && !isBeforeCloseWindow) {
            val frontEmptyCount = list1_21.count { !it.isHasSausage }
            TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
            val plannedTargets = HighHeatSupplementResumePlanner.planInitialTargets(
                frontPans = list1_21,
                supplementGroup = list25_33,
                listBox = listBox,
                excludedReturnPositions = protectedOldLowHeatReturnPositions,
                committedFutureReturnPositions = committedOldLowHeatSourcePositions.toList(),
                committedReturnTasteByPosition = SupplementTasteMixPlanner.committedReturnTasteByPosition(
                    activeIntent,
                    1..21
                )
            )
            val availableStockByTaste = listBox
                .filter { KaoPanHelper.isSupplyBoxAvailableForAutoSupplement(it) && !it.tasteCode.isNullOrBlank() }
                .groupBy { it.tasteCode.orEmpty() }
                .mapValues { (_, boxes) -> boxes.sumOf { it.num } }
            val targets = plannedTargets.map { target ->
                KaoChangOperate.SupplementTarget(
                    target.kaoPan,
                    target.tasteCode,
                    returnPositionSn = target.returnPositionSn
                )
            }
            val validInitialPlan = HighHeatSupplementResumePlanner.isValidInitialPlan(plannedTargets)
            if (!validInitialPlan) {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：25~33 未形成安全有效目标，跳过新批次，" +
                        "plannedSources=${plannedTargets.map { it.positionSn }}，" +
                        "plannedReturns=${plannedTargets.map { it.returnPositionSn }}，" +
                        "frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste"
                )
            }

            val movedCount = if (validInitialPlan) {
                reportPlannedPanState(
                    pans = list1_21 + list25_33,
                    reason = "低转高25~33实际数量补肠规划已确定",
                    source = "low_to_high_high_heat_planned"
                )
                KaoChangOperate.heating(3)
                KaoChangOperate.moveSausagesToKaoPansByTaste(
                    targets,
                    batchTypeOverride = SupplementBatchType.HIGH_HEAT_25_33
                )
            } else {
                0
            }
            if (movedCount > 0) {
                val partialBatch = movedCount < targets.size
                val batchResultText = if (partialBatch) {
                    "25~33 本次补肠被订单、供给降级或下平台隔离中断，保留未完成目标等待恢复续补"
                } else {
                    "已按当前库存启动高并发补热批次，目标数=${targets.size}"
                }
                recordSupplementLaunchOutcome(
                    requestedTargetCount = targets.size,
                    movedCount = movedCount,
                    now = System.currentTimeMillis(),
                    reason = "低转高过渡启动25~33高并发补肠"
                )
                LogUtils.i(
                    TAG,
                    "【并发切换】低转高过渡：已向 25~33 启动高并发补肠，batchResult=$batchResultText，" +
                        "frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste，" +
                        "protectedOldLowHeatReturnPositions=$protectedOldLowHeatReturnPositions，" +
                        "committedOldLowHeatSourcePositions=$committedOldLowHeatSourcePositions，" +
                        "requestedTargetCount=${targets.size}，actualMovedCount=$movedCount，" +
                        "partialBatch=$partialBatch，targetPositions=${targets.map { it.kaoPan.positionSn }}，" +
                        "returnPositions=${targets.map { it.returnPositionSn }}，" +
                        "lastFillPitTime=$lastFillPitTime"
                )
            } else {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：尝试向 25~33 启动高并发补肠，但本次未成功移动烤肠，" +
                        "frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste，" +
                        "protectedOldLowHeatReturnPositions=$protectedOldLowHeatReturnPositions，" +
                        "committedOldLowHeatSourcePositions=$committedOldLowHeatSourcePositions，" +
                        "requestedTargetCount=${targets.size}，targetPositions=${targets.map { it.kaoPan.positionSn }}"
                )
            }
        }

        val highHeatReturnStartedAndUncleared = hasHighHeatReturnStartedAndUncleared(
            recordedBatchSize = pitCount,
            highHeatPans = list25_33
        )
        if (schedulingEnabled && !highHeatReturnStartedAndUncleared) {
            planOldLowHeatReturnGroups(
                oldLowHeatPans = list13_18,
                nowMs = System.currentTimeMillis()
            ).forEach { group ->
                moveFinishedOldLowHeatGroupToFront(
                    list = list,
                    activeIntent = activeIntent,
                    config = config,
                    start = group.start,
                    end = group.end,
                    groupName = group.groupName
                )
            }
        } else if (schedulingEnabled && highHeatReturnStartedAndUncleared) {
            LogUtils.i(
                TAG,
                "【并发切换】低转高过渡：25~33 已开始回搬但未收口，跳过 13~18 旧低并发补热区搬回判断，" +
                    "pitCount=$pitCount，25~33剩余=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}，" +
                    "13~18当前有肠=${list13_18.filter { it.isHasSausage }.map { it.positionSn }}；" +
                    "13~18 若由 25~33 回搬填入，按高并发前区 1~21 可售肠处理"
            )
        }

        val hasOldLowHeatAreaSausage = hasOldLowHeatAreaSausageForLowToHighTransition(
            oldLowHeatPans = list13_18,
            highHeatPans = list25_33,
            recordedHighHeatBatchSize = pitCount
        )
        if (!hasOldLowHeatAreaSausage) {
            KaoChangOperate.stopHeating(2)
            if (schedulingEnabled) {
                checkHighHeatEnd(list25_33, 3, requireCompleteZone3Batch = false)
                checkHighHeatSausage(list)
            }
        }
        val hasStartedHighHeatSupplyNow = HighHeatSupplementResumePlanner.hasBatchStarted(list25_33)
        val hasFullHighHeatSupplyBatchNow = HighHeatSupplementResumePlanner.hasFullBatchStarted(list25_33)
        val hasReturnedHighHeatSupplyBatch =
            hadStartedHighHeatSupplyBefore && HighHeatSupplementResumePlanner.isReturnCleared(list25_33)
        val idleResolvedState = resolveLowToHighTransitionIdleState(
            currentModeType = config.modeType,
            schedulingEnabled = schedulingEnabled,
            boxSupplyAvailable = boxSupplyAvailable,
            hasActiveHeatingIn13_18 = hasOldLowHeatAreaSausage,
            hasStartedHighHeatSupplyNow = hasStartedHighHeatSupplyNow
        )
        if (idleResolvedState != null) {
            config.transitionMode = idleResolvedState.transitionMode
            config.modeType = idleResolvedState.modeType
            AppConfig.saveAppConfig(config)
            SendServerHelper.publishServiceUpdateStatus()
            resetSupplementTracking("低转高过渡收口并保留高并发目标")
            LogUtils.i(
                TAG,
                "【并发切换】低转高过渡暂不继续补肠：boxSupplyAvailable=$boxSupplyAvailable，schedulingEnabled=$schedulingEnabled，保留高并发目标等待后续调度启用"
            )
            return
        }
        val completionState = resolveLowToHighTransitionCompletionState(
            currentModeType = config.modeType,
            hasOldLowHeatAreaSausage = hasOldLowHeatAreaSausage,
            hasFullHighHeatSupplyBatchNow = hasFullHighHeatSupplyBatchNow,
            hasStartedHighHeatSupplyBatchNow = hasStartedHighHeatSupplyNow,
            hasReturnedHighHeatSupplyBatch = hasReturnedHighHeatSupplyBatch
        )
        if (completionState == null) {
            if (hasOldLowHeatAreaSausage && hasStartedHighHeatSupplyNow && !hasFullHighHeatSupplyBatchNow) {
                logLowToHighPartialWaitIfChanged(
                    list13_18 = list13_18,
                    list25_33 = list25_33,
                    boxSupplyAvailable = boxSupplyAvailable,
                    schedulingEnabled = schedulingEnabled,
                    errorStatus = config.errorStatus
                )
            }
            return
        }

        // 进入真正高并发正常态前，记录一次完成条件快照，便于排查“为什么此刻完成切换”。
        val frontOccupiedCount = list1_21.count { it.isHasSausage }
        val highSupplyOccupiedCount = list25_33.count { it.isHasSausage }
        LogUtils.i(
            TAG,
            "【并发切换】低转高完成条件满足：13~18 旧低并发补热区仍有烤肠=$hasOldLowHeatAreaSausage，" +
                "25~33 补热批次已启动=$hasStartedHighHeatSupplyNow，25~33 完整补热批次已启动=$hasFullHighHeatSupplyBatchNow，" +
                "25~33 第一批已全部搬回1~21=$hasReturnedHighHeatSupplyBatch，" +
                "25~33 残缺批次=${hasStartedHighHeatSupplyNow && !hasFullHighHeatSupplyBatchNow}，" +
                "1~21 当前有肠数=$frontOccupiedCount，25~33 当前有肠数=$highSupplyOccupiedCount"
        )

        config.modeType = completionState.modeType
        config.transitionMode = completionState.transitionMode
        clearLowToHighPartialWaitSnapshot()
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus()
        LogUtils.i(
            TAG,
            "【并发切换】低转高完成 -> 高并发稳态：modeType=${modeText(config.modeType)}，" +
                "transitionMode=${transitionText(config.transitionMode)}，targetMode（并发切换目标）=无切换"
        )
    }

    /**
     * 低转高期间，13~18 仍按低并发旧补热区处理。
     * 烤熟后必须搬回 1~9 的同口味预留空位，不能在 2 区原地进入保温可售。
     */
    private suspend fun moveFinishedOldLowHeatGroupToFront(
        list: List<KaoPan>,
        activeIntent: SupplementBatchIntent?,
        config: AppConfigBean,
        start: Int,
        end: Int,
        groupName: String
    ) {
        val group = TrafficModeHelper.getTrafficModePositions(start, end, list)
        val sourcePans = group.filter { it.isHasSausage }
        if (sourcePans.isEmpty()) {
            return
        }
        if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
            return
        }

        val now = System.currentTimeMillis()
        val hasUnfinished = sourcePans.any { kaoPan ->
            kaoPan.startTime > 0 &&
                FaultHeatPauseManager.effectiveBakingElapsedMs(kaoPan, nowMs = now) < kaoPan.bakingTime
        }
        if (hasUnfinished) {
            return
        }

        var beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val normalizedLegacySellableSources = normalizeOldLowHeatWaitingSourcesForReturn(sourcePans)
        if (normalizedLegacySellableSources.isNotEmpty()) {
            LogUtils.w(
                TAG,
                "【并发切换】低转高过渡：发现 $groupName 旧补热区历史原地保温可售状态，已恢复为等待回搬不可售，" +
                    "positions=$normalizedLegacySellableSources"
            )
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "低转高过渡${groupName}旧补热区历史原地可售状态纠偏为等待回搬"
            )
            beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        }
        sourcePans.forEach { sourcePan ->
            if (sourcePan.status != KaoPan.STATUS_WAITING_RETURN_KEEP_WARM || sourcePan.startTime > 0L) {
                sourcePan.startTime = 0L
                if (sourcePan.holdingTime <= 0L) {
                    sourcePan.holdingTime = now
                }
                sourcePan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            }
        }
        if (!KaoChangOperate.applyConfiguredTemperatureByPanState("低转高过渡${groupName}旧补热区回搬前温控重算")) {
            LogUtils.w(TAG, "【加热控制】低转高过渡 $groupName 旧补热区回搬前温控重算失败，继续尝试回搬成熟烤肠")
        }
        val frontPans = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        var movedAny = false
        sourcePans.forEach { kaoPan ->
            val tasteCode = kaoPan.taste?.tasteCode
            val targetPan = selectOldLowHeatReturnTarget(
                sourcePan = kaoPan,
                frontPans = frontPans,
                activeIntent = activeIntent
            )
            if (targetPan == null) {
                if (shouldDiscardWaitingOldLowHeatSource(kaoPan, config, now)) {
                    LogUtils.w(
                        TAG,
                        "【并发切换】低转高过渡：$groupName 旧补热区等待回搬已超过丢弃窗口，执行丢弃，" +
                            "sourcePan=${kaoPan.positionSn}，tasteCode=$tasteCode，sourceStartTime=${kaoPan.startTime}，" +
                            "bakingTime=${kaoPan.bakingTime}，closeTime=${kaoPan.closeTime}"
                    )
                    KaoChangOperate.discardSausage(
                        kaoPan,
                        source = "低转高旧补热区等待回搬超时",
                        detail = "group=$groupName，tasteCode=$tasteCode，bakingElapsed=${FaultHeatPauseManager.effectiveBakingElapsedMs(kaoPan, config, now)}ms，" +
                            "bakingTime=${kaoPan.bakingTime}ms，closeTime=${kaoPan.closeTime}ms"
                    )
                } else {
                    LogUtils.w(
                        TAG,
                        "【并发切换】低转高过渡：$groupName 旧补热区已熟但暂未找到 1~9 既定回补空位，保持等待回搬，二区按13~21整体盘面温控，" +
                            "sourcePan=${kaoPan.positionSn}，tasteCode=$tasteCode，sourceStartTime=${kaoPan.startTime}"
                    )
                }
                return@forEach
            }
            val moved = KaoChangOperate.moveKaoPanToKaoPan(
                kaoPan,
                targetPan,
                targetHoldingTime = kaoPan.holdingTime
            )
            if (!moved) {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：$groupName 旧补热区搬回失败，" +
                        "sourcePan=${kaoPan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=$tasteCode"
                )
            } else {
                clearReturnedOldLowHeatSourceMetadata(kaoPan)
                movedAny = true
            }
        }
        if (!movedAny) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "低转高过渡${groupName}旧补热区烤熟搬回1~9兜底同步"
            )
        }
    }

    /**
     * 高并发 -> 低并发过渡。
     * 不再继续执行 p>=9 的新补肠，只消化现有库存；
     * 当 1~21 只剩 9 根且 25~33 已清空时，切回低并发。
     */
    private suspend fun highTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean
    ) {
        val pendingHighHeatManualConfirmation =
            KaoChangOperate.pendingSupplementBatchRequiresManualConfirm()
                ?.type == SupplementBatchType.HIGH_HEAT_25_33
        if (pendingHighHeatManualConfirmation) {
            LogUtils.i(
                TAG,
                "【并发切换】高转低过渡保留25~33补热故障批次意图：等待人工确认后按10分钟窗口续补或取消未完成目标"
            )
        } else {
            KaoChangOperate.clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation("高转低过渡不再启动新的25~33补肠")
        }

        if (AppConfig.getAppConfig().onlineStatus == 4 || AppConfig.getAppConfig().moveStatus != 0) {
            LogUtils.i(
                TAG,
                "【并发切换】高转低过渡等待设备空闲：onlineStatus=${AppConfig.getAppConfig().onlineStatus}，moveStatus=${AppConfig.getAppConfig().moveStatus}"
            )
            return
        }

        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        checkHighHeatEnd(list25_33, 3)
        if (!schedulingEnabled) {
            LogUtils.i(TAG, "【并发切换】高转低过渡暂停自动搬盘：schedulingEnabled=false")
            return
        }
        val pendingHighHeatIntentBeforeReconcile = KaoChangOperate.activeSupplementBatchIntentForUi()
        val persistedBatchState = classifyPersistedHighHeatBatch(
            activeIntent = pendingHighHeatIntentBeforeReconcile,
            highHeatPans = list25_33
        )
        val blockUntrustedHighHeatRecovery = shouldBlockPendingHighHeatRecovery(
            activeIntent = pendingHighHeatIntentBeforeReconcile,
            persistedBatchState = persistedBatchState
        )
        if (
            pendingHighHeatIntentBeforeReconcile?.type == SupplementBatchType.HIGH_HEAT_25_33 &&
            !blockUntrustedHighHeatRecovery
        ) {
            KaoChangOperate.reconcilePendingSupplementTargetsAlreadyOnPans(
                allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                expectedBatchId = pendingHighHeatIntentBeforeReconcile.batchId,
                reason = "高转低过渡成熟检查前对账25~33已上盘目标",
                pans = list
            )
        }
        val pendingHighHeatIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        if (pendingHighHeatIntent?.type == SupplementBatchType.HIGH_HEAT_25_33 &&
            !pendingHighHeatIntent.requiresManualConfirm &&
            pendingHighHeatIntent.remainingTargets().isNotEmpty()
        ) {
            val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
            val isBeforeCloseWindow =
                KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)
            val pendingBatchHandling = if (blockUntrustedHighHeatRecovery) {
                LogUtils.w(
                    TAG,
                    "【并发切换】高转低已确认25~33批次证据不可信，跳过 pending 自动续补并继续成熟收口：" +
                        "persistedBatchState=$persistedBatchState，batchId=${pendingHighHeatIntent.batchId}"
                )
                KaoChangOperate.PendingSupplementBatchHandling.BLOCKED
            } else {
                KaoChangOperate.handlePendingSupplementBatchIfAny(
                    allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                    reason = "高转低过渡恢复25~33已确认未完成批次",
                    boxSupplyAvailable = boxSupplyAvailable,
                    schedulingEnabled = schedulingEnabled,
                    isBeforeCloseWindow = isBeforeCloseWindow
                )
            }
            if (pendingBatchHandling == KaoChangOperate.PendingSupplementBatchHandling.RESUMED) {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                return
            }
        }
        checkHighHeatSausage(list)

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val sausageCount = list1_21.count { it.isHasSausage }
        val sausageCount25_33 = list25_33.count { it.isHasSausage }
        val hasAnyUnreadySausage = hasUnreadySausage(list)

        if (canFinishHighToLowDrain(sausageCount, sausageCount25_33, hasUnreadySausage = hasAnyUnreadySausage)) {
            finishHighToLowCloseout(
                config = config,
                list = list,
                listBox = listBox,
                reason = "高转低过渡完成并回落低并发"
            )
        }

        if (schedulingEnabled) {
            checkHighHeatDiscard()
        }
    }

    private suspend fun finishHighToLowCloseout(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        reason: String
    ): Boolean {
        val initialList1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val initialList1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val sausageCount = initialList1_21.count { it.isHasSausage }
        val alreadySausages1_9 = initialList1_9.filter { it.isHasSausage }
        if (alreadySausages1_9.size < sausageCount) {
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            var movedAny = false
            while (true) {
                if (SupplementPauseController.shouldPauseForOrder()) {
                    LogUtils.i(TAG, "【并发切换】订单已进入等待，高转低收拢在当前单盘收口后让出")
                    break
                }
                val latestPans = KaoPanHelper.getKaoPanList()
                val kaoPan = TrafficModeHelper.sortByHoldingAgeThenPosition(
                    TrafficModeHelper.getTrafficModePositions(10, 21, latestPans)
                        .filter { it.isHasSausage }
                ).firstOrNull() ?: break
                val targetPan = TrafficModeHelper.getTrafficModePositions(1, 9, latestPans)
                    .firstOrNull { !it.isHasSausage } ?: break
                LogUtils.i(
                    TAG,
                    "【并发切换】高转低/残留收拢：烤盘${kaoPan.positionSn} -> 烤盘${targetPan.positionSn}，holdingTime=${kaoPan.holdingTime}"
                )
                val moved = KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                if (!moved) break
                movedAny = true
            }
            if (!movedAny) {
                KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                    beforeSellable,
                    KaoPanHelper.getKaoPanList(),
                    "高转低/残留收拢10~21剩余可售肠回1~9兜底同步"
                )
            }
        }

        val latestPans = KaoPanHelper.getKaoPanList()
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, latestPans)
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, latestPans)
        val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, latestPans)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, latestPans)

        val remaining10_21 = list10_21.filter { it.isHasSausage }
        if (remaining10_21.isNotEmpty()) {
            LogUtils.i(
                TAG,
                "【并发切换】高转低/残留收拢未完成，等待下轮继续搬移：remaining10_21=${remaining10_21.map { it.positionSn }}"
            )
            return false
        }

        val clearedResidualPositions = clearEmptyHighConcurrencyResidualMetadata(list1_21 + list25_33)
        if (clearedResidualPositions.isNotEmpty()) {
            LogUtils.i(
                TAG,
                "【并发切换】高转低/残留收拢清理空盘残留元数据：positions=$clearedResidualPositions，" +
                    "fields=isHasGrilling/startTime/holdingTime/status/temperature"
            )
        }

        val existingCountMap = list1_9
            .filter { it.isHasSausage && it.taste?.tasteCode != null }
            .groupBy { it.taste!!.tasteCode }
            .mapValues { (_, pans) -> pans.size }
        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_9.size)
        val needAllocation = mutableMapOf<String, Int>()
        flavorAllocation.forEach { (flavor, targetCount) ->
            val existing = existingCountMap.getOrDefault(flavor, 0)
            needAllocation[flavor] = (targetCount - existing).coerceAtLeast(0)
        }

        val emptyPans = list1_9.filter { !it.isHasSausage }
        var emptyIndex = 0
        needAllocation.forEach { (flavor, needCount) ->
            var allocated = 0
            while (allocated < needCount && emptyIndex < emptyPans.size) {
                emptyPans[emptyIndex].taste = Taste().apply { tasteCode = flavor }
                allocated++
                emptyIndex++
            }
        }

        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val oldResidualDrain = config.highConcurrencyResidualDrain
        KaoChangOperate.applyConfiguredTemperatureByPanState("高转低/残留收拢完成后刷新温控")
        config.transitionMode = 0
        config.modeType = 1
        config.highConcurrencyResidualDrain = 0
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus()
        resetSupplementTracking(reason)
        LogUtils.i(
            TAG,
            "【并发切换】${highToLowCloseoutCompleteText(reason)}：reason=$reason，" +
                "modeType=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "targetMode（并发切换目标）=无切换，" +
                "highConcurrencyResidualDrain=$oldResidualDrain->${config.highConcurrencyResidualDrain}"
        )
        return true
    }

    private fun highToLowCloseoutCompleteText(reason: String): String {
        return if (reason.contains("高并发残留消化")) {
            "高并发残留消化完成 -> 低并发稳态"
        } else {
            "高转低完成 -> 低并发稳态"
        }
    }

    /**
     * 初始化高并发模式。
     * 1 区、2 区同时加热，按比例向 1~21 工位上肠。
     */
    private suspend fun initHighTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.i(TAG, "【高并发初始化】开始初始化高并发：目标工位=1~21")
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w(TAG, "【高并发初始化】所有烤肠箱库存为空，停止 1 区和 2 区加热")
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.stopHeating(2)
            return
        }

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_21.size)
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_21.size) {
                    list1_21[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }

        reportPlannedPanState(
            pans = list1_21,
            reason = "高并发1~21初始化规划已确定",
            source = "high_initialization_planned"
        )

        KaoChangOperate.moveSausagesToKaoPansByTaste(
            list1_21.map { kaoPan ->
                KaoChangOperate.SupplementTarget(kaoPan, kaoPan.taste?.tasteCode)
            },
            batchTypeOverride = SupplementBatchType.HIGH_INITIALIZATION_1_21
        )
    }

    /**
     * 向 25~33 补热区批量补肠。
     * 只有当前区 1~21 形成 9 个坑位时才触发。
     */
    private suspend fun addHighHeatSausage(list: List<KaoPan>, listBox: List<KaoPanBox>): Int {
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        val hasSausageIn25_33 = list25_33.any { it.isHasSausage }
        if (hasSausageIn25_33) {
            return 0
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
        val emptyPositions = list1_21.filter { !it.isHasSausage }
        val emptyPositions25_33 = list25_33.filter { !it.isHasSausage }
        if (emptyPositions.size >= 9 && emptyPositions25_33.size >= 9) {
            val committedReturnTasteByPosition = SupplementTasteMixPlanner.committedReturnTasteByPosition(
                KaoChangOperate.completedHeatReturnIntentForUi(),
                1..21
            )
            val plannedTargets = HighHeatSupplementResumePlanner.planInitialTargets(
                frontPans = list1_21,
                supplementGroup = list25_33,
                listBox = listBox,
                committedReturnTasteByPosition = committedReturnTasteByPosition
            )
            if (!HighHeatSupplementResumePlanner.isValidInitialPlan(plannedTargets)) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】跳过 25~33 补热区补肠：未形成安全有效目标，" +
                        "plannedSources=${plannedTargets.map { it.positionSn }}，" +
                        "plannedReturns=${plannedTargets.map { it.returnPositionSn }}，" +
                        "frontEmpty=${emptyPositions.size}，zone3Empty=${emptyPositions25_33.size}"
                )
                return 0
            }
            reportPlannedPanState(
                pans = list1_21 + list25_33,
                reason = "高并发25~33实际数量补肠规划已确定",
                source = "high_heat_25_33_planned"
            )
            KaoChangOperate.heating(3)
            val targets = plannedTargets.map { target ->
                KaoChangOperate.SupplementTarget(
                    target.kaoPan,
                    target.tasteCode,
                    returnPositionSn = target.returnPositionSn
                )
            }
            val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
                targets,
                batchTypeOverride = SupplementBatchType.HIGH_HEAT_25_33
            )
            if (movedCount > 0) {
                recordSupplementLaunchOutcome(
                    requestedTargetCount = targets.size,
                    movedCount = movedCount,
                    now = System.currentTimeMillis(),
                    reason = "高并发正常态向25~33批量补肠"
                )
                if (movedCount < targets.size) {
                    LogUtils.w(
                        TAG,
                        "【高并发补肠】25~33 补热区批量补肠被中断，保留未完成目标等待恢复续补：" +
                            "requestedTargetCount=${targets.size}，movedCount=$movedCount，targetPositions=${targets.map { it.kaoPan.positionSn }}"
                    )
                }
            }
            return movedCount
        }
        return 0
    }

    private suspend fun resumeIncompleteHighHeatSupplement(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        reason: String
    ): Int {
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        val resumeTargets = HighHeatSupplementResumePlanner.planResumeTargets(list1_21, list25_33, activeIntent)
        if (resumeTargets.isEmpty()) {
            LogUtils.w(
                TAG,
                "【高并发补肠】检测到 25~33 未完成批次但无法续补：reason=$reason，" +
                    "occupied=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}，" +
                    "empty=${list25_33.filter { !it.isHasSausage }.map { it.positionSn }}"
            )
            return 0
        }

        reportPlannedPanState(
            pans = list1_21 + list25_33,
            reason = "高并发25~33未完成批次续补规划已确定",
            source = "high_heat_25_33_resume_planned"
        )

        KaoChangOperate.heating(3)
        LogUtils.i(
            TAG,
            "【高并发补肠】续补 25~33 未完成批次：reason=$reason，目标烤盘=${resumeTargets.map { it.positionSn }}，" +
                "回搬目标=${resumeTargets.map { "${it.positionSn}->${it.returnPositionSn ?: "未知"}" }}"
        )
        val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
            resumeTargets.map { target ->
                KaoChangOperate.SupplementTarget(
                    target.kaoPan,
                    target.tasteCode,
                    returnPositionSn = target.returnPositionSn
                )
            },
            batchTypeOverride = SupplementBatchType.HIGH_HEAT_25_33
        )
        if (movedCount > 0) {
            recordSupplementLaunch(
                filledCount = movedCount,
                now = System.currentTimeMillis(),
                reason = reason
            )
            LogUtils.i(
                TAG,
                "【高并发补肠】25~33 未完成批次续补完成：movedCount=$movedCount，lastFillPitTime=$lastFillPitTime"
            )
        } else {
            LogUtils.w(TAG, "【高并发补肠】25~33 未完成批次本轮续补未移动烤肠：reason=$reason")
        }
        return movedCount
    }

    /**
     * 检查 25~33 补热区是否已经烤熟。
     * 烤熟后搬回 1~21 同口味空位。
     */
    private suspend fun checkHighHeatSausage(list: List<KaoPan>): Boolean {
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        var beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val normalizedLegacySellableSources =
            HighHeatSupplementResumePlanner.normalizeLegacySellableKeepWarmSources(list25_33)
        if (normalizedLegacySellableSources.isNotEmpty()) {
            LogUtils.w(
                TAG,
                "【高并发补肠】25~33 补热区发现历史原地保温可售状态，已恢复为等待回搬不可售：positions=$normalizedLegacySellableSources"
            )
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高并发25~33补热区历史原地可售状态纠偏为等待回搬"
            )
            beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        }
        if (HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)) {
            val movedPartialBatch = moveFinishedPartialHighHeatBatchToFront(list, list25_33)
            if (!movedPartialBatch) {
                logUnfinishedPartialHighHeatBatch(list25_33)
            }
            return movedPartialBatch
        }
        if (HighHeatSupplementResumePlanner.isCompleteFinishedBatch(list25_33)) {
            val sourcePans = list25_33.filter { it.isHasSausage }
            val frontEmptyCount = TrafficModeHelper.getTrafficModePositions(1, 21, list).count { !it.isHasSausage }
            if (!canReturnHighHeatBatchToFront(frontEmptyCount = frontEmptyCount, batchSize = sourcePans.size)) {
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 补热区已烤熟但暂不回搬：1~21空位=$frontEmptyCount，本批实际数量=${sourcePans.size}，等待空位足够后整批回搬"
                )
                return true
            }
            LogUtils.i(TAG, "【高并发补肠】25~33 补热区已烤熟，开始回补 1~21")
            KaoChangOperate.keepWarm(3)
            KaoChangOperate.keepWarm(2)
            KaoChangOperate.keepWarm(1)

            val movedTargetPositions = mutableListOf<Int>()
            var pausedReason: String? = null
            val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
            for (kaoPan in sourcePans) {
                if (kaoPan.isHasSausage && kaoPan.taste != null) {
                    val pauseReason = highHeatReturnPauseReason()
                    if (pauseReason != null) {
                        pausedReason = pauseReason
                        LogUtils.i(
                            TAG,
                            "【高并发补肠】25~33 补热区回补被订单或机械状态暂停：$pauseReason，" +
                                "remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}，" +
                                "movedTargetPositions=$movedTargetPositions"
                        )
                        break
                    }
                    val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                    val bindingResult = SupplementReturnBindingResolver.resolveReturnTarget(
                        sourcePan = kaoPan,
                        frontPans = list1_21,
                        activeIntent = activeIntent,
                        allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                        returnPositionRange = 1..21
                    )
                    val targetPan = bindingResult.targetPan
                    if (targetPan == null) {
                        LogUtils.w(
                            TAG,
                            "【高并发补肠】25~33 补热区回补失败，未找到安全的绑定回搬目标：sourcePan=${kaoPan.positionSn}，" +
                                "returnPositionSn=${bindingResult.returnPositionSn}，tasteCode=${bindingResult.tasteCode}，" +
                                "reason=${bindingResult.failureReason}，保留源盘等待下轮"
                                + highHeatReturnTargetDiagnostic(list)
                        )
                        continue
                    }
                    val moved = KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    if (!moved) {
                        LogUtils.w(
                            TAG,
                            "【高并发补肠】25~33 补热区回补失败，机械调度未启动或搬移失败：sourcePan=${kaoPan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=${bindingResult.tasteCode}，保留源盘等待下轮"
                        )
                    } else {
                        movedTargetPositions += targetPan.positionSn
                    }
                }
            }
            val returnCleared = HighHeatSupplementResumePlanner.isReturnCleared(list25_33)
            if (returnCleared) {
                KaoChangOperate.stopHeating(3)
                clearPitCountAfterBatch("25~33 补热区回补完成")
                KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
                    types = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                    sourcePositionsWithSausage = list25_33.filter { it.isHasSausage }.map { it.positionSn }.toSet(),
                    reason = "高并发25~33补热区回搬完成"
                )
            } else if (pausedReason != null) {
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 补热区回补暂停，保留源盘等待订单结束后续搬：reason=$pausedReason，" +
                        "remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}"
                )
            } else {
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 补热区回补未完成，保留源盘等待下轮：remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}"
                )
            }
            if (movedTargetPositions.isEmpty()) {
                KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                    beforeSellable,
                    KaoPanHelper.getKaoPanList(),
                    "高并发25~33补热区烤熟搬回1~21兜底同步"
                )
            }
            return returnCleared
        }
        return false
    }

    private fun highHeatReturnPauseReason(): String? {
        val config = AppConfig.getAppConfig()
        val pendingOrderNo = SupplementPauseController.currentOrderNo()
        if (!pendingOrderNo.isNullOrBlank()) {
            return "顾客订单已进入等待队列，当前单次搬盘完成后暂停剩余回搬：orderNo=$pendingOrderNo，" +
                "onlineStatus（设备营业状态）=${config.onlineStatus}，moveStatus（机械动作状态）=${config.moveStatus}，" +
                "errorStatus（设备故障状态）=${config.errorStatus}"
        }
        val dispatchDecision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            moveStatus = config.moveStatus,
            lowerPlatformStatus = config.lowerPlatformStatus,
            lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName,
            orderPending = SupplementPauseController.shouldPauseForOrder()
        )
        if (dispatchDecision.canStart) {
            return null
        }
        return "${dispatchDecision.reason}，onlineStatus（设备营业状态）=${config.onlineStatus}，" +
            "moveStatus（机械动作状态）=${config.moveStatus}，errorStatus（设备故障状态）=${config.errorStatus}"
    }

    private suspend fun moveFinishedPartialHighHeatBatchToFront(
        list: List<KaoPan>,
        list25_33: List<KaoPan>
    ): Boolean {
        if (KaoChangOperate.shouldDelayHeatReturnForBatchCompleteness(setOf(SupplementBatchType.HIGH_HEAT_25_33))) {
            LogUtils.i(
                TAG,
                "【高并发补肠】25~33 残缺补热批次已熟但暂不拆批回搬：下平台故障仍在10分钟续补窗口内，等待人工确认后优先补齐整批"
            )
            return true
        }
        if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        val keepWarmPans = HighHeatSupplementResumePlanner.findKeepWarmPartialBatchReadyToMove(list25_33)
        val finishedPans = if (keepWarmPans.isEmpty()) {
            HighHeatSupplementResumePlanner.findFinishedPartialBatch(list25_33, nowMs)
        } else {
            emptyList()
        }
        if (keepWarmPans.isEmpty() && finishedPans.isEmpty()) {
            return false
        }

        val dispatchDecision = AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = AppConfig.getAppConfig().onlineStatus,
            errorStatus = AppConfig.getAppConfig().errorStatus,
            inspectionMode = AppConfig.getAppConfig().inspectionMode,
            moveStatus = AppConfig.getAppConfig().moveStatus,
            lowerPlatformStatus = AppConfig.getAppConfig().lowerPlatformStatus,
            lowerPlatformIsolationActionName = AppConfig.getAppConfig().lowerPlatformIsolationActionName,
            orderPending = SupplementPauseController.shouldPauseForOrder()
        )
        if (!dispatchDecision.canStart) {
            LogUtils.i(
                TAG,
                "【高并发补肠】25~33 残缺补热批次暂不回搬：${dispatchDecision.reason}"
            )
            return true
        }

        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val readyPans = keepWarmPans.ifEmpty {
            HighHeatSupplementResumePlanner.markFinishedPartialBatchKeepWarm(list25_33, nowMs)
        }
        if (readyPans.isEmpty()) {
            return false
        }
        if (finishedPans.isNotEmpty()) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            runCatching {
                PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                    changedPanPositions = readyPans.map { it.positionSn },
                    reason = "高并发25~33残缺补热批次切换等待回搬保温",
                    source = "high_partial_heat_matured_keep_warm"
                )
            }.onFailure { error ->
                LogUtils.w(TAG, "【云端同步】高并发残缺补热批次成熟物理增量上报异常，等待快照补偿：${error.message}")
            }
        }
        val frontEmptyCount = TrafficModeHelper.getTrafficModePositions(1, 21, list).count { !it.isHasSausage }
        if (!canReturnHighHeatBatchToFront(frontEmptyCount = frontEmptyCount, batchSize = readyPans.size)) {
            LogUtils.i(
                TAG,
                "【高并发补肠】25~33 残缺补热批次已烤熟但暂不回搬：1~21空位=$frontEmptyCount，本批实际数量=${readyPans.size}，等待空位足够后整批回搬"
            )
            return true
        }

        LogUtils.i(
            TAG,
            "【高并发补肠】25~33 残缺补热批次已烤熟，开始回补 1~21：sourcePositions=${readyPans.map { it.positionSn }}，targetRange=1~21"
        )
        KaoChangOperate.keepWarm(3)
        KaoChangOperate.keepWarm(2)
        KaoChangOperate.keepWarm(1)

        val movedTargetPositions = mutableListOf<Int>()
        var pausedReason: String? = null
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        for (sourcePan in readyPans) {
            val pauseReason = highHeatReturnPauseReason()
            if (pauseReason != null) {
                pausedReason = pauseReason
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 残缺补热批次回补被订单或机械状态暂停：$pauseReason，" +
                        "remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}，" +
                        "movedTargetPositions=$movedTargetPositions"
                )
                break
            }
            val bindingResult = SupplementReturnBindingResolver.resolveReturnTarget(
                sourcePan = sourcePan,
                frontPans = TrafficModeHelper.getTrafficModePositions(1, 21, list),
                activeIntent = activeIntent,
                allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                returnPositionRange = 1..21
            )
            val targetPan = bindingResult.targetPan
            if (targetPan == null) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】25~33 残缺补热批次搬回失败，未找到安全的绑定回搬目标：sourcePan=${sourcePan.positionSn}，" +
                        "returnPositionSn=${bindingResult.returnPositionSn}，tasteCode=${bindingResult.tasteCode}，" +
                        "reason=${bindingResult.failureReason}，保留源盘等待下轮"
                        + highHeatReturnTargetDiagnostic(list)
                )
                continue
            }
            val moved = KaoChangOperate.moveKaoPanToKaoPan(
                sourcePan,
                targetPan,
                targetHoldingTime = sourcePan.holdingTime
            )
            if (!moved) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】25~33 残缺补热批次搬回失败，机械调度未启动或搬移失败：sourcePan=${sourcePan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=${bindingResult.tasteCode}，保留源盘等待下轮"
                )
            } else {
                movedTargetPositions += targetPan.positionSn
            }
        }

        if (movedTargetPositions.isEmpty()) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高并发25~33残缺批次烤熟搬回1~21兜底同步"
            )
        }
        if (HighHeatSupplementResumePlanner.isReturnCleared(list25_33)) {
            KaoChangOperate.stopHeating(3)
            clearPitCountAfterBatch("25~33 残缺补热批次回补完成")
            KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
                types = setOf(SupplementBatchType.HIGH_HEAT_25_33),
                sourcePositionsWithSausage = list25_33.filter { it.isHasSausage }.map { it.positionSn }.toSet(),
                reason = "高并发25~33残缺补热批次回搬完成"
            )
        } else if (pausedReason != null) {
            LogUtils.i(
                TAG,
                "【高并发补肠】25~33 残缺补热批次回补暂停，保留源盘等待订单结束后续搬：reason=$pausedReason，" +
                    "remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}"
            )
        }
        return true
    }

    private fun highHeatReturnTargetDiagnostic(list: List<KaoPan>): String {
        val frontEmptyPans = TrafficModeHelper.getTrafficModePositions(1, 21, list)
            .filter { !it.isHasSausage }
        if (frontEmptyPans.isEmpty()) {
            return "，frontEmptyPans=空"
        }
        val detail = frontEmptyPans.joinToString(";") { pan ->
            val tasteCode = pan.taste?.tasteCode ?: "null"
            val uiTasteCode = pan.uiReservationTaste?.tasteCode ?: "null"
            "${pan.positionSn}(status=${pan.status},taste=$tasteCode,isHasGrilling=${pan.isHasGrilling},uiTaste=$uiTasteCode)"
        }
        return "，frontEmptyPans=$detail"
    }

    private fun logUnfinishedPartialHighHeatBatch(list25_33: List<KaoPan>) {
        val nowMs = System.currentTimeMillis()
        val occupiedPans = list25_33.filter { it.isHasSausage }.sortedBy { it.positionSn }
        val longestRemainingMs = occupiedPans.maxOfOrNull { pan ->
            when {
                pan.startTime <= 0L || pan.bakingTime <= 0L -> pan.bakingTime.coerceAtLeast(0L)
                else -> (pan.bakingTime - FaultHeatPauseManager.effectiveBakingElapsedMs(pan, nowMs = nowMs)).coerceAtLeast(0L)
            }
        } ?: 0L
        LogUtils.i(
            TAG,
            "【高并发补肠】25~33 残缺补热批次未烤熟，等待续补或下轮回搬：occupiedPositions=${occupiedPans.map { it.positionSn }}，longestRemainingMs=$longestRemainingMs"
        )
    }

    /**
     * 检查指定区域是否已经整体烤熟。
     * 烤熟后关闭对应加热区并进入保温状态。
     */
    private fun checkHighHeatEnd(
        list: List<KaoPan>,
        area: Int,
        requireCompleteZone3Batch: Boolean = true
    ) {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
            return
        }
        if (area == 3 && requireCompleteZone3Batch) {
            val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
            if (HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)) {
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 补热区未补满，不切换 3 区保温：当前有肠数=${list25_33.count { it.isHasSausage }}，目标=9"
                )
                return
            }
        }
        val nowMs = System.currentTimeMillis()
        if (shouldSwitchHighHeatAreaToKeepWarm(list, area, AppConfig.getAppConfig(), nowMs, requireCompleteZone3Batch)) {
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            val maturedPositions = list
                .filter { it.isHasSausage && it.startTime > 0L }
                .map { it.positionSn }
            KaoChangOperate.stopHeating(area)
            KaoChangOperate.keepWarm(area)
            list.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = nowMs
                    kaoPan.status = if (area == 3) {
                        KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
                    } else {
                        KaoPan.STATUS_SELLABLE_KEEP_WARM
                    }
                }
            }
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            runCatching {
                PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                    changedPanPositions = maturedPositions,
                    reason = "高并发${area}区切换保温",
                    source = "high_area_matured_keep_warm"
                )
            }.onFailure { error ->
                LogUtils.w(TAG, "【云端同步】高并发${area}区成熟物理增量上报异常，等待快照补偿：${error.message}")
            }
            val stateText = if (area == 3) "等待回搬保温不可售" else "保温可售"
            LogUtils.i(TAG, "【加热控制】${area} 区烤肠已全部烤熟，切换为$stateText")
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高并发${area}区切换$stateText"
            )
        }
    }

    /**
     * 检查高并发前区可售烤肠是否保温到期。
     */
    private suspend fun checkHighHeatDiscard() {
        OverKeepWarmDiscardScanner.scanAndDiscard(1..33, "高并发轮询")
    }

    /**
     * 判断是否需要触发高转低。
     * 触发条件：
     * 1. 接近营业结束；
     * 2. 距离上次高并发补肠已超过高转低间隔阈值。
     */
    private fun checkSwitchToLow(
        kaoPanList: List<KaoPan>,
        boxSupplyAvailable: Boolean
    ) {
        val businessTime = AppConfig.getAppConfig().businessTime
        if (businessTime != null) {
            val remainingTimeUntilClose = getRemainingTimeUntilClose(businessTime)
            val beforeCloseThreshold =
                (AppConfig.getAppConfig().timeBeforeClose.takeIf { it > 0 } ?: 30) * 60 * 1000L
            if (remainingTimeUntilClose > 0 && remainingTimeUntilClose < beforeCloseThreshold) {
                switchHighToLow("接近营业结束", kaoPanList)
                return
            }
        }

        val currentTime = System.currentTimeMillis()
        if (lastFillPitTime <= 0L) {
            applySupplementTrackingState(
                nextState = currentSupplementTrackingState().copy(lastFillPitTime = currentTime),
                reason = "高并发稳态建立高转低计时锚点，boxSupplyAvailable=$boxSupplyAvailable"
            )
            return
        }
        val timeSinceLastFill = currentTime - lastFillPitTime
        val timeThresholdLowMinutes = AppConfig.getAppConfig().timeThresholdLow
        if (shouldSwitchHighToLowByTimeout(lastFillPitTime, currentTime, timeThresholdLowMinutes)) {
            LogUtils.w(
                TAG,
                "【并发切换】高并发补肠间隔超时判定成立：lastFillPitTime=$lastFillPitTime，currentTime=$currentTime，" +
                    "elapsedMs=$timeSinceLastFill，timeThresholdLow=${timeThresholdLowMinutes}分钟，" +
                    "boxSupplyAvailable=$boxSupplyAvailable，高转低间隔不再依赖箱库存"
            )
            val reason = if (boxSupplyAvailable) {
                "高并发补肠间隔超时"
            } else {
                "高并发补肠间隔超时（箱内无可用库存）"
            }
            switchHighToLow(reason, kaoPanList)
        }
    }

    /**
     * 计算距离当日营业结束的剩余时间。
     * 返回值为毫秒；无法计算时返回 -1。
     */
    private fun getRemainingTimeUntilClose(businessTime: BusinessTime): Long {
        return BusinessTimeWindowHelper.remainingMillisUntilClose(
            BusinessTimeWindowHelper.currentTimeString(),
            businessTime
        )
    }

    /**
     * 高并发 -> 低并发。
     * 这里只写入过渡态，由高转低过渡处理器负责最终落回低并发。
     */
    private fun switchHighToLow(reason: String, kaoPanList: List<KaoPan> = KaoPanHelper.getKaoPanList()) {
        val config = AppConfig.getAppConfig()
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val resolvedTransition = resolveAutomaticTrafficTransition(
            currentModeType = oldModeType,
            currentTransitionMode = oldTransitionMode,
            requestedTransitionMode = 2
        )
        if (resolvedTransition == null) {
            LogUtils.w(
                TAG,
                "【并发切换诊断】拒绝自动写入 transitionMode=2（高转低过渡）：source=HighCurrentTrafficMode.switchHighToLow，reason=$reason，" +
                    "modeType（并发模式）=${modeText(oldModeType)}，transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}，" +
                    "原因=自动算法只允许高并发稳态进入高转低过渡态"
            )
            return
        }
        config.modeType = resolvedTransition.modeType
        config.transitionMode = resolvedTransition.transitionMode
        KaoChangOperate.clearPendingSupplementBatchIntentUnlessHighHeatManualConfirmation("写入高转低过渡态后停止25~33未完成补肠意图")
        LogUtils.w(
            TAG,
            "【并发切换诊断】写入 transitionMode=2（高转低过渡）：source=HighCurrentTrafficMode.switchHighToLow，reason=$reason，" +
                "modeType（并发模式）=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "targetMode（并发切换目标）=低并发，" +
                "1~21空位=${TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList).filter { !it.isHasSausage }.map { it.positionSn }}，" +
                "13~18状态=${panStatusSummary(TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList))}，" +
                "25~33状态=${panStatusSummary(TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList))}"
        )
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus()
        resetSupplementTracking("进入高转低过渡")
    }
}
