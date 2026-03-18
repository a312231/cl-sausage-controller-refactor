package cn.niuyannet.kaochang.android.init

import android.provider.Settings
import com.alibaba.fastjson.JSON
import cn.niuyannet.kaochang.android.MyApp
import cn.niuyannet.kaochang.android.utils.PreferenceUtils

/**
 * 初始化配置信息
 */
object AppConfig {
    const val Sp_File_URL = "KAOCHANG_APP_CONFIG"
    private const val KEY_APP_CONFIG_OBJECT = "sp_app_config"
    private const val KEY_APP_CONFIG_JSON = "sp_app_config_json"
    private var appConfigBean: AppConfigBean? = null

    /**
     * 获取设备订阅地址
     */
    fun getTopicDeviceIdUrl(url: String): String {
        return url.replace("{device_id}", getDeviceId())
    }

    /**
     * 获取 app 初始化参数。
     * 优先读取 JSON 配置，兼容迁移历史的 Java 序列化对象配置。
     */
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
        return appConfigBean!!
    }

    /**
     * 保存配置。
     * 使用 JSON 持久化，避免类字段变更导致升级后反序列化失败。
     */
    fun saveAppConfig(appConfigBean: AppConfigBean) {
        this.appConfigBean = appConfigBean
        PreferenceUtils.saveStringPreference(Sp_File_URL, KEY_APP_CONFIG_JSON, JSON.toJSONString(appConfigBean))
    }

    /**
     * 判断是否已经拿到可用于启动算法的有效设备配置。
     */
    fun hasValidDeviceConfig(): Boolean {
        val config = getAppConfig()
        return !config.deviceCode.isNullOrBlank() &&
            config.businessTime != null &&
            config.status in 0..1 &&
            config.onlineStatus in 0..4
    }

    /**
     * 获取设备唯一标识
     */
    fun getDeviceId(): String {
        var androidId = PreferenceUtils.getStringPreference(Sp_File_URL, "sp_android_id", null)
        if (androidId == null) {
            androidId = Settings.Secure.getString(MyApp.instance().contentResolver, Settings.Secure.ANDROID_ID)
            PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
        }
        return androidId
    }

    /**
     * 保存设备 id
     */
    fun saveDeviceId(androidId: String) {
        PreferenceUtils.saveStringPreference(Sp_File_URL, "sp_android_id", androidId)
    }
}
