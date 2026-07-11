package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import com.alibaba.fastjson.JSONObject

object RemoteRuntimeConfigPatchApplier {
    private val TEMPERATURE_FIELDS = setOf(
        "keepWarmTemperature",
        "heatingTemperature",
        "zone1HeatingOffset",
        "zone2HeatingOffset",
        "zone3HeatingOffset"
    )

    data class ApplyResult(
        val appliedFields: List<String>,
        val rejectedFields: List<String>,
        val normalizedFields: Map<String, Int>,
        val unknownFields: List<String>,
        val temperatureChanged: Boolean
    ) {
        val hasApplied: Boolean = appliedFields.isNotEmpty()
        val hasRejected: Boolean = rejectedFields.isNotEmpty() || unknownFields.isNotEmpty()
        val isPartial: Boolean = hasApplied && (hasRejected || normalizedFields.isNotEmpty())
    }

    fun apply(config: AppConfigBean, patch: JSONObject): ApplyResult {
        val appliedFields = mutableListOf<String>()
        val rejectedFields = mutableListOf<String>()
        val normalizedFields = linkedMapOf<String, Int>()
        val unknownFields = mutableListOf<String>()
        var temperatureChanged = false

        for (entry in patch.entries) {
            val rawField = entry.key ?: continue
            val field = when (rawField) {
                "concurrencyMode" -> "modeType"
                "scanBlockStatus" -> "homeQrHidden"
                else -> rawField
            }
            val oldTemperatureValue = if (field in TEMPERATURE_FIELDS) readIntField(config, field) else null
            val applied = when (field) {
                "onlineStatus" -> applyIntRange(config, field, entry.value, 0, 4) { config.onlineStatus = it }
                "isEnable" -> applyIntRange(config, field, entry.value, 0, 1) { config.isEnable = it }
                "homeQrHidden" -> applyIntRange(config, field, entry.value, 0, 1) {
                    config.homeQrHidden = it
                    config.scanBlockStatus = it
                }
                "modeType" -> applyIntRange(config, field, entry.value, 1, 2) { config.modeType = it }
                "transitionMode" -> applyIntRange(config, field, entry.value, 0, 2) { config.transitionMode = it }
                "highConcurrencyResidualDrain" ->
                    applyIntRange(config, field, entry.value, 0, 1) { config.highConcurrencyResidualDrain = it }
                "keepWarmTemperature" ->
                    applyTemperatureTarget(config, field, entry.value, normalizedFields) { config.keepWarmTemperature = it }
                "heatingTemperature" ->
                    applyTemperatureTarget(config, field, entry.value, normalizedFields) { config.heatingTemperature = it }
                "zone1HeatingOffset" ->
                    applyHeatingOffset(config, field, entry.value, normalizedFields) { config.zone1HeatingOffset = it }
                "zone2HeatingOffset" ->
                    applyHeatingOffset(config, field, entry.value, normalizedFields) { config.zone2HeatingOffset = it }
                "zone3HeatingOffset" ->
                    applyHeatingOffset(config, field, entry.value, normalizedFields) { config.zone3HeatingOffset = it }
                "bakingTime" -> applyPositiveInt(config, field, entry.value) { config.bakingTime = it }
                "adminPasswordHash" ->
                    applySha256Hash(config, field, entry.value) { config.adminPasswordHash = it }
                else -> {
                    unknownFields.add(rawField)
                    false
                }
            }

            if (applied) {
                appliedFields.add(rawField)
                if (field in TEMPERATURE_FIELDS && oldTemperatureValue != readIntField(config, field)) {
                    temperatureChanged = true
                }
            } else if (rawField !in unknownFields) {
                rejectedFields.add(rawField)
            }
        }

        return ApplyResult(
            appliedFields = appliedFields,
            rejectedFields = rejectedFields,
            normalizedFields = normalizedFields,
            unknownFields = unknownFields,
            temperatureChanged = temperatureChanged
        )
    }

    private fun applyIntValue(
        config: AppConfigBean,
        field: String,
        value: Any?,
        setter: (Int) -> Unit
    ): Boolean {
        val intValue = toInt(value) ?: return false
        setter(intValue)
        return readIntField(config, field) == intValue
    }

    private fun applyIntRange(
        config: AppConfigBean,
        field: String,
        value: Any?,
        min: Int,
        max: Int,
        setter: (Int) -> Unit
    ): Boolean {
        val intValue = toInt(value) ?: return false
        if (intValue !in min..max) {
            return false
        }
        setter(intValue)
        return readIntField(config, field) == intValue
    }

    private fun applyHeatingOffset(
        config: AppConfigBean,
        field: String,
        value: Any?,
        normalizedFields: MutableMap<String, Int>,
        setter: (Int) -> Unit
    ): Boolean {
        val intValue = toInt(value) ?: return false
        val normalized = intValue.coerceIn(AppConfig.MIN_HEATING_OFFSET, AppConfig.MAX_HEATING_OFFSET)
        setter(normalized)
        if (normalized != intValue) {
            normalizedFields[field] = normalized
        }
        return readIntField(config, field) == normalized
    }

    private fun applyPositiveInt(
        config: AppConfigBean,
        field: String,
        value: Any?,
        setter: (Int) -> Unit
    ): Boolean {
        val intValue = toInt(value) ?: return false
        if (intValue <= 0) {
            return false
        }
        setter(intValue)
        return readIntField(config, field) == intValue
    }

    private fun applyTemperatureTarget(
        config: AppConfigBean,
        field: String,
        value: Any?,
        normalizedFields: MutableMap<String, Int>,
        setter: (Int) -> Unit
    ): Boolean {
        val intValue = toInt(value) ?: return false
        val normalized = cn.niuyannet.kaochang.android.services.FourFTemperaturePolicy.clampTargetTemperature(intValue)
        setter(normalized)
        if (normalized != intValue) {
            normalizedFields[field] = normalized
        }
        return readIntField(config, field) == normalized
    }

    private fun toInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun applySha256Hash(
        config: AppConfigBean,
        field: String,
        value: Any?,
        setter: (String) -> Unit
    ): Boolean {
        val hash = (value as? String)?.trim() ?: return false
        if (!Regex("^[0-9a-fA-F]{64}$").matches(hash)) {
            return false
        }
        setter(hash.lowercase())
        return readStringField(config, field) == hash.lowercase()
    }

    private fun readIntField(config: AppConfigBean, field: String): Int? {
        return when (field) {
            "onlineStatus" -> config.onlineStatus
            "isEnable" -> config.isEnable
            "homeQrHidden", "scanBlockStatus" -> config.homeQrHidden
            "modeType" -> config.modeType
            "transitionMode" -> config.transitionMode
            "highConcurrencyResidualDrain" -> config.highConcurrencyResidualDrain
            "keepWarmTemperature" -> config.keepWarmTemperature
            "heatingTemperature" -> config.heatingTemperature
            "zone1HeatingOffset" -> config.zone1HeatingOffset
            "zone2HeatingOffset" -> config.zone2HeatingOffset
            "zone3HeatingOffset" -> config.zone3HeatingOffset
            "bakingTime" -> config.bakingTime
            else -> null
        }
    }

    private fun readStringField(config: AppConfigBean, field: String): String? {
        return when (field) {
            "adminPasswordHash" -> config.adminPasswordHash
            else -> null
        }
    }
}
