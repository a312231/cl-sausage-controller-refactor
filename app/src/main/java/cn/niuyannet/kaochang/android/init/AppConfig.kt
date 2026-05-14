package cn.niuyannet.kaochang.android.init

import android.provider.Settings
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject

object AppConfig {
    const val Sp_File_URL = "KAOCHANG_APP_CONFIG"
    const val DEFAULT_BOX_REFILL_TARGET_NUM = 10
    const val MIN_BOX_REFILL_TARGET_NUM = 1
    const val MAX_BOX_REFILL_TARGET_NUM = 15
    const val MIN_HEATING_OFFSET = -20
    const val MAX_HEATING_OFFSET = 20
    const val DEFAULT_FAULT_BAKING_PAUSE_ABORT_MS = 2L * 60L * 60L * 1000L
    const val DEFAULT_FAULT_PAUSE_ANOMALY_MS = 4L * 60L * 60L * 1000L
    const val DEFAULT_HTTP_FALLBACK_MIN_INTERVAL_MS = 30_000L
    private const val KEY_APP_CONFIG_OBJECT = "sp_app_config"
    private const val KEY_APP_CONFIG_JSON = "sp_app_config_json"
    private const val KEY_INSPECTION_SNAPSHOT_JSON = "sp_inspection_snapshot_json"
    private val BENIGN_PRESERVED_FIELDS = setOf("available", "availableCountdownLatched")
    private val LOCAL_RUNTIME_ONLY_FIELDS = setOf(
        "faultHeatPauseActive",
        "faultHeatPauseStartedAtMs",
        "faultHeatPauseReason",
        "faultHeatPauseSource",
        "pendingDiscardPositions",
        "foodSafetyAlertCode",
        "foodSafetyAlertReason"
    )
    private var appConfigBean: AppConfigBean? = null

    data class RemoteConfigMergeResult(
        val applied: Boolean,
        val usedLocalFallback: Boolean,
        val message: String
    )

    data class InspectionSnapshot(
        val savedAt: Long,
        val configJson: String,
        val kaoPanJson: String,
        val kaoPanBoxJson: String
    )

    fun normalizeRestStatusSource(restStatusSource: String?): String {
        return when (restStatusSource) {
            AppConfigBean.REST_SOURCE_NONE,
            AppConfigBean.REST_SOURCE_UNKNOWN,
            AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS,
            AppConfigBean.REST_SOURCE_MANUAL_REMOTE,
            AppConfigBean.REST_SOURCE_MANUAL_LOCAL -> restStatusSource
            else -> AppConfigBean.REST_SOURCE_UNKNOWN
        }
    }

    fun restStatusSourceText(restStatusSource: String?): String = when (normalizeRestStatusSource(restStatusSource)) {
        AppConfigBean.REST_SOURCE_NONE -> "NONE（当前不是休息中）"
        AppConfigBean.REST_SOURCE_UNKNOWN -> "UNKNOWN（来源未知/历史旧数据）"
        AppConfigBean.REST_SOURCE_AUTO_END_BUSINESS -> "AUTO_END_BUSINESS（系统自动营业结束）"
        AppConfigBean.REST_SOURCE_MANUAL_REMOTE -> "MANUAL_REMOTE（后台手动设置休息中）"
        AppConfigBean.REST_SOURCE_MANUAL_LOCAL -> "MANUAL_LOCAL（本地人工设置休息中）"
        else -> "${restStatusSource ?: "null"}（未知）"
    }

    fun alignRestStatusSource(config: AppConfigBean) {
        config.restStatusSource = when (config.onlineStatus) {
            2 -> normalizeRestStatusSource(config.restStatusSource)
            else -> AppConfigBean.REST_SOURCE_NONE
        }
    }

    fun getTopicDeviceIdUrl(url: String): String {
        return url.replace("{device_id}", getDeviceId())
    }

    fun normalizeBoxRefillTargetNum(value: Int): Int {
        return if (value in MIN_BOX_REFILL_TARGET_NUM..MAX_BOX_REFILL_TARGET_NUM) {
            value
        } else {
            DEFAULT_BOX_REFILL_TARGET_NUM
        }
    }

    fun isValidBoxRefillTargetNum(value: Int): Boolean {
        return value in MIN_BOX_REFILL_TARGET_NUM..MAX_BOX_REFILL_TARGET_NUM
    }

