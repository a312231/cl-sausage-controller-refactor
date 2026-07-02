package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.mqtt.RuntimeStateSignatureBuilder
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.StringCallback
import com.lzy.okgo.model.Response
import com.lzy.okgo.request.base.Request

/**
 * 上位机与云端的 HTTP 接口封装。
 */
object NetApi {
    // 正式环境接口地址
    var API_URL_START: String = "https://iotadmin.cheerluck.com/prod-api/api/app/device"

    // 测试环境示例
    // var API_URL_START: String = "https://iotadmintest.cheerluck.com/stage-api/api/app/device"

    // 设备初始化
    var init_url = "$API_URL_START/init"

    // 获取烤肠箱库存
    var stock_url = "$API_URL_START/stock"
    var getKcProductList = "$API_URL_START/getKcProductList"

    // 更新烤肠箱库存
    var updateStock_url = "$API_URL_START/updateStock"

    // 清空烤肠箱库存
    var clearStock_url = "$API_URL_START/clearStock"

    // 上传烤盘状态
    var updateKaoPanList_url = "$API_URL_START/updateKaoPanList"
    var reportSnapshot_url = "$API_URL_START/reportSnapshot"
    var updateDeviceInfo_url = "$API_URL_START/updateDeviceInfo"
    var actionTelemetry_url = "$API_URL_START/actionTelemetry"
    var consumableEvent_url = "$API_URL_START/consumable/event"
    var consumableRefillStickFull_url = "$API_URL_START/consumable/refillStickFull"
    var consumableClearWaste_url = "$API_URL_START/consumable/clearWaste"
    var consumableState_url = "$API_URL_START/consumable/state"
    var checkUpdate_url = "$API_URL_START/checkUpdate"
    var reportVersion_url = "$API_URL_START/reportVersion"

    // 广告与设备信息
    var ads_url = "$API_URL_START/ads"
    var getDeviceInfo_url = "$API_URL_START/getDeviceInfo"

