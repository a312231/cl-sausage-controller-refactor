package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.ActivityStartInitBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.net.ApiResponseCompat
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils

/**
 * 设备初始化入口页。
 *
 * 当前主流程不再依赖视觉识别或 ARUCO 标定作为设备初始化前置条件。
 */
class StartInitActivity : BaseActivity() {
    private lateinit var binding: ActivityStartInitBinding
    private val spFileKey = "sp_file_init"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PreferenceUtils.getBooleanPreference(spFileKey, "sp_init_status", false)) {
            MainActivity.start(this)
            finish()
            return
        }

        binding = ActivityStartInitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etDeviceId.setText(AppConfig.getDeviceId())

        binding.btnConfirm.setOnClickListener {
            val config = AppConfig.getAppConfig()
            val canInitialize = StartInitPolicy.canInitializeDevice(
                forceVisionSuccess = config.forceVisionSuccess,
                hasArucoCorners = false
            )
            if (!canInitialize) {
                ToastUtils.showShort("当前设备状态不允许初始化")
                return@setOnClickListener
            }
            LogUtils.i("设备初始化：跳过 ARUCO 前置检测，直接尝试登录")
            
            showLoadingExt("初始化中...")
            val initInput = StartInitIdentity.normalize(binding.etDeviceId.text.toString())
            if (StartInitIdentity.isActivationCode(initInput)) {
                NetApi.initDeviceByActivationCode(initInput.uppercase()) { code, content ->
                    handleInitResult(initInput, code, content)
                }
            } else {
                NetApi.initDevice(initInput) { code, content ->
                    handleInitResult(initInput, code, content)
                }
            }
        }

        binding.btnStartCheck.setOnClickListener {
            SauceDetectionLauncher.launch(this)
        }
    }

    private fun handleInitResult(initInput: String, code: Int, content: String?) {
        dismissLoadingExt()
        if (code == 0) {
            val json = JSON.parseObject(content)
            if (ApiResponseCompat.isSuccessful(json)) {
                val data = json.getJSONObject("data")
                val deviceData = data?.getJSONObject("device")
                val remoteConfig = deviceData ?: data
                if (remoteConfig != null) {
                    val mergeResult = AppConfig.mergeRemoteConfig(
                        remoteConfig,
                        content,
                        "设备初始化",
                        preserveRuntimeConcurrencyState = false
                    )
                    if (!mergeResult.applied) {
                        LogUtils.w("设备初始化：云端返回设备配置未完整应用，message=${mergeResult.message}")
                    }
                }
                val deviceCode = data?.getString("deviceCode")?.takeIf { it.isNotBlank() }
                    ?: deviceData?.getString("deviceCode")?.takeIf { it.isNotBlank() }
                    ?: initInput
                if (AppConfig.getAppConfig().mqttPassword.isNullOrBlank()) {
                    LogUtils.w("设备初始化：云端未返回设备通信凭证，已拒绝完成初始化；请使用后台生成的一次性激活码")
                    ToastUtils.showShort("请使用后台生成的一次性激活码初始化设备")
                    return
                }
                PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", true)
                AppConfig.saveDeviceId(deviceCode)
                MainActivity.start(this)
                finish()
            } else {
                ToastUtils.showShort(ApiResponseCompat.messageText(json, "设备初始化失败"))
            }
        } else {
            ToastUtils.showShort("网络服务错误")
        }
    }
}
