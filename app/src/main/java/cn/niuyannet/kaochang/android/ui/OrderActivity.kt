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
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

/**
 * 下单流程
 */
abstract class OrderActivity: BaseSauceDetection() {

    private data class DeliveryResult(
        val success: Boolean,
        val deliveredCount: Int
    )

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

    private fun updateProcessingDialog(current: Int, total: Int) {
        processingDialog?.view?.findViewById<TextView>(R.id.tv_num)?.text = "$current/$total"
    }

    /**
     * 设备已经恢复到可营业态时，自动清掉遗留的错误标记。
     * 当前项目里 errorStatus 在故障时会被置为 1/2，但只有管理员页有手动清零逻辑，
     * 所以这里补一层“恢复营业即清错”的自动恢复，避免后台设备列表长期显示“错误”。
     */
    protected fun clearErrorStatusIfRecovered(
        reason: String,
        retryDelaysMs: List<Long> = emptyList()
    ): Boolean {
        val config = AppConfig.getAppConfig()
        if (config.errorStatus == 0) {
            return false
        }
        if (config.status != 1 || config.onlineStatus != 1 || config.moveStatus != 0) {
            LogUtils.d(
                "跳过自动清错：reason=$reason, status=${config.status}, onlineStatus=${config.onlineStatus}, moveStatus=${config.moveStatus}, errorStatus=${config.errorStatus}"
            )
            return false
        }
        val oldErrorStatus = config.errorStatus
        config.errorStatus = 0
        AppConfig.saveAppConfig(config)
        LogUtils.w("设备已恢复运营，自动清除错误标记：reason=$reason, errorStatus=$oldErrorStatus->0")
        syncDeviceStatusToServer(retryDelaysMs = retryDelaysMs)
        return true
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
    fun purchaseOrder(orderMessage:String) {

            lifecycleScope.launch(Dispatchers.IO) {

                // 最多等待机械臂空闲 240 秒
                val maxWaitTime = 240_000L
                val checkInterval = 1000L
                var waited = 0L

                // 运动状态 为0是说明机械臂闲置
                // moveStatus 是本地机械动作状态，不是设备营业状态：
                // 0 = 空闲，2 = 正在执行取肠/出餐动作
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
                    // 机械开始执行当前订单，本地动作状态置为 2
                    appState = 2
                    AppConfig.getAppConfig().moveStatus = appState
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                }

                val json = JSON.parseObject(orderMessage)
                val orderNo = json.getString("order_no")
                val goodsList =
                    JSON.parseArray(json.getString("goods_items"), KcOrderItem::class.java)
                // 遍历商品列表
                val totalCount = goodsList.sumOf { it.quantity }
                var deliveredTotal = 0
                var orderCompleted = true
                for (item in goodsList) {
                    val result = handleOrderItemDelivery(
                        orderNo = orderNo,
                        item = item,
                        totalCount = totalCount,
                        deliveredBase = deliveredTotal
                    )
                    deliveredTotal += result.deliveredCount
                    if (!result.success) {
                        orderCompleted = false
                        break
                    }
                }
                orderCompleted = orderCompleted && deliveredTotal == totalCount

                // 当前订单动作结束后，本地动作状态恢复为空闲
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                withContext(Dispatchers.Main) {
                    val status = AppConfig.getAppConfig().onlineStatus
                    // 订单动作结束后，如果设备仍停留在 4（工作中），主动回到 1（运营中）
                    if (status == 4){
                        AppConfig.getAppConfig().onlineStatus = if (orderCompleted) 1 else 3
                    }
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    val errorRecovered = if (orderCompleted) {
                        clearErrorStatusIfRecovered(
                            reason = "order_completed",
                            retryDelaysMs = listOf(2000L, 6000L)
                        )
                    } else false
                    // 这里上报的是设备营业状态（onlineStatus），不是订单事件 status
                    // 订单结束时同时走 MQTT + HTTP，并做轻量重试，降低“假工作中”概率
                    if (!errorRecovered) {
                        syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                    }
                    hideProcessingDialog()
                    if (orderCompleted) {
                        showCompletedDialog()
                    } else {
                        Toast.makeText(this@OrderActivity, "订单未完成，请联系维护人员", Toast.LENGTH_LONG).show()
                    }
                    // 显示QRCode
                    setDataToView()
                }
            }
    }

