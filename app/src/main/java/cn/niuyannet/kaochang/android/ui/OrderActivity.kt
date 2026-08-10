package cn.niuyannet.kaochang.android.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import cn.niuyannet.kaochang.android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.KcOrderItem
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.services.KaoChangAlgorithm
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.services.OrderExecutionOutcome
import cn.niuyannet.kaochang.android.services.OrderSupplementResumePolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderEventCompensationPolicy
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderContext
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupOrderStatus
import cn.niuyannet.kaochang.android.services.actiongroup.OrderDeliveryClaim
import cn.niuyannet.kaochang.android.services.actiongroup.OrderFailureMetadata
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.OrderProcessingStage
import cn.niuyannet.kaochang.android.utils.OrderProcessingStageBridge
import cn.niuyannet.kaochang.android.utils.OrderStateManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 订单履约流程。
 *
 * 这里负责：
 * - 接收已下发到上位机的订单内容
 * - 串行执行每一根烤肠的出餐
 * - 在动作开始/完成/失败时向后端上报订单事件
 * - 在订单结束时把设备营业状态从“工作中”收口回“运营中/维护中”
 */
abstract class OrderActivity: BaseSauceDetection() {

    private data class DeliveryResult(
        val success: Boolean,
        val deliveredCount: Int,
        val outcome: OrderExecutionOutcome = if (success) {
            OrderExecutionOutcome.SUCCESS
        } else {
            OrderExecutionOutcome.MECHANICAL_FAILED
        }
    )

    private data class MoveStatusNormalizationResult(
        val canContinue: Boolean,
        val initialStatus: Int,
        val finalStatus: Int,
        val waitedMs: Long,
        val reason: String
    )

    private var processingDialog: MaterialDialog? = null
    private val processingStageListener: (OrderProcessingStage) -> Unit = { updateOrderProcessingStage(it) }

    private fun orderEventStatusText(status: Int): String = when (status) {
        1 -> "1（开始夹取）"
        2 -> "2（单次履约完成：out4 已收口）"
        6 -> "6（历史状态：顾客超时未取，4F 主流程不再产生）"
        5 -> "5（单次履约失败）"
        else -> "${status}（未知）"
    }

    private fun onlineStatusText(status: Int): String = when (status) {
        0 -> "0（待投运）"
        1 -> "1（运营中）"
        2 -> "2（休息中）"
        3 -> "3（维护中）"
        4 -> "4（工作中/正在出餐）"
        else -> "${status}（未知）"
    }

    private fun errorStatusText(status: Int): String = when (status) {
        0 -> "0（正常）"
        1 -> "1（识别/流程错误）"
        2 -> "2（通信/硬件级故障）"
        else -> "${status}（未知）"
    }

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
        
