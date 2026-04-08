package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveKaoPanBoxList
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveTasteList
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
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

            "isEnable" -> when ((value as? Number)?.toInt()) {
                0 -> "0(已关闭)"
                1 -> "1(已开启)"
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
     * - 进入维护中：自动关闭算法，并回写 isEnable=0
     * - 离开维护中：保持算法关闭，不自动恢复加热
     */
    fun handleRemoteOnlineStatusTransition(
        previousOnlineStatus: Int,
        previousIsEnable: Int,
        mergedConfig: AppConfigBean,
        source: String
    ) {
        if (previousOnlineStatus != 3 && mergedConfig.onlineStatus == 3) {
            logD(
                "远端配置切换到维护中，自动关闭算法并回写服务端：" +
                    "source=$source, " +
                    "previousOnlineStatus=${formatDiffValue("onlineStatus", previousOnlineStatus)}, " +
                    "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                    "previousIsEnable=${formatDiffValue("isEnable", previousIsEnable)}, " +
                    "newIsEnable=${formatDiffValue("isEnable", 0)}"
            )
            KaoChangAlgorithm.disableRoastAlgorithm(
                reason = "远端配置切换到维护中(source=$source)"
            )
            return
        }

        if (previousOnlineStatus == 3 && mergedConfig.onlineStatus in listOf(1, 2)) {
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
            if (!KaoChangAlgorithm.isEnabled || currentIsEnable != 1) {
                logD(
                    "远端配置已离开维护中，但按约定保持算法关闭，不自动恢复加热：" +
                        "source=$source, " +
                        "newOnlineStatus=${formatDiffValue("onlineStatus", mergedConfig.onlineStatus)}, " +
                        "currentIsEnable=${formatDiffValue("isEnable", currentIsEnable)}"
                )
                return
            }
            helperScope.launch {
                KaoChangOperate.restoreHeatingStateFromCurrentPans("远端配置切回非维护状态且算法已开启")
            }
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
        val bakingCount = kaoPanList.count { it.isHasSausage && it.status == 1 }
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
                logD("库存已同步：箱位数=$stockItemCount, 总库存=$stockTotalCount")
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
            val previousIsEnable = AppConfig.getAlgorithmEnableFlag()

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

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, LOG_SYNC)
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
            appendDiff(changes, "keepWarmTemperature", oldKeepWarmTemperature, mergedConfig.keepWarmTemperature)
            appendDiff(changes, "heatingTemperature", oldHeatingTemperature, mergedConfig.heatingTemperature)
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

            if (oldDiscardTime != mergedConfig.discardTime) {
                val refreshedCount = KaoPanHelper.refreshDiscardCloseTime(mergedConfig.discardTime)
                val newCloseTimeMillis = mergedConfig.discardTime.coerceAtLeast(0) * 60L * 60L * 1000L
                logD(
                    "远端配置已更新丢弃时间，并已回刷烤盘保温超时阈值：" +
                        "discardTime=${oldDiscardTime}小时->${mergedConfig.discardTime}小时, " +
                        "回刷烤盘数=$refreshedCount, 新closeTime=${newCloseTimeMillis}ms"
                )
            }

            handleRemoteOnlineStatusTransition(previousOnlineStatus, previousIsEnable, mergedConfig, "getDeviceInfo")
        }
    }

    fun refreshHomeDeviceState(onResult: ((changed: Boolean) -> Unit)? = null) {
        val appConfigBean = AppConfig.getAppConfig()
        val oldStatus = appConfigBean.status
        val oldOnlineStatus = appConfigBean.onlineStatus
        val oldQcodeUrl = appConfigBean.qcodeURL
        val oldAvailable = appConfigBean.available
        val oldAvailableCountdownLatched = appConfigBean.availableCountdownLatched
        val previousIsEnable = AppConfig.getAlgorithmEnableFlag()

        NetApi.getDeviceInfo(AppConfig.getDeviceId()) { code, content ->
            if (code != 0) {
                logE("首页状态轮询失败：HTTP请求失败，code=$code, content=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val json = try {
                JSON.parseObject(content)
            } catch (e: Exception) {
                logE("首页状态轮询失败：响应解析异常，raw=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            if (json == null || json.getIntValue("code") != 200) {
                logE("首页状态轮询失败：业务返回异常，raw=$content")
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val mergeResult = AppConfig.mergeRemoteConfig(json.getJSONObject("data"), content, "$LOG_SYNC-home")
            if (!mergeResult.applied) {
                onResult?.invoke(false)
                return@getDeviceInfo
            }

            val mergedConfig = AppConfig.getAppConfig()
            handleRemoteOnlineStatusTransition(oldOnlineStatus, previousIsEnable, mergedConfig, "refreshHomeDeviceState")
            val changed =
                oldStatus != mergedConfig.status ||
                    oldOnlineStatus != mergedConfig.onlineStatus ||
                    oldQcodeUrl != mergedConfig.qcodeURL ||
                    oldAvailable != mergedConfig.available ||
                    oldAvailableCountdownLatched != mergedConfig.availableCountdownLatched

            if (changed) {
                logD(
                    "首页相关配置发生变化：" +
                        "status（设备启用状态）=${formatDiffValue("status", oldStatus)}->${formatDiffValue("status", mergedConfig.status)}，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(oldOnlineStatus)}->${onlineStatusText(mergedConfig.onlineStatus)}，" +
                        "qrcodeChanged（二维码地址是否变化）=${oldQcodeUrl != mergedConfig.qcodeURL}，" +
                        "available（二维码页预计时间）=${oldAvailable}->${mergedConfig.available}，" +
                        "availableCountdownLatched（二维码页预计时间锁存标记）=${oldAvailableCountdownLatched}->${mergedConfig.availableCountdownLatched}"
                )
            }

            onResult?.invoke(changed)
        }
    }

    fun uploadDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null) {
        NetApi.updateDeviceInfo(callback)
    }
}
