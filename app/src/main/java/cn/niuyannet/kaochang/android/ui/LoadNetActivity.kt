package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.MainActivityBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.net.NetApi
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils

abstract class LoadNetActivity: BaseActivity() {
    lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE); // 移除标题栏
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN); // 设置全屏

        binding = MainActivityBinding.inflate(layoutInflater)
    }
    /**
     * 加载网络数据
     */
     fun loadDeviceInfo(){
        binding.mainContent.visibility= View.GONE
        binding.loadingLayout.visibility= View.VISIBLE
        binding.emptyLayout.visibility= View.GONE
        NetApi.getDeviceInfo(AppConfig.getDeviceId()){ code, content ->
            if (code==0){
                val json= JSON.parseObject(content)
                if (json.getIntValue("code")==200){
                    binding.mainContent.visibility= View.VISIBLE
                    binding.loadingLayout.visibility= View.GONE
                    binding.emptyLayout.visibility= View.GONE
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
                    appConfigBean.errorStatus=jsonData.getIntValue("errorStatus")//错误状态
                    appConfigBean.keepWarmTemperature=jsonData.getIntValue("keepWarmTemperature")
                    appConfigBean.heatingTemperature=jsonData.getIntValue("heatingTemperature")
                    Log.d("LoadNetActivity---服务端获取值-", "bakingTime:---"+jsonData.getIntValue("bakingTime")+"---  discardTime:"+jsonData.getIntValue("discardTime"))

                    appConfigBean.bakingTime=jsonData.getIntValue("bakingTime")
                    appConfigBean.discardTime=jsonData.getIntValue("discardTime")
                    appConfigBean.timeThresholdLow=jsonData.getIntValue("timeThresholdLow")
                    appConfigBean.timeBeforeClose=jsonData.getIntValue("timeBeforeClose")

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
                    Log.d(
                        "LoadNetActivity---服务端获取值-",
                        "bakingTime=${appConfigBean.bakingTime}, discardTime=${appConfigBean.discardTime}, " +
                            "postSellSupplementGuardSeconds=${appConfigBean.postSellSupplementGuardSeconds}, " +
                            "postSellIdleStableSeconds=${appConfigBean.postSellIdleStableSeconds}, " +
                            "postSellIdleMaxWaitSeconds=${appConfigBean.postSellIdleMaxWaitSeconds}"
                    )
                    AppConfig.saveAppConfig(appConfigBean)
                }else{
                    binding.mainContent.visibility= View.GONE
                    binding.loadingLayout.visibility= View.GONE
                    binding.emptyLayout.visibility= View.VISIBLE
                    binding.tvEmptyText.text=json.getString("msg")
                    ToastUtils.showShort(json.getString("msg"))
                }
            }else{
                binding.mainContent.visibility= View.GONE
                binding.loadingLayout.visibility= View.GONE
                binding.emptyLayout.visibility= View.VISIBLE
                binding.tvEmptyText.text="网络错误"
                ToastUtils.showShort("网络错误")
            }
        }
    }

}