        OrderProcessingStageBridge.register(processingStageListener)
    }

    private fun updateOrderProcessingStage(stage: OrderProcessingStage) {
        processingDialog?.view?.findViewById<TextView>(R.id.tv_message)?.text = stage.customerText
    }
    
    /**
     * 由子类实现的 UI 刷新钩子。
     * 订单流程结束、二维码需要重新显示时会回调这里。
     */
    open fun setDataToView() {}

    /**
     * 显示正在出烤肠对话框
     */
     fun showProcessingDialog(current: Int = 1, total: Int = 1) {
        updateProcessingDialog(current, total.coerceAtLeast(1))
        updateOrderProcessingStage(OrderProcessingStage.WAITING)
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
    
    override fun onResume() {
        super.onResume()
        LogUtils.d("【订单流转】订单页重新进入前台")
        // 如果已经有权限，重新启动相机
//        if (allPermissionsGranted() &&SauceDetectionUitls.cameraProvider == null) {
//            LogUtils.d("order 重新启动相机")
//            startCamera()
//        }
    }
    override fun onPause() {
        super.onPause()
         // 释放相机资源
//        if (SauceDetectionUitls.cameraProvider != null) {
//            LogUtils.d("order 閲婃斁鐩告満")
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // 娓呯悊璧勬簮
        processingDialog?.dismiss()
        OrderProcessingStageBridge.unregister(processingStageListener)

//        if (SauceDetectionUitls.cameraProvider != null) {
//            SauceDetectionUitls.cameraProvider!!.unbindAll()
//            SauceDetectionUitls.cameraProvider=null
//            LogUtils.d("order 销毁相机资源")
//        }
    }

    /**
     * 执行整单履约。
     * 一个订单可能包含多个商品项，每个商品项又可能包含多根烤肠，这里按根串行处理。
     */
    fun purchaseOrder(orderMessage:String) {
        val safeOrderNo = try { com.alibaba.fastjson.JSON.parseObject(orderMessage).getString("order_no") ?: "" } catch (_: Exception) { "" }
        KaoChangOperate.requestSupplementPauseForOrder(safeOrderNo)
        var orderOutcome = OrderExecutionOutcome.UNKNOWN
        var normalCloseoutCompleted = false
        var preDeliveryClaim: OrderDeliveryClaim? = null
        var orderClaimReleased = false
        var orderCloseoutCommitted = false
        OrderProcessingStageBridge.publish(
            if (AppConfig.getAppConfig().moveStatus != OrderMoveStatusPolicy.MOVE_STATUS_IDLE) {
                OrderProcessingStage.SUPPLEMENTING
            } else {
                OrderProcessingStage.WAITING
            }
        )
            lifecycleScope.launch(Dispatchers.IO) {
                try {

                // 售卖优先不代表硬中断动作：等待当前补肠/搬盘/丢弃动作安全收口后再出餐。
                val fallbackMaxWaitTime = 240_000L
                val checkInterval = 1000L
                var waited = 0L
                val manualOutRecoveryPending = KaoChangOperate.shouldRecoverManualOutBeforeIncomingOrder(safeOrderNo)
                val manualOutRecoveryFailed = if (manualOutRecoveryPending) {
                    val recovered = runCatching {
                        KaoChangOperate.ensureActionGroupReadyForOrderDispatch(
                            "订单入口恢复残留维护手动取肠：orderNo=$safeOrderNo"
                        )
                    }.getOrElse { error ->
                        LogUtils.e(
                            "【订单流转】订单入口恢复残留维护手动取肠异常：" +
                                "orderNo=$safeOrderNo，error=${error.message ?: error.javaClass.simpleName}"
                        )
                        false
                    }
                    if (!recovered) {
                        LogUtils.w(
                            "【订单流转】订单入口恢复残留维护手动取肠未通过，直接结束本单，" +
                                "避免无意义等待：orderNo=$safeOrderNo"
                        )
                    }
                    !recovered
                } else {
                    false
                }
                var lastWaitLogAt = -10_000L
                var lastWaitSignature = ""

                // 运动状态不为 0 或动作组资源锁未释放时，说明机械臂仍在忙碌。
                // moveStatus 是本地机械动作状态，不是设备营业状态：
                // 0 = 空闲，2 = 正在执行取肠/出餐动作。
                var appState = AppConfig.getAppConfig().moveStatus
                var actionGroupBusy = KaoChangOperate.isActionGroupBusyForOrderGate()
                var maxWaitTime = if (manualOutRecoveryFailed) 0L else OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(appState)
                    ?: fallbackMaxWaitTime
                while (!manualOutRecoveryFailed && (appState != 0 || actionGroupBusy) && waited < maxWaitTime) {
                    delay(checkInterval)
                    appState = AppConfig.getAppConfig().moveStatus
                    actionGroupBusy = KaoChangOperate.isActionGroupBusyForOrderGate()
                    val waitSignature = "$appState/$actionGroupBusy"
                    if (waited == 0L || waitSignature != lastWaitSignature || waited - lastWaitLogAt >= 10_000L) {
                        LogUtils.d(
                            "【订单流转】等待机械动作安全收口：" +
                                "moveStatus=${OrderMoveStatusPolicy.describe(appState)}, " +
                                "actionGroupBusy=$actionGroupBusy, waitedMs=$waited/$maxWaitTime"
                        )
                        lastWaitLogAt = waited
                        lastWaitSignature = waitSignature
                    }
                    maxWaitTime = maxOf(
                        maxWaitTime,
                        OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(appState)
                            ?: fallbackMaxWaitTime
                    )
                    waited += checkInterval
                }

                if (appState != 0 || actionGroupBusy) {
                    LogUtils.w(
                        "【订单流转】售卖优先等待当前机械动作安全收口超时，暂不硬出餐：" +
                            "moveStatus=${OrderMoveStatusPolicy.describe(appState)}, " +
                            "actionGroupBusy=$actionGroupBusy, waitedMs=$waited/$maxWaitTime"
                    )
                }
                else {
                    // 机械臂开始执行当前订单，本地动作状态置为 2。
                    // 只有 moveStatus=2 才允许后续继续执行单根出餐。
                    preDeliveryClaim = KaoChangOperate.tryClaimOrderDeliveryMoveStatus(safeOrderNo)
                    appState = AppConfig.getAppConfig().moveStatus
                    if (preDeliveryClaim == null) {
                        LogUtils.w(
                            "【订单流转】锁内重新检查后未能接管出餐，停止本单动作：" +
                                "moveStatus=${OrderMoveStatusPolicy.describe(appState)}"
                        )
                    }
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
                    "【订单流转】收到新的顾客下单请求：orderNo=$orderNo, itemCount=${goodsList.size}, totalQuantity=$totalCount, items=[$orderSummary]"
                )
                var deliveredTotal = 0
                var orderCompleted = true
                if (preDeliveryClaim == null) {
                    orderCompleted = false
                    orderOutcome = OrderExecutionOutcome.NO_DISPATCH
                    goodsList.forEach { item ->
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            5,
                            OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                        )
                    }
                } else {
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
                            orderOutcome = result.outcome
                            break
                        }
                    }
                }
                orderCompleted = orderCompleted && deliveredTotal == totalCount
                if (orderCompleted && deliveredTotal == totalCount) {
                    orderOutcome = OrderExecutionOutcome.SUCCESS
                } else if (orderOutcome == OrderExecutionOutcome.UNKNOWN) {
                    orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN
                }
                val terminalStatePersisted = when (orderOutcome) {
                    OrderExecutionOutcome.SUCCESS -> OrderStateManager.markAsFinishedDurable(safeOrderNo)
                    OrderExecutionOutcome.MECHANICAL_FAILED -> OrderStateManager.markAsFailedDurable(safeOrderNo)
                    else -> true
                }
                if (!terminalStatePersisted) {
                    orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN
                    orderCompleted = false
                }

                // 当前订单动作结束后，本地动作状态恢复为空闲；如果本单没有拿到出餐控制权，
                // 不能清掉补肠/搬盘/丢弃等其他动作的 moveStatus。
                if (terminalStatePersisted && OrderSupplementResumePolicy.shouldReleaseClaim(orderOutcome)) {
                    orderClaimReleased = preDeliveryClaim?.let { claim ->
                        KaoChangOperate.releaseOrderDeliveryMoveStatus(claim, "订单动作收口：$safeOrderNo，outcome=$orderOutcome")
                    } ?: true
                    if (!orderClaimReleased && preDeliveryClaim != null) {
                        orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN
                        orderCompleted = false
                    } else if (orderClaimReleased && orderOutcome == OrderExecutionOutcome.MECHANICAL_FAILED) {
                        runCatching { KaoChangOperate.clearSettledOrderPause(safeOrderNo) }
                            .onFailure { error ->
                                LogUtils.e(
                                    "【订单流转】机械失败已释放 claim，但清理订单暂停失败：" +
                                        "orderNo=$safeOrderNo，error=${error.message ?: error.javaClass.simpleName}"
                                )
                            }
                    }
                }
                orderCloseoutCommitted = terminalStatePersisted && orderClaimReleased
                orderCompleted = orderCompleted && OrderSupplementResumePolicy.shouldResume(orderOutcome)
                withContext(Dispatchers.Main) {
                    val config = AppConfig.getAppConfig()
                    val status = config.onlineStatus
                    // 订单动作结束后，如果设备仍停留在 4（工作中），主动收口到 1（运营中）或 3（维护中）。
                    if (status == 4){
                        val nextStatus = if (
                            !OrderSupplementResumePolicy.shouldEnterMaintenance(orderOutcome) &&
                            config.errorStatus == 0
                        ) 1 else 3
                        config.onlineStatus = nextStatus
                        LogUtils.i(
                            "【状态切换】订单结束后设备状态收口：onlineStatus（设备营业状态）=" +
                                "${onlineStatusText(status)}->${onlineStatusText(nextStatus)}，" +
                                "errorStatus（设备故障状态）=${errorStatusText(config.errorStatus)}"
                        )
                    }
                    AppConfig.saveAppConfig(config)
                    // 这里上报的是设备营业状态（onlineStatus），不是订单事件 status。
                    // 订单结束后用 MQTT 轻量补发设备运行态，降低“假工作中”的概率。
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                    // 订单结束后立即同步烤盘状态和库存到云端，保证扫码端看到实时数据。
                    DataManagementAPI.uploadDataToServer()
                    hideProcessingDialog()
                    if (!orderCompleted) {
                        Toast.makeText(this@OrderActivity, "订单未完成，请联系维护人员", Toast.LENGTH_LONG).show()
                    }
                    // 订单结束后刷新首页二维码、状态文案和预计时间。
                    setDataToView()
                }
                normalCloseoutCompleted = true
            } catch (cancelled: CancellationException) {
                if (!orderCloseoutCommitted) {
                    orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN
                    LogUtils.e("【订单流转】整单处理被取消，结果保持 UNKNOWN 并保留机械恢复证据：orderNo=$safeOrderNo")
                } else {
                    LogUtils.e("【订单流转】订单收口已提交，后续流程被取消，不回滚订单结果：orderNo=$safeOrderNo")
                }
                throw cancelled
            } catch (e: Exception) {
                if (!orderCloseoutCommitted) {
                    orderOutcome = OrderExecutionOutcome.EVIDENCE_UNKNOWN
                    LogUtils.e(
                        "【订单流转】整单处理异常，结果保持 UNKNOWN 并保留机械恢复证据：" +
                            "orderNo=$safeOrderNo，error=${e.message ?: e.javaClass.simpleName}"
                    )
                } else {
                    LogUtils.e(
                        "【订单流转】订单收口已提交，后续状态或界面同步异常，不回滚订单结果：" +
                            "orderNo=$safeOrderNo，error=${e.message ?: e.javaClass.simpleName}"
                    )
                    try {
                        withContext(Dispatchers.Main) {
                            hideProcessingDialog()
                            setDataToView()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cleanupError: Exception) {
                        LogUtils.e(
                            "【订单流转】订单已收口，但异常后的界面清理仍失败：" +
                                "orderNo=$safeOrderNo，error=${cleanupError.message ?: cleanupError.javaClass.simpleName}"
                        )
                    }
                    normalCloseoutCompleted = true
                }
            } finally {
                try {
                    var supplementResumeSucceeded = true
                    var supplementResumeCancellation: CancellationException? = null
                    if (OrderSupplementResumePolicy.shouldResume(orderOutcome)) {
                        supplementResumeSucceeded = try {
                            KaoChangOperate.clearSupplementPauseAndResume(
                                safeOrderNo,
                                "整单履约成功后恢复暂停补肠"
                            )
                        } catch (cancelled: CancellationException) {
                            supplementResumeCancellation = cancelled
                            LogUtils.e(
                                "【订单流转】整单履约成功但补肠续作被取消，先进入维护再传播取消：" +
                                    "orderNo=$safeOrderNo"
                            )
                            false
                        } catch (error: Exception) {
                            LogUtils.e(
                                "【订单流转】整单履约成功但补肠续作异常，按失败进入维护：" +
                                    "orderNo=$safeOrderNo，error=${error.message ?: error.javaClass.simpleName}"
                            )
                            false
                        }
                    }
                    if (OrderSupplementResumePolicy.shouldEnterMaintenance(orderOutcome, supplementResumeSucceeded)) {
                        val config = AppConfig.getAppConfig()
                        val previousStatus = config.onlineStatus
                        config.onlineStatus = 3
                        config.isEnable = 0
                        AppConfig.saveAppConfig(config)
                        HomeUiRefreshBridge.requestRefresh("order:maintenance")
                        val maintenanceReason = if (orderOutcome == OrderExecutionOutcome.SUCCESS) {
                            "整单已履约，但剩余补肠续作失败"
                        } else {
                            "整单未成功"
                        }
                        LogUtils.e(
                            "【订单流转】$maintenanceReason，设备进入维护并保留恢复证据：" +
                                "orderNo=$safeOrderNo，outcome=$orderOutcome，" +
                                "supplementResumeSucceeded=$supplementResumeSucceeded，" +
                                "onlineStatus=${onlineStatusText(previousStatus)}->${onlineStatusText(3)}，" +
                                "moveStatus=${OrderMoveStatusPolicy.describe(config.moveStatus)}"
                        )
                        syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                        if (!normalCloseoutCompleted) {
                            withContext(Dispatchers.Main) {
                                DataManagementAPI.uploadDataToServer()
                                hideProcessingDialog()
                                Toast.makeText(
                                    this@OrderActivity,
                                    "订单结果未知，请联系维护人员",
                                    Toast.LENGTH_LONG
                                ).show()
                                setDataToView()
                            }
                        }
                    }
                    supplementResumeCancellation?.let { throw it }
                } finally {
                    KaoChangOperate.releaseManualOutOrderRecovery(safeOrderNo)
                    if (preDeliveryClaim == null) {
                        orderClaimReleased = true
                    }
                    if (orderClaimReleased) {
                        OrderStateManager.releaseProcessing(safeOrderNo)
                    }
                }
            }
        }
    }

    /**
     * 执行单个商品项的逐根出餐。
     *
     * 注意：
     * - 4F 主流程下 TAKEN_BY_USER 表示 out4 已收口，本单已处理完成
     * - DISCARDED 仅作历史兼容，若出现也会按 status=2（单次履约完成）推进
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
                showProcessingDialog(deliveredBase + deliveredCount + 1, totalCount)
            }
            val currentIndex = deliveredBase + deliveredCount + 1
            LogUtils.d(
                "【订单流转】第$currentIndex/${totalCount}根准备出餐：orderNo=$orderNo，tasteCode=${item.tasteCode}"
            )
            LogUtils.d(
                "【订单流转】开始处理单根烤肠：orderNo=$orderNo, itemId=${item.id}, productId=${item.productId}, " +
                    "tasteCode=${item.tasteCode}, current=$currentIndex/$totalCount, itemProgress=${deliveredCount + 1}/${item.quantity}, attempt=$attemptCount/$maxAttempts"
            )
            val moveStatusResult = normalizeMoveStatusBeforeSingleDelivery(
                orderNo = orderNo,
                itemId = item.id,
                currentIndex = currentIndex,
                totalCount = totalCount,
                itemProgress = deliveredCount + 1,
                itemQuantity = item.quantity,
                attemptCount = attemptCount,
                maxAttempts = maxAttempts
            )
            if (!moveStatusResult.canContinue) {
                LogUtils.w(
                    "【订单流转】终止当前出餐：单根出餐前 moveStatus（机械动作状态）规整失败，" +
                        "orderNo=$orderNo, itemId=${item.id}, current=$currentIndex/$totalCount, " +
                        "initialMoveStatus=${OrderMoveStatusPolicy.describe(moveStatusResult.initialStatus)}，" +
                        "finalMoveStatus=${OrderMoveStatusPolicy.describe(moveStatusResult.finalStatus)}，" +
                        "waitedMs=${moveStatusResult.waitedMs}，reason=${moveStatusResult.reason}"
                )
                reportOrderEvent(
                    orderNo,
                    item.id,
                    item.productId,
                    item.tasteCode,
                    5,
                    OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                )
                return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
            }

            var failureEventReported = false
            val readinessBeforeModbus = evaluateSingleDeliveryReadiness(
                orderNo = orderNo,
                itemId = item.id,
                currentIndex = currentIndex,
                totalCount = totalCount,
                stage = "出餐前状态检查"
            )
            if (!readinessBeforeModbus.canStartDelivery) {
                reportOrderEvent(
                    orderNo,
                    item.id,
                    item.productId,
                    item.tasteCode,
                    5,
                    OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                )
                return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
            }

            try {
                OrderProcessingStageBridge.publish(OrderProcessingStage.DELIVERING)
                val modbusReadyResult = ensureModbusReadyForDelivery(orderNo, item.id)
                if (!modbusReadyResult.canStartDelivery) {
                    val config = AppConfig.getAppConfig()
                    val diagnosticMessage = modbusReadyResult.toDiagnosticMessage(
                        orderNo = orderNo,
                        itemId = item.id,
                        onlineStatusText = onlineStatusText(config.onlineStatus),
                        errorStatus = config.errorStatus,
                        moveStatus = config.moveStatus,
                        modbusAddress = config.modbusAddress
                    )
                    LogUtils.e("【订单流转】禁止下发 out1：$diagnosticMessage")
                    reportOrderEvent(
                        orderNo,
                        item.id,
                        item.productId,
                        item.tasteCode,
                        ActionGroupOrderStatus.ERROR,
                        OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                    )
                    return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
                }
                val readinessAfterModbus = evaluateSingleDeliveryReadiness(
                    orderNo = orderNo,
                    itemId = item.id,
                    currentIndex = currentIndex,
                    totalCount = totalCount,
                    stage = "Modbus恢复检查后"
                )
                if (!readinessAfterModbus.canStartDelivery) {
                    reportOrderEvent(
                        orderNo,
                        item.id,
                        item.productId,
                        item.tasteCode,
                        5,
                        OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                    )
                    return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
                }
                val orderContext = ActionGroupOrderContext(
                    orderNo = orderNo,
                    itemId = item.id,
                    productId = item.productId,
                    tasteCode = item.tasteCode
                )

                when (KaoChangAlgorithm.purchase(
                    tasteCode = item.tasteCode,
                    productId = item.productId,
                    orderContext = orderContext,
                    deliveryIndex = currentIndex,
                    deliveryTotal = totalCount
                )) {
                    KaoChangOperate.TakeSausageResult.TAKEN_BY_USER -> {
                        deliveredCount++
                        LogUtils.d(
                            "【订单流转】第${deliveredBase + deliveredCount}/${totalCount}根完成：out4 已收口，本单已处理完成，订单号=$orderNo"
                        )
                        LogUtils.d(
                            "【履约完结】out4 已收口，本单已处理完成，已触发订单终态事件入队(status=2)：orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${deliveredBase + deliveredCount}/$totalCount"
                        )
                        KaoChangOperate.reportActionGroupOrderTerminalEvent(
                            orderContext,
                            ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.TAKEN_BY_USER),
                            "正常订单出餐完成"
                        )
                    }

                    KaoChangOperate.TakeSausageResult.DISCARDED -> {
                        deliveredCount++
                        LogUtils.d(
                            "【订单流转】第${deliveredBase + deliveredCount}/${totalCount}根完成：历史丢弃结果按 4F 履约完成处理，订单号=$orderNo"
                        )
                        LogUtils.w(
                            "【履约完结】历史丢弃结果已按 status=2（单次履约完成）触发订单终态事件入队：orderNo=$orderNo, " +
                                "itemId=${item.id}, productId=${item.productId}, tasteCode=${item.tasteCode}, current=${deliveredBase + deliveredCount}/$totalCount"
                        )
                        KaoChangOperate.reportActionGroupOrderTerminalEvent(
                            orderContext,
                            ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.DISCARDED),
                            "正常订单出餐历史丢弃结果按完成收口"
                        )
                        LogUtils.w("【订单流转】本轮出餐按 4F 完成收口，继续处理剩余数量：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attempt=$attemptCount/$maxAttempts")
                    }

                    KaoChangOperate.TakeSausageResult.NO_SELLABLE_STOCK -> {
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            ActionGroupOrderStatus.ERROR,
                            OrderFailureMetadata.NO_SELLABLE_STOCK
                        )
                        return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
                    }

                    KaoChangOperate.TakeSausageResult.NO_DISPATCH -> {
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            ActionGroupOrderStatus.ERROR,
                            OrderFailureMetadata.DEVICE_BUSY_NO_DISPATCH
                        )
                        return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.NO_DISPATCH)
                    }

                    KaoChangOperate.TakeSausageResult.EVIDENCE_UNKNOWN -> {
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            ActionGroupOrderStatus.ERROR,
                            OrderFailureMetadata.EVIDENCE_UNKNOWN
                        )
                        return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.EVIDENCE_UNKNOWN)
                    }

                    KaoChangOperate.TakeSausageResult.ERROR -> {
                        reportOrderEvent(
                            orderNo,
                            item.id,
                            item.productId,
                            item.tasteCode,
                            ActionGroupOrderStatus.statusFor(KaoChangOperate.TakeSausageResult.ERROR),
                            OrderFailureMetadata.MECHANICAL_FAILED
                        )
                        failureEventReported = true
                        LogUtils.e("【出餐】第$currentIndex/${totalCount}根失败：订单号=$orderNo，原因=动作组返回失败")
                        LogUtils.e("【订单流转】单根出餐失败，已触发 Java 订单事件入队 status=5：orderNo=$orderNo, itemId=${item.id}")
                        throw IllegalStateException("单根出餐失败：orderNo=$orderNo, itemId=${item.id}")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("【失败原因】第$currentIndex/${totalCount}根出餐失败：${e.message ?: "未知原因"}")
                LogUtils.e("【订单流转】处理单根烤肠时发生异常：${e.message}")
                if (!failureEventReported) {
                    reportOrderEvent(
                        orderNo,
                        item.id,
                        item.productId,
                        item.tasteCode,
                        5,
                        OrderFailureMetadata.EVIDENCE_UNKNOWN
                    )
                    LogUtils.e("【订单流转】单根出餐异常且 out1 证据不明，已补充触发订单事件入队 status=5：orderNo=$orderNo, itemId=${item.id}")
                }
                LogUtils.e("【设备状态】单根出餐异常，已切到维护中并准备上报：${e.message}")
                withContext(Dispatchers.Main) {
                    val config = AppConfig.getAppConfig()
                    val previousOnlineStatus = config.onlineStatus
                    OrderDeliveryFaultPolicy.applySingleDeliveryExceptionFault(config, e)
                    AppConfig.saveAppConfig(config)
                    LogUtils.e(
                        "【状态切换】设备进入维护：onlineStatus（设备营业状态）=" +
                            "${onlineStatusText(previousOnlineStatus)}->${onlineStatusText(3)}，" +
                            "errorStatus（设备故障状态）=${errorStatusText(2)}"
                    )
                    syncDeviceStatusToServer(retryDelaysMs = listOf(2000L, 6000L))
                }
                return DeliveryResult(
                    false,
                    deliveredCount,
                    if (failureEventReported) {
                        OrderExecutionOutcome.MECHANICAL_FAILED
                    } else {
                        OrderExecutionOutcome.EVIDENCE_UNKNOWN
                    }
                )
            }
        }

        if (deliveredCount < item.quantity) {
            LogUtils.e("【订单流转】商品项未全部履约完成：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
            LogUtils.e("【订单流转】商品项履约失败，已触发订单事件入队 status=5：orderNo=$orderNo, itemId=${item.id}, deliveredCount=$deliveredCount/${item.quantity}, attemptCount=$attemptCount/$maxAttempts")
            reportOrderEvent(
                orderNo,
                item.id,
                item.productId,
                item.tasteCode,
                5,
                OrderFailureMetadata.EVIDENCE_UNKNOWN
            )
            return DeliveryResult(false, deliveredCount, OrderExecutionOutcome.EVIDENCE_UNKNOWN)
        }

        return DeliveryResult(true, deliveredCount)
    }

    private fun evaluateSingleDeliveryReadiness(
        orderNo: String,
        itemId: Int,
        currentIndex: Int,
        totalCount: Int,
        stage: String
    ): OrderDeliveryReadinessResult {
        val config = AppConfig.getAppConfig()
        val result = OrderDeliveryReadinessPolicy.evaluate(
            onlineStatus = config.onlineStatus,
            errorStatus = config.errorStatus,
            inspectionMode = config.inspectionMode
        )
        if (!result.canStartDelivery) {
            LogUtils.w(
                "【订单流转】禁止下发 out1：stage=$stage，orderNo=$orderNo，itemId=$itemId，" +
                    "current=$currentIndex/$totalCount，onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${config.errorStatus}，inspectionMode（检修模式）=${config.inspectionMode}，" +
                    "reason=${result.reason}"
            )
        } else {
            LogUtils.d(
                "【订单流转】单根出餐状态检查通过：stage=$stage，orderNo=$orderNo，itemId=$itemId，" +
                    "current=$currentIndex/$totalCount，onlineStatus（设备营业状态）=${onlineStatusText(config.onlineStatus)}，" +
                    "errorStatus（设备故障状态）=${config.errorStatus}，inspectionMode（检修模式）=${config.inspectionMode}"
            )
        }
        return result
    }

    /**
     * 单根出餐前规整本地 moveStatus。
     *
     * 300/400 动作组已由 ResourceLocks 和下位机结果寄存器做真实动作互斥。
     * 这里的 moveStatus 只作为订单/UI 层状态：单根之间短暂回到 0（空闲）是正常的，
     * 下一根开始前重新置为 2（取肠/出餐中），避免多根订单被旧全局锁语义误拦截。
     */
    private suspend fun normalizeMoveStatusBeforeSingleDelivery(
        orderNo: String,
        itemId: Int,
        currentIndex: Int,
        totalCount: Int,
        itemProgress: Int,
        itemQuantity: Int,
        attemptCount: Int,
        maxAttempts: Int,
        maxWaitMs: Long = 30_000L,
        checkIntervalMs: Long = 1_000L
    ): MoveStatusNormalizationResult {
        val initialStatus = AppConfig.getAppConfig().moveStatus
        var currentStatus = initialStatus
        var actionGroupBusy = KaoChangOperate.isActionGroupBusyForOrderGate()
        var waitedMs = 0L
        var effectiveMaxWaitMs = OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(currentStatus)
            ?: maxWaitMs

        while ((OrderMoveStatusPolicy.shouldWaitForBlockingAction(currentStatus)
                    || (currentStatus == OrderMoveStatusPolicy.MOVE_STATUS_IDLE && actionGroupBusy))
            && waitedMs < effectiveMaxWaitMs) {
            LogUtils.d(
                "【订单流转】单根出餐前等待冲突机械动作收尾：orderNo=$orderNo, itemId=$itemId, " +
                    "current=$currentIndex/$totalCount, itemProgress=$itemProgress/$itemQuantity, " +
                    "attempt=$attemptCount/$maxAttempts, moveStatus（机械动作状态）=${OrderMoveStatusPolicy.describe(currentStatus)}，" +
                    "actionGroupBusy=$actionGroupBusy，waitedMs=$waitedMs/$effectiveMaxWaitMs"
            )
            delay(checkIntervalMs)
            waitedMs += checkIntervalMs
            currentStatus = AppConfig.getAppConfig().moveStatus
            actionGroupBusy = KaoChangOperate.isActionGroupBusyForOrderGate()
            effectiveMaxWaitMs = maxOf(
                effectiveMaxWaitMs,
                OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(currentStatus)
                    ?: maxWaitMs
            )
        }

        if (OrderMoveStatusPolicy.shouldNormalizeToDelivering(currentStatus) && !actionGroupBusy) {
            val claimed = KaoChangOperate.tryClaimOrderDeliveryMoveStatus(orderNo)
            currentStatus = AppConfig.getAppConfig().moveStatus
            if (claimed == null) {
                return MoveStatusNormalizationResult(
                    canContinue = false,
                    initialStatus = initialStatus,
                    finalStatus = currentStatus,
                    waitedMs = waitedMs,
                    reason = "锁内重新检查后未能接管出餐"
                )
            }
            LogUtils.d(
                "【订单流转】单根出餐前规整 moveStatus：orderNo=$orderNo, itemId=$itemId, " +
                    "current=$currentIndex/$totalCount, " +
                    "moveStatus（机械动作状态）=${OrderMoveStatusPolicy.describe(initialStatus)}->" +
                    "${OrderMoveStatusPolicy.describe(OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING)}，waitedMs=$waitedMs"
            )
            return MoveStatusNormalizationResult(
                canContinue = true,
                initialStatus = initialStatus,
                finalStatus = OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING,
                waitedMs = waitedMs,
                reason = "空闲状态已规整为取肠/出餐中"
            )
        }

        if (OrderMoveStatusPolicy.canStartSingleDelivery(currentStatus)) {
            LogUtils.d(
                "【订单流转】单根出餐前 moveStatus 允许继续：orderNo=$orderNo, itemId=$itemId, " +
                    "current=$currentIndex/$totalCount, " +
                    "moveStatus（机械动作状态）=${OrderMoveStatusPolicy.describe(currentStatus)}，waitedMs=$waitedMs"
            )
            return MoveStatusNormalizationResult(
                canContinue = true,
                initialStatus = initialStatus,
                finalStatus = currentStatus,
                waitedMs = waitedMs,
                reason = "当前状态允许单根出餐"
            )
        }

        val reason = if (OrderMoveStatusPolicy.shouldWaitForBlockingAction(currentStatus)) {
            "等待冲突机械动作收尾超时"
        } else if (currentStatus == OrderMoveStatusPolicy.MOVE_STATUS_IDLE && actionGroupBusy) {
            "等待动作组资源锁释放超时"
        } else {
            "未知 moveStatus，不下发 out1"
        }
        return MoveStatusNormalizationResult(
            canContinue = false,
            initialStatus = initialStatus,
            finalStatus = currentStatus,
            waitedMs = waitedMs,
            reason = reason
        )
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
    ): OrderModbusReadyForDeliveryResult {
        if (VMModbusHelper.connectStatus()) {
            val recovered = KaoChangOperate.ensureActionGroupReadyForOrderDispatch(
                "订单出餐前通信已连接：orderNo=$orderNo,itemId=$itemId"
            )
            return if (recovered) {
                OrderModbusReadyForDeliveryResult.alreadyConnected()
            } else {
                OrderModbusReadyForDeliveryResult.actionGroupRecoveryFailed(
                    KaoChangOperate.getLastActionGroupRecoveryFailureReason()
                        ?: "Modbus 已连接，但动作组恢复检查未通过"
                )
            }
        }

        LogUtils.w("【订单流转】检测到 Modbus 未连接，开始出餐前通信重试：orderNo=$orderNo, itemId=$itemId")
        repeat(maxRetryCount) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                LogUtils.d("【订单流转】Modbus 重连结果：attempt=$attempt/$maxRetryCount, status=$status, orderNo=$orderNo, itemId=$itemId")
            }
            delay(retryDelayMs)
            if (VMModbusHelper.connectStatus()) {
                LogUtils.d("【订单流转】Modbus 重连成功：attempt=$attempt/$maxRetryCount, orderNo=$orderNo, itemId=$itemId")
                val recovered = KaoChangOperate.ensureActionGroupReadyForOrderDispatch(
                    "订单出餐前 Modbus 重连成功：orderNo=$orderNo,itemId=$itemId"
                )
                return if (recovered) {
                    OrderModbusReadyForDeliveryResult.reconnected(attempt)
                } else {
                    OrderModbusReadyForDeliveryResult.actionGroupRecoveryFailed(
                        KaoChangOperate.getLastActionGroupRecoveryFailureReason()
                            ?: "Modbus 重连成功，但动作组恢复检查未通过"
                    )
                }
            }
        }

        LogUtils.e("【订单流转】Modbus 重连失败，放弃本次出餐：orderNo=$orderNo, itemId=$itemId")
        return OrderModbusReadyForDeliveryResult.reconnectFailed(
            "连续${maxRetryCount}次重连后仍未连通"
        )
    }

    /**
     * 同步设备运行时状态。
     * MQTT 是主通道，retryDelaysMs 仅用于 MQTT 轻量补发；
     * onlineStatus（设备营业状态）同时镜像到 HTTP，避免后台设备表和小程序状态源停留在旧值。
     */
    protected fun syncDeviceStatusToServer(retryDelaysMs: List<Long> = emptyList()) {
        val expectedStatus = AppConfig.getAppConfig().onlineStatus
        LogUtils.d(
            "【设备状态】开始同步设备营业状态：" +
                "expectedOnlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}，" +
                "retryCount=${retryDelaysMs.size}"
        )
        LogUtils.d("【设备状态】触发 MQTT 上报：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        val firstPublishOk = SendServerHelper.publishServiceUpdateStatus()
        if (firstPublishOk) {
            LogUtils.d("【设备状态】MQTT 发送队列已接受：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        } else {
            LogUtils.e("【设备状态】MQTT 发送队列拒绝：attempt=0, onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}")
        }
        mirrorDeviceStatusToHttp(expectedStatus, attempt = 0)

        retryDelaysMs.forEachIndexed { index, delayMs ->
            lifecycleScope.launch(Dispatchers.IO) {
                delay(delayMs)
                val currentStatus = AppConfig.getAppConfig().onlineStatus
                if (currentStatus == expectedStatus) {
                    val attempt = index + 1
                    LogUtils.d("【设备状态】触发补发：attempt=$attempt, delayMs=$delayMs, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    LogUtils.d("【设备状态】触发 MQTT 上报：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    val retryPublishOk = SendServerHelper.publishServiceUpdateStatus()
                    if (retryPublishOk) {
                        LogUtils.d("【设备状态】MQTT 发送队列已接受：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    } else {
                        LogUtils.e("【设备状态】MQTT 发送队列拒绝：attempt=$attempt, onlineStatus（设备营业状态）=${onlineStatusText(currentStatus)}")
                    }
                    mirrorDeviceStatusToHttp(currentStatus, attempt = attempt)
                } else {
                    LogUtils.d(
                        "【设备状态】取消补发：" +
                            "attempt=${index + 1}, " +
                            "expectedOnlineStatus（期望设备营业状态）=${onlineStatusText(expectedStatus)}, " +
                            "currentOnlineStatus（当前设备营业状态）=${onlineStatusText(currentStatus)}"
                    )
                }
            }
        }
    }

    private fun mirrorDeviceStatusToHttp(expectedStatus: Int, attempt: Int) {
        if (!DeviceStatusSyncPolicy.shouldMirrorOnlineStatusToHttp(expectedStatus)) {
            LogUtils.w(
                "【设备状态】跳过 HTTP 镜像设备营业状态：attempt=$attempt，" +
                    "onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}"
            )
            return
        }
        LogUtils.i(
            "【设备状态】HTTP 镜像设备营业状态：attempt=$attempt，" +
                "onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}"
        )
        DataManagementAPI.uploadDeviceInfo { code, content ->
            if (code == 0) {
                LogUtils.i(
                    "【设备状态】HTTP 镜像设备营业状态成功：attempt=$attempt，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}"
                )
            } else {
                LogUtils.e(
                    "【设备状态】HTTP 镜像设备营业状态失败：attempt=$attempt，" +
                        "onlineStatus（设备营业状态）=${onlineStatusText(expectedStatus)}，code=$code，content=$content"
                )
            }
        }
    }

    /**
     * 上报订单事件到服务端（MQTT），并在发送失败时记录错误日志。
     * MQTT 发送失败不阻断出餐流程，但必须记录以便排查订单状态不同步问题。
     */
    protected fun reportOrderEvent(
        orderNo: String,
        itemId: Int,
        productId: Int,
        tasteCode: String,
        status: Int,
        failureMetadata: OrderFailureMetadata? = null
    ): Boolean {
        val eventDesc = orderEventStatusText(status)
        val payload = com.alibaba.fastjson.JSONObject().apply {
            put("order_no", orderNo)
            put("item_id", itemId)
            put("product_id", productId)
            put("taste_code", tasteCode)
            put("status", status)
            if (status == ActionGroupOrderStatus.ERROR && failureMetadata != null) {
                put("failure_reason", failureMetadata.failureReason.name)
                put("delivery_evidence", failureMetadata.deliveryEvidence.name)
                failureMetadata.out1Dispatched?.let { put("out1_dispatched", it) }
            }
        }
        val ok = SendServerHelper.publishServiceUpdateOrder(payload)
        if (!ok) {
            LogUtils.e(
                "【订单流转】订单事件进入 MQTT 发送队列失败（MQTT 可能断连或队列已满）：" +
                    "orderNo=$orderNo, itemId=$itemId, productId=$productId, tasteCode=$tasteCode, " +
                    "status（订单事件状态）=$eventDesc，订单状态可能与服务端不同步"
            )
            if (ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status, ok)) {
                KaoChangOperate.savePendingOrderErrorEventAndTryFlush(
                    orderContext = ActionGroupOrderContext(
                        orderNo = orderNo,
                        itemId = itemId,
                        productId = productId,
                        tasteCode = tasteCode
                    ),
                    panNo = 0,
                    source = "正式出餐 status=5 MQTT 发送队列拒绝",
                    failureMetadata = failureMetadata
                )
            }
        } else {
            LogUtils.i(
                "【云端同步】订单事件已进入 MQTT 发送队列：订单号=$orderNo，状态=${eventDesc.substringAfter("（").substringBefore("）")}"
            )
            LogUtils.d(
                "【订单流转】MQTT 发送队列已接受订单事件：" +
                    "orderNo=$orderNo, itemId=$itemId, productId=$productId, tasteCode=$tasteCode, " +
                    "status（订单事件状态）=$eventDesc"
            )
        }
        return ok
    }

    /**
     * 写入寄存器。
     */
    private fun writeRegister(register: Int, value: Int, description: String) {
        lifecycleScope.launch {
            try {
                LogUtils.d("【寄存器写入】开始写入：description=$description, register=$register, value=$value")
                withContext(Dispatchers.IO) {
                    KaoChangOperate.writeSingleRegister(register, value)
                }
                LogUtils.d("【寄存器写入】写入完成：description=$description, register=$register, value=$value")
            } catch (e: Exception) {
                LogUtils.e("【寄存器写入】写入失败：description=$description, register=$register, value=$value, error=${e.message}", e)
            }
        }
    }
}