    fun isLocalRuntimeOnlyField(key: String): Boolean {
        return key in LOCAL_RUNTIME_ONLY_FIELDS
    }

    fun getBoxRefillTargetNum(): Int {
        val config = getAppConfig()
        val normalized = normalizeBoxRefillTargetNum(config.boxRefillTargetNum)
        if (normalized != config.boxRefillTargetNum) {
            LogUtils.w(
                "【配置校验】boxRefillTargetNum（烤肠箱加满目标库存）非法，" +
                    "当前值=${config.boxRefillTargetNum}，允许范围=${MIN_BOX_REFILL_TARGET_NUM}..${MAX_BOX_REFILL_TARGET_NUM}，" +
                    "已回退为默认值=$normalized"
            )
            config.boxRefillTargetNum = normalized
            saveAppConfig(config)
        }
        return normalized
    }

    fun getAppConfig(): AppConfigBean {
        if (appConfigBean != null) {
            return appConfigBean!!
        }

        val jsonConfig = PreferenceUtils.getStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, null)
        if (!jsonConfig.isNullOrBlank()) {
            try {
                appConfigBean = JSON.parseObject(jsonConfig, AppConfigBean::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (appConfigBean == null) {
            val legacyConfig = PreferenceUtils.getObjectPreference<AppConfigBean>(Sp_File_URL, KEY_APP_CONFIG_OBJECT)
            if (legacyConfig != null) {
                appConfigBean = legacyConfig
                saveAppConfig(legacyConfig)
            }
        }

        if (appConfigBean == null) {
            appConfigBean = AppConfigBean()
        }

        val config = appConfigBean!!
        syncHomeQrHiddenFields(config)
        val actionGroupPrefetchMigrated = migrateActionGroupPrefetchDefault(config)
        val forceVisionSuccessMigrated = migrateForceVisionSuccessDefault(config)
        val boxRefillTargetNumMigrated = migrateBoxRefillTargetNumDefault(config)
        val faultSafetyMigrated = normalizeFaultSafetyConfig(config, "配置加载")
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        normalizeConcurrencyState(config, "配置加载")
        if (actionGroupPrefetchMigrated ||
            forceVisionSuccessMigrated ||
            boxRefillTargetNumMigrated ||
            faultSafetyMigrated ||
            oldModeType != config.modeType ||
            oldTransitionMode != config.transitionMode
        ) {
            saveAppConfig(config)
        }
        return appConfigBean!!
    }

    private fun migrateBoxRefillTargetNumDefault(config: AppConfigBean): Boolean {
        if (isValidBoxRefillTargetNum(config.boxRefillTargetNum)) {
            return false
        }
        val oldValue = config.boxRefillTargetNum
        config.boxRefillTargetNum = DEFAULT_BOX_REFILL_TARGET_NUM
        LogUtils.w(
            "【配置迁移】boxRefillTargetNum（烤肠箱加满目标库存）非法，" +
                "原值=$oldValue，允许范围=${MIN_BOX_REFILL_TARGET_NUM}..${MAX_BOX_REFILL_TARGET_NUM}，" +
                "已使用默认值=${config.boxRefillTargetNum}"
        )
        return true
    }

    private fun normalizeFaultSafetyConfig(config: AppConfigBean, logPrefix: String): Boolean {
        var changed = false
        if (config.faultBakingPauseAbortMs <= 0L) {
            LogUtils.w(
                "[$logPrefix] faultBakingPauseAbortMs（故障停热烤制报废阈值）非法，" +
                    "原值=${config.faultBakingPauseAbortMs}，已回退默认值=$DEFAULT_FAULT_BAKING_PAUSE_ABORT_MS"
            )
            config.faultBakingPauseAbortMs = DEFAULT_FAULT_BAKING_PAUSE_ABORT_MS
            changed = true
        }
        if (config.faultPauseAnomalyMs <= 0L) {
            LogUtils.w(
                "[$logPrefix] faultPauseAnomalyMs（故障停热暂停异常阈值）非法，" +
                    "原值=${config.faultPauseAnomalyMs}，已回退默认值=$DEFAULT_FAULT_PAUSE_ANOMALY_MS"
            )
            config.faultPauseAnomalyMs = DEFAULT_FAULT_PAUSE_ANOMALY_MS
            changed = true
        }
        if (config.httpFallbackMinIntervalMs <= 0L) {
            LogUtils.w(
                "[$logPrefix] httpFallbackMinIntervalMs（HTTP 兜底最小间隔）非法，" +
                    "原值=${config.httpFallbackMinIntervalMs}，已回退默认值=$DEFAULT_HTTP_FALLBACK_MIN_INTERVAL_MS"
            )
            config.httpFallbackMinIntervalMs = DEFAULT_HTTP_FALLBACK_MIN_INTERVAL_MS
            changed = true
        }
        return changed
    }

    private fun migrateForceVisionSuccessDefault(config: AppConfigBean): Boolean {
        if (config.forceVisionSuccess != 1) {
            return false
        }
        config.forceVisionSuccess = 0
        LogUtils.i(
            "【配置迁移】检测到旧视觉联调开关历史开启，已自动关闭：" +
                "forceVisionSuccess（视觉识别固定返回成功开关）=1（开启）->0（关闭），" +
                "当前主出餐链路使用 out5（光电检查顾客是否取走），不再依赖售卖口视觉识别"
        )
        return true
    }

    private fun migrateActionGroupPrefetchDefault(config: AppConfigBean): Boolean {
        if (config.actionGroupPrefetchRemoteConfigured == 1 || config.enableActionGroupPrefetch == 1) {
            return false
        }
        config.enableActionGroupPrefetch = 1
        LogUtils.i(
            "【配置迁移】检测到旧版本动作组补肠预取默认值，已自动开启：" +
                "enableActionGroupPrefetch（动作组补肠预取开关）=0（关闭）->1（开启），" +
                "actionGroupPrefetchRemoteConfigured（后台是否明确配置预取）=0（未明确配置）"
        )
        return true
    }

    fun saveAppConfig(appConfigBean: AppConfigBean) {
        syncHomeQrHiddenFields(appConfigBean)
        alignRestStatusSource(appConfigBean)
        this.appConfigBean = appConfigBean
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, JSON.toJSONString(appConfigBean))
    }

    fun hasValidDeviceConfig(): Boolean {
        val config = getAppConfig()
        return !config.deviceCode.isNullOrBlank() &&
            config.businessTime != null &&
            config.status in 0..1 &&
            config.onlineStatus in 0..4
    }

    fun getAlgorithmEnableFlag(): Int {
        val config = getAppConfig()
        return if (config.isEnable == 1) 1 else 0
    }

    private fun syncHomeQrHiddenFields(config: AppConfigBean) {
        val normalized = when {
            config.homeQrHidden == AppConfigBean.HOME_QR_HIDDEN_HIDDEN -> AppConfigBean.HOME_QR_HIDDEN_HIDDEN
            config.scanBlockStatus == AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED -> AppConfigBean.HOME_QR_HIDDEN_HIDDEN
            else -> AppConfigBean.HOME_QR_HIDDEN_SHOW
        }
        config.homeQrHidden = normalized
        config.scanBlockStatus = normalized
    }

    internal fun applyHomeQrHiddenRemoteConfigInto(
        config: AppConfigBean,
        jsonData: JSONObject
    ): List<String> {
        val remoteValue: Int? = when {
            jsonData.containsKey("homeQrHidden") && jsonData.get("homeQrHidden") != null ->
                jsonData.getIntValue("homeQrHidden")
            jsonData.containsKey("scanBlockStatus") && jsonData.get("scanBlockStatus") != null ->
                jsonData.getIntValue("scanBlockStatus")
            else -> null
        }
        if (remoteValue == null) {
            syncHomeQrHiddenFields(config)
            return listOf("homeQrHidden")
        }
        val normalized = if (remoteValue == AppConfigBean.HOME_QR_HIDDEN_HIDDEN) {
            AppConfigBean.HOME_QR_HIDDEN_HIDDEN
        } else {
            AppConfigBean.HOME_QR_HIDDEN_SHOW
        }
        config.homeQrHidden = normalized
        config.scanBlockStatus = normalized
        return emptyList()
    }

    private fun normalizeConcurrencyState(config: AppConfigBean, logPrefix: String) {
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        val oldResidualDrain = config.highConcurrencyResidualDrain
        config.modeType = when (config.modeType) {
            1, 2 -> config.modeType
            else -> 1
        }
        config.transitionMode = when (config.transitionMode) {
            0, 1, 2 -> config.transitionMode
            else -> 0
        }
        if (config.modeType == 2) {
            config.highConcurrencyResidualDrain = 0
        } else {
            config.highConcurrencyResidualDrain = if (config.highConcurrencyResidualDrain == 1) 1 else 0
        }
        if (oldModeType == config.modeType &&
            oldTransitionMode == config.transitionMode &&
            oldResidualDrain == config.highConcurrencyResidualDrain
        ) {
            return
        }
        LogUtils.w(
            "[$logPrefix] 远端配置并发运行态已归一化：" +
                "原 modeType（并发模式）=$oldModeType，原 transitionMode（并发切换过渡态）=$oldTransitionMode，" +
                "原 highConcurrencyResidualDrain（高并发残留消化态）=$oldResidualDrain，" +
                "新 modeType（并发模式）=${config.modeType}，新 transitionMode（并发切换过渡态）=${config.transitionMode}，" +
                "新 highConcurrencyResidualDrain（高并发残留消化态）=${config.highConcurrencyResidualDrain}"
        )
    }

    fun mergeRemoteConfig(
        jsonData: JSONObject?,
        rawContent: String?,
        logPrefix: String
    ): RemoteConfigMergeResult {
        val config = getAppConfig()
        if (jsonData == null) {
            val hasFallback = hasValidDeviceConfig()
            val message = "远端配置数据为空，responseLength=${rawContent?.length ?: 0}"
            if (hasFallback) {
                LogUtils.w("[$logPrefix] $message，继续使用本地有效配置")
            } else {
                LogUtils.e("[$logPrefix] $message，且本地也没有有效配置")
            }
            return RemoteConfigMergeResult(hasFallback, hasFallback, message)
        }

        val preservedFields = mutableListOf<String>()
        val parseIssues = mutableListOf<String>()

        fun mergeString(
            key: String,
            fieldLabel: String,
            allowBlank: Boolean = true,
            setter: (String) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val value = jsonData.getString(key)
            if (!allowBlank && value.isNullOrBlank()) {
                preservedFields.add(fieldLabel)
                return
            }
            setter(value ?: "")
        }

        fun mergeInt(
            key: String,
            fieldLabel: String,
            setter: (Int) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            setter(jsonData.getIntValue(key))
        }

        fun mergeLong(
            key: String,
            fieldLabel: String,
            setter: (Long) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            setter(jsonData.getLongValue(key))
        }

        fun mergePositiveLongOrDefault(
            key: String,
            fieldLabel: String,
            defaultValue: Long,
            setter: (Long) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                setter(defaultValue)
                return
            }
            val remoteValue = jsonData.getLongValue(key)
            if (remoteValue <= 0L) {
                setter(defaultValue)
                preservedFields.add("$fieldLabel(invalid_default)")
                LogUtils.w(
                    "[$logPrefix] 远端配置 $fieldLabel 非法，remoteValue=$remoteValue，" +
                        "已使用默认值=$defaultValue，食品安全护栏不允许被关闭"
                )
                return
            }
            setter(remoteValue)
        }

        fun mergeHeatingOffset(
            key: String,
            fieldLabel: String,
            setter: (Int) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val rawValue = jsonData.getIntValue(key)
            val clampedValue = rawValue.coerceIn(MIN_HEATING_OFFSET, MAX_HEATING_OFFSET)
            setter(clampedValue)
            if (rawValue != clampedValue) {
                LogUtils.w(
                    "[$logPrefix] 远端分区烤制温度补偿超出安全范围，已截断：" +
                        "$fieldLabel=$rawValue℃，允许范围=${MIN_HEATING_OFFSET}..${MAX_HEATING_OFFSET}℃，" +
                        "实际使用=$clampedValue℃"
                )
            }
        }

        mergeString("mqttUrl", "mqttUrl") { config.mqttHost = it }
        mergeString("mqttUsername", "mqttUsername") { config.mqttUsername = it }
        mergeString("mqttPassword", "mqttPassword") { config.mqttPassword = it }
        mergeString("modbusAddress", "modbusAddress") { config.modbusAddress = it }
        mergeString("deviceCode", "deviceCode", allowBlank = false) { config.deviceCode = it }
        mergeString("name", "name", allowBlank = false) { config.deviceName = it }
        mergeString("address", "address") { config.deviceAddress = it }
        mergeString("qrcodeUrl", "qrcodeUrl") { config.qcodeURL = it }
        mergeLong("configVersion", "configVersion") { config.configVersion = it }

        val businessHoursRaw = jsonData.getString("businessHours")
        if (businessHoursRaw.isNullOrBlank()) {
            preservedFields.add("businessHours")
        } else {
            try {
                val businessTime = JSON.parseObject(businessHoursRaw, BusinessTime::class.java)
                if (businessTime != null) {
                    config.businessTime = businessTime
                } else {
                    parseIssues.add("businessHours")
                }
            } catch (e: Exception) {
                parseIssues.add("businessHours")
                LogUtils.w("[$logPrefix] 解析营业时间失败：businessHours=$businessHoursRaw", e)
            }
        }

        mergeInt("status", "status") { config.status = it }
        mergeInt("onlineStatus", "onlineStatus") { config.onlineStatus = it }
        mergeInt("supplyStatus", "supplyStatus") {
            config.supplyStatus = if (it == AppConfigBean.SUPPLY_STATUS_SOLD_OUT) {
                AppConfigBean.SUPPLY_STATUS_SOLD_OUT
            } else {
                AppConfigBean.SUPPLY_STATUS_NORMAL
            }
        }
        preservedFields.addAll(applyHomeQrHiddenRemoteConfigInto(config, jsonData))
        mergeInt("inspectionMode", "inspectionMode") {
            config.inspectionMode = if (it == AppConfigBean.INSPECTION_MODE_ACTIVE) {
                AppConfigBean.INSPECTION_MODE_ACTIVE
            } else {
                AppConfigBean.INSPECTION_MODE_NORMAL
            }
        }
        mergeInt("concurrencyMode", "concurrencyMode") { config.modeType = it }
        mergeInt("transitionMode", "transitionMode") { config.transitionMode = it }
        mergeInt("highConcurrencyResidualDrain", "highConcurrencyResidualDrain") {
            config.highConcurrencyResidualDrain = if (config.modeType == 1 && it == 1) 1 else 0
        }
        mergeInt("errorStatus", "errorStatus") { config.errorStatus = it }
        mergeInt("available", "available") { config.available = it.toLong() }
        mergeInt("availableCountdownLatched", "availableCountdownLatched") { config.availableCountdownLatched = it }
        mergeInt("keepWarmTemperature", "keepWarmTemperature") { config.keepWarmTemperature = it }
        mergeInt("heatingTemperature", "heatingTemperature") { config.heatingTemperature = it }
        mergeHeatingOffset("zone1HeatingOffset", "zone1HeatingOffset") { config.zone1HeatingOffset = it }
        mergeHeatingOffset("zone2HeatingOffset", "zone2HeatingOffset") { config.zone2HeatingOffset = it }
        mergeHeatingOffset("zone3HeatingOffset", "zone3HeatingOffset") { config.zone3HeatingOffset = it }
        mergeInt("bakingTime", "bakingTime") { config.bakingTime = it }
        mergeInt("discardTime", "discardTime") { config.discardTime = it }
        mergeInt("timeThresholdLow", "timeThresholdLow") { config.timeThresholdLow = it }
        mergeInt("timeBeforeClose", "timeBeforeClose") { config.timeBeforeClose = it }
        preservedFields.addAll(mergeFaultSafetyRemoteConfigInto(config, jsonData, logPrefix))
        if (jsonData.containsKey("boxRefillTargetNum")) {
            val remoteBoxRefillTargetNum = jsonData.getIntValue("boxRefillTargetNum")
            if (isValidBoxRefillTargetNum(remoteBoxRefillTargetNum)) {
                config.boxRefillTargetNum = remoteBoxRefillTargetNum
                LogUtils.i(
                    "[$logPrefix] 远端配置已更新：boxRefillTargetNum（烤肠箱加满目标库存）=$remoteBoxRefillTargetNum"
                )
            } else {
                preservedFields.add("boxRefillTargetNum(invalid_preserved_local)")
                LogUtils.w(
                    "[$logPrefix] 远端配置 boxRefillTargetNum（烤肠箱加满目标库存）非法，" +
                        "remoteValue=$remoteBoxRefillTargetNum，允许范围=${MIN_BOX_REFILL_TARGET_NUM}..${MAX_BOX_REFILL_TARGET_NUM}，" +
                        "继续使用本地值=${config.boxRefillTargetNum}"
                )
            }
        }

        if (jsonData.containsKey("boxToPlatformTimeoutSeconds")) {
            config.boxToPlatformTimeoutSeconds = jsonData.getIntValue("boxToPlatformTimeoutSeconds")
        }
        if (jsonData.containsKey("trayToSellPlatformTimeoutSeconds")) {
            config.trayToSellPlatformTimeoutSeconds = jsonData.getIntValue("trayToSellPlatformTimeoutSeconds")
        }
        if (jsonData.containsKey("postSellSupplementGuardSeconds")) {
            config.postSellSupplementGuardSeconds = jsonData.getIntValue("postSellSupplementGuardSeconds")
        }
        if (jsonData.containsKey("postSellIdleStableSeconds")) {
            config.postSellIdleStableSeconds = jsonData.getIntValue("postSellIdleStableSeconds")
        }
        if (jsonData.containsKey("postSellIdleMaxWaitSeconds")) {
            config.postSellIdleMaxWaitSeconds = jsonData.getIntValue("postSellIdleMaxWaitSeconds")
        }
        if (jsonData.containsKey("cameraPrewarmHoldSeconds")) {
            config.cameraPrewarmHoldSeconds = jsonData.getIntValue("cameraPrewarmHoldSeconds")
        }
        if (jsonData.containsKey("useActionGroupV2")) {
            val requestedUseActionGroupV2 = if (jsonData.getIntValue("useActionGroupV2") == 0) 0 else 1
            if (requestedUseActionGroupV2 == 0 && config.useActionGroupV2 == 1 && config.moveStatus in listOf(1, 2)) {
                config.pendingDisableActionGroupV2 = 1
                preservedFields.add("useActionGroupV2(pending_until_action_group_idle)")
                LogUtils.w(
                    "[$logPrefix] 收到 useActionGroupV2=0（关闭 300/400 动作组）但当前 moveStatus=${config.moveStatus}（动作组会话运行中），" +
                        "已记录 pendingDisableActionGroupV2=1（待会话收口后关闭）"
                )
            } else {
                config.useActionGroupV2 = requestedUseActionGroupV2
                if (requestedUseActionGroupV2 == 1) {
                    config.pendingDisableActionGroupV2 = 0
                }
            }
        }
        if (jsonData.containsKey("enableActionGroupPrefetch")) {
            config.enableActionGroupPrefetch = if (jsonData.getIntValue("enableActionGroupPrefetch") == 1) 1 else 0
            config.actionGroupPrefetchRemoteConfigured = 1
        } else if (config.actionGroupPrefetchRemoteConfigured != 1) {
            config.enableActionGroupPrefetch = 1
        }
        if (jsonData.containsKey("enableVisionShadowMode")) {
            config.enableVisionShadowMode = if (jsonData.getIntValue("enableVisionShadowMode") == 1) 1 else 0
        }
        if (jsonData.containsKey("pendingDisableActionGroupV2")) {
            config.pendingDisableActionGroupV2 = if (jsonData.getIntValue("pendingDisableActionGroupV2") == 1) 1 else 0
        }

        if (config.onlineStatus in 1..4 && config.status != 1) {
            preservedFields.add("status(normalized_from_onlineStatus)")
            config.status = 1
        }
        normalizeConcurrencyState(config, logPrefix)

        saveAppConfig(config)

        val hasValidConfig = hasValidDeviceConfig()
        val preservedFieldSet = preservedFields.distinct()
        val significantPreservedFields = preservedFieldSet.filterNot { it in BENIGN_PRESERVED_FIELDS }
        val benignPreservedFields = preservedFieldSet.filter { it in BENIGN_PRESERVED_FIELDS }
        val details = mutableListOf<String>()
        if (significantPreservedFields.isNotEmpty()) {
            details.add("preserved=${significantPreservedFields.joinToString(",")}")
        }
        if (parseIssues.isNotEmpty()) {
            details.add("parseFailed=${parseIssues.distinct().joinToString(",")}")
        }

        val message = buildString {
            append("远端配置合并完成")
            if (benignPreservedFields.isNotEmpty()) {
                append("，首页预计时间字段继续沿用本地值：")
                append(benignPreservedFields.joinToString(","))
            }
            if (details.isNotEmpty()) {
                append("（")
                append(details.joinToString("; "))
                append("）")
            }
            append("，deviceCode=")
            append(config.deviceCode.ifBlank { "未知" })
            jsonData.getString("updateTime")?.takeIf { it.isNotBlank() }?.let {
                append("，serverUpdateTime=")
                append(it)
            }
        }

        if (details.isNotEmpty()) {
            if (hasValidConfig) {
                LogUtils.w("[$logPrefix] $message")
            } else {
                LogUtils.e("[$logPrefix] $message，合并后配置仍然无效")
            }
        }

        val userVisibleFallback = parseIssues.isNotEmpty() ||
            preservedFields.any {
                it == "deviceCode" ||
                    it == "name" ||
                    it == "businessHours" ||
                    it == "qrcodeUrl" ||
                    it == "status" ||
                    it == "onlineStatus"
            }

        return RemoteConfigMergeResult(
            applied = hasValidConfig,
            usedLocalFallback = userVisibleFallback,
            message = message
        )
    }

