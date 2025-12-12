package cn.niuyannet.kaochang.android.ui

import android.os.Bundle
import cn.niuyannet.kaochang.android.base.BaseActivity
import cn.niuyannet.kaochang.android.databinding.ActivityStartInitBinding
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.utils.dismissLoadingExt
import cn.niuyannet.kaochang.android.utils.showLoadingExt
import com.alibaba.fastjson.JSON
import com.blankj.utilcode.util.ToastUtils

/**
 * 初始化配置类
 * 
 */
class StartInitActivity : BaseActivity() {
    private lateinit var binding: ActivityStartInitBinding
    val sp_file_url="sp_file_init"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PreferenceUtils.getBooleanPreference(sp_file_url,"sp_init_status",false)){
            //SauceDetectionLauncher.launch(this)
            MainActivity.start(this)
            finish()
            return
        }
        binding = ActivityStartInitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etDeviceId.setText(AppConfig.getDeviceId())
        binding.btnConfirm.setOnClickListener {
            var sharedPreferences = getSharedPreferences("aruco_helper_prefs", MODE_PRIVATE);
              var cornersJson = sharedPreferences.getString("corners_data", null);
            if (cornersJson == null) {
                ToastUtils.showShort("未检测aruco")
                return@setOnClickListener
            }
            showLoadingExt("初始化中...")
            NetApi.initDevice(binding.etDeviceId.text.toString()){code,content->
                dismissLoadingExt()
                if (code==0){
                    val json=JSON.parseObject(content)
                    if (json.getIntValue("code")==200){
                        PreferenceUtils.saveBooleanPreference(sp_file_url,"sp_init_status",true)
                        AppConfig.saveDeviceId(binding.etDeviceId.text.toString())
                        MainActivity.start(it.context)
                        finish()
                    }else{
                        ToastUtils.showShort(json.getString("msg"))
                    }
                }else{
                    ToastUtils.showShort("网络服务错误")
                }
            }

        }
        binding.btnStartCheck.setOnClickListener {
            SauceDetectionLauncher.launch(this)
        }
    }


}