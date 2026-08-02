package cn.niuyannet.kaochang.android.init

import android.provider.Settings
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.fault.FaultCode
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.services.FourFTemperaturePolicy
import cn.niuyannet.kaochang.android.services.LowerPlatformEpisodeReducer
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import java.security.MessageDigest
import java.util.UUID

object AppConfig {
    const val Sp_File_URL = "KAOCHANG_APP_CONFIG"
    const val DEFAULT_ADMIN_PASSWORD_HASH = "e7d5ef5d4a76223f930e1264c0690f7aa0fea6faf27eefa0110c1a16bfe8bf2d"
    const val DEFAULT_BOX_REFILL_TARGET_NUM = 10
    const val MIN_BOX_REFILL_TARGET_NUM = 1
    const val MAX_BOX_REFILL_TARGET_NUM = 15
    const val MIN_HEATING_OFFSET = -50
    const val MAX_HEATING_OFFSET = 50
    const val DEFAULT_FAULT_BAKING_PAUSE_ABORT_MS = 2L * 60L * 60L * 1000L
    const val DEFAULT_FAULT_PAUSE_ANOMALY_MS = 4L * 60L * 60L * 1000L
    const val DEFAULT_HTTP_FALLBACK_MIN_INTERVAL_MS = 30_000L
    private const val KEY_APP_CONFIG_OBJECT = "sp_app_config"
    private const val KEY_APP_CONFIG_JSON = "sp_app_config_json"
    private const val KEY_INSPECTION_SNAPSHOT_JSON = "sp_inspection_snapshot_json"
    private val BENIGN_PRESERVED_FIELDS = setOf(
        "available",
        "available(runtime_local)",
        "availableCountdownLatched",
        "availableCountdownLatched(runtime_local)"
    )
    private val LOCAL_RUNTIME_ONLY_FIELDS = setOf(
        "faultHeatPauseActive",
        "faultHeatStopConfirmed",
        "faultHeatPauseStartedAtMs",
        "faultHeatPauseReason",
        "faultHeatPauseSource",
        "faultHeatRecoveryJournalJson",
        "pendingDiscardPositions",
        "foodSafetyAlertCode",
        "foodSafetyAlertReason",
        "lastRuntimeAliveAtMs",
        "lowerPlatformStatus",
        "lowerPlatformIsolationActionName",
        "lowerPlatformGeneration",
        "lowerPlatformAggregateVersion",
        "lowerPlatformEpisodes",
        "supplementEnabled"
    )
    private var appConfigBean: AppConfigBean? = null

    data class RemoteConfigMergeResult(
        val applied: Boolean,
        val usedLocalFallback: Boolean,
        val message: String
    )

    data class FaultRuntimeSnapshot(
        val onlineStatus: Int,
        val errorStatus: Int,
        val errorCode: String?,
        val isEnable: Int
    ) {
        val isFaultLatched: Boolean =
            errorStatus != AppConfigBean.ERROR_STATUS_NORMAL

        val isNonModbusAutoRecoverableFault: Boolean =
            isFaultLatched && errorCode != FaultCode.MODBUS_DISCONNECT.name

        companion object {
            fun from(config: AppConfigBean): FaultRuntimeSnapshot =
                FaultRuntimeSnapshot(
                    onlineStatus = config.onlineStatus,
                    errorStatus = config.errorStatus,
                    errorCode = config.errorCode,
                    isEnable = config.isEnable
                )
        }
    }

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