    internal fun mergeFaultSafetyRemoteConfigInto(
        config: AppConfigBean,
        jsonData: JSONObject,
        logPrefix: String,
        emitLogs: Boolean = true
    ): List<String> {
        val preservedFields = mutableListOf<String>()

        fun mergePositiveLongOrDefault(
            key: String,
            fieldLabel: String,
            defaultValue: Long,
            setter: (Long) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                setter(defaultValue)
                return
            }
            val remoteValue = jsonData.getLongValue(key)
            if (remoteValue <= 0L) {
                preservedFields.add("$fieldLabel(invalid_default)")
                setter(defaultValue)
                if (emitLogs) {
                    LogUtils.w(
                        "[$logPrefix] 远端配置 $fieldLabel 非法，remoteValue=$remoteValue，" +
                            "已使用默认值=$defaultValue，食品安全护栏不允许被关闭"
                    )
                }
                return
            }
            setter(remoteValue)
        }

        mergePositiveLongOrDefault(
            "faultBakingPauseAbortMs",
            "faultBakingPauseAbortMs",
            DEFAULT_FAULT_BAKING_PAUSE_ABORT_MS
        ) { config.faultBakingPauseAbortMs = it }
        mergePositiveLongOrDefault(
            "faultPauseAnomalyMs",
            "faultPauseAnomalyMs",
            DEFAULT_FAULT_PAUSE_ANOMALY_MS
        ) { config.faultPauseAnomalyMs = it }
        mergePositiveLongOrDefault(
            "httpFallbackMinIntervalMs",
            "httpFallbackMinIntervalMs",
            DEFAULT_HTTP_FALLBACK_MIN_INTERVAL_MS
        ) { config.httpFallbackMinIntervalMs = it }

