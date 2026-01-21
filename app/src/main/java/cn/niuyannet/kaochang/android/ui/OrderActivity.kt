package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.detecition.SauceDetectionLauncher
import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

/**
 * 下单流程
 */
abstract class OrderActivity: BaseSauceDetection() {
    
    private var processingDialog: MaterialDialog? = null
    private var completedDialog: MaterialDialog? = null
    private var countDownTimer: CountDownTimer? = null

    private val REQUEST_CODE_PERMISSIONS: Int = 10
    private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.CAMERA
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化对话框
        initDialogs()
    }

    private fun initDialogs() {
        // 初始化处理中对话框
        processingDialog = MaterialDialog(this).apply {
            customView(view = LayoutInflater.from(context).inflate(R.layout.dialog_processing, null))
            cancelable(false)
            cancelOnTouchOutside(false)
        }
        
        // 初始化完成对话框
        completedDialog = MaterialDialog(this).apply {
            customView(view = LayoutInflater.from(context).inflate(R.layout.dialog_completed, null))
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }
    
    /**
     * 由子类实现的 UI 绑定钩子。
     * OrderActivity 在业务流程中会调用该方法以刷新界面。
     * 默认不做任何事，避免非 UI 子类需要实现。
     */
    open fun setDataToView() {}

    /**
     * 显示正在出烤肠对话框
     */
     fun showProcessingDialog() {
        processingDialog?.show()
    }
    
    /**
     * 隐藏正在出烤肠对话框
     */
     fun hideProcessingDialog() {
        processingDialog?.dismiss()
    }
    
    /**
     * 显示烤肠已出对话框，5秒后自动关闭
     */
     fun showCompletedDialog() {
        completedDialog?.show()
        
        // 开始倒计时
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                completedDialog?.view?.findViewById<TextView>(R.id.tv_countdown)?.text = 
                    "${seconds}秒后自动关闭"
            }
            
            override fun onFinish() {
                hideCompletedDialog()
            }
        }.start()
    }
    
    /**
     * 隐藏烤肠已出对话框
     */
     fun hideCompletedDialog() {
        countDownTimer?.cancel()
        completedDialog?.dismiss()
    }
    override fun onResume() {
        super.onResume()
        LogUtils.d("购买程序重新启动")
        // 如果已经有权限，重新启动相机
//        if (allPermissionsGranted() &&SauceDetectionUitls.cameraProvider == null) {
//            LogUtils.d("order 重启相机")
//            startCamera()
//        }
    }
    override fun onPause() {
        super.onPause()
         // 释放相机资源
//        if (SauceDetectionUitls.cameraProvider != null) {
//            LogUtils.d("order 释放相机")
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // 清理资源
        countDownTimer?.cancel()
        processingDialog?.dismiss()
        completedDialog?.dismiss()

//        if (SauceDetectionUitls.cameraProvider != null) {
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//            LogUtils.d("order 销毁相机资源")
//        }
    }

    /**
     * 取出来保温状态下的烤肠
      */
    @OptIn(DelicateCoroutinesApi::class)
    fun purchaseOrder(orderMessage:String) {
            LogUtils.d("order 客户下单购买")
            //更新状态到云端
            AppConfig.getAppConfig().onlineStatus = 4
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            SendServerHelper.publishServiceUpdateStatus()
            // 显示处理中对话框
            showProcessingDialog()

            // 隐藏QRCode
            setDataToView()

            GlobalScope.launch(Dispatchers.IO) {

                // 最多等待机械臂空闲 240 秒
                val maxWaitTime = 240_000L
                val checkInterval = 1000L
                var waited = 0L

                // 运动状态 为0是说明机械臂闲置
                var appState = AppConfig.getAppConfig().moveStatus
                while (appState != 0 && waited < maxWaitTime) {
                    delay(checkInterval)
                    LogUtils.d("等待运动状态改变 ${appState}")
                    appState = AppConfig.getAppConfig().moveStatus
                    waited += checkInterval
                }

                if (appState != 0) {
                    LogUtils.d("等待机械臂空闲超时，放弃取肠")
                }
                else {
                    // 机械臂状态改为取肠
                    appState = 2
                    AppConfig.getAppConfig().moveStatus = appState
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                }

                val json = JSON.parseObject(orderMessage)
                val orderNo = json.getString("order_no")
                val goodsList =
                    JSON.parseArray(json.getString("goods_items"), KcOrderItem::class.java)
                // 遍历商品列表
                goodsList.forEach { item ->
                    // 根据数量循环取出烤肠
                    repeat(item.quantity) {
                        if (appState != 2) { // 若机械臂不处于夹取状态
                            // 构建MQTT消息
                            SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                                put("order_no", orderNo)
                                put("item_id", item.id)
                                put("product_id", item.productId)
                                put("taste_code", item.tasteCode)
                                put("status", 5) // 退钱
                            })
                        }
                        else{ // 若机械臂处于夹取状态
                            try {
                                // 构建MQTT消息
                                SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                                    put("order_no", orderNo)
                                    put("item_id", item.id)
                                    put("product_id", item.productId)
                                    put("taste_code", item.tasteCode)
                                    put("status", 1)
                                })
                                // 调用取货算法
                                KaoChangAlgorithm.purchase(item.tasteCode, item.productId)
                                // 构建MQTT消息
                                SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                                    put("order_no", orderNo)
                                    put("item_id", item.id)
                                    put("product_id", item.productId)
                                    put("taste_code", item.tasteCode)
                                    put("status", 2)
                                })
                            }catch (e: Exception) {
                                LogUtils.e("机械臂执行失败: ${e.message}")
                                GlobalScope.launch(Dispatchers.Main) {
                                    AppConfig.getAppConfig().onlineStatus = 3
                                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                                }
                            }
                        }
                    }
                }

                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                GlobalScope.launch(Dispatchers.Main) {
                    val status = AppConfig.getAppConfig().onlineStatus
                    if (status == 4){
                        AppConfig.getAppConfig().onlineStatus = 1
                    }
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    SendServerHelper.publishServiceUpdateStatus()
                    hideProcessingDialog()
                    showCompletedDialog()
                    // 显示QRCode
                    setDataToView()
                }
            }
    }

    /**
     * 写入寄存器
     */
    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    KaoChangOperate.writeSingleRegister(register,value)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}