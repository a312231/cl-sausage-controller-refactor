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
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
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
 * 订单履约流程。
 *
 * 这里负责：
 * - 接收已下发到上位机的订单内容
 * - 串行执行每一根烤肠的出餐
 * - 在动作开始/完成/失败时向后端上报订单事件
 * - 在订单结束时把设备营业状态从“工作中”收尾回“运营中/维护中”
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
     * 由子类实现的 UI 刷新钩子。
     * 订单流程结束、二维码需要重新显示时会回调这里。
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
     * 执行整单履约。
     * 一个订单可能包含多个商品项、每个商品项又可能包含多根烤肠，这里按根串行处理。
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
                val orderSummary = goodsList.joinToString(" | ") {
                    "itemId=${it.id}, productId=${it.productId}, tasteCode=${it.tasteCode}, quantity=${it.quantity}"
                }
                LogUtils.d(
                    "【订单流转】开始执行整单履约：orderNo=$orderNo, itemCount=${goodsList.size}, totalQuantity=$totalCount, items=[$orderSummary]"
                )
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
                    val config = AppConfig.getAppConfig()
                    val status = config.onlineStatus
                    // 订单动作结束后，如果设备仍停留在 4（工作中），主动回到 1（运营中）
                    if (status == 4){
                        config.onlineStatus = if (orderCompleted && config.errorStatus == 0) 1 else 3
                    }
                    AppConfig.saveAppConfig(config)
                    // 这里上报的是设备营业状态（onlineStatus），不是订单事件 status
                    // 订单结束时同时走 MQTT + HTTP，并做轻量重试，降低“假工作中”概率
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
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
     * 执行单个商品项的逐根出餐。
     *
     * 注意：
     * - TAKEN_BY_USER 和 DISCARDED 当前都会把 deliveredCount + 1
     * - 只有 ERROR 才会中断本商品项并上报 status=5
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
            val currentIndex = deliveredBase + deliveredCount + 1
            LogUtils.d(
                "【订单流转】开始执行子订单：orderNo=$orderNo, itemId=${item.id}, productId=${item.productId}, " +
                    "tasteCode=${item.tasteCode}, current=$currentIndex/$totalCount, itemProgress=${deliveredCount + 1}/${item.quantity}, attempt=$attemptCount/$maxAttempts"
            )
            val currentMoveStatus = AppConfig.getAppConfig().moveStatus
            if (currentMoveStatus != 2) {
                LogUtils.d("璁惧宸插湪澶瑰彇涓紝灞忚斀褰撳墠璁㈠崟璇锋眰")
                return DeliveryResult(false, deliveredCount)
            }

            try {
                if (!ensureModbusReadyForDelivery(orderNo, item.id)) {
                    throw IllegalStateException("出餐前检测到下位机未连接，已终止本次出餐：orderNo=$orderNo, itemId=${item.id}")
                }
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
                        LogUtils.d(
                            "【履约完结】视觉判定顾客已取走，开始上报 Java 后台订单状态变更(status=2)：orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${deliveredBase + deliveredCount}/$totalCount"
                        )
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
                        LogUtils.w(
                            "【履约完结】售卖口超时未取走，系统已丢弃并按本次履约完成处理，开始上报 Java 后台订单状态变更(status=2)：orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${deliveredBase + deliveredCount}/$totalCount"
                        )
                        SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                            put("order_no", orderNo)
                            put("item_id", item.id)
                            put("product_id", item.productId)
                            put("taste_code", item.tasteCode)
                            put("status", 2)
                        })
                        LogUtils.w("顾客未及时取走烤肠，系统已执行丢弃并按本次出餐完成处理：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attempt=$attemptCount/$maxAttempts")
                        LogUtils.w("现场请重点排查售卖口视觉识别是否稳定，以及顾客取走动作是否被正确识别：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attempt=$attemptCount/$maxAttempts")
                    }

                    KaoChangOperate.TakeSausageResult.ERROR -> {
                        SendServerHelper.publishServiceUpdateOrder(JSONObject().apply {
                            put("order_no", orderNo)
                            put("item_id", item.id)
                            put("product_id", item.productId)
                            put("taste_code", item.tasteCode)
                            put("status", 5)
                        })
                        LogUtils.e("出餐流程进入故障分支，已上报 status=5：orderNo=$orderNo, itemId=${item.id}")
                        throw IllegalStateException("取肠失败，无法继续履约：orderNo=$orderNo, itemId=${item.id}")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("出餐失败，设备切换为维护中：${e.message}")
                LogUtils.e("设备已上报故障状态，请现场检查下位机连接、售卖口识别和机械执行状态：${e.message}")
                withContext(Dispatchers.Main) {
                    AppConfig.getAppConfig().errorStatus = 2
                    AppConfig.getAppConfig().onlineStatus = 3
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                }
                return DeliveryResult(false, deliveredCount)
            }
        }

        if (deliveredCount < item.quantity) {
            LogUtils.e("出餐数量不足，按故障处理：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
            LogUtils.e("当前已停止继续履约，请现场检查售卖口与机械链路后再恢复：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
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

    /**
     * 出餐前的通信层防御性预检。
     * 这里只判断下位机是否可通信，不判断 201 的忙闲状态。
     */
    private suspend fun ensureModbusReadyForDelivery(
        orderNo: String,
        itemId: Int,
        maxRetryCount: Int = 3,
        retryDelayMs: Long = 1500L
    ): Boolean {
        if (VMModbusHelper.connectStatus()) {
            return true
        }

        LogUtils.w("出餐前检测到下位机连接断开，开始短时重连：orderNo=$orderNo, itemId=$itemId")
        repeat(maxRetryCount) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                LogUtils.d("出餐前重连下位机结果：attempt=$attempt/$maxRetryCount, status=$status, orderNo=$orderNo, itemId=$itemId")
            }
            delay(retryDelayMs)
            if (VMModbusHelper.connectStatus()) {
                LogUtils.d("出餐前下位机重连成功：attempt=$attempt/$maxRetryCount, orderNo=$orderNo, itemId=$itemId")
                return true
            }
        }

        LogUtils.e("出餐前下位机持续断开，已放弃本次出餐：orderNo=$orderNo, itemId=$itemId")
        return false
    }

    /**
     * 同步设备营业状态。
     * MQTT 是主通道，HTTP 作为补偿通道；补发只在期望状态未变化时执行。
     */
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
