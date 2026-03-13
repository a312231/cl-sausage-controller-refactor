package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.StringCallback
import com.lzy.okgo.model.Response
import com.lzy.okgo.request.base.Request

/**
 * 接口服务类
 */

object NetApi {
//     var API_URL_START: String = "http://10.10.43.26:8080/api/app/device" //接口地址
     var API_URL_START: String = "https://iotadmin.cheerluck.com/prod-api/api/app/device" //接口地址

//    var API_URL_START: String = "https://iotadmintest.cheerluck.com/stage-api/api/app/device" //接口地址
    //设备初始化
    var init_url=API_URL_START+"/init"
    //获取烤肠箱结构
    var stock_url=API_URL_START+"/stock"
    var getKcProductList=API_URL_START+"/getKcProductList"
    //更新烤肠箱
    var updateStock_url=API_URL_START+"/updateStock";
    //清空烤肠箱
    var clearStock_url=API_URL_START+"/clearStock"
    //上传烤盘的状态
    var updateKaoPanList_url=API_URL_START+"/updateKaoPanList"
    var updateDeviceInfo_url=API_URL_START+"/updateDeviceInfo"
    //获取广告
    var ads_url=API_URL_START+"/ads"
    //获取设备info
    var getDeviceInfo_url=API_URL_START+"/getDeviceInfo"

    /**
     * 初始化设备
     */
    fun initDevice(deviceId:String,callback: (code:Int,content:String?) -> Unit) {
        val json=com.alibaba.fastjson.JSONObject()
        json["deviceCode"] = deviceId
        OkGo.post<String>(init_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onStart(request: Request<String, out Request<Any, Request<*, *>>>?) {
                    super.onStart(request)
                }
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }

                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }

    /**
     * 获取广告
     */
    fun getAds(callback: (code:Int,content:String?) -> Unit) {
        OkGo.get<String>(ads_url)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }
    /**
     * 获取设备详情
     */
    fun getDeviceInfo(deviceId:String,callback: (code:Int,content:String?) -> Unit){
        OkGo.get<String>(getDeviceInfo_url).params("deviceCode",deviceId)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }
    /**
     * 获取产品列表
     */
    fun getKcProductList(callback: (code:Int,content:String?) -> Unit) {
        OkGo.get<String>(getKcProductList)
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }

    /**
     * 获取设备库存列表
     */
    fun getStockData(callback: (code:Int,content:String?) -> Unit){
        OkGo.get<String>(stock_url).params("deviceCode",AppConfig.getDeviceId())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,"网络错误")
                }
            })
    }

    /**
     * 更新烤肠箱库存
     */
    fun updateStock(content:String,callback: (code:Int,content:String?) -> Unit){
        val json=com.alibaba.fastjson.JSONObject()
        json["deviceCode"] = AppConfig.getDeviceId()
        json["content"] = content
        OkGo.post<String>(updateStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }

    /**
     * 清空烤盘
     */
    fun clearStock(content:String,callback: (code:Int,content:String?) -> Unit){
        val json=com.alibaba.fastjson.JSONObject()
        json["deviceCode"] = AppConfig.getDeviceId()
        json["content"] = content
        OkGo.post<String>(clearStock_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback(0,response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback(1,null)
                }
            })
    }

    /**
     * 上传烤盘的状态
     */
    fun updateKaoPanList(content: String?, callback: ((code:Int,content:String?) -> Unit)? = null) {
        val json=com.alibaba.fastjson.JSONObject()
        json["deviceCode"] = AppConfig.getDeviceId()
        json["content"] = content
        OkGo.post<String>(updateKaoPanList_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    LogUtils.d("烤盘状态上传成功")
                    callback?.invoke(0, response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    LogUtils.e("烤盘状态上传失败：${response?.exception?.message}")
                    callback?.invoke(1, response?.body())
                }
            })
    }
    /**
     * 更新设备营业状态及基础控制参数到云端。
     * 这是 HTTP 同步通道，和 MQTT 的 action=2 状态上报互为补偿。
     */
    fun updateDeviceInfo(callback: ((code:Int,content:String?) -> Unit)? = null){
        val json=com.alibaba.fastjson.JSONObject()
        json["deviceCode"] = AppConfig.getDeviceId()
        json["errorStatus"] = AppConfig.getAppConfig().errorStatus
        json["onlineStatus"] = AppConfig.getAppConfig().onlineStatus
        json["modeType"] = AppConfig.getAppConfig().modeType
        json["transitionMode"] = AppConfig.getAppConfig().transitionMode
        json["isEnable"] = AppConfig.getAppConfig().isEnable
        OkGo.post<String>(updateDeviceInfo_url)
            .upJson(json.toJSONString())
            .execute(object : StringCallback() {
                override fun onSuccess(response: Response<String>?) {
                    callback?.invoke(0, response?.body())
                }
                override fun onError(response: Response<String>?) {
                    super.onError(response)
                    callback?.invoke(1, response?.body())
                }
            })
    }
    /**
     * 上报
     */
}
