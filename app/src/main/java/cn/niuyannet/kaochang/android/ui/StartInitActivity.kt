package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.ActivityStartInitBinding
import cn.niuyannet.kaochang.android.init.AppConfig
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
            NetApi.initDevice(binding.etDeviceId.text.toString()) { code, content ->
                dismissLoadingExt()
                if (code == 0) {
                    val json = JSON.parseObject(content)
                    if (json.getIntValue("code") == 200) {
                        PreferenceUtils.saveBooleanPreference(spFileKey, "sp_init_status", true)
                        AppConfig.saveDeviceId(binding.etDeviceId.text.toString())
                        MainActivity.start(it.context)
                        finish()
                    } else {
                        ToastUtils.showShort(json.getString("msg"))
                    }
                } else {
                    ToastUtils.showShort("网络服务错误")
                }
            }
        }

        binding.btnStartCheck.setOnClickListener {
            SauceDetectionLauncher.launch(this)
        }
    }
}
