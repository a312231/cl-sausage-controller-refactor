package cn.niuyannet.kaochang.android.net

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveKaoPanBoxList
import cn.niuyannet.kaochang.android.model.KaoPanHelper.saveTasteList
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.LogUtils

import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils
import kotlin.math.log

/**
 * 数据请求接口管理api
 */
object DataManagementAPI {

    /**
     * 从网络拉取数据
     */
    fun  dataSyncServer(){
        //烤肠箱
        NetApi.getStockData { code, content ->
            val kaoPanBoxList = mutableListOf<KaoPanBox>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    // 解析烤箱数据
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
            }
        }
        //获取设备信息
        this.getDeviceInfo()
    }
    /**
     * 同步设备信息到云端
     */
    fun uploadDataToServer(){
        //上传烤盘的状态数据
        NetApi.updateKaoPanList(JSON.toJSONString(KaoPanHelper.getKaoPanList())) { code, content ->
            if (code == 0) {
                LogUtils.d("烤盘状态同步成功")
            } else {
                LogUtils.e("烤盘状态同步失败：code=$code, content=$content")
            }
        }
        //上传烤肠箱数据
        val kaoPanBoxList = KaoPanHelper.getKaoPanBoxList()
        val stockPayload = JSON.toJSONString(kaoPanBoxList)
        val stockItemCount = kaoPanBoxList.size
        val stockTotalCount = kaoPanBoxList.sumOf { box ->
            box.num
        }
        NetApi.updateStock(stockPayload) { code, content ->
            if (code == 0) {
                LogUtils.d("库存同步成功：明细数=$stockItemCount, 总库存=$stockTotalCount")
            } else {
                LogUtils.e("库存同步失败：明细数=$stockItemCount, 总库存=$stockTotalCount, code=$code, content=$content")
            }
        }
    }

    /**
     * 获取设备信息
     */
    fun getDeviceInfo(){
        //口味数据初始化
        NetApi.getKcProductList {code, content ->
            val tasteList = mutableListOf<Taste>()
            if (code == 0) {
                val json = JSON.parseObject(content)
                if (json.getIntValue("code") == 200) {
                    val jsonData = json.getJSONArray("data")
                    // 解析口味数据
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
            }
        }
        NetApi.getDeviceInfo(AppConfig.getDeviceId()){ code, content ->
            if (code==0){
                val json= JSON.parseObject(content)
                if (json.getIntValue("code")==200){
                  try {
                      val jsonData=json.getJSONObject("data")
                      val appConfigBean= AppConfig.getAppConfig()
                      appConfigBean.mqttHost=jsonData.getString("mqttUrl")
                      appConfigBean.mqttUsername=jsonData.getString("mqttUsername")
                      appConfigBean.mqttPassword=jsonData.getString("mqttPassword")
                      appConfigBean.modbusAddress=jsonData.getString("modbusAddress")
                      appConfigBean.deviceCode=jsonData.getString("deviceCode")
                      appConfigBean.deviceName=jsonData.getString("name")
                      appConfigBean.deviceAddress=jsonData.getString("address")
                      appConfigBean.businessTime=JSON.parseObject(jsonData.getString("businessHours"), BusinessTime::class.java)
                      appConfigBean.status=jsonData.getIntValue("status")
                      appConfigBean.onlineStatus=jsonData.getIntValue("onlineStatus")
                      appConfigBean.qcodeURL=jsonData.getString("qrcodeUrl")
                      appConfigBean.modeType=jsonData.getIntValue("concurrencyMode")
                      appConfigBean.transitionMode=jsonData.getIntValue("transitionMode")//过渡态
                     // appConfigBean.errorStatus=jsonData.getIntValue("errorStatus")//错误状态
                      appConfigBean.keepWarmTemperature=jsonData.getIntValue("keepWarmTemperature")
                      appConfigBean.heatingTemperature=jsonData.getIntValue("heatingTemperature")//过渡态
                      appConfigBean.bakingTime=jsonData.getIntValue("bakingTime")
                      appConfigBean.discardTime=jsonData.getIntValue("discardTime")
                      appConfigBean.timeThresholdLow=jsonData.getIntValue("timeThresholdLow")
                      appConfigBean.timeBeforeClose=jsonData.getIntValue("timeBeforeClose")
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
                      LogUtils.d(
                          "设备配置拉取成功：bakingTime=${appConfigBean.bakingTime}, discardTime=${appConfigBean.discardTime}, " +
                              "postSellSupplementGuardSeconds=${appConfigBean.postSellSupplementGuardSeconds}, " +
                              "postSellIdleStableSeconds=${appConfigBean.postSellIdleStableSeconds}, " +
                              "postSellIdleMaxWaitSeconds=${appConfigBean.postSellIdleMaxWaitSeconds}"
                      )
                      AppConfig.saveAppConfig(appConfigBean)
                  }catch (e:Exception){
                      e.printStackTrace()
                  }
                }
            }
        }
    }

    /**
     * 上传设备营业状态到云端。
     * 这个 HTTP 通道用于补偿 MQTT 状态上报偶发丢失的情况。
     */
    fun uploadDeviceInfo(callback: ((code:Int,content:String?) -> Unit)? = null){
        // 上传设备状态基础配置信息
        NetApi.updateDeviceInfo(callback)
    }

}
