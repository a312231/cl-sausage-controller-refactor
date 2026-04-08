package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveKaoPanBoxList
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveTasteList
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DataManagementAPI {
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val LOG_SYNC = "云端同步"

    private fun logD(message: String) = LogUtils.d("[$LOG_SYNC] $message")
    private fun logE(message: String) = LogUtils.e("[$LOG_SYNC] $message")

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

            "keepWarmTemperature", "heatingTemperature" -> "${value}℃"
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
            changes.add(
                "$label: ${formatDiffValue(label, oldValue)}->${
                    formatDiffValue(
                        label,
                        newValue
                    )
                }"
            )
        }
    }

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

    fun dataSyncServer() {
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
                }
            } else {
                ToastUtils.showShort("网络错误")
                logE("同步烤肠箱库存失败：code=$code, content=$content")
            }
        }

        getDeviceInfo()
    }

    fun uploadDataToServer() {
        val metadataNormalized = KaoPanHelper.normalizeKaoPanTasteMetadata()
        if (metadataNormalized) {
            logD("上传前已统一烤盘口味元数据")
        }
        val kaoPanList = KaoPanHelper.getKaoPanList()
        val kaoPanCount = kaoPanList.size
        val warmingCount = kaoPanList.count { it.isHasSausage && it.status == 2 }
        val bakingCount  = kaoPanList.count { it.isHasSausage && it.status == 1 }
        NetApi.updateKaoPanList(JSON.toJSONString(kaoPanList)) { code, content ->
            if (code != 0) {
                logE("烤盘同步失败：code=$code, content=$content")
            } else {
                logD("烤盘状态已同步：烤盘总数=$kaoPanCount, 烤制中=$bakingCount, 保温中=$warmingCount")
            }
        }

        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        val stockPayload = JSON.toJSONString(kaoPanBoxList)
        val stockItemCount = kaoPanBoxList.size
        val stockTotalCount = kaoPanBoxList.sumOf { box -> box.num }
        NetApi.updateStock(stockPayload) { code, content ->
            if (code != 0) {
                logE(
                    "库存同步失败：明细数=$stockItemCount, 总库存=$stockTotalCount, code=$code, content=$content"
                )
            } else {
                logD("库存已同步：箱位数=$stockItemCount, 总库存=$stockTotalCount 根")
            }
        }
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

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) { code, content ->
            if (code != 0) {
                logE("设备配置获取失败：code=$code, content=$content")
                return@getDeviceInfo
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("设备配置响应解析失败：raw=$content")
                e.printStackTrace()
                return@getDeviceInfo
            }

            if (json == null) {
                logE("设备配置响应为空：raw=$content")
                return@getDeviceInfo
            }

            if (json.getIntValue("code") != 200) {
                logE("设备配置业务返回失败：code=${json.getIntValue("code")}, content=$content")
                return@getDeviceInfo
            }

            val appConfigBean = AppConfig.getAppConfig()
            val previousOnlineStatus = appConfigBean.onlineStatus

            val oldBusinessTime = appConfigBean.businessTime
            val oldStatus = appConfigBean.status
            val oldOnlineStatus = appConfigBean.onlineStatus
            val oldModeType = appConfigBean.modeType
            val oldTransitionMode = appConfigBean.transitionMode
            val oldKeepWarmTemperature = appConfigBean.keepWarmTemperature
            val oldHeatingTemperature = appConfigBean.heatingTemperature
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

            val mergeResult =
                AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, LOG_SYNC)
            if (!mergeResult.applied) {
                return@getDeviceInfo
            }

            val mergedConfig = AppConfig.getAppConfig()
            val changes = mutableListOf<String>()
            appendBusinessTimeDiff(changes, oldBusinessTime, mergedConfig.businessTime)
            appendDiff(changes, "status", oldStatus, mergedConfig.status)
            appendDiff(changes, "onlineStatus", oldOnlineStatus, mergedConfig.onlineStatus)
            appendDiff(changes, "modeType", oldModeType, mergedConfig.modeType)
            appendDiff(changes, "transitionMode", oldTransitionMode, mergedConfig.transitionMode)
            appendDiff(
                changes,
                "keepWarmTemperature",
                oldKeepWarmTemperature,
                mergedConfig.keepWarmTemperature
            )
            appendDiff(
                changes,
                "heatingTemperature",
                oldHeatingTemperature,
                mergedConfig.heatingTemperature
            )
            appendDiff(changes, "bakingTime", oldBakingTime, mergedConfig.bakingTime)
            appendDiff(changes, "discardTime", oldDiscardTime, mergedConfig.discardTime)
            appendDiff(
                changes,
                "timeThresholdLow",
                oldTimeThresholdLow,
                mergedConfig.timeThresholdLow
            )
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

            if (oldDiscardTime != mergedConfig.discardTime) {
                val refreshedCount = KaoPanHelper.refreshDiscardCloseTime(mergedConfig.discardTime)
                val newCloseTimeMillis = mergedConfig.discardTime.coerceAtLeast(0) * 60L * 60L * 1000L
                logD(
                    "远端配置已更新丢弃时间，并已回刷烤盘保温超时阈值：discardTime=${oldDiscardTime}小时->${mergedConfig.discardTime}小时，" +
                        "回刷烤盘数=$refreshedCount，新的closeTime=${newCloseTimeMillis}ms"
                )
            }

            if (
                previousOnlineStatus == 3 &&
                mergedConfig.onlineStatus in listOf(1, 2) &&
                VMModbusHelper.connectStatus()
            ) {
                helperScope.launch {
                    KaoChangOperate.restoreHeatingStateFromCurrentPans("远端配置切回非维护状态")
                }
            }
        }
    }

    fun refreshHomeDeviceState(onResult: ((changed: Boolean) -> Unit)? = null) {
        val appConfigBean = AppConfig.getAppConfig()
        val oldStatus = appConfigBean.status
        val oldOnlineStatus = appConfigBean.onlineStatus
        val oldQcodeUrl = appConfigBean.qcodeURL

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) { code, content ->
            if (code != 0) {
                logE("home device state refresh failed: code=$code, content=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("home device state refresh parse failed: raw=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            if (json == null || json.getIntValue("code") != 200) {
                logE("home device state refresh business failed: raw=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, "$LOG_SYNC-home")
            if (!mergeResult.applied) {
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val mergedConfig = AppConfig.getAppConfig()
            val changed =
                oldStatus != mergedConfig.status ||
                    oldOnlineStatus != mergedConfig.onlineStatus ||
                    oldQcodeUrl != mergedConfig.qcodeURL

            if (changed) {
                logD(
                    "home ui relevant config changed: " +
                        "status=${oldStatus}->${mergedConfig.status}, " +
                        "onlineStatus=${oldOnlineStatus}->${mergedConfig.onlineStatus}, " +
                        "qrcodeChanged=${oldQcodeUrl != mergedConfig.qcodeURL}"
                )
            }

            onResult?.invoke(changed)
        }
    }

    fun uploadDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        NetApi.updateDeviceInfo(callback)
    }
}
