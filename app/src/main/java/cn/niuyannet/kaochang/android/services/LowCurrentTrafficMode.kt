package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 低并发模式控制器。
 *
 * 负责前区 1~9 的初始化、补肠、保温与过保丢弃，
 * 管理补热区 13~18 的加热与回补，并在满足条件时触发低并发转高并发。
 */
object LowCurrentTrafficMode {
    private const val TAG = "KaoChangAlgorithm"

    /**
     * 2 区加热开关：
     * - 0：允许加热
     * - 1：已经关闭，避免重复下发停加热指令
     */
    var TEMPERATURE_OFF = 0

    private var lastLowTrafficSnapshot: String? = null
    private var lastLowInitializationDecisionLog: String? = null
    private var lastLowHeatSupplementGateLog: String? = null

    private fun reportPlannedPanState(pans: Collection<KaoPan>, reason: String, source: String) {
        val changedPositions = pans.map { it.positionSn }.distinct().sorted()
        if (changedPositions.isEmpty()) return
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        runCatching {
            PanPhysicalStateDeltaReporter.reportPanPositionsChanged(changedPositions, reason, source)
        }.onFailure { error ->
            LogUtils.w(TAG, "【云端同步】补肠规划物理增量上报异常，等待快照补偿：source=$source，error=${error.message}")
        }
    }

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

    private fun lowSupplementStateText(value: Int): String = when (value) {
        0 -> "0(无需补肠)"
        1 -> "1(补 13~15 补热区)"
        2 -> "2(补 16~18 补热区)"
        3 -> "3(前区空位不足 3 且补热区已空，可关闭 2 区加热)"
        else -> "$value(未知)"
    }

