package cn.niuyannet.kaochang.android.init

import android.content.Context
import android.provider.Settings
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.utils.PreferenceUtils

/**
 * 初始化配置信息
 */
object AppConfig {
    const val Sp_File_URL="KAOCHANG_APP_CONFIG" //配置路径地址
    private var appConfigBean: AppConfigBean?=null//配置实体


    /**
     * 获取设备订阅地址
     */
    fun getTopicDeviceIdUrl(url:String):String{
        return url.replace("{device_id}", getDeviceId())
    }
    /**
     * 获取app初始化参数
     */
    fun getAppConfig(): AppConfigBean {
        if (appConfigBean !=null){
            return appConfigBean!!
        }
        appConfigBean = PreferenceUtils.getObjectPreference<AppConfigBean>(Sp_File_URL,"sp_app_config")
        if (appConfigBean ==null){
            //初始化参数
            appConfigBean = AppConfigBean()
        }
        return appConfigBean!!
    }

    /**
     * 保存存储配置信息
     */
    fun saveAppConfig(appConfigBean: AppConfigBean) {
        this.appConfigBean=appConfigBean
        PreferenceUtils.saveObjectPreference(Sp_File_URL,"sp_app_config",appConfigBean)
    }

    /**
     * 获取设备唯一标识
     */
    fun getDeviceId():String{
        var androidId= PreferenceUtils.getStringPreference(Sp_File_URL,"sp_android_id",null)
        if (androidId==null){
            androidId=Settings.Secure.getString(MyApp.instance().contentResolver, Settings.Secure.ANDROID_ID)
            PreferenceUtils.saveStringPreference(Sp_File_URL,"sp_android_id",androidId)
        }
        return androidId
    }

    /**
     * 保存设备id
     */
    fun saveDeviceId(androidId:String){
        PreferenceUtils.saveStringPreference(Sp_File_URL,"sp_android_id",androidId)
    }
}