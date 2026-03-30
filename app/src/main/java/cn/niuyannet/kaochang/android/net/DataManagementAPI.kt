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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 云端数据同步入口。
 *
 * 主要负责：
 * 1. 拉取烤肠箱库存、口味列表、设备配置；
 * 2. 回传烤盘状态、库存状态、设备状态；
 * 3. 在设备配置发生变化时打印“旧值 -> 新值”的差异日志。
 */
object DataManagementAPI {
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

    private fun appendDiff(changes: MutableList<String>, label: String, oldValue: Any?, newValue: Any?) {
        if (oldValue != newValue) {
            changes.add("$label: ${formatDiffValue(label, oldValue)}->${formatDiffValue(label, newValue)}")
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

    /**
     * 从云端同步库存和设备配置。
     */
    fun dataSyncServer() {
        NetApi.getStockData { code, content ->
            val kaoPanBoxList = mutableListOf<KaoPanBox>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
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

    /**
     * 上传烤盘与库存状态到云端。
     * 成功日志默认不刷屏，只保留失败日志。
     */
    fun uploadDataToServer() {
        NetApi.updateKaoPanList(JSON.toJSONString(KaoPanHelper.getKaoPanList())) { code, content ->
            if (code != 0) {
                logE("烤盘状态同步失败：code=$code, content=$content")
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
            }
        }
    }

    /**
     * 拉取口味列表和设备配置。
     * 如果发现设备参数变化，只打印差异日志，不做每轮全量刷屏。
     */
    fun getDeviceInfo() {
        NetApi.getKcProductList { code, content ->
            val tasteList = mutableListOf<Taste>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
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
                logE("拉取设备配置失败：code=$code, content=$content")
                return@getDeviceInfo
            }

            val json = JSON.parseObject(content)
            if (json.getIntValue("code") != 200) {
                logE("拉取设备配置失败：业务码=${json.getIntValue("code")}, content=$content")
                return@getDeviceInfo
            }

            try {
                val jsonData = json.getJSONObject("data")
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

                appConfigBean.mqttHost = jsonData.getString("mqttUrl")
                appConfigBean.mqttUsername = jsonData.getString("mqttUsername")
                appConfigBean.mqttPassword = jsonData.getString("mqttPassword")
                appConfigBean.modbusAddress = jsonData.getString("modbusAddress")
                appConfigBean.deviceCode = jsonData.getString("deviceCode")
                appConfigBean.deviceName = jsonData.getString("name")
                appConfigBean.deviceAddress = jsonData.getString("address")
                appConfigBean.businessTime =
                    JSON.parseObject(jsonData.getString("businessHours"), BusinessTime::class.java)
                appConfigBean.status = jsonData.getIntValue("status")
                appConfigBean.onlineStatus = jsonData.getIntValue("onlineStatus")
                appConfigBean.qcodeURL = jsonData.getString("qrcodeUrl")
                appConfigBean.modeType = jsonData.getIntValue("concurrencyMode")
                appConfigBean.transitionMode = jsonData.getIntValue("transitionMode")
                appConfigBean.keepWarmTemperature = jsonData.getIntValue("keepWarmTemperature")
                appConfigBean.heatingTemperature = jsonData.getIntValue("heatingTemperature")
                appConfigBean.bakingTime = jsonData.getIntValue("bakingTime")
                appConfigBean.discardTime = jsonData.getIntValue("discardTime")
                appConfigBean.timeThresholdLow = jsonData.getIntValue("timeThresholdLow")
                appConfigBean.timeBeforeClose = jsonData.getIntValue("timeBeforeClose")
                appConfigBean.boxToPlatformTimeoutSeconds =
                    if (jsonData.containsKey("boxToPlatformTimeoutSeconds")) {
                        jsonData.getIntValue("boxToPlatformTimeoutSeconds")
                    } else {
                        90
                    }
                appConfigBean.trayToSellPlatformTimeoutSeconds =
                    if (jsonData.containsKey("trayToSellPlatformTimeoutSeconds")) {
                        jsonData.getIntValue("trayToSellPlatformTimeoutSeconds")
                    } else {
                        90
                    }
                appConfigBean.postSellSupplementGuardSeconds =
                    if (jsonData.containsKey("postSellSupplementGuardSeconds")) {
                        jsonData.getIntValue("postSellSupplementGuardSeconds")
                    } else {
                        5
                    }
                appConfigBean.postSellIdleStableSeconds =
                    if (jsonData.containsKey("postSellIdleStableSeconds")) {
                        jsonData.getIntValue("postSellIdleStableSeconds")
                    } else {
                        2
                    }
                appConfigBean.postSellIdleMaxWaitSeconds =
                    if (jsonData.containsKey("postSellIdleMaxWaitSeconds")) {
                        jsonData.getIntValue("postSellIdleMaxWaitSeconds")
                    } else {
                        12
                    }
                appConfigBean.cameraPrewarmHoldSeconds =
                    if (jsonData.containsKey("cameraPrewarmHoldSeconds")) {
                        jsonData.getIntValue("cameraPrewarmHoldSeconds")
                    } else {
                        8
                    }

                val changes = mutableListOf<String>()
                appendBusinessTimeDiff(changes, oldBusinessTime, appConfigBean.businessTime)
                appendDiff(changes, "status", oldStatus, appConfigBean.status)
                appendDiff(changes, "onlineStatus", oldOnlineStatus, appConfigBean.onlineStatus)
                appendDiff(changes, "modeType", oldModeType, appConfigBean.modeType)
                appendDiff(changes, "transitionMode", oldTransitionMode, appConfigBean.transitionMode)
                appendDiff(changes, "keepWarmTemperature", oldKeepWarmTemperature, appConfigBean.keepWarmTemperature)
                appendDiff(changes, "heatingTemperature", oldHeatingTemperature, appConfigBean.heatingTemperature)
                appendDiff(changes, "bakingTime", oldBakingTime, appConfigBean.bakingTime)
                appendDiff(changes, "discardTime", oldDiscardTime, appConfigBean.discardTime)
                appendDiff(changes, "timeThresholdLow", oldTimeThresholdLow, appConfigBean.timeThresholdLow)
                appendDiff(changes, "timeBeforeClose", oldTimeBeforeClose, appConfigBean.timeBeforeClose)
                appendDiff(
                    changes,
                    "boxToPlatformTimeoutSeconds",
                    oldBoxToPlatformTimeoutSeconds,
                    appConfigBean.boxToPlatformTimeoutSeconds
                )
                appendDiff(
                    changes,
                    "trayToSellPlatformTimeoutSeconds",
                    oldTrayToSellPlatformTimeoutSeconds,
                    appConfigBean.trayToSellPlatformTimeoutSeconds
                )
                appendDiff(
                    changes,
                    "postSellSupplementGuardSeconds",
                    oldPostSellSupplementGuardSeconds,
                    appConfigBean.postSellSupplementGuardSeconds
                )
                appendDiff(
                    changes,
                    "postSellIdleStableSeconds",
                    oldPostSellIdleStableSeconds,
                    appConfigBean.postSellIdleStableSeconds
                )
                appendDiff(
                    changes,
                    "postSellIdleMaxWaitSeconds",
                    oldPostSellIdleMaxWaitSeconds,
                    appConfigBean.postSellIdleMaxWaitSeconds
                )
                appendDiff(
                    changes,
                    "cameraPrewarmHoldSeconds",
                    oldCameraPrewarmHoldSeconds,
                    appConfigBean.cameraPrewarmHoldSeconds
                )

                if (changes.isNotEmpty()) {
                    logD("【云端下控更新】检测到设备参数变化：${changes.joinToString(", ")}")
                }

                AppConfig.saveAppConfig(appConfigBean)

                if (
                    previousOnlineStatus == 3 &&
                    appConfigBean.onlineStatus in listOf(1, 2) &&
                    VMModbusHelper.connectStatus()
                ) {
                    GlobalScope.launch(Dispatchers.IO) {
                        KaoChangOperate.restoreHeatingStateFromCurrentPans("设备配置切出维护中")
                    }
                }
            } catch (e: Exception) {
                logE("解析设备配置异常：${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 通过 HTTP 补偿上报设备状态。
     * 这条链路用于覆盖 MQTT 状态上报偶发丢失的情况。
     */
    fun uploadDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        NetApi.updateDeviceInfo(callback)
    }
}