    /**
     * 设备初始化。
     */
    fun initDevice(deviceId: String, callback: (code: Int, content: String?) -> Unit) {
        val json = JSONObject()
        json["deviceCode"] = deviceId
        LogUtils.d("【网络请求】开始初始化设备：deviceCode=$deviceId")
        OkGo.post<String>(init_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onStart(request: Request<String, out Request<Any, Request<*, *>>>?) {
                    super.onStart(request)
                }

                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】设备初始化成功：deviceCode=$deviceId")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】设备初始化失败：deviceCode=$deviceId, error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    fun initDeviceByActivationCode(activationCode: String, callback: (code: Int, content: String?) -> Unit) {
        val json = JSONObject()
        json["activationCode"] = activationCode
        LogUtils.d("【网络请求】开始使用一次性激活码初始化设备：activationCode=$activationCode")
        OkGo.post<String>(init_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】一次性激活码初始化请求成功")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】一次性激活码初始化请求失败：error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 获取广告列表。
     */
    fun getAds(callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【网络请求】开始获取广告列表")
        OkGo.get<String>(ads_url)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取广告列表成功")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取广告列表失败：error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 获取设备详情。
     */
    fun getDeviceInfo(deviceId: String, callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【网络请求】开始获取设备详情：deviceCode=$deviceId")
        val request = OkGo.get<String>(getDeviceInfo_url)
            .params("deviceCode", deviceId)
        val credential = AppConfig.getAppConfig().mqttPassword
        if (!credential.isNullOrBlank()) {
            request.headers("X-Device-Credential", credential)
        }
        request
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取设备详情成功：deviceCode=$deviceId")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取设备详情失败：deviceCode=$deviceId, error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    fun getConsumableState(deviceId: String, callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【耗材状态】开始获取耗材状态：deviceCode=$deviceId")
        OkGo.get<String>(consumableState_url)
            .params("deviceCode", deviceId)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【耗材状态】获取耗材状态成功：deviceCode=$deviceId")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【耗材状态】获取耗材状态失败：deviceCode=$deviceId, error=${response?.exception?.message}")
                    callback(1, response?.body())
                }
            })
    }

    /**
     * 获取产品列表。
     */
    fun getKcProductList(callback: (code: Int, content: String?) -> Unit) {
        LogUtils.d("【网络请求】开始获取产品列表")
        val request = OkGo.get<String>(getKcProductList)
        val deviceCode = AppConfig.getDeviceId()
        if (!deviceCode.isNullOrBlank()) {
            request.params("deviceCode", deviceCode)
        }
        request
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取产品列表成功")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取产品列表失败：error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 获取设备库存列表。
     */
    fun getStockData(callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = AppConfig.getDeviceId()
        LogUtils.d("【网络请求】开始获取库存：deviceCode=$deviceCode")
        OkGo.get<String>(stock_url)
            .params("deviceCode", deviceCode)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】获取库存成功：deviceCode=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】获取库存失败：deviceCode=$deviceCode, error=${response?.exception?.message}")
                    callback(1, "网络错误")
                }
            })
    }

    /**
     * 更新烤肠箱库存。
     */
    fun updateStock(content: String, callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始更新烤肠箱库存：deviceCode=$deviceCode")
        OkGo.post<String>(updateStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】更新烤肠箱库存成功：deviceCode=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】更新烤肠箱库存失败：deviceCode=$deviceCode, error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 清空烤盘库存。
     */
    fun clearStock(content: String, callback: (code: Int, content: String?) -> Unit) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始清空烤盘：deviceCode=$deviceCode")
        OkGo.post<String>(clearStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】清空烤盘成功：deviceCode=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】清空烤盘失败：deviceCode=$deviceCode, error=${response?.exception?.message}")
                    callback(1, null)
                }
            })
    }

    /**
     * 上传烤盘状态。
     */
    fun updateKaoPanList(content: String?, callback: ((code: Int, content: String?) -> Unit)? = null) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["content"] = content
        LogUtils.d("【网络请求】开始上传烤盘状态：deviceCode=$deviceCode")
        OkGo.post<String>(updateKaoPanList_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】上传烤盘状态成功：deviceCode=$deviceCode")
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】上传烤盘状态失败：deviceCode=$deviceCode, error=${response?.exception?.message}")
                    callback?.invoke(1, response?.body())
                }
            })
    }

    /**
     * 上报设备数据快照。
     *
     * sequence（设备快照版本号）由上位机本机递增，用于云端拒绝旧请求覆盖新状态。
     * reportTime（设备本机上报时间）是毫秒时间戳，作为缺少 sequence 时的兜底排序依据。
     */
    fun reportSnapshot(
        kaoPanList: String,
        stockList: String,
        sequence: Long,
        reportTime: Long,
        snapshotHash: String? = null,
        snapshotReason: String? = null,
        forceFullSnapshot: Boolean = false,
        callback: ((code: Int, content: String?) -> Unit)? = null
    ) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["sequence"] = sequence
        json["reportTime"] = reportTime
        json["kaoPanList"] = kaoPanList
        json["stockList"] = stockList
        snapshotHash?.let { json["snapshotHash"] = it }
        snapshotReason?.let { json["snapshotReason"] = it }
        json["forceFullSnapshot"] = forceFullSnapshot
        val snapshotContext = buildSnapshotContext(deviceCode, sequence, reportTime, kaoPanList, stockList)
        LogUtils.d("【网络请求】开始上报设备数据快照：$snapshotContext")
        OkGo.post<String>(reportSnapshot_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    val body = response?.body()
                    val result = CloudSnapshotReportResponseParser.parse(body)
                    if (result.accepted) {
                        LogUtils.d(
                            "【网络请求】上报设备数据快照成功：" +
                                "$snapshotContext, ${result.reason}, raw=$body"
                        )
                        callback?.invoke(0, body)
                    } else {
                        val errorMessage =
                            "云端未接收设备数据快照：$snapshotContext, reason=${result.reason}, raw=$body"
                        LogUtils.e("【网络请求】$errorMessage")
                        callback?.invoke(1, errorMessage)
                    }
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【网络请求】上报设备数据快照失败：" +
                            "$snapshotContext, error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }

    private fun buildSnapshotContext(
        deviceCode: String,
        sequence: Long,
        reportTime: Long,
        kaoPanList: String,
        stockList: String
    ): String {
        return "deviceCode=$deviceCode, " +
            "sequence（设备快照版本号）=$sequence, " +
            "reportTime（设备本机上报时间）=$reportTime, " +
            "kaoPanList（烤盘快照）=${payloadSummary(kaoPanList)}, " +
            "stockList（库存快照）=${payloadSummary(stockList)}"
    }

    private fun payloadSummary(payload: String): String {
        val itemCount = try {
            JSON.parseArray(payload).size
        } catch (_: Exception) {
            -1
        }
        val countText = if (itemCount >= 0) {
            "条目数=$itemCount"
        } else {
            "条目数=解析失败"
        }
        return "$countText，jsonLength=${payload.length}"
    }

    /**
     * 更新设备营业状态和基础控制参数。
     * 这是 HTTP 同步通道，和 MQTT 的 action=2 状态上报互为补充。
     */
    fun updateDeviceInfo(callback: ((code: Int, content: String?) -> Unit)? = null, source: String? = null) {
        val deviceCode = AppConfig.getDeviceId()
        val config = AppConfig.getAppConfig()
        val json = buildUpdateDeviceInfoPayload(deviceCode, config, source)
        LogUtils.d("【网络请求】开始更新设备信息：deviceCode=$deviceCode")
        OkGo.post<String>(updateDeviceInfo_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【网络请求】更新设备信息成功：deviceCode=$deviceCode")
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("【网络请求】更新设备信息失败：deviceCode=$deviceCode, error=${response?.exception?.message}")
                    callback?.invoke(1, response?.body())
                }
            })
    }

    fun uploadConsumableEvent(
        event: cn.niuyannet.kaochang.android.mqtt.ConsumableEvent,
        callback: ((code: Int, content: String?) -> Unit)? = null
    ) {
        val json = cn.niuyannet.kaochang.android.mqtt.ConsumableEventPayloadBuilder.buildPayload(event)
        LogUtils.d(
            "【耗材事件】开始 HTTP 兜底上报耗材事件：" +
                "sourceEventKey=${event.sourceEventKey}，eventType=${event.eventType}，delta=${event.deltaValue}"
        )
        OkGo.post<String>(consumableEvent_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.i(
                        "【耗材事件】HTTP 兜底上报成功：" +
                            "sourceEventKey=${event.sourceEventKey}，eventType=${event.eventType}"
                    )
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【耗材事件】HTTP 兜底上报失败：" +
                            "sourceEventKey=${event.sourceEventKey}，eventType=${event.eventType}，" +
                            "error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }

    fun uploadActionGroupTelemetry(
        payload: JSONObject,
        callback: ((code: Int, content: String?) -> Unit)? = null
    ) {
        val eventId = payload.getString("eventId").orEmpty()
        val actionName = payload.getString("actionName").orEmpty()
        val eventPhase = payload.getString("eventPhase").orEmpty()
        LogUtils.d(
            "【300/400动作组】开始 HTTP 兜底上报结构化动作日志：" +
                "eventId=$eventId，actionName=$actionName，eventPhase=$eventPhase"
        )
        OkGo.post<String>(actionTelemetry_url)
            .upJson(payload.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.i(
                        "【300/400动作组】HTTP 兜底上报成功：" +
                            "eventId=$eventId，actionName=$actionName，eventPhase=$eventPhase"
                    )
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【300/400动作组】HTTP 兜底上报失败：" +
                            "eventId=$eventId，actionName=$actionName，eventPhase=$eventPhase，" +
                            "error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }

    fun refillStickFull(callback: ((code: Int, content: String?) -> Unit)? = null) {
        postConsumableManualAction(
            url = consumableRefillStickFull_url,
            actionName = "补满竹签",
            callback = callback
        )
    }

    fun clearWasteBucket(callback: ((code: Int, content: String?) -> Unit)? = null) {
        postConsumableManualAction(
            url = consumableClearWaste_url,
            actionName = "清空垃圾桶",
            callback = callback
        )
    }

    private fun postConsumableManualAction(
        url: String,
        actionName: String,
        callback: ((code: Int, content: String?) -> Unit)? = null
    ) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        LogUtils.i("【耗材现场操作】开始上报$actionName：deviceCode=$deviceCode")
        OkGo.post<String>(url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.i("【耗材现场操作】$actionName 上报成功：deviceCode=$deviceCode")
                    callback?.invoke(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【耗材现场操作】$actionName 上报失败：" +
                            "deviceCode=$deviceCode，error=${response?.exception?.message}"
                    )
                    callback?.invoke(1, response?.body())
                }
            })
    }

    internal fun buildUpdateDeviceInfoPayload(
        deviceCode: String,
        config: AppConfigBean,
        source: String? = null
    ): JSONObject {
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        putIfNotBlank(json, "source", source)
        json["errorStatus"] = config.errorStatus
        if (config.errorStatus != AppConfigBean.ERROR_STATUS_NORMAL) {
            putIfNotBlank(json, "errorCode", config.errorCode)
            putIfNotBlank(json, "errorCategory", config.errorCategory)
            putIfNotBlank(json, "errorReason", config.errorReason)
        }
        json["onlineStatus"] = config.onlineStatus
        json["supplyStatus"] = config.supplyStatus
        json["homeQrHidden"] = config.homeQrHidden
        json["scanBlockStatus"] = config.scanBlockStatus
        json["inspectionMode"] = config.inspectionMode
        json["modeType"] = config.modeType
        json["transitionMode"] = config.transitionMode
        json["highConcurrencyResidualDrain"] = config.highConcurrencyResidualDrain
        json["available"] = config.available
        json["availableCountdownLatched"] = config.availableCountdownLatched
        json["isEnable"] = if (config.isEnable == 1) 1 else 0
        json["lowerPlatformStatus"] = config.lowerPlatformStatus ?: AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL
        json["lowerPlatformIsolationActionName"] = config.lowerPlatformIsolationActionName ?: ""
        json["supplementEnabled"] = config.supplementEnabled
        json["orderBlockReason"] = config.orderBlockReason ?: ""
        json["foodSafetyAlertCode"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertCode(config)
        json["foodSafetyAlertReason"] = RuntimeStateSignatureBuilder.reportableFoodSafetyAlertReason(config)
        json["pendingDiscardPositions"] = config.pendingDiscardPositions
        return json
    }

    private fun putIfNotBlank(json: JSONObject, key: String, value: String?) {
        if (!value.isNullOrBlank()) {
            json[key] = value
        }
    }

    fun checkAppUpdate(
        credential: String,
        versionCode: Int,
        versionName: String,
        packageName: String,
        channel: String,
        callback: (code: Int, content: String?) -> Unit
    ) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["credential"] = credential
        json["versionCode"] = versionCode
        json["versionName"] = versionName
        json["packageName"] = packageName
        json["channel"] = channel
        LogUtils.d(
            "【应用更新】开始检查应用版本：" +
                "deviceCode=$deviceCode，versionCode（当前版本号）=$versionCode，" +
                "versionName（当前版本名）=$versionName，channel=$channel，" +
                "credential（设备更新凭证）=已隐藏"
        )
        OkGo.post<String>(checkUpdate_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【应用更新】检查应用版本请求成功：deviceCode=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【应用更新】检查应用版本请求失败：" +
                            "deviceCode=$deviceCode，error=${response?.exception?.message}"
                    )
                    callback(1, response?.body())
                }
            })
    }

    fun reportAppVersion(
        credential: String,
        versionCode: Int,
        versionName: String,
        packageName: String,
        channel: String,
        callback: (code: Int, content: String?) -> Unit
    ) {
        val deviceCode = AppConfig.getDeviceId()
        val json = JSONObject()
        json["deviceCode"] = deviceCode
        json["credential"] = credential
        json["versionCode"] = versionCode
        json["versionName"] = versionName
        json["packageName"] = packageName
        json["channel"] = channel
        json["reportedAt"] = System.currentTimeMillis()
        LogUtils.d(
            "【应用更新】开始上报当前应用版本：" +
                "deviceCode=$deviceCode，versionCode（当前版本号）=$versionCode，" +
                "versionName（当前版本名）=$versionName，channel=$channel，" +
                "credential（设备更新凭证）=已隐藏"
        )
        OkGo.post<String>(reportVersion_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("【应用更新】当前应用版本上报成功：deviceCode=$deviceCode")
                    callback(0, response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e(
                        "【应用更新】当前应用版本上报失败：" +
                            "deviceCode=$deviceCode，error=${response?.exception?.message}"
                    )
                    callback(1, response?.body())
                }
            })
    }
}
