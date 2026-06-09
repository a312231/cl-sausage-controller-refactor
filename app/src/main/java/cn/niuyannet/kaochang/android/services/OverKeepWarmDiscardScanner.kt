package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.utils.LogUtils

/**
 * 过保与待丢弃扫描器。
 *
 * 标记阶段不依赖算法开关，确保待丢弃烤盘立即从可售口径排除；
 * 机械丢弃阶段再受 isEnable、moveStatus、onlineStatus、errorStatus 等自动调度门控约束。
 */
object OverKeepWarmDiscardScanner {
    private const val TAG = "KaoChangAlgorithm"

    data class ScanResult(
        val markedPositions: List<Int>,
        val attemptedPositions: List<Int>,
        val successfulPositions: List<Int>,
        val mechanicalBlockedReason: String?
    )

    suspend fun scanAndDiscard(
        positionRange: IntRange,
        source: String,
        allowMechanicalAction: Boolean = true
    ): ScanResult {
        val beforeSellable = KaoPanCloudSyncReporter.captureSellableSnapshot(KaoPanHelper.getKaoPanList())
        val nowMs = System.currentTimeMillis()
        val config = AppConfig.getAppConfig()
        val pans = KaoPanHelper.getKaoPanList()
            .filter { it.positionSn in positionRange }
            .sortedBy { it.positionSn }
        val normalizedPositions = FaultHeatPauseManager.normalizeStaleBakingHoldingTimes(pans, config)
        if (normalizedPositions.isNotEmpty()) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            AppConfig.saveAppConfig(config)
            LogUtils.w(
                TAG,
                "【丢弃流程】过保扫描前自愈烤制盘旧保温时间：source=$source，positions=$normalizedPositions"
            )
        }

        val candidates = collectDiscardCandidates(
            pans = pans,
            pendingDiscardPositions = config.pendingDiscardPositions,
            nowMs = nowMs
        )
        val pendingCandidates = candidates.pendingPositions
        val expiredCandidates = candidates.expiredPositions
        val markedPositions = candidates.allPositions

        var markedChanged = false
        if (markedPositions.isNotEmpty()) {
            markedChanged = FaultHeatPauseManager.markPendingDiscardPositions(
                positions = markedPositions,
                alertCode = FaultHeatPauseManager.ALERT_KEEP_WARM_EXPIRED,
                alertReason = "过保/待丢弃扫描触发：source=$source，range=${positionRange.first}..${positionRange.last}，positions=$markedPositions"
            )
            LogUtils.w(
                TAG,
                "【丢弃流程】待丢弃扫描摘要：source=$source，range=${positionRange.first}..${positionRange.last}，" +
                    "pendingCandidates=$pendingCandidates，expiredCandidates=$expiredCandidates，markedChanged=$markedChanged"
            )
        }

        if (markedChanged) {
            SendServerHelper.publishServiceUpdateStatus("runtime_state:pending_discard_marked:$source")
            SendServerHelper.publishAvailableState("pending_discard_marked:$source")
            KaoPanCloudSyncReporter.uploadIfSellableStateChanged(
                beforeSellable,
                KaoPanHelper.getKaoPanList(),
                "待丢弃标记后可售口径变化：$source"
            )
        }

        val gate = evaluateMechanicalGate(AppConfig.getAppConfig(), allowMechanicalAction)
        if (!gate.canStart) {
            if (markedPositions.isNotEmpty()) {
                LogUtils.w(
                    TAG,
                    "【丢弃流程】已完成可售阻断，但暂不执行机械丢弃：source=$source，" +
                        "positions=$markedPositions，reason=${gate.reason}"
                )
            }
            return ScanResult(
                markedPositions = markedPositions,
                attemptedPositions = emptyList(),
                successfulPositions = emptyList(),
                mechanicalBlockedReason = gate.reason
            )
        }

        val attempted = mutableListOf<Int>()
        val successful = mutableListOf<Int>()
        val latestPending = AppConfig.getAppConfig().pendingDiscardPositions.toSet()
        for (pan in KaoPanHelper.getKaoPanList().filter { it.positionSn in positionRange && it.positionSn in latestPending }.sortedBy { it.positionSn }) {
            if (!pan.isHasSausage) {
                FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(pan.positionSn)
                continue
            }
            attempted.add(pan.positionSn)
            val holdTime = if (pan.holdingTime > 0L) nowMs - pan.holdingTime else 0L
            val discardSuccess = KaoChangOperate.discardSausage(
                pan,
                source = "过保到期",
                detail = "scanner=$source，已保温=${holdTime}ms，阈值=${pan.closeTime}ms，pendingDiscard=true"
            )
            if (discardSuccess) {
                successful.add(pan.positionSn)
                FaultHeatPauseManager.clearDiscardPositionAndAlertIfFinished(pan.positionSn)
            }
            val afterConfig = AppConfig.getAppConfig()
            if (DiscardLoopPolicy.shouldStopAfterDiscard(discardSuccess, afterConfig.onlineStatus, afterConfig.errorStatus)) {
                LogUtils.w(
                    TAG,
                    "【丢弃流程】停止继续处理后续待丢弃烤肠：source=$source，lastPan=${pan.positionSn}，" +
                        "discardSuccess=$discardSuccess，reason=${DiscardLoopPolicy.stopReason(discardSuccess, afterConfig.onlineStatus, afterConfig.errorStatus)}"
                )
                break
            }
        }

        return ScanResult(
            markedPositions = markedPositions,
            attemptedPositions = attempted,
            successfulPositions = successful,
            mechanicalBlockedReason = null
        )
    }

    internal data class DiscardCandidates(
        val pendingPositions: List<Int>,
        val expiredPositions: List<Int>
    ) {
        val allPositions: List<Int> = (pendingPositions + expiredPositions).distinct().sorted()
    }

    internal fun collectDiscardCandidates(
        pans: List<KaoPan>,
        pendingDiscardPositions: Collection<Int>,
        nowMs: Long
    ): DiscardCandidates {
        val pendingCandidates = pans
            .filter { it.positionSn in pendingDiscardPositions && it.isHasSausage }
            .map { it.positionSn }
            .distinct()
            .sorted()
        val expiredCandidates = pans
            .filter { isExpiredHoldingPan(it, nowMs) }
            .map { it.positionSn }
            .distinct()
            .sorted()
        return DiscardCandidates(pendingCandidates, expiredCandidates)
    }

    private fun isExpiredHoldingPan(pan: KaoPan, nowMs: Long): Boolean =
        pan.isHasSausage &&
            !FaultHeatPauseManager.isStaleBakingHoldingTime(pan) &&
            pan.holdingTime > 0L &&
            pan.closeTime > 0L &&
            nowMs - pan.holdingTime > pan.closeTime

    internal fun evaluateMechanicalGate(
        config: AppConfigBean,
        allowMechanicalAction: Boolean
    ): AutoMechanicalDispatchDecision {
        if (!allowMechanicalAction) {
            return AutoMechanicalDispatchDecision(false, "调用方禁止本轮机械丢弃")
        }
        if (config.isEnable != 1) {
            return AutoMechanicalDispatchDecision(false, "isEnable（烤肠算法开关）=0（关闭），等待人工开启算法后执行机械丢弃")
        }
        return AutoMechanicalDispatchPolicy.evaluate(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode,
            moveStatus = config.moveStatus
        )
    }
}
