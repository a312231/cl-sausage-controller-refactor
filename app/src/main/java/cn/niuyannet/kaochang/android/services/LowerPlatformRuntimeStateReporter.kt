package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils

object LowerPlatformRuntimeStateReporter {
    fun applyLowerPlatformIsolated(config: AppConfigBean) {
        config.lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
        config.supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_NO
        config.orderBlockReason = ""
    }

    fun markSupplyDegraded(
        reason: String,
        failedBoxNos: List<Int> = emptyList(),
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails()
    ) {
        val config = AppConfig.getAppConfig()
        val previousLowerPlatformStatus = config.lowerPlatformStatus
        val previousIsolationActionName = config.lowerPlatformIsolationActionName
        val previousSupplementEnabled = config.supplementEnabled
        val previousOrderBlockReason = config.orderBlockReason
        val tx = LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
            applySupplyDegraded(config, reason, failedBoxNos, details = details)
            config.orderBlockReason = ""
        }
        LogUtils.w(
            "【补肠供给降级】已标记供给侧异常，不切整机维护：" +
                "lowerPlatformStatus=$previousLowerPlatformStatus->${config.lowerPlatformStatus}，" +
                "lowerPlatformIsolationActionName=$previousIsolationActionName->空，" +
                "supplementEnabled=$previousSupplementEnabled->${config.supplementEnabled}，" +
                "orderBlockReason=${previousOrderBlockReason ?: "null"}->空，" +
                "onlineStatus=${config.onlineStatus}，errorStatus=${config.errorStatus}，isEnable=${config.isEnable}，" +
                "reason=$reason"
        )
        publishOrFallback("runtime_state:supply_degraded", tx.snapshot)
    }

    fun applySupplyDegraded(
        config: AppConfigBean,
        reason: String,
        failedBoxNos: List<Int>,
        occurredAt: Long = System.currentTimeMillis(),
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails()
    ) {
        if (failedBoxNos.isNotEmpty()) {
            LowerPlatformEpisodeReducer.openSupplyDegraded(config, failedBoxNos, reason, occurredAt, details)
        } else if (config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            config.lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
        }
        if (config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            config.lowerPlatformIsolationActionName = ""
            config.supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
        }
        config.orderBlockReason = ""
    }

    fun persistIsolated(
        reason: String,
        actionName: String,
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails(requiresManualConfirm = true)
    ): LowerPlatformEpisodeReducer.StateSnapshot {
        val config = AppConfig.getAppConfig()
        val previousLowerPlatformStatus = config.lowerPlatformStatus
        val previousIsolationActionName = config.lowerPlatformIsolationActionName
        val previousSupplementEnabled = config.supplementEnabled
        val previousOrderBlockReason = config.orderBlockReason
        val previousMoveStatus = config.moveStatus
        val tx = LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
            if (details.closeoutGuarded) {
                LowerPlatformEpisodeReducer.openGuardedIsolation(config, actionName, reason, details = details)
            } else {
                LowerPlatformEpisodeReducer.openIsolated(config, actionName, reason, details = details)
            }
            applyLowerPlatformIsolated(config)
            config.lowerPlatformIsolationActionName = actionName
        }
        LogUtils.e(
            "【下平台隔离】补肠链路隔离已耐久保存，已停止自动补肠：" +
                "lowerPlatformStatus=$previousLowerPlatformStatus->${config.lowerPlatformStatus}，" +
                "lowerPlatformIsolationActionName=$previousIsolationActionName->$actionName，" +
                "supplementEnabled=$previousSupplementEnabled->${config.supplementEnabled}，" +
                "orderBlockReason=${previousOrderBlockReason ?: "null"}->空，" +
                "moveStatus=$previousMoveStatus->${config.moveStatus}，" +
                "onlineStatus=${config.onlineStatus}，errorStatus=${config.errorStatus}，isEnable=${config.isEnable}，" +
                "reason=$reason"
        )
        return tx.snapshot
    }

    fun publishPersistedIsolation(snapshot: LowerPlatformEpisodeReducer.StateSnapshot) {
        publishOrFallback("runtime_state:lower_platform_isolated", snapshot)
    }

    fun markIsolated(
        reason: String,
        actionName: String,
        details: LowerPlatformEpisodeDetails = LowerPlatformEpisodeDetails(requiresManualConfirm = true)
    ) {
        publishPersistedIsolation(persistIsolated(reason, actionName, details))
    }

    fun applySuccessfulSupplyBoxTake(
        config: AppConfigBean,
        sourceBoxNo: Int,
        recoveredAt: Long = System.currentTimeMillis()
    ): Boolean = LowerPlatformEpisodeReducer.recoverSupplyBox(config, sourceBoxNo, recoveredAt)

    fun markSupplyBoxRecoveredAfterSuccessfulTake(sourceBoxNo: Int, reason: String) {
        val config = AppConfig.getAppConfig()
        val previousStatus = config.lowerPlatformStatus
        val tx = LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
            applySuccessfulSupplyBoxTake(config, sourceBoxNo)
        }
        if (!tx.value) {
            return
        }
        LogUtils.i(
            "【补肠供给恢复】真实取肠成功，关闭同一来源箱供给降级事件：" +
                "sourceBox=$sourceBoxNo，lowerPlatformStatus=$previousStatus->${config.lowerPlatformStatus}，reason=$reason"
        )
        publishOrFallback("runtime_state:supply_box_recovered:$sourceBoxNo", tx.snapshot)
    }

    fun applyManualBoxMaintenanceRecovery(
        config: AppConfigBean,
        sourceBoxNos: Collection<Int>,
        recoveredAt: Long = System.currentTimeMillis()
    ): Boolean = LowerPlatformEpisodeReducer.recoverSupplyBoxes(config, sourceBoxNos, recoveredAt)

    fun markBoxesRecoveredAfterManualMaintenance(sourceBoxNos: Collection<Int>, reason: String) {
        val config = AppConfig.getAppConfig()
        val distinctBoxNos = sourceBoxNos.distinct()
        val tx = LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
            applyManualBoxMaintenanceRecovery(config, distinctBoxNos)
        }
        if (!tx.value) {
            return
        }
        LogUtils.i(
            "【补肠供给恢复】人工维护后仅恢复实际箱位：sourceBoxes=$distinctBoxNos，" +
                "lowerPlatformStatus=${config.lowerPlatformStatus}，reason=$reason"
        )
        publishOrFallback("runtime_state:supply_boxes_recovered", tx.snapshot)
    }

    fun confirmLowerPlatformIsolationAfterManualAction(reason: String): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.lowerPlatformEpisodes.any { it.lifecycle == "OPEN" && it.closeoutGuarded }) {
            return false
        }
        if (!shouldRecoverAfterManualLowerPlatformAction(config)) {
            return false
        }
        val tx = LowerPlatformEpisodeReducer.transaction(config, AppConfig::saveAppConfigDurable) {
            recoverLowerPlatformIsolation(config)
        }
        val before = tx.value
        LogUtils.i(
            "【下平台隔离恢复】人工确认后恢复自动机械调度：" +
                "lowerPlatformStatus=${before.lowerPlatformStatus}->${config.lowerPlatformStatus}，" +
                "lowerPlatformIsolationActionName=${before.lowerPlatformIsolationActionName}->空，" +
                "supplementEnabled=${before.supplementEnabled}->${config.supplementEnabled}，" +
                "reason=$reason"
        )
        publishOrFallback("runtime_state:lower_platform_isolation_recovered", tx.snapshot)
        return true
    }

    fun recoverLowerPlatformIsolation(config: AppConfigBean): RecoverySnapshot {
        val snapshot = RecoverySnapshot(
            lowerPlatformStatus = config.lowerPlatformStatus,
            lowerPlatformIsolationActionName = config.lowerPlatformIsolationActionName,
            supplementEnabled = config.supplementEnabled
        )
        LowerPlatformEpisodeReducer.recoverIsolation(config)
        config.lowerPlatformStatus = LowerPlatformEpisodeReducer.reduceStatus(config)
        if (config.lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            config.lowerPlatformIsolationActionName = ""
        }
        config.supplementEnabled = if (config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            AppConfigBean.SUPPLEMENT_ENABLED_NO
        } else {
            AppConfigBean.SUPPLEMENT_ENABLED_YES
        }
        return snapshot
    }

    fun shouldRecoverAfterManualBoxMaintenance(config: AppConfigBean): Boolean {
        return config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED ||
            config.orderBlockReason == AppConfigBean.ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED
    }

    fun shouldRecoverAfterManualLowerPlatformAction(config: AppConfigBean): Boolean {
        return config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED
    }

    fun shouldClearOrderBlockReasonAfterManualBoxMaintenance(config: AppConfigBean): Boolean {
        return config.orderBlockReason == AppConfigBean.ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED
    }

    data class RecoverySnapshot(
        val lowerPlatformStatus: String,
        val lowerPlatformIsolationActionName: String,
        val supplementEnabled: Int
    )

    private fun publishOrFallback(
        source: String,
        snapshot: LowerPlatformEpisodeReducer.StateSnapshot
    ) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus(source, snapshot)
        if (!mqttOk) {
            LogUtils.w("【补肠供给状态】MQTT 发送队列拒绝，回退 HTTP 补偿：source=$source")
            DataManagementAPI.uploadDeviceInfo(source = source, lowerPlatformSnapshot = snapshot)
        }
    }
}
