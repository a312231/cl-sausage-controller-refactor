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

                val pendingBatchHandling = KaoChangOperate.handlePendingSupplementBatchIfAny(
                    allowedTypes = setOf(
                        SupplementBatchType.HIGH_INITIALIZATION_1_21,
                        SupplementBatchType.HIGH_HEAT_25_33
                    ),
                    reason = "高并发正常态调度入口",
                    boxSupplyAvailable = boxSupplyAvailable,
                    schedulingEnabled = schedulingEnabled,
                    isBeforeCloseWindow = isBeforeCloseWindow
                )
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
                    val handledFinishedPartialBatch = if (schedulingEnabled) {
                        checkHighHeatSausage(list)
                    } else {
                        false
                    }
                    if (handledFinishedPartialBatch) {
                        // 已处理成熟残缺批次，本轮不再续补，避免新肠混入同一补热批次。
                    } else if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                        resumeIncompleteHighHeatSupplement(
                            list = list,
                            listBox = listBox,
                            reason = "高并发正常态续补25~33未完成批次"
                        )
                    } else {
                        LogUtils.i(
                            TAG,
                            "【高并发补肠】25~33 补热区未补满，等待续补，不执行回补：boxSupplyAvailable=$boxSupplyAvailable，schedulingEnabled=$schedulingEnabled，errorStatus=${config.errorStatus}，处于停业前收口窗口=$isBeforeCloseWindow"
                        )
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
                    if (p25_33 == 9) {
                        if (schedulingEnabled && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                            val movedCount = addHighHeatSausage(list, listBox)
                            if (movedCount > 0) {
                                recordSupplementLaunch(
                                    filledCount = movedCount,
                                    now = System.currentTimeMillis(),
                                    reason = "高并发正常态向25~33批量补肠"
                                )
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

                val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
                checkHighHeatEnd(list1_9, 1)
                checkHighHeatEnd(list10_21, 2)
                if (schedulingEnabled) {
                    checkHighHeatDiscard()
                }
                if (schedulingEnabled && boxSupplyAvailable) {
                    checkSwitchToLow()
                }
                if (schedulingEnabled) {
                    SelfCleanManager.maybeRunZone3SelfClean(list)
                }

                // 烤盘保存由 KaoPanHelper 内部后台作用域统一处理，这里直接提交最新状态。
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }

            1 -> {
                lowTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)
            }

            2 -> {
                highTrafficModeTransition(config, list, listBox, boxSupplyAvailable, schedulingEnabled)
            }
        }
    }

    /**
     * 低并发高并发残留消化态专用收尾。
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
        if (!schedulingEnabled) {
            LogUtils.i(TAG, "【并发残留】残留消化暂停自动搬盘：schedulingEnabled=false")
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return
        }

        checkHighHeatSausage(list)
        val frontSausageCount = TrafficModeHelper.getTrafficModePositions(1, 21, list).count { it.isHasSausage }
        val zone3SausageCount = list25_33.count { it.isHasSausage }
        if (canFinishHighToLowDrain(frontSausageCount, zone3SausageCount)) {
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

        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)
        LogUtils.i(
            TAG,
            "【高并发初始化】检测到 1~21 初始化未完成，续补剩余空位：目标烤盘=${resumeTargets.joinToString(",") { it.positionSn.toString() }}"
        )
        val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
            resumeTargets.map { KaoChangOperate.SupplementTarget(it.kaoPan, it.tasteCode) }
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

        val hasStartedHighHeatSupply = HighHeatSupplementResumePlanner.hasBatchStarted(list25_33)
        val pendingBatchHandling = KaoChangOperate.handlePendingSupplementBatchIfAny(
            allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
            reason = "低转高过渡调度入口",
            boxSupplyAvailable = boxSupplyAvailable,
            schedulingEnabled = schedulingEnabled,
            isBeforeCloseWindow = isBeforeCloseWindow
        )
        if (pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return
        }

        if (HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33) && boxSupplyAvailable && schedulingEnabled && !isBeforeCloseWindow) {
            resumeIncompleteHighHeatSupplement(
                list = list,
                listBox = listBox,
                reason = "低转高过渡续补25~33未完成批次"
            )
        } else if (!hasStartedHighHeatSupply && boxSupplyAvailable && schedulingEnabled && !isBeforeCloseWindow) {
            val frontEmptyCount = list1_21.count { !it.isHasSausage }
            TrafficModeHelper.fillMissingTasteReservations(list1_21, listBox)
            val plannedTargets = HighHeatSupplementResumePlanner.planInitialTargets(
                frontPans = list1_21,
                supplementGroup = list25_33,
                listBox = listBox
            )
            val availableStockByTaste = listBox
                .filter { it.num > 0 && !it.tasteCode.isNullOrBlank() }
                .groupBy { it.tasteCode.orEmpty() }
                .mapValues { (_, boxes) -> boxes.sumOf { it.num } }
            val targets = plannedTargets.map { target ->
                KaoChangOperate.SupplementTarget(target.kaoPan, target.tasteCode)
            }
            if (targets.isEmpty()) {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：无法规划 25~33 高并发补热批次，frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste"
                )
            }

            val movedCount = if (targets.isNotEmpty()) {
                KaoChangOperate.heating(3)
                KaoChangOperate.moveSausagesToKaoPansByTaste(targets)
            } else {
                0
            }
            if (movedCount > 0) {
                val partialBatch = movedCount < targets.size
                val batchResultText = if (partialBatch) {
                    "已启动残缺高并发补热批次，后续进入高并发正常态续补或成熟回补"
                } else {
                    "已启动完整高并发补热批次"
                }
                recordSupplementLaunch(
                    filledCount = movedCount,
                    now = System.currentTimeMillis(),
                    reason = "低转高过渡启动25~33高并发补肠"
                )
                LogUtils.i(
                    TAG,
                    "【并发切换】低转高过渡：已向 25~33 启动高并发补肠，batchResult=$batchResultText，" +
                        "frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste，" +
                        "requestedTargetCount=${targets.size}，actualMovedCount=$movedCount，" +
                        "partialBatch=$partialBatch，targetPositions=${targets.map { it.kaoPan.positionSn }}，" +
                        "lastFillPitTime=$lastFillPitTime"
                )
            } else {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：尝试向 25~33 启动高并发补肠，但本次未成功移动烤肠，" +
                        "frontEmptyCount=$frontEmptyCount，availableStockByTaste=$availableStockByTaste，" +
                        "requestedTargetCount=${targets.size}，targetPositions=${targets.map { it.kaoPan.positionSn }}"
                )
            }
        }

        if (schedulingEnabled) {
            moveFinishedOldLowHeatGroupToFront(list, 13, 15, "13~15")
            moveFinishedOldLowHeatGroupToFront(list, 16, 18, "16~18")
        }

        val hasOldLowHeatAreaSausage = list13_18.any { it.isHasSausage }
        if (!hasOldLowHeatAreaSausage) {
            KaoChangOperate.stopHeating(2)
        }
        val hasStartedHighHeatSupplyNow = HighHeatSupplementResumePlanner.hasBatchStarted(list25_33)
        val hasFullHighHeatSupplyBatchNow = HighHeatSupplementResumePlanner.hasFullBatchStarted(list25_33)
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
            hasStartedHighHeatSupplyBatchNow = hasStartedHighHeatSupplyNow
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
            "【并发切换】低转高过渡完成：modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}"
        )
    }

    /**
     * 低转高期间，13~18 仍按低并发旧补热区处理。
     * 烤熟后必须搬回 1~9 的同口味预留空位，不能在 2 区原地进入保温可售。
     */
    private suspend fun moveFinishedOldLowHeatGroupToFront(
        list: List<KaoPan>,
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

        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        KaoChangOperate.keepWarm(1)
        LogUtils.i(TAG, "【并发切换】低转高过渡：$groupName 旧补热区已烤熟，开始搬回 1~9 前区保温可售")
        sourcePans.forEach { kaoPan ->
            val tasteCode = kaoPan.taste?.tasteCode
            val targetPan = TrafficModeHelper.selectFrontTargetPan(
                TrafficModeHelper.getTrafficModePositions(1, 9, list),
                tasteCode,
                requireReserved = true
            )
            if (targetPan == null) {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：$groupName 旧补热区搬回失败，未找到 1~9 同口味预留空位，" +
                        "sourcePan=${kaoPan.positionSn}，tasteCode=$tasteCode，sourceStartTime=${kaoPan.startTime}"
                )
                return@forEach
            }
            val moved = KaoChangOperate.moveKaoPanToKaoPan(
                kaoPan,
                targetPan,
                targetHoldingTime = now
            )
            if (!moved) {
                LogUtils.w(
                    TAG,
                    "【并发切换】低转高过渡：$groupName 旧补热区搬回失败，" +
                        "sourcePan=${kaoPan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=$tasteCode"
                )
            }
        }
        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "低转高过渡${groupName}旧补热区烤熟搬回1~9"
        )
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
        val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
        val isBeforeCloseWindow =
            KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)
        val pendingBatchHandling = KaoChangOperate.handlePendingSupplementBatchIfAny(
            allowedTypes = setOf(SupplementBatchType.HIGH_HEAT_25_33),
            reason = "高转低过渡调度入口",
            boxSupplyAvailable = boxSupplyAvailable,
            schedulingEnabled = schedulingEnabled,
            isBeforeCloseWindow = isBeforeCloseWindow
        )
        if (pendingBatchHandling != KaoChangOperate.PendingSupplementBatchHandling.NONE) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return
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
        checkHighHeatSausage(list)

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val sausageCount = list1_21.count { it.isHasSausage }
        val sausageCount25_33 = list25_33.count { it.isHasSausage }

        if (canFinishHighToLowDrain(sausageCount, sausageCount25_33)) {
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
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        val sausageCount = list1_21.count { it.isHasSausage }
        val alreadySausages1_9 = list1_9.filter { it.isHasSausage }
        if (alreadySausages1_9.size < sausageCount) {
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            val remainingSausages = TrafficModeHelper.sortByHoldingAgeThenPosition(
                list10_21.filter { it.isHasSausage }
            )
            val remainingPositions1_9 = list1_9.filter { !it.isHasSausage }
            remainingSausages.forEachIndexed { index, kaoPan ->
                if (index < remainingPositions1_9.size) {
                    val targetPan = remainingPositions1_9[index]
                    LogUtils.i(
                        TAG,
                        "【并发切换】高转低/残留收拢：烤盘${kaoPan.positionSn} -> 烤盘${targetPan.positionSn}，holdingTime=${kaoPan.holdingTime}"
                    )
                    val moved = KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    if (!moved) {
                        LogUtils.w(
                            TAG,
                            "【并发切换】高转低/残留收拢搬移失败，保留源盘等待下轮：sourcePan=${kaoPan.positionSn}，targetPan=${targetPan.positionSn}"
                        )
                    }
                }
            }
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高转低/残留收拢10~21剩余可售肠回1~9"
            )
        }

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
        KaoChangOperate.stopHeating(2)
        KaoChangOperate.stopHeating(3)
        config.transitionMode = 0
        config.modeType = 1
        config.highConcurrencyResidualDrain = 0
        AppConfig.saveAppConfig(config)
        SendServerHelper.publishServiceUpdateStatus()
        resetSupplementTracking(reason)
        LogUtils.i(
            TAG,
            "【并发切换】高转低/残留收拢完成：reason=$reason，" +
                "modeType=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "highConcurrencyResidualDrain=$oldResidualDrain->${config.highConcurrencyResidualDrain}"
        )
        return true
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

        KaoChangOperate.moveSausagesToKaoPansByTaste(
            list1_21.map { kaoPan ->
                KaoChangOperate.SupplementTarget(kaoPan, kaoPan.taste?.tasteCode)
            }
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
            KaoChangOperate.heating(3)
            val neededTastes = emptyPositions.take(9).mapNotNull { it.taste?.tasteCode?.takeIf { code -> code.isNotBlank() } }
            if (neededTastes.size < 9) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】跳过 25~33 补热区补肠：前区空盘口味预留不足，empty=${emptyPositions.size}，reserved=${neededTastes.size}"
                )
                return 0
            }
            val targets = neededTastes.mapIndexedNotNull { index, tasteCode ->
                if (index < emptyPositions25_33.size) {
                    val targetPosition = emptyPositions25_33[index]
                    targetPosition.taste = Taste().apply { this.tasteCode = tasteCode }
                    KaoChangOperate.SupplementTarget(targetPosition, tasteCode)
                } else {
                    null
                }
            }
            val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(targets)
            repeat(movedCount) {
                SelfCleanManager.markZone3Dirty()
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
        val resumeTargets = HighHeatSupplementResumePlanner.planResumeTargets(list1_21, list25_33)
        if (resumeTargets.isEmpty()) {
            LogUtils.w(
                TAG,
                "【高并发补肠】检测到 25~33 未完成批次但无法续补：reason=$reason，" +
                    "occupied=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}，" +
                    "empty=${list25_33.filter { !it.isHasSausage }.map { it.positionSn }}"
            )
            return 0
        }

        KaoChangOperate.heating(3)
        LogUtils.i(
            TAG,
            "【高并发补肠】续补 25~33 未完成批次：reason=$reason，目标烤盘=${resumeTargets.map { it.positionSn }}"
        )
        val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
            resumeTargets.map { target ->
                KaoChangOperate.SupplementTarget(target.kaoPan, target.tasteCode)
            }
        )
        if (movedCount > 0) {
            recordSupplementLaunch(
                filledCount = movedCount,
                now = System.currentTimeMillis(),
                reason = reason
            )
            repeat(movedCount) {
                SelfCleanManager.markZone3Dirty()
            }
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
        if (HighHeatSupplementResumePlanner.hasIncompleteBatch(list25_33)) {
            val movedPartialBatch = moveFinishedPartialHighHeatBatchToFront(list, list25_33)
            if (!movedPartialBatch) {
                logUnfinishedPartialHighHeatBatch(list25_33)
            }
            return movedPartialBatch
        }
        if (HighHeatSupplementResumePlanner.isCompleteFinishedBatch(list25_33)) {
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            LogUtils.i(TAG, "【高并发补肠】25~33 补热区已烤熟，开始回补 1~21")
            KaoChangOperate.keepWarm(3)
            KaoChangOperate.keepWarm(2)
            KaoChangOperate.keepWarm(1)

            list25_33.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.taste != null) {
                    val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                    val tasteCode = kaoPan.taste.tasteCode
                    val targetPan = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(list1_21, tasteCode)
                    if (targetPan == null) {
                        LogUtils.w(
                            TAG,
                            "【高并发补肠】25~33 补热区回补失败，未找到 1~21 同口味或空白口味空位：sourcePan=${kaoPan.positionSn}，tasteCode=$tasteCode，保留源盘等待下轮"
                        )
                        return@forEach
                    }
                    val moved = KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    if (!moved) {
                        LogUtils.w(
                            TAG,
                            "【高并发补肠】25~33 补热区回补失败，机械调度未启动或搬移失败：sourcePan=${kaoPan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=$tasteCode，保留源盘等待下轮"
                        )
                    }
                }
            }
            val returnCleared = HighHeatSupplementResumePlanner.isReturnCleared(list25_33)
            if (returnCleared) {
                KaoChangOperate.stopHeating(3)
                clearPitCountAfterBatch("25~33 补热区回补完成")
                SelfCleanManager.markZone3CleanPendingIfNeeded(list)
            } else {
                LogUtils.i(
                    TAG,
                    "【高并发补肠】25~33 补热区回补未完成，保留源盘等待下轮：remainingPositions=${list25_33.filter { it.isHasSausage }.map { it.positionSn }}"
                )
            }
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高并发25~33补热区烤熟搬回1~21"
            )
            return returnCleared
        }
        return false
    }

    private suspend fun moveFinishedPartialHighHeatBatchToFront(
        list: List<KaoPan>,
        list25_33: List<KaoPan>
    ): Boolean {
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
            moveStatus = AppConfig.getAppConfig().moveStatus
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

        LogUtils.i(
            TAG,
            "【高并发补肠】25~33 残缺补热批次已烤熟，开始回补 1~21：sourcePositions=${readyPans.map { it.positionSn }}，targetRange=1~21"
        )
        KaoChangOperate.keepWarm(3)
        KaoChangOperate.keepWarm(2)
        KaoChangOperate.keepWarm(1)

        readyPans.forEach { sourcePan ->
            val tasteCode = sourcePan.taste?.tasteCode
            val targetPan = TrafficModeHelper.selectFrontTargetPanForHighHeatReturn(
                TrafficModeHelper.getTrafficModePositions(1, 21, list),
                tasteCode
            )
            if (targetPan == null) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】25~33 残缺补热批次搬回失败，未找到 1~21 同口味或空白口味空位：sourcePan=${sourcePan.positionSn}，tasteCode=$tasteCode，保留源盘等待下轮"
                )
                return@forEach
            }
            val moved = KaoChangOperate.moveKaoPanToKaoPan(
                sourcePan,
                targetPan,
                targetHoldingTime = sourcePan.holdingTime
            )
            if (!moved) {
                LogUtils.w(
                    TAG,
                    "【高并发补肠】25~33 残缺补热批次搬回失败，机械调度未启动或搬移失败：sourcePan=${sourcePan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=$tasteCode，保留源盘等待下轮"
                )
            }
        }

        KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
            beforeSellable,
            KaoPanHelper.getKaoPanList(),
            "高并发25~33残缺批次烤熟搬回1~21"
        )
        if (HighHeatSupplementResumePlanner.isReturnCleared(list25_33)) {
            KaoChangOperate.stopHeating(3)
            clearPitCountAfterBatch("25~33 残缺补热批次回补完成")
            SelfCleanManager.markZone3CleanPendingIfNeeded(list)
        }
        return true
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
        var allFinished = true
        var heatingCount = 0
        val nowMs = System.currentTimeMillis()
        list.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                heatingCount += 1
                val heatTime = FaultHeatPauseManager.effectiveBakingElapsedMs(kaoPan, nowMs = nowMs)
                if (heatTime < kaoPan.bakingTime) {
                    allFinished = false
                }
            }
        }
        if (allFinished && heatingCount > 0) {
            val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
            KaoChangOperate.stopHeating(area)
            KaoChangOperate.keepWarm(area)
            list.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = nowMs
                    kaoPan.status = 2
                }
            }
            LogUtils.i(TAG, "【加热控制】${area} 区烤肠已全部烤熟，切换为保温状态")
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "高并发${area}区切换保温可售"
            )
        }
    }

    /**
     * 检查高并发前区可售烤肠是否保温到期。
     */
    private suspend fun checkHighHeatDiscard() {
        OverKeepWarmDiscardScanner.scanAndDiscard(1..21, "高并发轮询")
    }

    /**
     * 判断是否需要触发高转低。
     * 触发条件：
     * 1. 接近营业结束；
     * 2. 距离上次高并发补肠已超过高转低间隔阈值。
     */
    private fun checkSwitchToLow() {
        val businessTime = AppConfig.getAppConfig().businessTime
        if (businessTime != null) {
            val remainingTimeUntilClose = getRemainingTimeUntilClose(businessTime)
            val beforeCloseThreshold =
                (AppConfig.getAppConfig().timeBeforeClose.takeIf { it > 0 } ?: 30) * 60 * 1000L
            if (remainingTimeUntilClose > 0 && remainingTimeUntilClose < beforeCloseThreshold) {
                switchHighToLow("接近营业结束")
                return
            }
        }

        if (lastFillPitTime > 0) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastFill = currentTime - lastFillPitTime
            val timeThresholdLowMinutes = AppConfig.getAppConfig().timeThresholdLow
            if (shouldSwitchHighToLowByTimeout(lastFillPitTime, currentTime, timeThresholdLowMinutes)) {
                LogUtils.w(
                    TAG,
                    "【并发切换】高并发补肠间隔超时判定成立：lastFillPitTime=$lastFillPitTime，currentTime=$currentTime，elapsedMs=$timeSinceLastFill，timeThresholdLow=${timeThresholdLowMinutes}分钟"
                )
                switchHighToLow("高并发补肠间隔超时")
            }
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
    private fun switchHighToLow(reason: String) {
        LogUtils.w(
            TAG,
            "【并发切换】触发高转低：原因=$reason，modeType=${modeText(AppConfig.getAppConfig().modeType)}，transitionMode=${transitionText(AppConfig.getAppConfig().transitionMode)}->2(高转低过渡)"
        )
        AppConfig.getAppConfig().apply {
            transitionMode = 2
        }
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        SendServerHelper.publishServiceUpdateStatus()
        resetSupplementTracking("进入高转低过渡")
    }
}