    /**
     * 低并发主处理器。
     *
     * 关键状态说明：
     * - p1_9：前区 1~9 空位数
     * - p13_15：13~15 空位数
     * - p16_18：16~18 空位数
     * - supplementDecision：
     *   0=无需补肠，1=补 13~15，2=补 16~18，
     *   3=前区空位不足 3 且 13~18 补热区为空，可关 2 区加热
     */
    suspend fun lowTrafficMode(
        config: AppConfigBean,
        kaoPanList: List<KaoPan>,
        listBox: List<KaoPanBox>,
        boxSupplyAvailable: Boolean,
        schedulingEnabled: Boolean = true
    ): Int {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, kaoPanList)
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, kaoPanList)

        val p1_9 = list1_9.count { !it.isHasSausage }
        val p13_18 = list13_18.count { !it.isHasSausage }
        val p13_15 = list13_15.count { !it.isHasSausage }
        val p16_18 = list16_18.count { !it.isHasSausage }
        val totalEmpty = p1_9 + p13_18

        val supplementDecision = lowConcurrencyIntestinalSupplementation(p1_9, p13_15, p16_18)
        val stateSnapshot =
            "total=$totalEmpty|front=$p1_9|13_15=$p13_15|16_18=$p16_18|decision=$supplementDecision|mode=${config.modeType}|transition=${config.transitionMode}|error=${config.errorStatus}"
        if (stateSnapshot != lastLowTrafficSnapshot) {
            LogUtils.d(
                TAG,
                "【低并发状态】状态变化：总空位=$totalEmpty，1~9空位=$p1_9，13~15空位=$p13_15，16~18空位=$p16_18，补肠决策=${lowSupplementStateText(supplementDecision)}，modeType=${modeText(config.modeType)}，transitionMode=${transitionText(config.transitionMode)}，errorStatus=${config.errorStatus}"
            )
            lastLowTrafficSnapshot = stateSnapshot
        }

        val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
        val isBeforeCloseWindow =
            KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)

        val pendingBatchHandling = KaoChangOperate.handlePendingSupplementBatchIfAny(
            allowedTypes = setOf(
                SupplementBatchType.LOW_INITIALIZATION_1_9,
                SupplementBatchType.LOW_HEAT_13_15,
                SupplementBatchType.LOW_HEAT_16_18
            ),
            reason = "低并发调度入口",
            boxSupplyAvailable = boxSupplyAvailable,
            schedulingEnabled = schedulingEnabled,
            isBeforeCloseWindow = isBeforeCloseWindow
        )
        if (pendingBatchHandling.completedMechanicalWorkThisRound) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            return totalEmpty
        }
        val canStartSupplement = schedulingEnabled && !pendingBatchHandling.blocksNewSupplement

        KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
            types = setOf(
                SupplementBatchType.LOW_HEAT_13_15,
                SupplementBatchType.LOW_HEAT_16_18,
                SupplementBatchType.HIGH_HEAT_25_33
            ),
            sourcePositionsWithSausage = kaoPanList
                .filter { it.positionSn in 13..18 || it.positionSn in 25..33 }
                .filter { it.isHasSausage }
                .map { it.positionSn }
                .toSet(),
            sourcePanStatuses = kaoPanList.associate { it.positionSn to it.status },
            reason = "低并发调度入口检测补热源盘已清空"
        )
        val activeLowHeatIntentTargetPositions = KaoChangOperate.activeSupplementBatchTargetPositions(
            setOf(SupplementBatchType.LOW_HEAT_13_15, SupplementBatchType.LOW_HEAT_16_18)
        )
        val clearedGhostReservations = clearGhostLowFrontReservations(
            list1_9 = list1_9,
            list13_18 = list13_18,
            activeLowHeatIntentTargetPositions = activeLowHeatIntentTargetPositions
        )
        if (clearedGhostReservations.isNotEmpty()) {
            LogUtils.w(
                TAG,
                "【低并发自愈】清除前区幽灵预留：positions=$clearedGhostReservations，" +
                    "reason=13~18全空且无活动低并发补热批次"
            )
        }

        logLowInitializationDecision(
            frontEmpty = p1_9,
            frontCapacity = list1_9.size,
            heatEmpty = p13_18,
            heatCapacity = list13_18.size,
            schedulingEnabled = canStartSupplement,
            boxSupplyAvailable = boxSupplyAvailable,
            errorStatus = config.errorStatus,
            isBeforeCloseWindow = isBeforeCloseWindow
        )

        if (totalEmpty == list1_9.size + list13_18.size) {
            if (canStartSupplement && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                initLowTrafficMode(kaoPanList, listBox)
            }
        } else if (p1_9 >= list13_15.size || p13_18 < list13_18.size) {
            if (schedulingEnabled && p16_18 < list16_18.size) {
                checkLowHeatSausage16_18(kaoPanList)
            }

            if (schedulingEnabled && p13_15 < list13_15.size) {
                checkLowHeatSausage(kaoPanList)
            }

            if (canStartSupplement && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                if (resumeLowInitialization(listBox, list1_9, list13_18)) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return totalEmpty
                }
                if (resumeLowHeatSupplementGroup(kaoPanList, list13_15, "13~15")) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return totalEmpty
                }
                if (resumeLowHeatSupplementGroup(kaoPanList, list16_18, "16~18")) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                    return totalEmpty
                }
            }

            logLowHeatSupplementGate(
                supplementDecision = supplementDecision,
                schedulingEnabled = canStartSupplement,
                boxSupplyAvailable = boxSupplyAvailable,
                errorStatus = config.errorStatus,
                isBeforeCloseWindow = isBeforeCloseWindow,
                selectedFrontPositions = when (supplementDecision) {
                    1 -> list1_9.filter { !it.isHasSausage }.take(list13_15.size)
                    2 -> list1_9.filter { !it.isHasSausage }.take(list16_18.size)
                    else -> emptyList()
                }
            )

            if (canStartSupplement && supplementDecision == 1 && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                TEMPERATURE_OFF = 0
                if (addLowHeatSausage(kaoPanList, listBox, list13_15.size)) {
                    LogUtils.i(TAG, "【低并发补肠】已向 13~15 补热区补肠")
                }
            } else if (canStartSupplement && supplementDecision == 2 && boxSupplyAvailable && config.errorStatus == 0 && !isBeforeCloseWindow) {
                TEMPERATURE_OFF = 0
                if (addLowHeatSausage16_18(kaoPanList, listBox, list16_18.size)) {
                    LogUtils.i(TAG, "【低并发补肠】已向 16~18 补热区补肠")
                }
            }
        }

        if (supplementDecision == 3 && TEMPERATURE_OFF != 1) {
            TEMPERATURE_OFF = 1
            LogUtils.i(TAG, "【加热控制】2 区补热区已空，关闭 2 区加热")
            KaoChangOperate.stopHeating(2)
        }

        checkLowHeatEnd(list1_9)
        if (schedulingEnabled) {
            checkLowHeatDiscard()
        }

        val effectiveFrontEmptyPositions = LowToHighDemandGate.effectiveEmptyFrontPositions(list1_9)
        val blockedFrontPositions = LowToHighDemandGate.blockedFrontPositions()
        if (!pendingBatchHandling.blocksNewSupplement &&
            schedulingEnabled &&
            boxSupplyAvailable &&
            config.modeType == 1 &&
            config.transitionMode == 0 &&
            LowToHighDemandGate.canTriggerLowToHigh(
                frontPans = list1_9,
                lowHeatPans = list13_18
            )
        ) {
            LogUtils.w(
                TAG,
                "【并发切换】触发低转高：1~9空位=$p1_9，" +
                    "过保丢弃空位=$blockedFrontPositions，" +
                    "有效空位=${effectiveFrontEmptyPositions.size}，13~18 低并发补热批次在烤，" +
                    "目标=高并发，" +
                    "modeType=${modeText(config.modeType)}->2(高并发)，" +
                    "transitionMode=${transitionText(config.transitionMode)}->1(低转高过渡)"
            )
            switchLowToHigh(kaoPanList)
        } else if (schedulingEnabled &&
            boxSupplyAvailable &&
            config.modeType == 1 &&
            config.transitionMode == 0 &&
            p1_9 > 7 &&
            p13_18 == 0 &&
            blockedFrontPositions.isNotEmpty()
        ) {
            LogUtils.i(
                TAG,
                "【并发切换】暂不触发低转高：1~9空位=$p1_9，" +
                    "过保丢弃空位=$blockedFrontPositions，" +
                    "有效空位=${effectiveFrontEmptyPositions.size}，" +
                    "原因=过保丢弃造成的空位不计入高需求证据"
            )
        }

        // 烤盘保存由 KaoPanHelper 内部后台作用域统一处理，这里直接提交最新状态。
        KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        return totalEmpty
    }

    private fun logLowInitializationDecision(
        frontEmpty: Int,
        frontCapacity: Int,
        heatEmpty: Int,
        heatCapacity: Int,
        schedulingEnabled: Boolean,
        boxSupplyAvailable: Boolean,
        errorStatus: Int,
        isBeforeCloseWindow: Boolean
    ) {
        val message = LowTrafficDecisionLogFormatter.lowInitializationDecision(
            frontEmpty = frontEmpty,
            frontCapacity = frontCapacity,
            heatEmpty = heatEmpty,
            heatCapacity = heatCapacity,
            schedulingEnabled = schedulingEnabled,
            boxSupplyAvailable = boxSupplyAvailable,
            errorStatus = errorStatus,
            isBeforeCloseWindow = isBeforeCloseWindow
        )
        if (message != lastLowInitializationDecisionLog) {
            LogUtils.i(TAG, message)
            lastLowInitializationDecisionLog = message
        }
    }

    private suspend fun resumeLowInitialization(
        listBox: List<KaoPanBox>,
        list1_9: List<KaoPan>,
        list13_18: List<KaoPan>
    ): Boolean {
        TrafficModeHelper.fillMissingTasteReservations(list1_9, listBox)
        val resumeTargets = TrafficInitializationResumePlanner.planFrontInitResumeTargets(list1_9, list13_18)
        if (resumeTargets.isEmpty()) {
            return false
        }
        reportPlannedPanState(list1_9, "低并发初始化续补口味规划", "low_initialization_resume_planned")

        KaoChangOperate.heating(1)
        LogUtils.i(
            TAG,
            "【低并发初始化】检测到 1~9 初始化未完成，续补剩余空位：目标烤盘=${resumeTargets.joinToString(",") { it.positionSn.toString() }}"
        )
        val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(
            resumeTargets.map { KaoChangOperate.SupplementTarget(it.kaoPan, it.tasteCode) },
            batchTypeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )
        LogUtils.i(
            TAG,
            "【低并发初始化】1~9 初始化续补结束：目标数=${resumeTargets.size}，完成数=$movedCount"
        )
        return true
    }

    private suspend fun resumeLowHeatSupplementGroup(
        list: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        groupName: String
    ): Boolean {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        if (activeIntent != null) {
            LogUtils.w(
                TAG,
                "【低并发补肠】存在活动补肠意图，generic 残缺组续补不重建物理空洞：" +
                    "group=$groupName，batchId=${activeIntent.batchId}"
            )
            return false
        }
        val resumeTargets = LowHeatSupplementResumePlanner.planResumeTargets(list1_9, supplementGroup, activeIntent)
        if (resumeTargets.isEmpty()) {
            return false
        }
        reportPlannedPanState(
            list1_9 + supplementGroup,
            "低并发${groupName}补热批次续补口味规划",
            "low_heat_resume_planned"
        )

        LogUtils.i(
            TAG,
            "【低并发补肠】续补 $groupName 未完成批次：目标烤盘=${resumeTargets.joinToString(",") { it.positionSn.toString() }}，" +
                "口味=${resumeTargets.joinToString(",") { it.tasteCode }}，" +
                "回搬目标=${resumeTargets.joinToString(",") { "${it.positionSn}->${it.returnPositionSn ?: "未知"}" }}"
        )
        val attempt = KaoChangOperate.moveSausagesToKaoPansForResume(
            resumeTargets.map {
                KaoChangOperate.SupplementTarget(
                    it.kaoPan,
                    it.tasteCode,
                    returnPositionSn = it.returnPositionSn
                )
            },
            batchTypeOverride = if (groupName == "13~15") {
                SupplementBatchType.LOW_HEAT_13_15
            } else {
                SupplementBatchType.LOW_HEAT_16_18
            },
            onAdmitted = {
                TEMPERATURE_OFF = 0
                KaoChangOperate.heating(2)
            }
        )
        if (!attempt.admitted) {
            LogUtils.w(TAG, "【低并发补肠】续补 $groupName 未获批或未移动，本轮不恢复前区预留")
            return false
        }
        if (attempt.movedCount > 0) {
            restoreFrontReservationsAfterLowHeatResume(list1_9, resumeTargets)
        }
        LogUtils.i(
            TAG,
            "【低并发补肠】续补 $groupName 未完成批次结束：目标数=${resumeTargets.size}，" +
                "完成数=${attempt.movedCount}，batchId=${attempt.batchId}"
        )
        return true
    }

    internal fun restoreFrontReservationsAfterLowHeatResume(
        frontPans: List<KaoPan>,
        targets: List<LowHeatSupplementResumePlanner.ResumeTarget>
    ): List<Int> {
        return targets
            .filter { it.kaoPan.isHasSausage }
            .mapNotNull { target ->
                if (restoreFrontReservationAfterLowHeatResume(frontPans, target)) {
                    target.returnPositionSn
                } else {
                    null
                }
            }
    }

    private fun restoreFrontReservationAfterLowHeatResume(
        frontPans: List<KaoPan>,
        target: LowHeatSupplementResumePlanner.ResumeTarget
    ): Boolean {
        val returnPositionSn = target.returnPositionSn ?: return false
        val frontPan = frontPans.firstOrNull { it.positionSn == returnPositionSn && it.positionSn in 1..9 }
            ?: return false
        if (frontPan.isHasSausage) {
            LogUtils.w(
                TAG,
                "【低并发补肠】续补后无法恢复前区回搬预占：sourcePan=${target.positionSn}，returnPositionSn=$returnPositionSn，原因=目标前区已有烤肠"
            )
            return false
        }
        frontPan.status = KaoPan.STATUS_EMPTY
        frontPan.isHasGrilling = true
        if (frontPan.taste?.tasteCode != target.tasteCode) {
            frontPan.taste = Taste(target.tasteCode)
        }
        return true
    }

    /**
     * 根据前区空位情况决定补肠动作。
     *
     * 返回值：
     * - 0：无需补肠
     * - 1：补 13~15
     * - 2：补 16~18
     * - 3：前区空位不足 3 且 13~18 补热区为空，可关 2 区加热
     */
    internal fun lowConcurrencyIntestinalSupplementation(
        p1_9: Int,
        p13_15: Int,
        p16_18: Int
    ): Int {
        val p13_18 = p13_15 + p16_18
        if (p1_9 >= 3) {
            if (p13_15 == 3 && p16_18 == 3) {
                return 1
            } else if (p13_15 == 3 && p1_9 >= 6 && p16_18 == 0) {
                return 1
            } else if (p16_18 == 3 && p1_9 >= 6 && p13_15 == 0) {
                return 2
            }
        } else if (p13_18 == 6) {
            return 3
        }
        return 0
    }

    /**
     * 初始化低并发模式。
     * 从烤肠箱按口味比例向 1~9 工位上肠，并启动 1 区加热。
     */
    private suspend fun initLowTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.i(TAG, "【低并发初始化】开始初始化低并发：前区目标工位=1~9")
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w(TAG, "【低并发初始化】所有烤肠箱库存为空，停止 1 区加热")
            KaoChangOperate.stopHeating(1)
            return
        }

        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        KaoChangOperate.heating(1)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_9.size)
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_9.size) {
                    list1_9[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }
        reportPlannedPanState(list1_9, "低并发初始化口味规划", "low_initialization_planned")

        KaoChangOperate.moveSausagesToKaoPansByTaste(
            list1_9.map { kaoPan ->
                KaoChangOperate.SupplementTarget(kaoPan, kaoPan.taste?.tasteCode)
            },
            batchTypeOverride = SupplementBatchType.LOW_INITIALIZATION_1_9
        )
    }

    /**
     * 检查 13~15 补热区是否已经烤熟。
     * 烤熟后搬回前区同口味的待补位，并在搬运成功后才将目标位标记为可售。
     * 若本轮回搬后 13~18 已空，会立即关闭 2 区加热，不等下一轮 supplementDecision 重算。
     */
    private suspend fun checkLowHeatSausage(list: List<KaoPan>) {
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        moveFinishedLowHeatPansToFront(list, list13_15, "13~15")
    }

    /**
     * 检查 16~18 补热区是否已经烤熟。
     * 烤熟后搬回前区同口味的待补位，并在搬运成功后才将目标位标记为可售。
     * 若本轮回搬后 13~18 已空，会立即关闭 2 区加热，不等下一轮 supplementDecision 重算。
     */
    private suspend fun checkLowHeatSausage16_18(list: List<KaoPan>) {
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        moveFinishedLowHeatPansToFront(list, list16_18, "16~18")
    }

    private suspend fun moveFinishedLowHeatPansToFront(
        list: List<KaoPan>,
        supplementGroup: List<KaoPan>,
        groupName: String
    ): Boolean {
        val batchType = when (groupName) {
            "13~15" -> SupplementBatchType.LOW_HEAT_13_15
            "16~18" -> SupplementBatchType.LOW_HEAT_16_18
            else -> null
        }
        if (batchType != null &&
            KaoChangOperate.shouldDelayHeatReturnForBatchCompleteness(setOf(batchType))
        ) {
            LogUtils.i(
                TAG,
                "【低并发补肠】$groupName 补热区已熟烤肠暂不拆批回搬：下平台故障仍在10分钟续补窗口内，等待人工确认后优先补齐整批"
            )
            return true
        }
        if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        var beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val normalizedLegacySellableSources =
            LowHeatSupplementResumePlanner.normalizeLegacySellableKeepWarmSources(supplementGroup)
        if (normalizedLegacySellableSources.isNotEmpty()) {
            LogUtils.w(
                TAG,
                "【低并发补肠】$groupName 补热区发现历史原地保温可售状态，已恢复为等待回搬不可售：positions=$normalizedLegacySellableSources"
            )
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "低并发${groupName}补热区历史原地可售状态纠偏为等待回搬"
            )
            beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        }
        val keepWarmPans = LowHeatSupplementResumePlanner.findKeepWarmPansReadyToMove(supplementGroup)
        val finishedPans = if (keepWarmPans.isEmpty()) {
            LowHeatSupplementResumePlanner.findFinishedPans(supplementGroup, nowMs)
        } else {
            emptyList()
        }
        if (keepWarmPans.isEmpty() && finishedPans.isEmpty()) {
            logUnfinishedLowHeatPans(supplementGroup, groupName, nowMs)
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
                "【低并发补肠】$groupName 补热区已熟烤肠暂不回搬：${dispatchDecision.reason}"
            )
            return true
        }

        val readyPans = keepWarmPans.ifEmpty {
            LowHeatSupplementResumePlanner.markFinishedPansKeepWarm(supplementGroup, nowMs)
        }
        if (readyPans.isEmpty()) {
            return false
        }
        if (finishedPans.isNotEmpty()) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            runCatching {
                PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                    changedPanPositions = readyPans.map { it.positionSn },
                    reason = "低并发${groupName}残缺补热批次切换等待回搬保温",
                    source = "low_partial_heat_matured_keep_warm"
                )
            }.onFailure { error ->
                LogUtils.w(TAG, "【云端同步】低并发残缺补热批次成熟物理增量上报异常，等待快照补偿：${error.message}")
            }
        }

        LogUtils.i(
            TAG,
            "【低并发补肠】$groupName 补热区已熟批次开始回补 1~9：sourcePositions=${readyPans.map { it.positionSn }}，targetRange=1~9"
        )
        if (!KaoChangOperate.applyConfiguredTemperatureByPanState("低并发${groupName}补热区回搬前温控重算")) {
            LogUtils.w(TAG, "【加热控制】$groupName 补热区回搬前温控重算失败，继续尝试回搬成熟烤肠")
        }
        var movedAny = false
        val activeIntent = KaoChangOperate.activeSupplementBatchIntentForUi()
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        readyPans.forEach { sourcePan ->
            val bindingResult = SupplementReturnBindingResolver.resolveReturnTarget(
                sourcePan = sourcePan,
                frontPans = list1_9,
                activeIntent = activeIntent,
                allowedTypes = setOfNotNull(batchType),
                returnPositionRange = 1..9
            )
            val targetPan = bindingResult.targetPan
            if (targetPan == null) {
                LogUtils.w(
                    TAG,
                    "【低并发补肠】$groupName 补热区已熟回补失败：未找到安全的绑定回搬目标，" +
                        "sourcePan=${sourcePan.positionSn}，returnPositionSn=${bindingResult.returnPositionSn}，" +
                        "tasteCode=${bindingResult.tasteCode}，reason=${bindingResult.failureReason}，保留源盘等待下轮"
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
                    "【低并发补肠】$groupName 补热区已熟回补失败：机械调度未启动或搬移失败，sourcePan=${sourcePan.positionSn}，targetPan=${targetPan.positionSn}，tasteCode=${bindingResult.tasteCode}，保留源盘等待下轮"
                )
            } else {
                movedAny = true
            }
        }

        val list13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        if (list13_18.none { it.isHasSausage }) {
            LogUtils.i(TAG, "【加热控制】13~18 补热区已无烤肠，关闭 2 区加热")
            KaoChangOperate.stopHeating(2)
            if (batchType != null) {
                KaoChangOperate.clearCompletedHeatReturnBindingIfSourcesCleared(
                    types = setOf(batchType),
                    sourcePositionsWithSausage = list13_18.filter { it.isHasSausage }.map { it.positionSn }.toSet(),
                    sourcePanStatuses = list13_18.associate { it.positionSn to it.status },
                    reason = "低并发${groupName}补热区回搬完成"
                )
            }
        }
        if (!movedAny) {
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "低并发补热区残缺批次烤熟搬回1~9兜底同步"
            )
        }
        return true
    }

    private fun logUnfinishedLowHeatPans(
        supplementGroup: List<KaoPan>,
        groupName: String,
        nowMs: Long
    ) {
        val occupiedPans = supplementGroup.filter { it.isHasSausage }.sortedBy { it.positionSn }
        if (occupiedPans.isEmpty()) {
            return
        }
        val longestRemainingMs = LowHeatSupplementResumePlanner.longestRemainingBakingMs(supplementGroup, nowMs) ?: 0L
        LogUtils.i(
            TAG,
            "【低并发补肠】$groupName 补热区仍在烤制，等待续补或下轮回搬：occupiedPositions=${occupiedPans.map { it.positionSn }}，longestRemainingMs=$longestRemainingMs"
        )
    }

    /**
     * 向 13~15 补热区补肠。
     * 前区空位的口味决定补热区本轮上肠口味。
     */
    private suspend fun addLowHeatSausage(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        pNum: Int
    ): Boolean {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        val hasSausageIn13_15 = list13_15.any { it.isHasSausage }
        if (hasSausageIn13_15) {
            return false
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_9, listBox)
        val committedReturnTasteByPosition = SupplementTasteMixPlanner.committedReturnTasteByPosition(
            KaoChangOperate.completedHeatReturnIntentForUi(),
            1..9
        )
        val emptyPositions = list1_9.filter {
            !it.isHasSausage &&
                !it.isHasGrilling &&
                it.positionSn !in committedReturnTasteByPosition
        }
        val emptyPositions13_15 = list13_15.filter { !it.isHasSausage }
        if (emptyPositions.size >= pNum && emptyPositions13_15.size >= pNum) {
            val selectedFrontPositions = emptyPositions.take(pNum)
            val plan = planLowHeatSupplementTargets(
                selectedFrontPositions = selectedFrontPositions,
                supplementPositions = emptyPositions13_15.take(pNum),
                listBox = listBox,
                allFrontPans = list1_9,
                committedReturnTasteByPosition = committedReturnTasteByPosition
            )
            if (plan.targets.isEmpty()) {
                LogUtils.w(
                    TAG,
                    "【低并发补肠】跳过 13~15 补热区补肠：当前无可执行补肠目标，" +
                        "frontEmpty=${emptyPositions.size}，heatEmpty=${emptyPositions13_15.size}"
                )
                return false
            }
            reportPlannedPanState(
                list1_9 + plan.targets.map { it.kaoPan },
                "低并发13~15补热批次口味规划",
                "low_heat_13_15_planned"
            )
            KaoChangOperate.heating(2)
            val targets = plan.targets
            val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(targets)
            val activeTargets = KaoChangOperate.activeSupplementBatchTargetPositions(
                setOf(SupplementBatchType.LOW_HEAT_13_15)
            )
            val result = applyLowHeatSupplementReservationResult(
                frontPans = plan.frontPans,
                supplementTargets = targets,
                activeLowHeatIntentTargetPositions = activeTargets,
                movedCount = movedCount
            )
            reportPlannedPanState(
                plan.frontPans + targets.map { it.kaoPan },
                "低并发13~15补热批次预留收口",
                "low_heat_13_15_reservation_settled"
            )
            LogUtils.i(
                TAG,
                "【低并发补肠】13~15 补热区补肠结果收口：movedCount=$movedCount，" +
                    "reserved=${result.reservedFrontPositions}，cleared=${result.clearedFrontPositions}，" +
                    "keptForIntent=${result.keptForActiveIntentFrontPositions}，failedTargets=${result.failedTargetPositions}"
            )
            return true
        }
        return false
    }

    /**
     * 向 16~18 补热区补肠。
     */
    private suspend fun addLowHeatSausage16_18(
        list: List<KaoPan>,
        listBox: List<KaoPanBox>,
        pNum: Int
    ): Boolean {
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        val list16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        val hasSausageIn16_18 = list16_18.any { it.isHasSausage }
        if (hasSausageIn16_18) {
            return false
        }

        TrafficModeHelper.fillMissingTasteReservations(list1_9, listBox)
        val committedReturnTasteByPosition = SupplementTasteMixPlanner.committedReturnTasteByPosition(
            KaoChangOperate.completedHeatReturnIntentForUi(),
            1..9
        )
        val emptyPositions = list1_9.filter {
            !it.isHasSausage &&
                !it.isHasGrilling &&
                it.positionSn !in committedReturnTasteByPosition
        }
        val emptyPositions16_18 = list16_18.filter { !it.isHasSausage }
        if (emptyPositions.size >= pNum && emptyPositions16_18.size >= pNum) {
            val selectedFrontPositions = emptyPositions.take(pNum)
            val plan = planLowHeatSupplementTargets(
                selectedFrontPositions = selectedFrontPositions,
                supplementPositions = emptyPositions16_18.take(pNum),
                listBox = listBox,
                allFrontPans = list1_9,
                committedReturnTasteByPosition = committedReturnTasteByPosition
            )
            if (plan.targets.isEmpty()) {
                LogUtils.w(
                    TAG,
                    "【低并发补肠】跳过 16~18 补热区补肠：当前无可执行补肠目标，" +
                        "frontEmpty=${emptyPositions.size}，heatEmpty=${emptyPositions16_18.size}"
                )
                return false
            }
            reportPlannedPanState(
                list1_9 + plan.targets.map { it.kaoPan },
                "低并发16~18补热批次口味规划",
                "low_heat_16_18_planned"
            )
            KaoChangOperate.heating(2)
            val targets = plan.targets
            val movedCount = KaoChangOperate.moveSausagesToKaoPansByTaste(targets)
            val activeTargets = KaoChangOperate.activeSupplementBatchTargetPositions(
                setOf(SupplementBatchType.LOW_HEAT_16_18)
            )
            val result = applyLowHeatSupplementReservationResult(
                frontPans = plan.frontPans,
                supplementTargets = targets,
                activeLowHeatIntentTargetPositions = activeTargets,
                movedCount = movedCount
            )
            reportPlannedPanState(
                plan.frontPans + targets.map { it.kaoPan },
                "低并发16~18补热批次预留收口",
                "low_heat_16_18_reservation_settled"
            )
            LogUtils.i(
                TAG,
                "【低并发补肠】16~18 补热区补肠结果收口：movedCount=$movedCount，" +
                    "reserved=${result.reservedFrontPositions}，cleared=${result.clearedFrontPositions}，" +
                    "keptForIntent=${result.keptForActiveIntentFrontPositions}，failedTargets=${result.failedTargetPositions}"
            )
            return true
        }
        return false
    }

    private fun hasSameTasteSupplyForAll(neededTastes: List<String>, listBox: List<KaoPanBox>): Boolean {
        return neededTastes.distinct().all { tasteCode ->
            validSupplyBoxesByTaste(listBox, tasteCode).isNotEmpty()
        }
    }

    private fun validSupplyBoxesByTaste(
        listBox: List<KaoPanBox>,
        tasteCode: String,
        isBoxAvailable: (KaoPanBox) -> Boolean = KaoPanHelper::isSupplyBoxAvailableForAutoSupplement
    ): List<KaoPanBox> {
        if (tasteCode.isBlank()) return emptyList()
        return listBox
            .filter {
                it.tasteCode == tasteCode &&
                    isBoxAvailable(it) &&
                    SourceBoxPositionPolicy.isValidSourceBox(it.positionSn)
            }
            .sortedByDescending { it.positionSn }
    }

    private fun logLowHeatSupplementGate(
        supplementDecision: Int,
        schedulingEnabled: Boolean,
        boxSupplyAvailable: Boolean,
        errorStatus: Int,
        isBeforeCloseWindow: Boolean,
        selectedFrontPositions: List<KaoPan>
    ) {
        if (supplementDecision !in 1..2) {
            return
        }
        val message = formatLowHeatSupplementGateLog(
            supplementDecision = supplementDecision,
            schedulingEnabled = schedulingEnabled,
            boxSupplyAvailable = boxSupplyAvailable,
            errorStatus = errorStatus,
            isBeforeCloseWindow = isBeforeCloseWindow,
            selectedFrontPositions = selectedFrontPositions
        )
        if (message != lastLowHeatSupplementGateLog) {
            val blocked = !schedulingEnabled || !boxSupplyAvailable || errorStatus != 0 || isBeforeCloseWindow
            if (blocked) {
                LogUtils.w(TAG, message)
            } else {
                LogUtils.i(TAG, message)
            }
            lastLowHeatSupplementGateLog = message
        }
    }

    internal fun formatLowHeatSupplementGateLog(
        supplementDecision: Int,
        schedulingEnabled: Boolean,
        boxSupplyAvailable: Boolean,
        errorStatus: Int,
        isBeforeCloseWindow: Boolean,
        selectedFrontPositions: List<KaoPan>
    ): String {
        val groupName = when (supplementDecision) {
            1 -> "13~15"
            2 -> "16~18"
            else -> "无"
        }
        val blockedReasons = buildList {
            if (!schedulingEnabled) add("schedulingDisabled")
            if (!boxSupplyAvailable) add("boxSupplyUnavailable")
            if (errorStatus != 0) add("errorStatus=$errorStatus")
            if (isBeforeCloseWindow) add("beforeCloseWindow")
        }
        val actionText = if (blockedReasons.isEmpty()) "准备进入" else "未进入"
        val frontTasteSummary = selectedFrontPositions.joinToString(",") { pan ->
            val tasteCode = pan.taste?.tasteCode?.takeIf { it.isNotBlank() } ?: "未预留"
            val reserveState = when {
                pan.isHasGrilling -> "回补预占"
                !pan.taste?.tasteCode.isNullOrBlank() -> "预占"
                else -> "空缺"
            }
            "${pan.positionSn}:$tasteCode($reserveState)"
        }.ifBlank { "空" }
        return "【低并发补肠】$groupName 补热区补肠$actionText：" +
            "decision=${lowSupplementStateText(supplementDecision)}，" +
            "阻断原因=${blockedReasons.joinToString(",").ifBlank { "无" }}，" +
            "schedulingEnabled=$schedulingEnabled，boxSupplyAvailable=$boxSupplyAvailable，" +
            "errorStatus=$errorStatus，beforeCloseWindow=$isBeforeCloseWindow，" +
            "前区空位口味=$frontTasteSummary"
    }

    internal fun formatNoSameTasteStockLog(
        groupName: String,
        selectedFrontPositions: List<KaoPan>,
        neededTastes: List<String>,
        listBox: List<KaoPanBox>,
        isBoxAvailable: (KaoPanBox) -> Boolean = KaoPanHelper::isSupplyBoxAvailableForAutoSupplement
    ): String {
        val missingTastes = neededTastes
            .distinct()
            .filter { tasteCode -> validSupplyBoxesByTaste(listBox, tasteCode, isBoxAvailable).isEmpty() }
        val frontTasteSummary = selectedFrontPositions.joinToString(",") { pan ->
            "${pan.positionSn}:${pan.taste?.tasteCode?.takeIf { it.isNotBlank() } ?: "未预留"}"
        }.ifBlank { "空" }
        val availableStockSummary = listBox
            .mapNotNull { it.tasteCode?.takeIf { tasteCode -> tasteCode.isNotBlank() } }
            .distinct()
            .flatMap { tasteCode -> validSupplyBoxesByTaste(listBox, tasteCode, isBoxAvailable) }
            .groupBy { it.tasteCode.orEmpty() }
            .mapValues { (_, boxes) -> boxes.sumOf { it.num } }
            .entries
            .sortedBy { it.key }
            .joinToString(",") { (tasteCode, count) -> "$tasteCode=$count" }
            .ifBlank { "无" }
        return "【低并发补肠】跳过 $groupName 补热区补肠：无同口味可用库存，" +
            "目标补热区=$groupName，前区空位口味=$frontTasteSummary，" +
            "缺失口味=${missingTastes.joinToString(",").ifBlank { "无" }}，" +
            "可用源箱库存=$availableStockSummary"
    }

    internal data class LowHeatSupplementReservationResult(
        val reservedFrontPositions: List<Int>,
        val clearedFrontPositions: List<Int>,
        val keptForActiveIntentFrontPositions: List<Int>,
        val failedTargetPositions: List<Int>,
        val movedCount: Int,
        val actualSuccessCount: Int
    )

    internal data class LowHeatSupplementPlan(
        val frontPans: List<KaoPan>,
        val targets: List<KaoChangOperate.SupplementTarget>
    )

    internal fun planLowHeatSupplementTargets(
        selectedFrontPositions: List<KaoPan>,
        supplementPositions: List<KaoPan>,
        listBox: List<KaoPanBox>,
        allFrontPans: List<KaoPan> = selectedFrontPositions,
        committedReturnTasteByPosition: Map<Int, String> = emptyMap(),
        isBoxAvailable: (KaoPanBox) -> Boolean = KaoPanHelper::isSupplyBoxAvailableForAutoSupplement
    ): LowHeatSupplementPlan {
        val maxCount = minOf(selectedFrontPositions.size, supplementPositions.size)
        val availableStockByTaste = buildAvailableStockByTaste(listBox, isBoxAvailable)
        val occupiedTasteByPosition = allFrontPans
            .filter { it.isHasSausage || it.isHasGrilling }
            .mapNotNull { pan ->
                val tasteCode = committedReturnTasteByPosition[pan.positionSn]
                    ?: pan.taste?.tasteCode?.takeIf { it.isNotBlank() }
                tasteCode?.let { pan.positionSn to it }
            }
            .toMap()
            .toMutableMap()
            .apply {
                committedReturnTasteByPosition.forEach { (positionSn, tasteCode) ->
                    if (positionSn in 1..9 && tasteCode.isNotBlank()) {
                        put(positionSn, tasteCode)
                    }
                }
            }
        val targetCountByTaste = KaoPanHelper.computeKaoPamRatio(listBox, 9)
        val tastePlan = SupplementTasteMixPlanner.plan(
            frontCapacity = 9,
            targetCountByTaste = targetCountByTaste,
            availableStockByTaste = availableStockByTaste,
            occupiedTasteByPosition = occupiedTasteByPosition,
            assignablePositions = selectedFrontPositions.take(maxCount).map { it.positionSn }
        )
        val assignmentByPosition = tastePlan.assignments.associateBy { it.positionSn }
        val plannedFrontPans = mutableListOf<KaoPan>()
        val targets = mutableListOf<KaoChangOperate.SupplementTarget>()
        supplementPositions.take(maxCount).forEach { targetPan ->
            if (!targetPan.isHasSausage) {
                targetPan.taste = null
            }
        }
        for (index in 0 until maxCount) {
            val frontPan = selectedFrontPositions[index]
            val targetPan = supplementPositions[index]
            val tasteCode = assignmentByPosition[frontPan.positionSn]?.tasteCode ?: break
            if (frontPan.taste?.tasteCode != tasteCode) {
                frontPan.taste = Taste(tasteCode)
            }
            targetPan.taste = Taste(tasteCode)
            plannedFrontPans += frontPan
            targets += KaoChangOperate.SupplementTarget(
                targetPan,
                tasteCode,
                returnPositionSn = frontPan.positionSn
            )
        }
        if (targets.isNotEmpty()) {
            val assignmentText = tastePlan.assignments.joinToString(",") {
                "${it.positionSn}:${it.tasteCode}"
            }
            runCatching {
                LogUtils.i(
                    TAG,
                    "【低并发补肠】新批次口味规划：target=${tastePlan.targetCountByTaste}，" +
                        "occupied=${tastePlan.occupiedCountByTaste}，" +
                        "assignments=$assignmentText，" +
                        "stockLimited=${tastePlan.stockLimited}"
                )
            }
        }
        return LowHeatSupplementPlan(plannedFrontPans, targets)
    }

    private fun buildAvailableStockByTaste(
        listBox: List<KaoPanBox>,
        isBoxAvailable: (KaoPanBox) -> Boolean
    ): MutableMap<String, Int> {
        return listBox
            .filter {
                isBoxAvailable(it) &&
                    SourceBoxPositionPolicy.isValidSourceBox(it.positionSn) &&
                    !it.tasteCode.isNullOrBlank()
            }
            .groupBy { it.tasteCode.orEmpty() }
            .mapValues { (_, boxes) -> boxes.sumOf { it.num } }
            .filterValues { it > 0 }
            .toMutableMap()
    }

    internal fun applyLowHeatSupplementReservationResult(
        frontPans: List<KaoPan>,
        supplementTargets: List<KaoChangOperate.SupplementTarget>,
        activeLowHeatIntentTargetPositions: Set<Int>,
        movedCount: Int
    ): LowHeatSupplementReservationResult {
        require(frontPans.size == supplementTargets.size) {
            "frontPans and supplementTargets must be index-aligned: front=${frontPans.size}, targets=${supplementTargets.size}"
        }
        val reserved = mutableListOf<Int>()
        val cleared = mutableListOf<Int>()
        val keptForIntent = mutableListOf<Int>()
        val failedTargets = mutableListOf<Int>()

        supplementTargets.forEachIndexed { index, target ->
            val frontPan = frontPans[index]
            val targetPan = target.kaoPan
            if (targetPan.isHasSausage) {
                frontPan.isHasGrilling = true
                reserved += frontPan.positionSn
            } else if (targetPan.positionSn in activeLowHeatIntentTargetPositions) {
                frontPan.isHasGrilling = true
                keptForIntent += frontPan.positionSn
                failedTargets += targetPan.positionSn
            } else {
                frontPan.isHasGrilling = false
                targetPan.taste = null
                cleared += frontPan.positionSn
                failedTargets += targetPan.positionSn
            }
        }

        return LowHeatSupplementReservationResult(
            reservedFrontPositions = reserved.sorted(),
            clearedFrontPositions = cleared.sorted(),
            keptForActiveIntentFrontPositions = keptForIntent.sorted(),
            failedTargetPositions = failedTargets.sorted(),
            movedCount = movedCount,
            actualSuccessCount = supplementTargets.count { it.kaoPan.isHasSausage }
        )
    }

    internal fun clearGhostLowFrontReservations(
        list1_9: List<KaoPan>,
        list13_18: List<KaoPan>,
        activeLowHeatIntentTargetPositions: Set<Int>
    ): List<Int> {
        if (activeLowHeatIntentTargetPositions.isNotEmpty() || list13_18.any { it.isHasSausage }) {
            return emptyList()
        }
        val cleared = mutableListOf<Int>()
        list1_9
            .filter { !it.isHasSausage && it.isHasGrilling }
            .forEach { pan ->
                pan.isHasGrilling = false
                cleared += pan.positionSn
            }
        return cleared.sorted()
    }

    /**
     * 检查前区 1~9 是否整体加热完成。
     * 完成后关闭 1 区加热并进入保温状态。
     */
    private fun checkLowHeatEnd(list: List<KaoPan>) {
        if (FaultHeatPauseManager.shouldSkipBakingCompletion()) {
            return
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
            val maturedPositions = list
                .filter { it.isHasSausage && it.startTime > 0L }
                .map { it.positionSn }
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.keepWarm(1)
            list.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    kaoPan.startTime = 0
                    kaoPan.holdingTime = nowMs
                    kaoPan.status = KaoPan.STATUS_SELLABLE_KEEP_WARM
                }
            }
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            runCatching {
                PanPhysicalStateDeltaReporter.reportPanPositionsChanged(
                    changedPanPositions = maturedPositions,
                    reason = "低并发前区1~9切换保温可售",
                    source = "low_front_matured_keep_warm"
                )
            }.onFailure { error ->
                LogUtils.w(TAG, "【云端同步】低并发前区成熟物理增量上报异常，等待快照补偿：${error.message}")
            }
            LogUtils.i(TAG, "【加热控制】前区 1~9 已全部烤熟，切换为保温状态")
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "低并发前区1~9切换保温可售"
            )
        }
    }

    /**
     * 检查前区可售烤肠是否保温到期。
     * 仅当 holdingTime 超过 closeTime 时才触发自动丢弃。
     */
    private suspend fun checkLowHeatDiscard() {
        OverKeepWarmDiscardScanner.scanAndDiscard(1..18, "低并发轮询")
    }

    /**
     * 低并发 -> 高并发。
     * modeType 切到 2，transitionMode 切到 1，表示进入低转高过渡态。
     */
    private fun switchLowToHigh(kaoPanList: List<KaoPan>) {
        val config = AppConfig.getAppConfig()
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val resolvedTransition = resolveAutomaticTrafficTransition(
            currentModeType = oldModeType,
            currentTransitionMode = oldTransitionMode,
            requestedTransitionMode = 1
        )
        if (resolvedTransition == null) {
            LogUtils.w(
                TAG,
                "【并发切换诊断】拒绝自动写入 transitionMode=1（低转高过渡）：source=LowCurrentTrafficMode.switchLowToHigh，" +
                    "modeType（并发模式）=${modeText(oldModeType)}，transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}，" +
                    "原因=自动算法只允许低并发稳态进入低转高过渡态"
            )
            return
        }
        config.modeType = resolvedTransition.modeType
        config.transitionMode = resolvedTransition.transitionMode
        AppConfig.saveAppConfig(config)
        LogUtils.i(
            TAG,
            "【并发切换诊断】写入 transitionMode=1（低转高过渡）：source=LowCurrentTrafficMode.switchLowToHigh，" +
                "modeType（并发模式）=${modeText(oldModeType)}->${modeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionText(oldTransitionMode)}->${transitionText(config.transitionMode)}，" +
                "targetMode（并发切换目标）=高并发，" +
                "1~21空位=${TrafficModeHelper.getTrafficModePositions(1, 21, kaoPanList).filter { !it.isHasSausage }.map { it.positionSn }}，" +
                "13~18状态=${panStatusSummary(TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList))}，" +
                "25~33状态=${panStatusSummary(TrafficModeHelper.getTrafficModePositions(25, 33, kaoPanList))}"
        )
        SendServerHelper.publishServiceUpdateStatus()
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
}
