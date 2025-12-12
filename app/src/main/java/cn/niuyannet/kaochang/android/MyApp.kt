package cn.niuyannet.kaochang.android

import android.app.Application
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.parser.ParserConfig
import com.lzy.okgo.OkGo
import com.tencent.bugly.crashreport.CrashReport
import kotlin.properties.Delegates

class MyApp: Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化日志系统
        LogUtils.init(this)
        // 记录应用启动日志
        LogUtils.i("Application started")
        OkGo.getInstance().init(this)
        //烤盘初始化
        KaoPanHelper.init()
        //网络数据初始化更新
        DataManagementAPI.dataSyncServer()
        //是否初始化，如果没有初始化就先进行初始化， 否则进行初始化
        ParserConfig.getGlobalInstance().isAutoTypeSupport = true
        //bugly 奔溃记录
        CrashReport.initCrashReport(applicationContext, "5905e69ba3", false)
    }
    companion object {
        private var instance: MyApp by Delegates.notNull()
        fun instance() = instance
    }
}