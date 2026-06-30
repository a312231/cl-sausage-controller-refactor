package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils

object LowerPlatformRuntimeStateReporter {
    fun markSupplyDegraded(reason: String) {
        val config = AppConfig.getAppConfig()
        val previousLowerPlatformStatus = config.lowerPlatformStatus
        val previousSupplementEnabled = config.supplementEnabled
        val previousOrderBlockReason = config.orderBlockReason
        config.lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED
        config.supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
        config.orderBlockReason = ""
        LogUtils.w(
            "【补肠供给降级】已标记供给侧异常，不切整机维护：" +
                "lowerPlatformStatus=$previousLowerPlatformStatus->${config.lowerPlatformStatus}，" +
                "supplementEnabled=$previousSupplementEnabled->${config.supplementEnabled}，" +
                "orderBlockReason=${previousOrderBlockReason ?: "null"}->空，" +
                "onlineStatus=${config.onlineStatus}，errorStatus=${config.errorStatus}，isEnable=${config.isEnable}，" +
                "reason=$reason"
        )
        AppConfig.saveAppConfig(config)
        publishOrFallback("runtime_state:supply_degraded")
    }

    fun markNormalAfterManualBoxMaintenance(reason: String) {
        val config = AppConfig.getAppConfig()
        if (!shouldRecoverAfterManualBoxMaintenance(config)) {
            return
        }
        val previousLowerPlatformStatus = config.lowerPlatformStatus
        val previousSupplementEnabled = config.supplementEnabled
        val previousOrderBlockReason = config.orderBlockReason
        config.lowerPlatformStatus = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        config.supplementEnabled = AppConfigBean.SUPPLEMENT_ENABLED_YES
        if (shouldClearOrderBlockReasonAfterManualBoxMaintenance(config)) {
            config.orderBlockReason = ""
        }
        AppConfig.saveAppConfig(config)
        LogUtils.i(
            "【补肠供给恢复】人工维护烤肠箱后恢复下平台供给状态：" +
                "lowerPlatformStatus=$previousLowerPlatformStatus->${config.lowerPlatformStatus}，" +
                "supplementEnabled=$previousSupplementEnabled->${config.supplementEnabled}，" +
                "orderBlockReason=${previousOrderBlockReason ?: "null"}->${config.orderBlockReason ?: "null"}，" +
                "reason=$reason"
        )
        publishOrFallback("runtime_state:supply_recovered")
    }

    fun shouldRecoverAfterManualBoxMaintenance(config: AppConfigBean): Boolean {
        return config.lowerPlatformStatus == AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED ||
            config.orderBlockReason == AppConfigBean.ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED
    }

    fun shouldClearOrderBlockReasonAfterManualBoxMaintenance(config: AppConfigBean): Boolean {
        return config.orderBlockReason == AppConfigBean.ORDER_BLOCK_REASON_LOWER_PLATFORM_RECOVERY_REQUIRED
    }

    private fun publishOrFallback(source: String) {
        val mqttOk = SendServerHelper.publishServiceUpdateStatus(source)
        if (!mqttOk) {
            LogUtils.w("【补肠供给状态】MQTT 发送队列拒绝，回退 HTTP 补偿：source=$source")
            DataManagementAPI.uploadDeviceInfo(source = source)
        }
    }
}
