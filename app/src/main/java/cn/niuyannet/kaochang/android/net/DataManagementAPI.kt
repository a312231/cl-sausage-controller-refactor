package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.mqtt.MqttProtocol
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveKaoPanBoxList
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveTasteList
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.HighConcurrencyResidualDrainEvaluator
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DataManagementAPI {
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val LOG_SYNC = "云端同步"
    @Volatile
    private var lastAppliedConcurrencyControlSeq: Long = -1L

    data class RemoteDeviceStateRefreshResult(
        val success: Boolean,
        val changed: Boolean,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String
    )

    data class RuntimeControlApplyResult(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val onlineStatus: Int,
        val supplyStatus: Int,
        val scanBlockStatus: Int,
        val inspectionMode: Int,
        val isEnable: Int,
        val restStatusSource: String,
        val configVersion: Long? = null,
        val appliedFields: List<String> = emptyList(),
        val rejectedFields: List<String> = emptyList(),
        val normalizedFields: Map<String, Int> = emptyMap()
    )

    data class ConcurrencyControlApplyResult(
        val controlSeq: Long,
        val applied: Boolean,
        val changed: Boolean,
        val reason: String,
        val modeType: Int,
        val transitionMode: Int,
        val overrodeConflictingTransition: Boolean
    )

    private fun logD(message: String) = LogUtils.d("[$LOG_SYNC] $message")
    private fun logW(message: String) = LogUtils.w("[$LOG_SYNC] $message")
    private fun logE(message: String) = LogUtils.e("[$LOG_SYNC] $message")
    private fun logCloudSyncFile(message: String) = LogUtils.i("【$LOG_SYNC】$message")

    private fun reconcileDiscardCloseTime(
        logPrefix: String,
        oldDiscardTime: Int,
        mergedConfig: AppConfigBean
    ) {
        val correctedCount = KaoPanHelper.refreshDiscardCloseTime(mergedConfig.discardTime)
        if (correctedCount <= 0) {
            return
        }

        val closeTimeMillis = mergedConfig.discardTime.coerceAtLeast(0) * 60L * 60L * 1000L
        if (oldDiscardTime != mergedConfig.discardTime) {
            LogUtils.d(
                "[$logPrefix] 远端配置已更新丢弃时间，并已回刷烤盘保温超时阈值：" +
                    "discardTime=${oldDiscardTime}小时->${mergedConfig.discardTime}小时, " +
                    "已修正烤盘数=$correctedCount, 新closeTime=${closeTimeMillis}ms"
            )
        } else {
            LogUtils.w(
                "[$logPrefix] 启动/同步时发现烤盘保温超时阈值与当前配置不一致，已自动回刷：" +
                    "discardTime=${mergedConfig.discardTime}小时, 已修正烤盘数=$correctedCount, " +
                    "目标closeTime=${closeTimeMillis}ms"
            )
        }
    }

    private fun formatDiffValue(label: String, value: Any?): String {
        if (value == null) {
            return "null"
        }
        return when (label) {
            "status" -> when ((value as? Number)?.toInt()) {
                0 -> "0(未启用/离线)"
                1 -> "1(启用/在线)"
                else -> value.toString()
            }

            "onlineStatus" -> when ((value as? Number)?.toInt()) {
                0 -> "0(未启用)"
                1 -> "1(运营中)"
                2 -> "2(休息中)"
                3 -> "3(维护中)"
                4 -> "4(工作中/正在出餐)"
                else -> value.toString()
            }

            "supplyStatus" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.SUPPLY_STATUS_NORMAL -> "0(正常)"
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT -> "1(售罄/待补货)"
                else -> value.toString()
            }

            "scanBlockStatus" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.SCAN_BLOCK_STATUS_NORMAL -> "0(正常展示)"
                AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> "1(隐藏二维码并禁止下单/支付)"
                else -> value.toString()
            }

            "inspectionMode" -> when ((value as? Number)?.toInt()) {
                AppConfigBean.INSPECTION_MODE_NORMAL -> "0(正常)"
                AppConfigBean.INSPECTION_MODE_ACTIVE -> "1(检修中)"
                else -> value.toString()
            }

            "modeType" -> when ((value as? Number)?.toInt()) {
                1 -> "1(低并发)"
                2 -> "2(高并发)"
                else -> value.toString()
            }

            "transitionMode" -> when ((value as? Number)?.toInt()) {
                0 -> "0(无过渡态)"
                1 -> "1(低转高过渡)"
                2 -> "2(高转低过渡)"
                else -> value.toString()
            }

            "isEnable" -> when ((value as? Number)?.toInt()) {
                0 -> "0(已关闭)"
                1 -> "1(已开启)"
                else -> value.toString()
            }

            "keepWarmTemperature",
            "heatingTemperature",
            "zone1HeatingOffset",
            "zone2HeatingOffset",
            "zone3HeatingOffset" -> "${value}℃"
            "bakingTime" -> "${value}分钟"
            "discardTime" -> "${value}小时"
            "timeThresholdLow", "timeBeforeClose" -> "${value}分钟"
            "boxToPlatformTimeoutSeconds",
            "trayToSellPlatformTimeoutSeconds",
            "postSellSupplementGuardSeconds",
            "postSellIdleStableSeconds",
            "postSellIdleMaxWaitSeconds",
            "cameraPrewarmHoldSeconds" -> "${value}秒"

            else -> value.toString()
        }
    }

    private fun appendDiff(
        changes: MutableList<String>,
        label: String,
        oldValue: Any?,
        newValue: Any?
    ) {
        if (oldValue != newValue) {
            changes.add("$label: ${formatDiffValue(label, oldValue)}->${formatDiffValue(label, newValue)}")
        }
    }

    private fun onlineStatusText(value: Int): String = when (value) {
        0 -> "0（未启用）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "$value（未知）"
    }

    private fun scanBlockStatusText(value: Int): String = when (value) {
        AppConfigBean.SCAN_BLOCK_STATUS_NORMAL -> "0（正常展示）"
        AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> "1（隐藏二维码并禁止下单/支付）"
        else -> "$value（未知）"
    }

    private fun inspectionModeText(value: Int): String = when (value) {
        AppConfigBean.INSPECTION_MODE_NORMAL -> "0（正常）"
        AppConfigBean.INSPECTION_MODE_ACTIVE -> "1（检修中）"
        else -> "$value（未知）"
    }

    private fun canEnableAlgorithm(config: AppConfigBean): Boolean {
        return config.onlineStatus in listOf(1, 4) &&
            config.inspectionMode == AppConfigBean.INSPECTION_MODE_NORMAL &&
            config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL
    }

    private fun canApplyRemoteTemperatureConfig(config: AppConfigBean): Boolean {
        return config.onlineStatus in listOf(1, 4) &&
            config.errorStatus == AppConfigBean.ERROR_STATUS_NORMAL &&
            config.scanBlockStatus == AppConfigBean.SCAN_BLOCK_STATUS_NORMAL
    }

    private fun restStatusSourceText(value: String?): String = AppConfig.restStatusSourceText(value)

    private fun appendBusinessTimeDiff(
        changes: MutableList<String>,
        oldValue: BusinessTime?,
        newValue: BusinessTime?
    ) {
        val oldJson = JSON.toJSONString(oldValue)
        val newJson = JSON.toJSONString(newValue)
        if (oldJson != newJson) {
            changes.add("businessTime: $oldJson->$newJson")
        }
    }

    /**
     * 处理远端配置同步后的营业状态跃迁副作用。
     *
     * 约束：
     * - 进入 0/2/3（未启用/休息中/维护中）：自动关闭算法，但当前硬件流程允许自然收尾
     * - 离开维护中：若算法仍处于开启态且当前允许开启，则按烤盘现状恢复加热/保温
     */
    fun handleRemoteOnlineStatusTransition(
        previousOnlineStatus: Int,
        previousIsEnable: Int,
        previousRestStatusSource: String,
        mergedConfig: AppConfigBean,
        source: String,
        syncAlgorithmChangeToServer: Boolean = true
    ) {
        if (mergedConfig.onlineStatus == 2) {
            val normalizedPreviousRestSource = AppConfig.normalizeRestStatusSource(previousRestStatusSource)
            val shouldMarkManualRemote =
                previousOnlineStatus != 2 ||
                    normalizedPreviousRestSource !in setOf(
                        AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
                        AppConfigBean.REST_SOURCE_MANUAL_REMOTE
                    )
            if (shouldMarkManualRemote) {
                mergedConfig.restStatusSource = AppConfigBean.REST_SOURCE_MANUAL_REMOTE
                AppConfig.saveAppConfig(mergedConfig)
                logD(
                    "远端状态同步：检测到后台手动设置 onlineStatus（设备营业状态）=2（休息中），" +
                        "已标记 restStatusSource（休息中来源）=${restStatusSourceText(mergedConfig.restStatusSource)}，" +
                        "source=$source，previousOnlineStatus=${formatDiffValue("onlineStatus", previousOnlineStatus)}"
                )
            }
        }

        if (previousOnlineStatus in listOf(1, 4) && mergedConfig.onlineStatus in listOf(0, 2, 3)) {
            logD(
                "远端配置切出可开算法营业状态，自动关闭算法：" +
                    "source=$source, " +
                    "previousOnlineStatus=${formatDiffValue("onlineStatus", previousOnlineStatus)}, " +
                    "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                    "previousIsEnable=${formatDiffValue("isEnable", previousIsEnable)}, " +
                    "newIsEnable=${formatDiffValue("isEnable", 0)}"
            )
            KaoChangAlgorithm.disableRoastAlgorithm(
                reason = "远端配置切出运营/工作中(source=$source)",
                syncToServer = syncAlgorithmChangeToServer
            )
            return
        }

        if (previousOnlineStatus == 3 && mergedConfig.onlineStatus in listOf(1, 4)) {
            val currentIsEnable = AppConfig.getAlgorithmEnableFlag()
            if (!VMModbusHelper.connectStatus()) {
                logD(
                    "远端配置已离开维护中，但下位机未连接，保持算法关闭并跳过恢复加热：" +
                        "source=$source, " +
                        "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                        "currentIsEnable=${formatDiffValue("isEnable", currentIsEnable)}"
                )
                return
            }
            if (!KaoChangAlgorithm.isEnabled || currentIsEnable != 1 || !canEnableAlgorithm(mergedConfig)) {
                logD(
                    "远端配置已离开维护中，但当前条件不满足自动恢复加热：" +
                        "source=$source, " +
                        "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                        "inspectionMode（检修模式）=${inspectionModeText(mergedConfig.inspectionMode)}，" +
                        "errorStatus=${mergedConfig.errorStatus}，" +
                        "currentIsEnable=${formatDiffValue("isEnable", currentIsEnable)}"
                )
                return
            }
            helperScope.launch {
                KaoChangOperate.restoreHeatingStateFromCurrentPans("远端配置切回非维护状态且算法已开启")
            }
        }
    }

    fun applyRemoteRuntimeControl(
        content: String,
        source: String = MqttProtocol.SOURCE_MQTT_ACTION_2
    ): RuntimeControlApplyResult {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            logE("远端运行时控制解析失败：source=$source, error=${e.message}, raw=$content")
            val config = AppConfig.getAppConfig()
            return RuntimeControlApplyResult(
                controlSeq = -1L,
                applied = false,
                changed = false,
                reason = "parse_failed",
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = config.isEnable,
                restStatusSource = config.restStatusSource
            )
        }

        val controlSeq = json.getLongValue("controlSeq").takeIf { it > 0 } ?: json.getLongValue("ts")
        val incomingConfigVersion = json.getLong("configVersion")
        val runtimePatch = JSONObject()
        listOf(
            "onlineStatus",
            "scanBlockStatus",
            "isEnable",
            "modeType",
            "concurrencyMode",
            "transitionMode",
            "highConcurrencyResidualDrain"
        ).forEach { field ->
            if (json.containsKey(field)) {
                runtimePatch[field] = json.get(field)
            }
        }
        json.getJSONObject("configPatch")?.entries?.forEach { entry ->
            runtimePatch[entry.key] = entry.value
        }
        if (runtimePatch.isEmpty()) {
            val config = AppConfig.getAppConfig()
            logD(
                "远端运行时控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "不包含可应用字段，reason=missing_runtime_fields"
            )
            return RuntimeControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "missing_runtime_fields",
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = config.isEnable,
                restStatusSource = config.restStatusSource,
                configVersion = config.configVersion
            )
        }

        val currentConfigBeforeApply = AppConfig.getAppConfig()
        val rejectReason = RemoteRuntimeControlGuard.rejectReason(
            controlSeq = controlSeq,
            lastAppliedControlSeq = currentConfigBeforeApply.lastAppliedControlSeq,
            incomingConfigVersion = incomingConfigVersion,
            localConfigVersion = currentConfigBeforeApply.configVersion
        )
        if (rejectReason != null) {
            logW(
                "远端运行时控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "lastAppliedControlSeq=${currentConfigBeforeApply.lastAppliedControlSeq}，" +
                    "incomingConfigVersion=${incomingConfigVersion ?: "null"}，" +
                    "localConfigVersion=${currentConfigBeforeApply.configVersion}，reason=$rejectReason，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(currentConfigBeforeApply.onlineStatus)}，" +
                    "isEnable（烤肠算法开关）=${formatDiffValue("isEnable", currentConfigBeforeApply.isEnable)}"
            )
            return RuntimeControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = rejectReason,
                onlineStatus = currentConfigBeforeApply.onlineStatus,
                supplyStatus = currentConfigBeforeApply.supplyStatus,
                scanBlockStatus = currentConfigBeforeApply.scanBlockStatus,
                inspectionMode = currentConfigBeforeApply.inspectionMode,
                isEnable = currentConfigBeforeApply.isEnable,
                restStatusSource = currentConfigBeforeApply.restStatusSource,
                configVersion = currentConfigBeforeApply.configVersion
            )
        }

        val config = currentConfigBeforeApply
        val previousOnlineStatus = config.onlineStatus
        val previousSupplyStatus = config.supplyStatus
        val previousScanBlockStatus = config.scanBlockStatus
        val previousInspectionMode = config.inspectionMode
        val previousIsEnable = config.isEnable
        val previousModeType = config.modeType
        val previousTransitionMode = config.transitionMode
        val previousHighConcurrencyResidualDrain = config.highConcurrencyResidualDrain
        val previousRestStatusSource = config.restStatusSource
        val operator = json.getString("operator").orEmpty()

        val patchResult = RemoteRuntimeConfigPatchApplier.apply(config, runtimePatch)
        if (!patchResult.hasApplied) {
            val reason = if (patchResult.unknownFields.isNotEmpty()) "unknown_fields" else "invalid_value"
            logW(
                "远端运行时控制未应用：source=$source，controlSeq=$controlSeq，reason=$reason，" +
                    "rejectedFields=${(patchResult.rejectedFields + patchResult.unknownFields).joinToString(",")}"
            )
            return RuntimeControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = reason,
                onlineStatus = config.onlineStatus,
                supplyStatus = config.supplyStatus,
                scanBlockStatus = config.scanBlockStatus,
                inspectionMode = config.inspectionMode,
                isEnable = config.isEnable,
                restStatusSource = config.restStatusSource,
                configVersion = config.configVersion,
                rejectedFields = patchResult.rejectedFields + patchResult.unknownFields,
                normalizedFields = patchResult.normalizedFields
            )
        }

        AppConfig.alignRestStatusSource(config)
        AppConfig.saveAppConfig(config)

        if (patchResult.appliedFields.contains("onlineStatus")) {
            handleRemoteOnlineStatusTransition(
                previousOnlineStatus = previousOnlineStatus,
                previousIsEnable = previousIsEnable,
                previousRestStatusSource = previousRestStatusSource,
                mergedConfig = config,
                source = source,
                syncAlgorithmChangeToServer = false
            )
        }

        if (config.onlineStatus == 3) {
            KaoChangAlgorithm.disableRoastAlgorithm(
                reason = "远端MQTT运行时控制要求维护中(source=$source, operator=${operator.ifBlank { "unknown" }})",
                syncToServer = false
            )
        } else if (patchResult.appliedFields.contains("isEnable")) {
            when (config.isEnable) {
                1 -> KaoChangAlgorithm.enableRoastAlgorithm(
                    reason = "远端MQTT运行时控制开启算法(source=$source, operator=${operator.ifBlank { "unknown" }})",
                    syncToServer = false
                )
                else -> KaoChangAlgorithm.disableRoastAlgorithm(
                    reason = "远端MQTT运行时控制关闭算法(source=$source, operator=${operator.ifBlank { "unknown" }})",
                    syncToServer = false
                )
            }
        }

        val currentConfig = AppConfig.getAppConfig()
        if (incomingConfigVersion != null && incomingConfigVersion >= currentConfig.configVersion) {
            currentConfig.configVersion = incomingConfigVersion
        }
        if (controlSeq > 0) {
            currentConfig.lastAppliedControlSeq = maxOf(currentConfig.lastAppliedControlSeq, controlSeq)
        }
        AppConfig.saveAppConfig(currentConfig)

        if (patchResult.temperatureChanged && canApplyRemoteTemperatureConfig(currentConfig)) {
            KaoChangOperate.applyConfiguredTemperatureByPanState("MQTT runtime_control 后台温度配置变化")
        } else if (patchResult.temperatureChanged) {
            logW(
                "MQTT 温度配置已变化，但设备当前不可加热，跳过重刷温度寄存器：" +
                    "source=$source，onlineStatus（设备营业状态）=${onlineStatusText(currentConfig.onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${currentConfig.errorStatus}，" +
                    "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(currentConfig.scanBlockStatus)}"
            )
        }

        val changed =
            previousOnlineStatus != currentConfig.onlineStatus ||
                previousSupplyStatus != currentConfig.supplyStatus ||
                previousScanBlockStatus != currentConfig.scanBlockStatus ||
                previousInspectionMode != currentConfig.inspectionMode ||
                previousIsEnable != currentConfig.isEnable ||
                previousModeType != currentConfig.modeType ||
                previousTransitionMode != currentConfig.transitionMode ||
                previousHighConcurrencyResidualDrain != currentConfig.highConcurrencyResidualDrain ||
                patchResult.temperatureChanged

        val rejectedFields = patchResult.rejectedFields + patchResult.unknownFields
        val reason = if (patchResult.isPartial) "partial" else "applied"
        logD(
            "远端运行时控制已应用：" +
                "source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                "configVersion（配置版本）=${incomingConfigVersion ?: "null"}->${currentConfig.configVersion}，" +
                "reason=$reason，appliedFields=${patchResult.appliedFields.joinToString(",")}，" +
                "rejectedFields=${rejectedFields.joinToString(",")}，normalizedFields=${patchResult.normalizedFields}，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(currentConfig.onlineStatus)}，" +
                "supplyStatus（补货状态）=${formatDiffValue("supplyStatus", previousSupplyStatus)}->${formatDiffValue("supplyStatus", currentConfig.supplyStatus)}，" +
                "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(previousScanBlockStatus)}->${scanBlockStatusText(currentConfig.scanBlockStatus)}，" +
                "inspectionMode（检修模式）=${inspectionModeText(previousInspectionMode)}->${inspectionModeText(currentConfig.inspectionMode)}，" +
                "isEnable（烤肠算法开关）=${formatDiffValue("isEnable", previousIsEnable)}->${formatDiffValue("isEnable", currentConfig.isEnable)}，" +
                "modeType（并发模式）=${formatDiffValue("modeType", previousModeType)}->${formatDiffValue("modeType", currentConfig.modeType)}，" +
                "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", previousTransitionMode)}->${formatDiffValue("transitionMode", currentConfig.transitionMode)}，" +
                "highConcurrencyResidualDrain（高并发残留消化态）=${previousHighConcurrencyResidualDrain}->${currentConfig.highConcurrencyResidualDrain}，" +
                "restStatusSource（休息中来源）=${restStatusSourceText(previousRestStatusSource)}->${restStatusSourceText(currentConfig.restStatusSource)}"
        )
        return RuntimeControlApplyResult(
            controlSeq = controlSeq,
            applied = true,
            changed = changed,
            reason = reason,
            onlineStatus = currentConfig.onlineStatus,
            supplyStatus = currentConfig.supplyStatus,
            scanBlockStatus = currentConfig.scanBlockStatus,
            inspectionMode = currentConfig.inspectionMode,
            isEnable = currentConfig.isEnable,
            restStatusSource = currentConfig.restStatusSource,
            configVersion = currentConfig.configVersion,
            appliedFields = patchResult.appliedFields,
            rejectedFields = rejectedFields,
            normalizedFields = patchResult.normalizedFields
        )
    }

    fun applyRemoteConcurrencyControl(
        content: String,
        source: String = MqttProtocol.SOURCE_MQTT_ACTION_2
    ): ConcurrencyControlApplyResult {
        val json = try {
            JSON.parseObject(content)
        } catch (e: Exception) {
            logE("远端并发控制解析失败：source=$source, error=${e.message}, raw=$content")
            val config = AppConfig.getAppConfig()
            return ConcurrencyControlApplyResult(
                controlSeq = -1L,
                applied = false,
                changed = false,
                reason = "parse_failed",
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val controlSeq = json.getLongValue("controlSeq").takeIf { it > 0 } ?: json.getLongValue("ts")
        val hasModeType = json.containsKey("modeType")
        val hasTransitionMode = json.containsKey("transitionMode")
        if (!hasModeType && !hasTransitionMode) {
            val config = AppConfig.getAppConfig()
            logD(
                "远端并发控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "不包含 modeType（并发模式）或 transitionMode（并发切换过渡态）"
            )
            return ConcurrencyControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "missing_concurrency_fields",
                modeType = config.modeType,
                transitionMode = config.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val currentConfigBeforeApply = AppConfig.getAppConfig()
        if (controlSeq > 0 && lastAppliedConcurrencyControlSeq >= 0 && controlSeq <= lastAppliedConcurrencyControlSeq) {
            logW(
                "远端并发控制已忽略：source=$source，controlSeq=$controlSeq，" +
                    "lastAppliedConcurrencyControlSeq=$lastAppliedConcurrencyControlSeq，reason=stale_control_seq，" +
                    "modeType（并发模式）=${formatDiffValue("modeType", currentConfigBeforeApply.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", currentConfigBeforeApply.transitionMode)}"
            )
            return ConcurrencyControlApplyResult(
                controlSeq = controlSeq,
                applied = false,
                changed = false,
                reason = "stale_control_seq",
                modeType = currentConfigBeforeApply.modeType,
                transitionMode = currentConfigBeforeApply.transitionMode,
                overrodeConflictingTransition = false
            )
        }

        val previousModeType = currentConfigBeforeApply.modeType
        val previousTransitionMode = currentConfigBeforeApply.transitionMode
        val previousResidualDrain = currentConfigBeforeApply.highConcurrencyResidualDrain
        val requestedModeType = json.getInteger("modeType") ?: previousModeType
        val requestedTransitionMode = json.getInteger("transitionMode")
        val operator = json.getString("operator").orEmpty()
        val resolvedModeType = requestedModeType.takeIf { it == 1 || it == 2 }
            ?: previousModeType.takeIf { it == 1 || it == 2 }
            ?: 1
        val resolvedTransitionMode = 0
        val resolvedResidualDrain = HighConcurrencyResidualDrainEvaluator.evaluate(
            modeType = resolvedModeType,
            kaoPanList = KaoPanHelper.getKaoPanList(),
            includeLowHeatOverlapArea = resolvedModeType == 1 &&
                (previousModeType == 2 || previousTransitionMode != 0 || previousResidualDrain == 1)
        )

        currentConfigBeforeApply.modeType = resolvedModeType
        currentConfigBeforeApply.transitionMode = resolvedTransitionMode
        currentConfigBeforeApply.highConcurrencyResidualDrain = resolvedResidualDrain
        AppConfig.saveAppConfig(currentConfigBeforeApply)

        val currentConfig = AppConfig.getAppConfig()
        val changed =
            previousModeType != currentConfig.modeType ||
                previousTransitionMode != currentConfig.transitionMode ||
                previousResidualDrain != currentConfig.highConcurrencyResidualDrain

        if (controlSeq > 0) {
            lastAppliedConcurrencyControlSeq = maxOf(lastAppliedConcurrencyControlSeq, controlSeq)
        }

        val overrodeConflictingTransition = previousTransitionMode != 0 && currentConfig.transitionMode == 0
        if (overrodeConflictingTransition) {
            logW(
                "远端并发控制覆盖了本地过渡态：source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                    "后台目标 modeType（并发模式）=${formatDiffValue("modeType", currentConfig.modeType)}，" +
                    "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", currentConfig.transitionMode)}，" +
                    "策略=以后台最新目标为准"
            )
        }
        if (requestedModeType != currentConfig.modeType || (requestedTransitionMode ?: 0) != currentConfig.transitionMode) {
            logW(
                "远端并发控制已按后台手动稳态语义归一化：source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                    "请求 modeType（并发模式）=${formatDiffValue("modeType", requestedModeType)}，" +
                    "请求 transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", requestedTransitionMode ?: 0)}，" +
                    "落地 modeType（并发模式）=${formatDiffValue("modeType", currentConfig.modeType)}，" +
                    "落地 transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", currentConfig.transitionMode)}，" +
                    "highConcurrencyResidualDrain（高并发残留消化态）=${currentConfig.highConcurrencyResidualDrain}"
            )
        }

        logD(
            "远端并发控制已应用：" +
                "source=$source，controlSeq=$controlSeq，operator=${operator.ifBlank { "unknown" }}，" +
                "modeType（并发模式）=${formatDiffValue("modeType", previousModeType)}->${formatDiffValue("modeType", currentConfig.modeType)}，" +
                "transitionMode（并发切换过渡态）=${formatDiffValue("transitionMode", previousTransitionMode)}->${formatDiffValue("transitionMode", currentConfig.transitionMode)}，" +
                "highConcurrencyResidualDrain（高并发残留消化态）=$previousResidualDrain->${currentConfig.highConcurrencyResidualDrain}"
        )
        return ConcurrencyControlApplyResult(
            controlSeq = controlSeq,
            applied = true,
            changed = changed,
            reason = "applied",
            modeType = currentConfig.modeType,
            transitionMode = currentConfig.transitionMode,
            overrodeConflictingTransition = overrodeConflictingTransition
        )
    }

    fun dataSyncServer() {
        refreshStockData()
        getDeviceInfo()
    }

    fun refreshStockData(
        logPrefix: String = LOG_SYNC,
        onResult: ((success: Boolean, stockCount: Int) -> Unit)? = null
    ) {
        NetApi.getStockData { code, content ->
            val kaoPanBoxList = mutableListOf<KaoPanBox>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json?.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    for (i in 0 until jsonData.size) {
                        val item = jsonData.getJSONObject(i)
                        val kaoPanBox = KaoPanBox().apply {
                            id = item.getIntValue("id")
                            deviceId = item.getString("deviceId")
                            productId = item.getIntValue("productId").toString()
                            positionSn = item.getIntValue("positionSn")
                            lastPositionSn = item.getIntValue("lastPositionSn")
                            num = maxOf(0, item.getIntValue("num"))
                            tasteCode = item.getString("tasteCode")
                            tasteName = item.getString("tasteName")
                            cmdValueTake = item.getIntValue("cmdValueTake")
                            cmdStatusTake = item.getIntValue("cmdStatusTake")
                        }
                        kaoPanBoxList.add(kaoPanBox)
                    }
                    saveKaoPanBoxList(kaoPanBoxList)
                    logD("[$logPrefix] 烤肠箱库存同步完成：箱位数=${kaoPanBoxList.size}")
                    onResult?.invoke(true, kaoPanBoxList.size)
                    return@getStockData
                }
                logE("[$logPrefix] 烤肠箱库存业务返回异常：content=$content")
            } else {
                ToastUtils.showShort("网络错误")
                logE("[$logPrefix] 同步烤肠箱库存失败：code=$code, content=$content")
            }
            onResult?.invoke(false, 0)
        }
    }

    fun uploadDataToServer() {
        val metadataNormalized = KaoPanHelper.normalizeKaoPanTasteMetadata()
        if (metadataNormalized) {
            logD("上传前已统一烤盘口味元数据")
        }
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val kaoPanPayload = JSON.toJSONString(kaoPanList)
        val kaoPanCount = kaoPanList.size
        val pendingDiscardPositions = AppConfig.getAppConfig().pendingDiscardPositions
        val warmingCount = kaoPanList.count { KaoPanHelper.isSellable(it, pendingDiscardPositions) }
        val bakingCount = kaoPanList.count { it.isHasSausage && it.status == 1 }

        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        val stockPayload = JSON.toJSONString(kaoPanBoxList)
        val stockItemCount = kaoPanBoxList.size
        val stockTotalCount = kaoPanBoxList.sumOf { box -> box.num }
        val sequence = CloudSnapshotSequenceStore.nextSequence()
        val reportTime = System.currentTimeMillis()
        val snapshotPayload = JSONObject()
        snapshotPayload["sequence"] = sequence
        snapshotPayload["reportTime"] = reportTime
        snapshotPayload["kaoPanList"] = kaoPanPayload
        snapshotPayload["stockList"] = stockPayload
        val snapshotSummary =
            "sequence（设备快照版本号）=$sequence, " +
                "reportTime（设备本机上报时间）=$reportTime, " +
                "烤盘总数=$kaoPanCount, 烤制中=$bakingCount, 保温中=$warmingCount, " +
                "箱位数=$stockItemCount, 总库存=$stockTotalCount"

        logPendingRetryIfNeeded(CloudSyncRetryStore.Type.SNAPSHOT)
        logPendingRetryIfNeeded(CloudSyncRetryStore.Type.KAO_PAN)
        logPendingRetryIfNeeded(CloudSyncRetryStore.Type.STOCK)
        NetApi.reportSnapshot(
            kaoPanList = kaoPanPayload,
            stockList = stockPayload,
            sequence = sequence,
            reportTime = reportTime
        ) { code, content ->
            if (code != 0) {
                recordCloudSyncFailure(
                    type = CloudSyncRetryStore.Type.SNAPSHOT,
                    payload = snapshotPayload.toJSONString(),
                    error = "code=$code, content=$content"
                )
                logE(
                    "设备数据快照同步失败：" +
                        "$snapshotSummary, code=$code, cloudResponse（云端原始响应）=$content"
                )
            } else {
                clearCloudSyncFailureIfNeeded(CloudSyncRetryStore.Type.SNAPSHOT)
                clearCloudSyncFailureIfNeeded(CloudSyncRetryStore.Type.KAO_PAN)
                clearCloudSyncFailureIfNeeded(CloudSyncRetryStore.Type.STOCK)
                logD(
                    "设备数据快照已同步：" +
                        "$snapshotSummary, cloudResponse（云端原始响应）=$content"
                )
            }
        }
    }

    private fun logPendingRetryIfNeeded(type: CloudSyncRetryStore.Type) {
        val pending = CloudSyncRetryStore.getPending(type) ?: return
        logD(
            "检测到待补传，正在用最新快照补传：" +
                "type=${CloudSyncRetryStore.typeText(type)}，" +
                "savedAt=${pending.savedAtMillis}，failureCount=${pending.failureCount}"
        )
    }

    private fun recordCloudSyncFailure(
        type: CloudSyncRetryStore.Type,
        payload: String,
        error: String
    ) {
        val pending = CloudSyncRetryStore.recordFailure(type, payload, error)
        logCloudSyncFile(
            "HTTP上传失败，已持久化待补传：" +
                "type=${CloudSyncRetryStore.typeText(type)}，" +
                "failureCount=${pending.failureCount}，" +
                "lastFailureAt=${pending.lastFailureAtMillis}，" +
                "lastError=${pending.lastError}"
        )
    }

    private fun clearCloudSyncFailureIfNeeded(type: CloudSyncRetryStore.Type) {
        val cleared = CloudSyncRetryStore.markSucceeded(type) ?: return
        logCloudSyncFile(
            "HTTP待补传已成功清除：" +
                "type=${CloudSyncRetryStore.typeText(type)}，" +
                "failureCount=${cleared.failureCount}，" +
                "savedAt=${cleared.savedAtMillis}"
        )
    }

    fun getDeviceInfo() {
        NetApi.getKcProductList { code, content ->
            val tasteList = mutableListOf<Taste>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json?.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    for (i in 0 until jsonData.size) {
                        val item = jsonData.getJSONObject(i)
                        val taste = Taste(
                            item.getIntValue("tasteId"),
                            item.getString("tasteCode"),
                            item.getString("tasteName"),
                            item.getIntValue("id"),
                            item.getString("name")
                        )
                        tasteList.add(taste)
                    }
                    saveTasteList(tasteList)
                }
            } else {
                ToastUtils.showShort("网络错误")
                logE("同步口味列表失败：code=$code, content=$content")
            }
        }

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) deviceInfoCallback@{ code, content ->
            if (code != 0) {
                logE("设备配置获取失败：code=$code, content=$content")
                return@deviceInfoCallback
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("设备配置响应解析失败：raw=$content")
                e.printStackTrace()
                return@deviceInfoCallback
            }

            if (json == null) {
                logE("设备配置响应为空：raw=$content")
                return@deviceInfoCallback
            }

            if (json.getIntValue("code") != 200) {
                logE("设备配置业务返回失败：code=${json.getIntValue("code")}, content=$content")
                return@deviceInfoCallback
            }

            val appConfigBean = AppConfig.getAppConfig()
            val previousOnlineStatus = appConfigBean.onlineStatus
            val previousIsEnable = AppConfig.getAlgorithmEnableFlag()
            val previousRestStatusSource = AppConfig.normalizeRestStatusSource(appConfigBean.restStatusSource)

            val oldBusinessTime = appConfigBean.businessTime
            val oldStatus = appConfigBean.status
            val oldOnlineStatus = appConfigBean.onlineStatus
            val oldSupplyStatus = appConfigBean.supplyStatus
            val oldScanBlockStatus = appConfigBean.scanBlockStatus
            val oldInspectionMode = appConfigBean.inspectionMode
            val oldModeType = appConfigBean.modeType
            val oldTransitionMode = appConfigBean.transitionMode
            val oldKeepWarmTemperature = appConfigBean.keepWarmTemperature
            val oldHeatingTemperature = appConfigBean.heatingTemperature
            val oldZone1HeatingOffset = appConfigBean.zone1HeatingOffset
            val oldZone2HeatingOffset = appConfigBean.zone2HeatingOffset
            val oldZone3HeatingOffset = appConfigBean.zone3HeatingOffset
            val oldBakingTime = appConfigBean.bakingTime
            val oldDiscardTime = appConfigBean.discardTime
            val oldTimeThresholdLow = appConfigBean.timeThresholdLow
            val oldTimeBeforeClose = appConfigBean.timeBeforeClose
            val oldBoxToPlatformTimeoutSeconds = appConfigBean.boxToPlatformTimeoutSeconds
            val oldTrayToSellPlatformTimeoutSeconds = appConfigBean.trayToSellPlatformTimeoutSeconds
            val oldPostSellSupplementGuardSeconds = appConfigBean.postSellSupplementGuardSeconds
            val oldPostSellIdleStableSeconds = appConfigBean.postSellIdleStableSeconds
            val oldPostSellIdleMaxWaitSeconds = appConfigBean.postSellIdleMaxWaitSeconds
            val oldCameraPrewarmHoldSeconds = appConfigBean.cameraPrewarmHoldSeconds

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, LOG_SYNC)
            if (!mergeResult.applied) {
                return@deviceInfoCallback
            }

            val mergedConfig = AppConfig.getAppConfig()
            val temperatureConfigChanged =
                oldKeepWarmTemperature != mergedConfig.keepWarmTemperature ||
                    oldHeatingTemperature != mergedConfig.heatingTemperature ||
                    oldZone1HeatingOffset != mergedConfig.zone1HeatingOffset ||
                    oldZone2HeatingOffset != mergedConfig.zone2HeatingOffset ||
                    oldZone3HeatingOffset != mergedConfig.zone3HeatingOffset
            val changes = mutableListOf<String>()
            appendBusinessTimeDiff(changes, oldBusinessTime, mergedConfig.businessTime)
            appendDiff(changes, "status", oldStatus, mergedConfig.status)
            appendDiff(changes, "onlineStatus", oldOnlineStatus, mergedConfig.onlineStatus)
            appendDiff(changes, "supplyStatus", oldSupplyStatus, mergedConfig.supplyStatus)
            appendDiff(changes, "scanBlockStatus", oldScanBlockStatus, mergedConfig.scanBlockStatus)
            appendDiff(changes, "inspectionMode", oldInspectionMode, mergedConfig.inspectionMode)
            appendDiff(changes, "modeType", oldModeType, mergedConfig.modeType)
            appendDiff(changes, "transitionMode", oldTransitionMode, mergedConfig.transitionMode)
            appendDiff(changes, "keepWarmTemperature", oldKeepWarmTemperature, mergedConfig.keepWarmTemperature)
            appendDiff(changes, "heatingTemperature", oldHeatingTemperature, mergedConfig.heatingTemperature)
            appendDiff(changes, "zone1HeatingOffset", oldZone1HeatingOffset, mergedConfig.zone1HeatingOffset)
            appendDiff(changes, "zone2HeatingOffset", oldZone2HeatingOffset, mergedConfig.zone2HeatingOffset)
            appendDiff(changes, "zone3HeatingOffset", oldZone3HeatingOffset, mergedConfig.zone3HeatingOffset)
            appendDiff(changes, "bakingTime", oldBakingTime, mergedConfig.bakingTime)
            appendDiff(changes, "discardTime", oldDiscardTime, mergedConfig.discardTime)
            appendDiff(changes, "timeThresholdLow", oldTimeThresholdLow, mergedConfig.timeThresholdLow)
            appendDiff(changes, "timeBeforeClose", oldTimeBeforeClose, mergedConfig.timeBeforeClose)
            appendDiff(
                changes,
                "boxToPlatformTimeoutSeconds",
                oldBoxToPlatformTimeoutSeconds,
                mergedConfig.boxToPlatformTimeoutSeconds
            )
            appendDiff(
                changes,
                "trayToSellPlatformTimeoutSeconds",
                oldTrayToSellPlatformTimeoutSeconds,
                mergedConfig.trayToSellPlatformTimeoutSeconds
            )
            appendDiff(
                changes,
                "postSellSupplementGuardSeconds",
                oldPostSellSupplementGuardSeconds,
                mergedConfig.postSellSupplementGuardSeconds
            )
            appendDiff(
                changes,
                "postSellIdleStableSeconds",
                oldPostSellIdleStableSeconds,
                mergedConfig.postSellIdleStableSeconds
            )
            appendDiff(
                changes,
                "postSellIdleMaxWaitSeconds",
                oldPostSellIdleMaxWaitSeconds,
                mergedConfig.postSellIdleMaxWaitSeconds
            )
            appendDiff(
                changes,
                "cameraPrewarmHoldSeconds",
                oldCameraPrewarmHoldSeconds,
                mergedConfig.cameraPrewarmHoldSeconds
            )

            if (changes.isNotEmpty()) {
                logD("远端配置已更新：${changes.joinToString(", ")}")
            }

            reconcileDiscardCloseTime(LOG_SYNC, oldDiscardTime, mergedConfig)

            handleRemoteOnlineStatusTransition(
                previousOnlineStatus,
                previousIsEnable,
                previousRestStatusSource,
                mergedConfig,
                "getDeviceInfo"
            )
            if (temperatureConfigChanged && canApplyRemoteTemperatureConfig(mergedConfig)) {
                KaoChangOperate.applyConfiguredTemperatureByPanState("getDeviceInfo 后台温度配置变化")
            } else if (temperatureConfigChanged) {
                logW(
                    "后台温度配置已变化，但设备当前不可加热，跳过重刷温度寄存器：" +
                        "source=getDeviceInfo，onlineStatus（设备营业状态）=${onlineStatusText(mergedConfig.onlineStatus)}，" +
                        "errorStatus（设备故障状态）=${mergedConfig.errorStatus}，" +
                        "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(mergedConfig.scanBlockStatus)}"
                )
            }
            refreshStockData(logPrefix = "$LOG_SYNC-stock-after-config")
        }
    }

    fun refreshRemoteDeviceState(
        logPrefix: String = "$LOG_SYNC-home",
        onResult: ((result: RemoteDeviceStateRefreshResult) -> Unit)? = null
    ) {
        val appConfigBean = AppConfig.getAppConfig()
        val oldStatus = appConfigBean.status
        val oldOnlineStatus = appConfigBean.onlineStatus
        val oldSupplyStatus = appConfigBean.supplyStatus
        val oldScanBlockStatus = appConfigBean.scanBlockStatus
        val oldInspectionMode = appConfigBean.inspectionMode
        val oldQcodeUrl = appConfigBean.qcodeURL
        val oldAvailable = appConfigBean.available
        val oldAvailableCountdownLatched = appConfigBean.availableCountdownLatched
        val oldDiscardTime = appConfigBean.discardTime
        val oldKeepWarmTemperature = appConfigBean.keepWarmTemperature
        val oldHeatingTemperature = appConfigBean.heatingTemperature
        val oldZone1HeatingOffset = appConfigBean.zone1HeatingOffset
        val oldZone2HeatingOffset = appConfigBean.zone2HeatingOffset
        val oldZone3HeatingOffset = appConfigBean.zone3HeatingOffset
        val previousIsEnable = AppConfig.getAlgorithmEnableFlag()
        val previousRestStatusSource = AppConfig.normalizeRestStatusSource(appConfigBean.restStatusSource)

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) refreshStateCallback@{ code, content ->
            if (code != 0) {
                logE("$logPrefix 失败：HTTP请求失败，code=$code, content=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("$logPrefix 失败：响应解析异常，raw=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            if (json == null || json.getIntValue("code") != 200) {
                logE("$logPrefix 失败：业务返回异常，raw=$content")
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, logPrefix)
            if (!mergeResult.applied) {
                onResult?.invoke(
                    RemoteDeviceStateRefreshResult(
                        success = false,
                        changed = false,
                        onlineStatus = oldOnlineStatus,
                        supplyStatus = oldSupplyStatus,
                        scanBlockStatus = oldScanBlockStatus,
                        inspectionMode = oldInspectionMode,
                        isEnable = previousIsEnable,
                        restStatusSource = previousRestStatusSource
                    )
                )
                return@refreshStateCallback
            }

            val mergedConfig = AppConfig.getAppConfig()
            val temperatureConfigChanged =
                oldKeepWarmTemperature != mergedConfig.keepWarmTemperature ||
                    oldHeatingTemperature != mergedConfig.heatingTemperature ||
                    oldZone1HeatingOffset != mergedConfig.zone1HeatingOffset ||
                    oldZone2HeatingOffset != mergedConfig.zone2HeatingOffset ||
                    oldZone3HeatingOffset != mergedConfig.zone3HeatingOffset
            reconcileDiscardCloseTime(logPrefix, oldDiscardTime, mergedConfig)
            handleRemoteOnlineStatusTransition(
                oldOnlineStatus,
                previousIsEnable,
                previousRestStatusSource,
                mergedConfig,
                "refreshHomeDeviceState"
            )
            val changed =
                oldStatus != mergedConfig.status ||
                    oldOnlineStatus != mergedConfig.onlineStatus ||
                    oldSupplyStatus != mergedConfig.supplyStatus ||
                    oldScanBlockStatus != mergedConfig.scanBlockStatus ||
                    oldInspectionMode != mergedConfig.inspectionMode ||
                    oldQcodeUrl != mergedConfig.qcodeURL ||
                    oldAvailable != mergedConfig.available ||
                    oldAvailableCountdownLatched != mergedConfig.availableCountdownLatched ||
                    temperatureConfigChanged

            if (changed) {
                logD(
                    "$logPrefix 成功：首页相关配置发生变化：" +
                        "status（设备启用状态）=${formatDiffValue("status", oldStatus)}->${formatDiffValue("status", mergedConfig.status)}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(oldOnlineStatus)}->${onlineStatusText(mergedConfig.onlineStatus)}，" +
                        "supplyStatus（补货状态）=${formatDiffValue("supplyStatus", oldSupplyStatus)}->${formatDiffValue("supplyStatus", mergedConfig.supplyStatus)}，" +
                        "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(oldScanBlockStatus)}->${scanBlockStatusText(mergedConfig.scanBlockStatus)}，" +
                        "inspectionMode（检修模式）=${inspectionModeText(oldInspectionMode)}->${inspectionModeText(mergedConfig.inspectionMode)}，" +
                        "qrcodeChanged（二维码地址是否变化）=${oldQcodeUrl != mergedConfig.qcodeURL}，" +
                        "available（二维码页预计时间）=${oldAvailable}->${mergedConfig.available}，" +
                        "availableCountdownLatched（二维码页预计时间锁存标记）=${oldAvailableCountdownLatched}->${mergedConfig.availableCountdownLatched}，" +
                        "keepWarmTemperature（保温温度）=${oldKeepWarmTemperature}℃->${mergedConfig.keepWarmTemperature}℃，" +
                        "heatingTemperature（加热温度）=${oldHeatingTemperature}℃->${mergedConfig.heatingTemperature}℃，" +
                        "zone1HeatingOffset（一区烤制补偿）=${oldZone1HeatingOffset}℃->${mergedConfig.zone1HeatingOffset}℃，" +
                        "zone2HeatingOffset（二区烤制补偿）=${oldZone2HeatingOffset}℃->${mergedConfig.zone2HeatingOffset}℃，" +
                        "zone3HeatingOffset（三区烤制补偿）=${oldZone3HeatingOffset}℃->${mergedConfig.zone3HeatingOffset}℃"
                )
            }
            if (temperatureConfigChanged && canApplyRemoteTemperatureConfig(mergedConfig)) {
                KaoChangOperate.applyConfiguredTemperatureByPanState("$logPrefix 后台温度配置变化")
            } else if (temperatureConfigChanged) {
                logW(
                    "后台温度配置已变化，但设备当前不可加热，跳过重刷温度寄存器：" +
                        "source=$logPrefix，onlineStatus（设备营业状态）=${onlineStatusText(mergedConfig.onlineStatus)}，" +
                        "errorStatus（设备故障状态）=${mergedConfig.errorStatus}，" +
                        "scanBlockStatus（前台屏蔽状态）=${scanBlockStatusText(mergedConfig.scanBlockStatus)}"
                )
            }

            onResult?.invoke(
                RemoteDeviceStateRefreshResult(
                    success = true,
                    changed = changed,
                    onlineStatus = mergedConfig.onlineStatus,
                    supplyStatus = mergedConfig.supplyStatus,
                    scanBlockStatus = mergedConfig.scanBlockStatus,
                    inspectionMode = mergedConfig.inspectionMode,
                    isEnable = AppConfig.getAlgorithmEnableFlag(),
                    restStatusSource = AppConfig.normalizeRestStatusSource(mergedConfig.restStatusSource)
                )
            )
        }
    }

    fun refreshHomeDeviceState(onResult: ((changed: Boolean) -> Unit)? = null) {
        refreshRemoteDeviceState { result ->
            onResult?.invoke(result.changed)
        }
    }

    fun uploadDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        NetApi.updateDeviceInfo(callback)
    }
}