    /**
     * 将当前设备营业状态同步到云端。
     * 主通道是 MQTT action=2，HTTP updateDeviceInfo 作为补偿通道。
     * retryDelaysMs 用于在网络瞬时抖动时补发，避免订单已完成但云端仍停留在工作中。
     */
    private suspend fun handleOrderItemDelivery(
        orderNo: String,
        item: KcOrderItem,
        totalCount: Int,
        deliveredBase: Int
    ): DeliveryResult {
        var deliveredCount = 0
        var attemptCount = 0
        val maxAttempts = item.quantity * 3

        while (deliveredCount < item.quantity && attemptCount < maxAttempts) {
            attemptCount++
            withContext(Dispatchers.Main) {
                showProcessingDialog()
                updateProcessingDialog(deliveredBase + deliveredCount + 1, totalCount)
            }
            val currentMoveStatus = AppConfig.getAppConfig().moveStatus
            if (currentMoveStatus != 2) {
                LogUtils.d("璁惧宸插湪澶瑰彇涓紝灞忚斀褰撳墠璁㈠崟璇锋眰")
                return DeliveryResult(false, deliveredCount)
            }

            try {
                SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                    put("order_no", orderNo)
                    put("item_id", item.id)
                    put("product_id", item.productId)
                    put("taste_code", item.tasteCode)
                    put("status", 1)
                })

                when (KaoChangAlgorithm.purchase(item.tasteCode, item.productId)) {
                    KaoChangOperate.TakeSausageResult.TAKEN_BY_USER -> {
                        deliveredCount++
                        SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                            put("order_no", orderNo)
                            put("item_id", item.id)
                            put("product_id", item.productId)
                            put("taste_code", item.tasteCode)
                            put("status", 2)
                        })
                    }

                    KaoChangOperate.TakeSausageResult.DISCARDED -> {
                        deliveredCount++
                        SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                            put("order_no", orderNo)
                            put("item_id", item.id)
                            put("product_id", item.productId)
                            put("taste_code", item.tasteCode)
                            put("status", 2)
                        })
                        LogUtils.w("用户未及时取走，按丢弃完成处理：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attempt=$attemptCount/$maxAttempts")
                        LogUtils.w("鍑洪鍚庣敤鎴锋湭鍙栬蛋锛屽凡涓㈠純锛屽噯澶囪ˉ鍙戝悓涓€浠? orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attempt=$attemptCount/$maxAttempts")
                    }

                    KaoChangOperate.TakeSausageResult.ERROR -> {
                        SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                            put("order_no", orderNo)
                            put("item_id", item.id)
                            put("product_id", item.productId)
                            put("taste_code", item.tasteCode)
                            put("status", 5)
                        })
                        throw IllegalStateException("鍙栬偁澶辫触锛屾棤娉曠户缁飽绾? orderNo=$orderNo, itemId=${item.id}")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("鏈烘鑷傛墽琛屽け璐? ${e.message}")
                withContext(Dispatchers.Main) {
                    AppConfig.getAppConfig().onlineStatus = 3
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                }
                return DeliveryResult(false, deliveredCount)
            }
        }

        if (deliveredCount < item.quantity) {
            LogUtils.e("璁㈠崟鏈畬鎴愬叏閮ㄥ飽绾︼細orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
            SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                put("order_no", orderNo)
                put("item_id", item.id)
                put("product_id", item.productId)
                put("taste_code", item.tasteCode)
                put("status", 5)
            })
            return DeliveryResult(false, deliveredCount)
        }

        return DeliveryResult(true, deliveredCount)
    }

    protected fun syncDeviceStatusToServer(retryDelaysMs: List<Long> = emptyList()) {
        val expectedStatus = AppConfig.getAppConfig().onlineStatus
        LogUtils.d("设备状态同步开始：expectedOnlineStatus=$expectedStatus, retryCount=${retryDelaysMs.size}")
        LogUtils.d("设备状态同步触发 MQTT 上报：attempt=0, onlineStatus=$expectedStatus")
        SendServerHelper.publishServiceUpdateStatus()
        LogUtils.d("设备状态同步触发 HTTP 上报：attempt=0, onlineStatus=$expectedStatus")
        DataManagementAPI.uploadDeviceInfo { code, content ->
            if (code == 0) {
                LogUtils.d("设备状态 HTTP 上报成功：attempt=0, onlineStatus=$expectedStatus")
            } else {
                LogUtils.e("设备状态 HTTP 上报失败：attempt=0, onlineStatus=$expectedStatus, code=$code, content=$content")
            }
        }

        retryDelaysMs.forEachIndexed { index, delayMs ->
            lifecycleScope.launch(Dispatchers.IO) {
                delay(delayMs)
                val currentStatus = AppConfig.getAppConfig().onlineStatus
                if (currentStatus == expectedStatus) {
                    val attempt = index + 1
                    LogUtils.d("设备状态同步触发补发：attempt=$attempt, delayMs=$delayMs, onlineStatus=$currentStatus")
                    LogUtils.d("设备状态同步触发 MQTT 上报：attempt=$attempt, onlineStatus=$currentStatus")
                    SendServerHelper.publishServiceUpdateStatus()
                    LogUtils.d("设备状态同步触发 HTTP 上报：attempt=$attempt, onlineStatus=$currentStatus")
                    DataManagementAPI.uploadDeviceInfo { code, content ->
                        if (code == 0) {
                            LogUtils.d("设备状态 HTTP 上报成功：attempt=$attempt, onlineStatus=$currentStatus")
                        } else {
                            LogUtils.e("设备状态 HTTP 上报失败：attempt=$attempt, onlineStatus=$currentStatus, code=$code, content=$content")
                        }
                    }
                } else {
                    LogUtils.d("设备状态同步取消补发：attempt=${index + 1}, expectedOnlineStatus=$expectedStatus, currentOnlineStatus=$currentStatus")
                }
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