        LOCAL_RUNTIME_ONLY_FIELDS.filter { jsonData.containsKey(it) }
            .forEach { preservedFields.add("$it(local_runtime_preserved)") }

        return preservedFields
    }

    fun getDeviceId(): String {
        var androidId = PreferenceUtils.getStringPreference(Sp_File_URL, "sp_android_id", null)
        if (androidId == null) {
            androidId = Settings.Secure.getString(MyApp.instance().contentResolver, Settings.Secure.ANDROID_ID)
            PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
        }
        return androidId
    }

    fun saveDeviceId(androidId: String) {
        PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
    }

    fun saveInspectionSnapshot(reason: String) {
        val payload = InspectionSnapshot(
            savedAt = System.currentTimeMillis(),
            configJson = JSON.toJSONString(getAppConfig()),
            kaoPanJson = JSON.toJSONString(KaoPanHelper.getKaoPanList()),
            kaoPanBoxJson = JSON.toJSONString(KaoPanHelper.getKaoPanBoxList())
        )
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, JSON.toJSONString(payload))
        LogUtils.i(
            "[检修模式] 已保存进入前快照：" +
                "reason=$reason，savedAt=${payload.savedAt}，" +
                "kaoPanCount=${KaoPanHelper.getKaoPanList().size}，kaoPanBoxCount=${KaoPanHelper.getKaoPanBoxList().size}"
        )
    }

    fun getInspectionSnapshot(): InspectionSnapshot? {
        val raw = PreferenceUtils.getStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, null)
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            JSON.parseObject(raw, InspectionSnapshot::class.java)
        } catch (e: Exception) {
            LogUtils.w("[检修模式] 读取检修快照失败，已忽略损坏数据", e)
            null
        }
    }

    fun clearInspectionSnapshot() {
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_INSPECTION_SNAPSHOT_JSON, "")
    }
}