    fun applyFaultLatchedRemoteStatusGuard(
        config: AppConfigBean,
        snapshot: FaultRuntimeSnapshot,
        field: String,
        remoteValue: Int,
        logPrefix: String,
        emitLogs: Boolean = true,
        setter: (Int) -> Unit
    ): Boolean {
        val preserve = snapshot.isNonModbusAutoRecoverableFault && when (field) {
            "onlineStatus" -> remoteValue != 3
            "errorStatus" -> remoteValue == AppConfigBean.ERROR_STATUS_NORMAL
            else -> false
        }
        if (!preserve) {
            setter(remoteValue)
            return false
        }
        if (emitLogs) {
            LogUtils.w(
                "[$logPrefix] 本地非断连故障锁存中，远端状态字段未覆盖本地故障态：" +
                    "field=$field，remoteValue=$remoteValue，localOnlineStatus=${snapshot.onlineStatus}，" +
                    "localErrorStatus=${snapshot.errorStatus}，localErrorCode=${snapshot.errorCode ?: "null"}，" +
                    "localIsEnable=${snapshot.isEnable}"
            )
        }
        when (field) {
            "onlineStatus" -> config.onlineStatus = snapshot.onlineStatus
            "errorStatus" -> config.errorStatus = snapshot.errorStatus
        }
        return true
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

    fun normalizeLowerPlatformStatus(value: String?): String {
        return when (value) {
            AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL,
            AppConfigBean.LOWER_PLATFORM_STATUS_SUPPLY_DEGRADED,
            AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED -> value
            else -> AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        }
    }

    fun normalizeSupplementEnabled(value: Int): Int =
        if (value == AppConfigBean.SUPPLEMENT_ENABLED_NO) {
            AppConfigBean.SUPPLEMENT_ENABLED_NO
        } else {
            AppConfigBean.SUPPLEMENT_ENABLED_YES
        }

    fun ensureLowerPlatformGeneration(
        config: AppConfigBean,
        generationSupplier: () -> String = { UUID.randomUUID().toString() }
    ): Boolean {
        if (!config.lowerPlatformGeneration.isNullOrBlank()) {
            return false
        }
        config.lowerPlatformGeneration = generationSupplier()
        return true
    }

    fun applyLowerPlatformRuntimeFieldsFromRemote(config: AppConfigBean, jsonData: JSONObject): List<String> {
        val preservedFields = mutableListOf(
            "lowerPlatformStatus(runtime_local)",
            "lowerPlatformIsolationActionName(runtime_local)",
            "lowerPlatformGeneration(runtime_local)",
            "lowerPlatformAggregateVersion(runtime_local)",
            "lowerPlatformEpisodes(runtime_local)",
            "supplementEnabled(runtime_local)"
        )
        // 首次启动/本地数据已清空时，服务端投影是唯一可信的代际锚点。
        // 一旦本地已有聚合版本或 episode，继续保持本地运行态优先，避免旧快照覆盖现场状态。
        val freshLocalState = config.lowerPlatformAggregateVersion <= 0L &&
            config.lowerPlatformEpisodes.isEmpty()
        val remoteGeneration = jsonData.getString("lowerPlatformGeneration")
        val hasCompleteRemoteProjection = jsonData.containsKey("lowerPlatformAggregateVersion") &&
            jsonData.containsKey("lowerPlatformEpisodes")
        if (freshLocalState && !remoteGeneration.isNullOrBlank() && hasCompleteRemoteProjection) {
            config.lowerPlatformGeneration = remoteGeneration
            if (jsonData.containsKey("lowerPlatformAggregateVersion")) {
                config.lowerPlatformAggregateVersion = jsonData.getLongValue("lowerPlatformAggregateVersion")
            }
            if (jsonData.containsKey("lowerPlatformStatus")) {
                config.lowerPlatformStatus = normalizeLowerPlatformStatus(
                    jsonData.getString("lowerPlatformStatus")
                )
            }
            if (jsonData.containsKey("lowerPlatformIsolationActionName")) {
                config.lowerPlatformIsolationActionName =
                    jsonData.getString("lowerPlatformIsolationActionName") ?: ""
            }
            if (jsonData.containsKey("supplementEnabled")) {
                config.supplementEnabled = normalizeSupplementEnabled(
                    jsonData.getIntValue("supplementEnabled")
                )
            }
            jsonData.getJSONArray("lowerPlatformEpisodes")?.let { remoteEpisodes ->
                try {
                    config.lowerPlatformEpisodes.clear()
                    config.lowerPlatformEpisodes.addAll(
                        remoteEpisodes.toJavaList(AppConfigBean.LowerPlatformEpisodeBean::class.java)
                    )
                } catch (e: Exception) {
                    LogUtils.w("[AppConfig] 冷启动接管下平台 episode 失败，保留空本地状态", e)
                    config.lowerPlatformEpisodes.clear()
                }
            }
        }
        if (jsonData.containsKey("orderBlockReason")) {
            config.orderBlockReason = jsonData.getString("orderBlockReason") ?: ""
        } else {
            preservedFields.add("orderBlockReason")
        }
        if (!freshLocalState || config.lowerPlatformEpisodes.isNotEmpty()) {
            LowerPlatformEpisodeReducer.normalize(config)
        }
        return preservedFields
    }

    internal fun applyRemoteAvailableState(
        config: AppConfigBean,
        jsonData: JSONObject,
        preserveRuntimeAvailableState: Boolean,
        emitLogs: Boolean = true,
        logPrefix: String = "AppConfig"
    ): List<String> {
        val preservedFields = mutableListOf<String>()

        fun applyAvailableLong(
            key: String,
            fieldLabel: String,
            currentValue: Long,
            setter: (Long) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val remoteValue = jsonData.getLongValue(key)
            if (preserveRuntimeAvailableState) {
                preservedFields.add("$fieldLabel(runtime_local)")
                if (emitLogs && remoteValue != currentValue) {
                    LogUtils.w(
                        "[$logPrefix] 远端首页预计时间未覆盖本地实时值：" +
                            "$fieldLabel remote=$remoteValue，local=$currentValue"
                    )
                }
                return
            }
            setter(remoteValue)
        }

        fun applyAvailableInt(
            key: String,
            fieldLabel: String,
            currentValue: Int,
            setter: (Int) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val remoteValue = jsonData.getIntValue(key)
            if (preserveRuntimeAvailableState) {
                preservedFields.add("$fieldLabel(runtime_local)")
                if (emitLogs && remoteValue != currentValue) {
                    LogUtils.w(
                        "[$logPrefix] 远端首页预计时间锁存标记未覆盖本地实时值：" +
                            "$fieldLabel remote=$remoteValue，local=$currentValue"
                    )
                }
                return
            }
            setter(remoteValue)
        }

        applyAvailableLong("available", "available", config.available) { config.available = it }
        applyAvailableInt(
            "availableCountdownLatched",
            "availableCountdownLatched",
            config.availableCountdownLatched
        ) {
            config.availableCountdownLatched = it
        }
        return preservedFields
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

    fun normalizeApiBaseUrl(value: String?): String {
        val trimmed = value?.trim().orEmpty().trimEnd('/')
        return trimmed.ifBlank { AppConfigBean.DEFAULT_API_BASE_URL }
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
        val apiBaseUrlMigrated = normalizeApiBaseUrlConfig(config)
        val actionGroupPrefetchMigrated = migrateActionGroupPrefetchDefault(config)
        val forceVisionSuccessMigrated = migrateForceVisionSuccessDefault(config)
        val boxRefillTargetNumMigrated = migrateBoxRefillTargetNumDefault(config)
        val faultSafetyMigrated = normalizeFaultSafetyConfig(config, "配置加载")
        val lowerPlatformRuntimeMigrated = normalizeLowerPlatformRuntimeConfig(config, "配置加载")
        val adminPasswordMigrated = normalizeAdminPasswordHash(config)
        val oldModeType = config.modeType
        val oldTransitionMode = config.transitionMode
        normalizeConcurrencyState(config, "配置加载")
        if (apiBaseUrlMigrated ||
            actionGroupPrefetchMigrated ||
            forceVisionSuccessMigrated ||
            boxRefillTargetNumMigrated ||
            faultSafetyMigrated ||
            lowerPlatformRuntimeMigrated ||
            adminPasswordMigrated ||
            oldModeType != config.modeType ||
            oldTransitionMode != config.transitionMode
        ) {
            saveAppConfig(config)
        }
        return appConfigBean!!
    }

    private fun normalizeApiBaseUrlConfig(config: AppConfigBean): Boolean {
        val normalized = normalizeApiBaseUrl(config.apiBaseUrl)
        if (normalized == config.apiBaseUrl) {
            return false
        }
        LogUtils.w(
            "【配置迁移】apiBaseUrl（云端HTTP地址）为空或带多余结尾斜杠，" +
                "原值=${config.apiBaseUrl ?: "null"}，已规范化为$normalized"
        )
        config.apiBaseUrl = normalized
        return true
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

    private fun normalizeLowerPlatformRuntimeConfig(config: AppConfigBean, logPrefix: String): Boolean {
        var changed = false
        if (ensureLowerPlatformGeneration(config)) {
            LogUtils.i("[$logPrefix] 初始化 lowerPlatformGeneration（下平台事件代际）=${config.lowerPlatformGeneration}")
            changed = true
        }
        val normalizedStatus = normalizeLowerPlatformStatus(config.lowerPlatformStatus)
        if (config.lowerPlatformStatus != normalizedStatus) {
            LogUtils.w(
                "[$logPrefix] lowerPlatformStatus（下平台/补肠链路状态）非法，" +
                    "原值=${config.lowerPlatformStatus ?: "null"}，已回退为$normalizedStatus"
            )
            config.lowerPlatformStatus = normalizedStatus
            changed = true
        }
        if (config.lowerPlatformIsolationActionName == null) {
            config.lowerPlatformIsolationActionName = ""
            changed = true
        }
        val normalizedSupplementEnabled = normalizeSupplementEnabled(config.supplementEnabled)
        if (config.supplementEnabled != normalizedSupplementEnabled) {
            LogUtils.w(
                "[$logPrefix] supplementEnabled（自动补肠开关）非法，" +
                    "原值=${config.supplementEnabled}，已回退为$normalizedSupplementEnabled"
            )
            config.supplementEnabled = normalizedSupplementEnabled
            changed = true
        }
        if (config.orderBlockReason == null) {
            config.orderBlockReason = ""
            changed = true
        }
        if (LowerPlatformEpisodeReducer.normalize(config)) {
            changed = true
        }
        return changed
    }

    private fun normalizeAdminPasswordHash(config: AppConfigBean): Boolean {
        val currentHash = config.adminPasswordHash
        if (isValidSha256Hash(currentHash)) {
            return false
        }
        config.adminPasswordHash = DEFAULT_ADMIN_PASSWORD_HASH
        LogUtils.w("【配置迁移】上位机管理员密码配置为空或非法，已回退为默认密码 cl123")
        return true
    }

    fun isAdminPasswordValid(password: String): Boolean {
        val config = getAppConfig()
        val configuredHash = if (isValidSha256Hash(config.adminPasswordHash)) {
            config.adminPasswordHash
        } else {
            DEFAULT_ADMIN_PASSWORD_HASH
        }
        return sha256(password.trim()).equals(configuredHash, ignoreCase = true)
    }

    private fun isValidSha256Hash(value: String?): Boolean {
        return value != null && Regex("^[0-9a-fA-F]{64}$").matches(value)
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun migrateForceVisionSuccessDefault(config: AppConfigBean): Boolean {
        if (config.forceVisionSuccess != 1) {
            return false
        }
        config.forceVisionSuccess = 0
        LogUtils.i(
            "【配置迁移】检测到旧视觉联调开关历史开启，已自动关闭：" +
                "forceVisionSuccess（视觉识别固定返回成功开关）=1（开启）->0（关闭），" +
                "当前 4F 主出餐链路使用 out4（开窗推送并收口）判定单次履约完成，不再依赖售卖口视觉识别"
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
        appConfigBean.apiBaseUrl = normalizeApiBaseUrl(appConfigBean.apiBaseUrl)
        syncHomeQrHiddenFields(appConfigBean)
        alignRestStatusSource(appConfigBean)
        this.appConfigBean = appConfigBean
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, JSON.toJSONString(appConfigBean))
    }

    fun saveAppConfigDurable(appConfigBean: AppConfigBean) {
        appConfigBean.apiBaseUrl = normalizeApiBaseUrl(appConfigBean.apiBaseUrl)
        syncHomeQrHiddenFields(appConfigBean)
        alignRestStatusSource(appConfigBean)
        this.appConfigBean = appConfigBean
        check(
            PreferenceUtils.saveStringPreferenceDurable(
                Sp_File_URL,
                KEY_APP_CONFIG_JSON,
                JSON.toJSONString(appConfigBean)
            )
        ) { "关键运行态同步落盘失败" }
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

    private fun logRemoteTransitionWriteIfNeeded(
        logPrefix: String,
        oldModeType: Int,
        oldTransitionMode: Int,
        config: AppConfigBean
    ) {
        if (config.transitionMode !in listOf(1, 2)) {
            return
        }
        if (oldModeType == config.modeType && oldTransitionMode == config.transitionMode) {
            return
        }
        val kaoPanList = KaoPanHelper.getKaoPanList()
        LogUtils.w(
            "[$logPrefix] 【并发切换诊断】远端配置写入 transitionMode=${transitionModeText(config.transitionMode)}：" +
                "source=AppConfig.mergeRemoteConfig，" +
                "modeType（并发模式）=${modeTypeText(oldModeType)}->${modeTypeText(config.modeType)}，" +
                "transitionMode（并发切换过渡态）=${transitionModeText(oldTransitionMode)}->${transitionModeText(config.transitionMode)}，" +
                "1~21空位=${kaoPanList.filter { it.positionSn in 1..21 && !it.isHasSausage }.map { it.positionSn }.sorted()}，" +
                "13~18状态=${panStatusSummary(kaoPanList.filter { it.positionSn in 13..18 }.sortedBy { it.positionSn })}，" +
                "25~33状态=${panStatusSummary(kaoPanList.filter { it.positionSn in 25..33 }.sortedBy { it.positionSn })}"
        )
    }

    private fun modeTypeText(value: Int): String = when (value) {
        1 -> "1（低并发）"
        2 -> "2（高并发）"
        else -> "$value（未知）"
    }

    private fun transitionModeText(value: Int): String = when (value) {
        0 -> "0（无过渡态）"
        1 -> "1（低转高过渡）"
        2 -> "2（高转低过渡）"
        else -> "$value（未知）"
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

    fun mergeRemoteConfig(
        jsonData: JSONObject?,
        rawContent: String?,
        logPrefix: String,
        preserveRuntimeConcurrencyState: Boolean = true,
        preserveRuntimeAvailableState: Boolean = true
    ): RemoteConfigMergeResult {
        val config = getAppConfig()
        val hadValidDeviceConfigBeforeMerge = hasValidDeviceConfig()
        val oldModeTypeForTransitionDiagnostics = config.modeType
        val oldTransitionModeForTransitionDiagnostics = config.transitionMode
        val faultRuntimeSnapshot = FaultRuntimeSnapshot.from(config)
        applyRemoteParallelSupplementPrefetchConfig(config, jsonData)
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

        fun mergeGuardedRuntimeInt(
            key: String,
            fieldLabel: String,
            setter: (Int) -> Unit
        ) {
            if (!jsonData.containsKey(key) || jsonData.get(key) == null) {
                preservedFields.add(fieldLabel)
                return
            }
            val remoteValue = jsonData.getIntValue(key)
            val preserved = applyFaultLatchedRemoteStatusGuard(
                config = config,
                snapshot = faultRuntimeSnapshot,
                field = fieldLabel,
                remoteValue = remoteValue,
                logPrefix = logPrefix,
                setter = setter
            )
            if (preserved) {
                preservedFields.add("$fieldLabel(fault_latched_local)")
            }
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

        if (!jsonData.containsKey("mqttPassword") && jsonData.containsKey("credentialNo")) {
            jsonData["mqttPassword"] = jsonData.getString("credentialNo")
        }
        if (!jsonData.containsKey("mqttUsername") && jsonData.containsKey("deviceCode")) {
            jsonData["mqttUsername"] = jsonData.getString("deviceCode")
        }
        if (!jsonData.containsKey("name") && jsonData.containsKey("deviceName")) {
            jsonData["name"] = jsonData.getString("deviceName")
        }

        mergeString("mqttUrl", "mqttUrl") { config.mqttHost = it }
        mergeString("mqttUsername", "mqttUsername") { config.mqttUsername = it }
        mergeString("mqttPassword", "mqttPassword") { config.mqttPassword = it }
        mergeString("adminPasswordHash", "adminPasswordHash", allowBlank = false) {
            if (isValidSha256Hash(it)) {
                config.adminPasswordHash = it.lowercase()
            } else {
                preservedFields.add("adminPasswordHash(invalid_preserved_local)")
                LogUtils.w("[$logPrefix] 远端上位机管理员密码 hash 非法，已继续使用本地配置")
            }
        }
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
        mergeGuardedRuntimeInt("onlineStatus", "onlineStatus") { config.onlineStatus = it }
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
        val shouldPreserveRuntimeConcurrencyState = preserveRuntimeConcurrencyState && hasValidDeviceConfig()
        if (shouldPreserveRuntimeConcurrencyState) {
            val remoteModeType = jsonData.getInteger("concurrencyMode")
            val remoteTransitionMode = jsonData.getInteger("transitionMode")
            val remoteResidualDrain = jsonData.getInteger("highConcurrencyResidualDrain")
            val remoteDiffers =
                (remoteModeType != null && remoteModeType != config.modeType) ||
                    (remoteTransitionMode != null && remoteTransitionMode != config.transitionMode) ||
                    (remoteResidualDrain != null && remoteResidualDrain != config.highConcurrencyResidualDrain)
            if (remoteDiffers) {
                LogUtils.w(
                    "[$logPrefix] 远端配置中的并发运行态未覆盖本地实时状态：" +
                        "source=AppConfig.mergeRemoteConfig，" +
                        "远端 modeType（并发模式）=${remoteModeType ?: "缺省"}，本地 modeType（并发模式）=${config.modeType}，" +
                        "远端 transitionMode（并发切换过渡态）=${remoteTransitionMode ?: "缺省"}，本地 transitionMode（并发切换过渡态）=${config.transitionMode}，" +
                        "远端 highConcurrencyResidualDrain（高并发残留消化态）=${remoteResidualDrain ?: "缺省"}，" +
                        "本地 highConcurrencyResidualDrain（高并发残留消化态）=${config.highConcurrencyResidualDrain}，" +
                        "说明=并发主状态以本机实时状态和 concurrency_control 明确指令为准，避免 getDeviceInfo 旧值打断低转高/高转低过渡"
                )
            }
        } else {
            mergeInt("concurrencyMode", "concurrencyMode") { config.modeType = it }
            mergeInt("transitionMode", "transitionMode") { config.transitionMode = it }
            mergeInt("highConcurrencyResidualDrain", "highConcurrencyResidualDrain") {
                config.highConcurrencyResidualDrain = if (config.modeType == 1 && it == 1) 1 else 0
            }
        }
        mergeGuardedRuntimeInt("errorStatus", "errorStatus") { config.errorStatus = it }
        preservedFields.addAll(applyLowerPlatformRuntimeFieldsFromRemote(config, jsonData))
        preservedFields.addAll(
            applyRemoteAvailableState(
                config = config,
                jsonData = jsonData,
                preserveRuntimeAvailableState = preserveRuntimeAvailableState && hadValidDeviceConfigBeforeMerge,
                logPrefix = logPrefix
            )
        )
        mergeInt("keepWarmTemperature", "keepWarmTemperature") {
            config.keepWarmTemperature = FourFTemperaturePolicy.clampTargetTemperature(it)
        }
        mergeInt("heatingTemperature", "heatingTemperature") {
            config.heatingTemperature = FourFTemperaturePolicy.clampTargetTemperature(it)
        }
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

        // config.status 仅本地使用，表示"运营态激活（1=已激活）"，与后端 kc_device.status（0=正常，1=停用）语义相反。
        // 严禁通过 MQTT/HTTP payload 上报至云端，详见 SendServerHelper 的 publishServiceUpdateStatus / publishAvailableState 收口策略。
        if (config.onlineStatus in 1..4 && config.status != 1) {
            preservedFields.add("status(normalized_from_onlineStatus)")
            config.status = 1
        }
        normalizeConcurrencyState(config, logPrefix)
        logRemoteTransitionWriteIfNeeded(
            logPrefix = logPrefix,
            oldModeType = oldModeTypeForTransitionDiagnostics,
            oldTransitionMode = oldTransitionModeForTransitionDiagnostics,
            config = config
        )

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

    internal fun applyRemoteParallelSupplementPrefetchConfig(
        config: AppConfigBean,
        jsonData: JSONObject?
    ) {
        val rawValue = jsonData?.get("enableParallelSupplementPrefetch")
        config.enableParallelSupplementPrefetch =
            if (rawValue is Byte || rawValue is Short || rawValue is Int || rawValue is Long) {
                if ((rawValue as Number).toLong() == 1L) 1 else 0
            } else {
                0
            }
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
