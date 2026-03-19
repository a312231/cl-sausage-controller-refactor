package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.detecition.SauceDetectionProcessor
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 上位机机械动作封装。
 *
 * 当前项目里和下位机交互最核心的寄存器语义是：
 * - 200：动作指令寄存器。上位机写入动作号，请求下位机执行动作。
 * - 201：动作状态寄存器。
 *   - 0 = 空闲 / 上一次动作已完成
 *   - 1 = 动作执行中
 *   - 2 = 设备忙，本次动作未被接收
 * - 202 / 203：烤盘搬移专用指令和结果寄存器，语义独立于 200 / 201。
 *
 * 因此：
 * - “是否断连”依赖 VMModbusHelper.connectStatus() 判断；
 * - “201=2”只表示设备忙，不等价于通信断开。
 */
object KaoChangOperate {
    private const val DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS = 5
    private const val DEFAULT_POST_SELL_IDLE_STABLE_SECONDS = 2
    private const val DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS = 12
    private const val SELL_PLATFORM_RETRY_DELAY_MS = 300L
    private const val SELL_PLATFORM_CAPTURE_TIMEOUT_MS = 2500L
    private val SELL_PLATFORM_PRESENCE_RETRY_DELAYS_MS = listOf(300L, 500L, 700L)
    private const val SELL_PLATFORM_MONITOR_INTERVAL_MS = 1000L
    private const val SELL_PLATFORM_WAIT_TIMEOUT_MS = 15_000L
    private const val SELL_PLATFORM_CAPTURE_FAILURE_TOLERANCE = 2

    @Volatile
    private var supplementBlockedUntilMs: Long = 0L

    enum class TakeSausageResult {
        TAKEN_BY_USER,
        DISCARDED,
        ERROR
    }

    private enum class ActionStatus201 {
        IDLE,
        RUNNING,
        BUSY,
        DISCONNECTED
    }

    private enum class ActionExecutionResult {
        SUCCESS,
        COMMAND_REJECTED,
        BUSY_TIMEOUT,
        DISCONNECTED,
        RUNNING_TIMEOUT
    }

    private fun markDeviceErrorAndUpload(logMessage: String) {
        AppConfig.getAppConfig().errorStatus = 2
        AppConfig.getAppConfig().onlineStatus = 3
        GlobalScope.launch(Dispatchers.Main) {
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            DataManagementAPI.uploadDeviceInfo()
        }
        LogUtils.e(logMessage)
    }

    private fun armPostSellSupplementGuard(
        reason: String,
        durationMs: Long = getPostSellSupplementGuardMs()
    ) {
        val blockUntil = System.currentTimeMillis() + durationMs
        if (blockUntil > supplementBlockedUntilMs) {
            supplementBlockedUntilMs = blockUntil
        }
        LogUtils.d("KaoChangAlgorithm", "$reason，进入售卖收尾保护窗：${durationMs}ms")
    }

    private suspend fun waitForPostSellSupplementGuardIfNeeded() {
        val blockUntil = supplementBlockedUntilMs
        if (blockUntil <= 0L) {
            return
        }

        val remaining = blockUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            LogUtils.d("KaoChangAlgorithm", "售卖收尾后延迟补肠：remaining=${remaining}ms")
            delay(remaining)
        }

        supplementBlockedUntilMs = 0L

        if (!VMModbusHelper.connectStatus()) {
            LogUtils.w("售卖收尾保护结束时检测到下位机未连接，跳过空闲稳定检查")
            return
        }

        var idleSince = -1L
        val idleStableMs = getPostSellIdleStableMs()
        val checkDeadline = System.currentTimeMillis() + getPostSellIdleMaxWaitMs()
        while (System.currentTimeMillis() < checkDeadline) {
            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionStatus201.IDLE -> {
                    val now = System.currentTimeMillis()
                    if (idleSince < 0L) {
                        idleSince = now
                    }
                    if (now - idleSince >= idleStableMs) {
                        LogUtils.d("KaoChangAlgorithm", "售卖收尾后下位机已连续空闲 ${now - idleSince}ms，允许补肠")
                        return
                    }
                }
                ActionStatus201.RUNNING, ActionStatus201.BUSY -> {
                    idleSince = -1L
                }
                ActionStatus201.DISCONNECTED -> {
                    LogUtils.w("售卖收尾后读取 201 失败，跳过空闲稳定检查")
                    return
                }
            }
            delay(500L)
        }

        LogUtils.w("售卖收尾后等待下位机稳定空闲超时，继续按常规补肠流程处理")
    }

    private suspend fun captureSellPlatform(tag: String = "KaoChangAlgorithm") =
        SauceDetectionUitls.captureAndDetect(
            roiFlag = 1,
            retryDelayMs = SELL_PLATFORM_RETRY_DELAY_MS,
            captureTimeoutMs = SELL_PLATFORM_CAPTURE_TIMEOUT_MS,
            tag = tag
        )

    private suspend fun captureSellPlatformPresenceSequence(): List<SauceDetectionProcessor.SausageInfo?> {
        val results = mutableListOf<SauceDetectionProcessor.SausageInfo?>()
        results += captureSellPlatform()
        if (results.last()?.code == 2) {
            return results
        }
        SELL_PLATFORM_PRESENCE_RETRY_DELAYS_MS.forEach { delayMs ->
            delay(delayMs)
            results += captureSellPlatform()
            if (results.last()?.code == 2) {
                return results
            }
        }
        return results
    }

    private suspend fun closeSellPlatformAfterUserTake(): TakeSausageResult {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 1)
        }
        return if (closeResult != ActionExecutionResult.SUCCESS) {
            markActionFailure("售卖台关门", closeResult)
            TakeSausageResult.ERROR
        } else {
            armPostSellSupplementGuard("售卖台关门成功")
            LogUtils.d("售卖台曾检测到烤肠，复核已为空，判定客户已经把肠取走")
            TakeSausageResult.TAKEN_BY_USER
        }
    }

    private suspend fun discardSellPlatformAfterTimeout(): TakeSausageResult {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台丢弃并关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 2)
        }
        return if (closeResult != ActionExecutionResult.SUCCESS) {
            markActionFailure("售卖台丢弃并关门", closeResult)
            TakeSausageResult.ERROR
        } else {
            armPostSellSupplementGuard("售卖台丢弃并关门成功")
            LogUtils.w("顾客在等待时间内未取走烤肠，已执行丢弃流程并结束本次出餐")
            TakeSausageResult.DISCARDED
        }
    }

    private suspend fun monitorSellPlatformUntilTakenOrDiscarded(
        detectDeadlineMs: Long
    ): TakeSausageResult {
        var consecutiveCaptureFailures = 0
        while (System.currentTimeMillis() < detectDeadlineMs) {
            delay(SELL_PLATFORM_MONITOR_INTERVAL_MS)
            val detectResult = captureSellPlatform()
            when {
                detectResult == null -> {
                    consecutiveCaptureFailures++
                    if (consecutiveCaptureFailures >= SELL_PLATFORM_CAPTURE_FAILURE_TOLERANCE) {
                        tryCloseSellPlatformOnError("售卖台轮询阶段连续拍照失败")
                        markDeviceErrorAndUpload("售卖台轮询阶段连续拍照失败，无法确认顾客是否已取走烤肠，设备进入维护中")
                        return TakeSausageResult.ERROR
                    }
                    LogUtils.w("售卖台轮询阶段拍照失败，继续每秒重试确认顾客是否已取走烤肠")
                }

                detectResult.code == 2 -> {
                    consecutiveCaptureFailures = 0
                    LogUtils.d("售卖台轮询检测到烤肠仍在，继续等待顾客取走")
                }

                else -> {
                    return closeSellPlatformAfterUserTake()
                }
            }
        }

        return discardSellPlatformAfterTimeout()
    }

    private suspend fun tryCloseSellPlatformOnError(reason: String) {
        val closeResult = executeActionWithRetry(
            actionName = "售卖台故障兜底关门"
        ) {
            writeSingleRegister2(action_address_shoumaitai, 1)
        }
        if (closeResult == ActionExecutionResult.SUCCESS) {
            LogUtils.w("售卖台进入故障分支前已执行兜底关门：$reason")
        } else {
            LogUtils.e("售卖台故障兜底关门失败：$reason, result=$closeResult")
        }
    }

    private fun getPostSellSupplementGuardMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellSupplementGuardSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_SUPPLEMENT_GUARD_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleStableMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleStableSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_STABLE_SECONDS
        return seconds * 1000L
    }

    private fun getPostSellIdleMaxWaitMs(): Long {
        val seconds = AppConfig.getAppConfig().postSellIdleMaxWaitSeconds
            .takeIf { it > 0 }
            ?: DEFAULT_POST_SELL_IDLE_MAX_WAIT_SECONDS
        return seconds * 1000L
    }

    // modbus 地址
    val modbus_address=1
    //动作组地址
    val action_address=200//动作组地址
    val action_address_status=201//动作状态地址
    val action_address_move=202//烤盘搬移
    val action_address_move_result=203//烤盘搬移执行结果

    val action_address_shenjiatai=206//烤肠在升降台位置 H（无符号8位整型，0为视觉未识别到烤肠，1为视觉识别到烤肠）L（无符号8位整型，0-255表示夹爪中心应当运动到的位置的mm数）
    val action_address_shoumaitai=207//用户是否取走烤肠（无符号16位整型，0为视觉未识别到烤肠，1为视觉识别到烤肠）
    //1区温度读取
    val action_address_1_temperature=500
    //2区温度读取
    val action_address_2_temperature=501
    //3区温度读取
    val action_address_3_temperature=502
    //1区温度写入
    val action_address_1_temperature_write=503
    //2区温度写入
    val action_address_2_temperature_write=504
    //3区温度写入
    val action_address_3_temperature_write=505

    //保温温度
    val keepWarmTemperature=90 //150 // 60
    //加热温度
    val heatingTemperature=150 //250 //160

    //debug
    val action_debug=255

    /**
     * 仅按当前和嵌入式确认的 201 协议语义解析动作状态。
     * 这里把读寄存器失败映射成 DISCONNECTED，避免和 201=2 的“设备忙”混淆。
     */
    private fun parseActionStatus201(value: Int): ActionStatus201 {
        return when (value) {
            0 -> ActionStatus201.IDLE
            1 -> ActionStatus201.RUNNING
            2 -> ActionStatus201.BUSY
            else -> ActionStatus201.DISCONNECTED
        }
    }

    /**
     * 在真正下发动作前做一次通信层预检。
     * 这里只解决“上下位机通不通”的问题，不负责判断设备忙闲。
     */
    private suspend fun ensureModbusReady(actionName: String): Boolean {
        if (VMModbusHelper.connectStatus()) {
            return true
        }
        LogUtils.w("$actionName 前检测到下位机通信断开，开始短时重连")
        repeat(3) { index ->
            val attempt = index + 1
            VMModbusHelper.connectModbus { status ->
                LogUtils.d("$actionName 前重连下位机结果：attempt=$attempt/3, status=$status")
            }
            delay(1500L)
            if (VMModbusHelper.connectStatus()) {
                LogUtils.d("$actionName 前下位机重连成功：attempt=$attempt/3")
                return true
            }
        }
        LogUtils.e("$actionName 前下位机持续断开，放弃执行动作")
        return false
    }

    /**
     * 统一封装 200/201 协议下的动作执行逻辑。
     *
     * 约束：
     * - 下发动作前先做 connectStatus() 预检
     * - 201=2 按“设备忙”处理，有限次等待后重试
     * - 201=1 长时间不回到 0，按执行超时处理
     * - 通信层失败才走 DISCONNECTED
     */
    private suspend fun executeActionWithRetry(
        actionName: String,
        maxBusyRetry: Int = 3,
        busyRetryDelayMs: Long = 1000L,
        runningPollDelayMs: Long = 1000L,
        runningTimeoutMs: Long = 60_000L,
        issueAction: suspend () -> VMModbusHelper.ModbusOperationResult
    ): ActionExecutionResult {
        var busyRetryCount = 0

        retryLoop@ while (busyRetryCount <= maxBusyRetry) {
            if (!ensureModbusReady(actionName)) {
                return ActionExecutionResult.DISCONNECTED
            }

            when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                ActionStatus201.IDLE -> Unit
                ActionStatus201.RUNNING, ActionStatus201.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        LogUtils.e("$actionName 前连续检测到设备忙，超过重试阈值：retry=$busyRetryCount")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    LogUtils.w("$actionName 前检测到设备忙(201!=0)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                ActionStatus201.DISCONNECTED -> {
                    LogUtils.e("$actionName 前读取动作状态失败")
                    return ActionExecutionResult.DISCONNECTED
                }
            }

            val issueResult = issueAction()
            when (issueResult.status) {
                VMModbusHelper.ModbusOperationStatus.SUCCESS -> Unit
                VMModbusHelper.ModbusOperationStatus.BUSY -> {
                    busyRetryCount++
                    if (busyRetryCount > maxBusyRetry) {
                        LogUtils.e("$actionName 下发指令时下位机持续返回忙，超过重试阈值")
                        return ActionExecutionResult.BUSY_TIMEOUT
                    }
                    LogUtils.w("$actionName 下发指令时下位机返回忙，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                    delay(busyRetryDelayMs)
                    continue@retryLoop
                }
                VMModbusHelper.ModbusOperationStatus.DISCONNECTED -> {
                    LogUtils.e("$actionName 下发指令失败：下位机通信断开")
                    return ActionExecutionResult.DISCONNECTED
                }
                VMModbusHelper.ModbusOperationStatus.PARAM_ERROR,
                VMModbusHelper.ModbusOperationStatus.DEVICE_FAILURE,
                VMModbusHelper.ModbusOperationStatus.PROTOCOL_ERROR,
                VMModbusHelper.ModbusOperationStatus.UNKNOWN_ERROR -> {
                    LogUtils.e("$actionName 下发指令失败：${issueResult.status} ${issueResult.message ?: ""}".trim())
                    return ActionExecutionResult.COMMAND_REJECTED
                }
            }
            val start = System.currentTimeMillis()

            while (true) {
                delay(runningPollDelayMs)
                if (!ensureModbusReady(actionName)) {
                    return ActionExecutionResult.DISCONNECTED
                }
                when (parseActionStatus201(VMModbusHelper.readHoldingRegisters(modbus_address, action_address_status))) {
                    ActionStatus201.RUNNING -> {
                        if (System.currentTimeMillis() - start > runningTimeoutMs) {
                            LogUtils.e("$actionName 长时间处于执行中(201=1)，已超时")
                            return ActionExecutionResult.RUNNING_TIMEOUT
                        }
                    }
                    ActionStatus201.IDLE -> return ActionExecutionResult.SUCCESS
                    ActionStatus201.BUSY -> {
                        busyRetryCount++
                        if (busyRetryCount > maxBusyRetry) {
                            LogUtils.e("$actionName 下发后连续收到设备忙(201=2)，超过重试阈值")
                            return ActionExecutionResult.BUSY_TIMEOUT
                        }
                        LogUtils.w("$actionName 下发后收到设备忙(201=2)，等待后重试：attempt=$busyRetryCount/$maxBusyRetry")
                        delay(busyRetryDelayMs)
                        continue@retryLoop
                    }
                    ActionStatus201.DISCONNECTED -> {
                        LogUtils.e("$actionName 执行过程中读取动作状态失败")
                        return ActionExecutionResult.DISCONNECTED
                    }
                }
            }
        }

        return ActionExecutionResult.BUSY_TIMEOUT
    }

    private fun markActionFailure(actionName: String, result: ActionExecutionResult) {
        val logMessage = when (result) {
            ActionExecutionResult.SUCCESS -> return
            ActionExecutionResult.COMMAND_REJECTED -> "$actionName 下发指令失败，设备进入维护中"
            ActionExecutionResult.BUSY_TIMEOUT -> "$actionName 连续收到设备忙(201=2)，超过重试阈值，设备进入维护中"
            ActionExecutionResult.DISCONNECTED -> "$actionName 过程中与下位机通信断开，设备进入维护中"
            ActionExecutionResult.RUNNING_TIMEOUT -> "$actionName 长时间处于执行中(201=1)，设备进入维护中"
        }
        markDeviceErrorAndUpload(logMessage)
    }

    /**
     * 自动补肠：从烤肠箱取一根生肠，经平台/运输台搬到指定烤盘。
     *
     * 只有“烤肠箱 -> 平台”和“平台 -> 烤盘”两个动作都成功时，
     * 才允许扣减烤肠箱库存并写入目标烤盘状态。
     */
    suspend fun moveSausageToKaoPan(kaoPan: KaoPan, kaoPanBox: KaoPanBox):Boolean {
        if (AppConfig.getAppConfig().errorStatus>0){
            LogUtils.d("KaoChangAlgorithm", "程序处于报错状态，不进行补肠操作")
            return false
        }
        if (kaoPanBox.num <= 0) {
            LogUtils.e("补肠取消：烤肠箱${kaoPanBox.positionSn}库存不足，当前库存=${kaoPanBox.num}")
            return false
        }

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            LogUtils.d("KaoChangAlgorithm", "补充烤肠 等待售卖完成 ${appState}")
        }
        waitForPostSellSupplementGuardIfNeeded()
        // 机械臂状态改为补肠
        AppConfig.getAppConfig().moveStatus = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运开始")
        val result = executeActionWithRetry(
            actionName = "烤肠箱${kaoPanBox.positionSn}到平台"
        ) {
            writeSingleRegister2(action_address, kaoPanBox.cmdValueTake)
        }
        if (result != ActionExecutionResult.SUCCESS) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markActionFailure("烤肠箱${kaoPanBox.positionSn}到平台", result)
            return false
        }
        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运从烤肠箱搬运到平台完成")
        kaoPanBox.cmdStatusTake = 0

        //todo 调用视觉算法，写入到寄存器，检测平台是否有烤肠，并且标识烤肠位置。
        val sausageInfo=SauceDetectionUitls.captureAndDetect(0)
        if (sausageInfo==null){
            writeSingleRegister2(action_address_shenjiatai,0)
            //未检测到烤肠，报错
            AppConfig.getAppConfig().errorStatus=1//未识别到烤肠报错
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            GlobalScope.launch(Dispatchers.Main) {
                DataManagementAPI.uploadDeviceInfo()
            }
            //未检测到烤肠直接就结束了
            return false
        }else{
            var value_y=sausageInfo.worldY.toInt()
            LogUtils.d("KaoChangAlgorithm", "-------坐标--前value-----${value_y}")
            if(value_y < 60){
                value_y=60
            }
            LogUtils.d("KaoChangAlgorithm", "-------坐标--后value-------${value_y}")
            var value = (1 shl 8) or value_y
            LogUtils.d("KaoChangAlgorithm", "-------实际value-------${value}")

            writeSingleRegister2(action_address_shenjiatai,value)
        }
//        val value_y = 60
//        var value = (1 shl 8) or value_y
//        writeSingleRegister2(action_address_shenjiatai,value)

        //从平台把烤肠搬运到烤盘
        delay(200)
        val result2 = executeActionWithRetry(
            actionName = "平台到烤盘${kaoPan.positionSn}"
        ) {
            writeSingleRegister2(action_address, kaoPan.cmdValueMove)
        }
        if (result2 != ActionExecutionResult.SUCCESS) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markActionFailure("平台到烤盘${kaoPan.positionSn}", result2)
        }
        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运从平台搬运到烤盘完成：$result2")

        kaoPan.cmdStatusMove = if (result2 == ActionExecutionResult.SUCCESS) 0 else 2
        val moveSuccess = result == ActionExecutionResult.SUCCESS && result2 == ActionExecutionResult.SUCCESS
        if (moveSuccess){
            kaoPan.isHasSausage=true
            // 减少烤肠箱中的数量，最低只允许到 0，防止库存被写成负数
            kaoPanBox.num = maxOf(0, kaoPanBox.num - 1)
            kaoPan.taste.apply {
                productId=kaoPanBox.productId.toInt()
                productName=kaoPanBox.tasteName
                tasteName=kaoPanBox.tasteName
            }
            //清空旧位置信息
            kaoPan.status=1//开始加热烤制
            // 记录开始时间
            kaoPan.startTime = System.currentTimeMillis()
        } else {
            LogUtils.e("补肠失败，未更新烤盘状态和库存：panId=${kaoPan.id}, boxPosition=${kaoPanBox.positionSn}, result=$result, result2=$result2")
        }

        // 修改运动状态为待机
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        //更新烤肠箱的库存和烤盘的状态
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanBoxList(KaoPanHelper.getKaoPanBoxList())
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            LogUtils.d("KaoChangAlgorithm", "补肠后烤盘状态更新完成")
        }
        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运结束")

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            LogUtils.d("KaoChangAlgorithm", "等待烤肠售卖结束，补下一根烤肠")
        }
        return moveSuccess
    }

    /**
     * 烤盘间搬移。
     *
     * 这条链路走 202/203 寄存器，不复用 200/201 的忙态语义：
     * - 202：下发搬移动作，值为“起始烤盘 << 8 | 目标烤盘”
     * - 203：搬移结果，0=完成，1=执行中，其余=错误，-1=上位机读失败
     *
     * 因此这里除了写指令预检外，还要单独处理：
     * - 203 长时间停在 1 的超时
     * - 203 读失败
     * - 203 返回非 0/1 的明确错误
     */
    suspend fun moveKaoPanToKaoPan(kaoPan: KaoPan, newKaoPan: KaoPan):Boolean  {
        // 计算16位值：前8位为起始位置，后8位为目标位置
        val sourcePosition = kaoPan.positionSn
        val targetPosition = newKaoPan.positionSn
        //相同位置不需要移动
        if (sourcePosition==targetPosition){
            return true
        }

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            LogUtils.d("KaoChangAlgorithm", "搬移烤肠 等待售卖完成 ${appState}")
        }
        // 机械臂状态改为搬运
        AppConfig.getAppConfig().moveStatus = 3
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        if (!ensureModbusReady("烤盘${sourcePosition}到烤盘${targetPosition}搬移")) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移前下位机通信断开，设备进入维护中")
            return false
        }

        // 将起始位置左移8位，然后与目标位置进行或运算
        val value = (sourcePosition shl 8) or targetPosition
        val writeMoveResult = writeSingleRegister2(action_address_move,value)
        if (!writeMoveResult.isSuccess) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移指令下发失败：${writeMoveResult.status}")
            return false
        }
        val moveStart = System.currentTimeMillis()
        val moveTimeoutMs = 60_000L
        var result = 1
        while (result == 1) {
            delay(2000)
            result = VMModbusHelper.readHoldingRegisters(modbus_address,action_address_move_result)
            if (result == -1) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移时读取203失败，设备进入维护中")
                return false
            }
            if (System.currentTimeMillis() - moveStart > moveTimeoutMs) {
                AppConfig.getAppConfig().moveStatus = 0
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移长时间处于执行中(203=1)，设备进入维护中")
                return false
            }
        }
        if (result != 0) {
            AppConfig.getAppConfig().moveStatus = 0
            AppConfig.saveAppConfig(AppConfig.getAppConfig())
            markDeviceErrorAndUpload("烤盘${sourcePosition}到烤盘${targetPosition}搬移失败，203=$result，设备进入维护中")
            return false
        }
        kaoPan.cmdStatusMove=result
        if (result==0){
            //移动新的位置
            newKaoPan.isHasSausage=true
            newKaoPan.isHasGrilling=false
            newKaoPan.taste.apply {
                productId=kaoPan.taste.productId
                productName=kaoPan.taste.tasteName
                tasteName=kaoPan.taste.tasteName
            }
            newKaoPan.holdingTime= kaoPan.holdingTime //System.currentTimeMillis() //kaoPan.holdingTime
            newKaoPan.status= 2 //kaoPan.status
            //清空旧位置信息
            kaoPan.isHasSausage=false
            kaoPan.startTime = 0
            kaoPan.status=0

        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }

        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            LogUtils.d("KaoChangAlgorithm", "等待烤肠售卖结束，搬移下一根烤肠")
        }
        return result==0
    }   

    /**
     * 旧的手动取肠入口，主要给维护页手动操作使用。
     * 这条路径不参与订单履约状态机，但故障时也会进入维护中并补齐 errorStatus。
     */
    suspend fun takeSausage(kaoPan: KaoPan) :Boolean {
        val result = executeActionWithRetry(
            actionName = "旧手动取肠-烤盘${kaoPan.positionSn}到售卖口"
        ) {
            writeSingleRegister2(action_address,kaoPan.cmdValueTake)
        }
        var panStateCleared = false
        if(result!=ActionExecutionResult.SUCCESS){
            markActionFailure("旧手动取肠-烤盘${kaoPan.positionSn}到售卖口", result)
        } else {
            // 只要烤盘到售卖口成功，这根肠就已经离开了原烤盘。
            // 即使后续售卖口识别或关门收尾失败，也不应继续在烤盘上显示“有肠”。
            kaoPan.isHasSausage=false
            kaoPan.holdingTime=0L
            kaoPan.startTime = 0
            kaoPan.status=0
            panStateCleared = true
            GlobalScope.launch(Dispatchers.Main) {
                KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
            }
            LogUtils.d("旧手动取肠：烤盘${kaoPan.positionSn}已到售卖口，先清空烤盘状态，再执行售卖口收尾")
            // 搬移完成后 等待一定时间再进行视觉检测
            delay(3000)
        val sausageInfo = captureSellPlatform()
            if (sausageInfo==null){ // 拍照失败
                markDeviceErrorAndUpload("售卖台第一次拍照失败，旧手动取肠流程进入维护中")
            }else if (sausageInfo.code == 2){ // 识别到烤肠，再确认一次客户是否没取走
                delay(3000)
            val sausageInfo_2 = captureSellPlatform()
                if (sausageInfo_2 == null) {
                    markDeviceErrorAndUpload("售卖台第二次拍照失败，旧手动取肠流程进入维护中")
                }else if (sausageInfo_2.code == 2){
                    val closeResult = executeActionWithRetry(
                        actionName = "旧手动取肠收尾-丢弃并关门"
                    ) {
                        writeSingleRegister2(action_address_shoumaitai,2)
                    }
                    if (closeResult != ActionExecutionResult.SUCCESS) {
                        markActionFailure("旧手动取肠收尾-丢弃并关门", closeResult)
                        return false
                    }
                    LogUtils.d("客户未及时取肠，执行丢弃并关门完成")
                }
                else{
                    val closeResult = executeActionWithRetry(
                        actionName = "旧手动取肠收尾-关门"
                    ) {
                        writeSingleRegister2(action_address_shoumaitai,1)
                    }
                    if (closeResult != ActionExecutionResult.SUCCESS) {
                        markActionFailure("旧手动取肠收尾-关门", closeResult)
                        return false
                    }
                    LogUtils.d("客户已经把肠取走，关门完成")
                }
            } else { // 未识别到 顾客取走了
                val closeResult = executeActionWithRetry(
                    actionName = "旧手动取肠收尾-关门"
                ) {
                    writeSingleRegister2(action_address_shoumaitai,1)
                }
                if (closeResult != ActionExecutionResult.SUCCESS) {
                    markActionFailure("旧手动取肠收尾-关门", closeResult)
                    return false
                }
                LogUtils.d("客户已经把肠取走，关门完成")
            }
        }

        kaoPan.cmdStatusTake=if (result == ActionExecutionResult.SUCCESS) 0 else 2

        if (result==ActionExecutionResult.SUCCESS && !panStateCleared){
            kaoPan.isHasSausage=false
            kaoPan.holdingTime=0L
            kaoPan.startTime = 0
            kaoPan.status=0
        }

        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        return result==ActionExecutionResult.SUCCESS
    }

    /**
     * 正式订单出餐入口。
     *
     * 当前真实业务语义：
     * - 顾客取走：返回 TAKEN_BY_USER
     * - 超时未取且系统已丢弃：返回 DISCARDED，当前订单逻辑按“本次已处理完成”推进
     * - 机械、识别或通信异常：返回 ERROR，并由上层上报 status=5
     */
    suspend fun takeSausageResult(kaoPan: KaoPan): TakeSausageResult {
        val result = executeActionWithRetry(
            actionName = "烤盘${kaoPan.positionSn}到售卖口"
        ) {
            writeSingleRegister2(action_address, kaoPan.cmdValueTake)
        }
        if (result != ActionExecutionResult.SUCCESS) {
            markActionFailure("烤盘${kaoPan.positionSn}到售卖口", result)
            return TakeSausageResult.ERROR
        }

        val presenceResults = captureSellPlatformPresenceSequence()
        val detectedSausageInQuickRetries = presenceResults.any { it?.code == 2 }
        val allPresenceCapturesFailed = presenceResults.all { it == null }
        val detectDeadlineMs = System.currentTimeMillis() + SELL_PLATFORM_WAIT_TIMEOUT_MS
        val takeResult = when {
            allPresenceCapturesFailed -> {
                tryCloseSellPlatformOnError("售卖台首次确认阶段连续拍照失败")
                markDeviceErrorAndUpload("售卖台首次确认阶段连续拍照失败，无法确认烤肠是否已送达售卖口，设备进入维护中")
                TakeSausageResult.ERROR
            }

            !detectedSausageInQuickRetries -> {
                LogUtils.w("售卖台首次确认阶段未检测到烤肠，已完成 1.5 秒短频补拍，继续每秒轮询确认是否到货或已被顾客快速取走")
                monitorSellPlatformUntilTakenOrDiscarded(detectDeadlineMs)
            }

            else -> {
                LogUtils.d("售卖台首次确认阶段已检测到烤肠，开始每秒轮询顾客是否已取走")
                monitorSellPlatformUntilTakenOrDiscarded(detectDeadlineMs)
            }
        }

        kaoPan.cmdStatusTake = 0
        if (result == ActionExecutionResult.SUCCESS) {
            kaoPan.isHasSausage = false
            kaoPan.holdingTime = 0L
            kaoPan.startTime = 0
            kaoPan.status = 0
        }

        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }

        return takeResult
    }

    /**
     * 过保丢弃：把烤盘中超过保温时长的烤肠丢弃。
     * 这条链路也按 201=忙时重试、通信失败/超时才判故障。
     */
    suspend fun discardSausage(kaoPan: KaoPan) :Boolean {

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            LogUtils.d("KaoChangAlgorithm", "丢弃烤肠 等待售卖完成 ${appState}")
        }
        // 机械臂状态改为搬运
        AppConfig.getAppConfig().moveStatus = 4
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        val result = executeActionWithRetry(
            actionName = "烤盘${kaoPan.positionSn}过保丢弃",
            runningPollDelayMs = 2000L,
            runningTimeoutMs = 120_000L
        ) {
            writeSingleRegister2(action_address,kaoPan.cmdValueDiscard)
        }
        if(result != ActionExecutionResult.SUCCESS){
            markActionFailure("烤盘${kaoPan.positionSn}过保丢弃", result)
        }
        kaoPan.cmdStatusDiscard=if (result == ActionExecutionResult.SUCCESS) 0 else 2
        if (result==ActionExecutionResult.SUCCESS){
            kaoPan.isHasSausage=false
            kaoPan.holdingTime=0
            kaoPan.startTime=0
            kaoPan.status=0
        }
        AppConfig.getAppConfig().moveStatus = 0
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        while (AppConfig.getAppConfig().onlineStatus == 4) {
            delay(5000L)
            LogUtils.d("KaoChangAlgorithm", "等待烤肠售卖结束，丢弃下一根烤肠")
        }
        return result==ActionExecutionResult.SUCCESS
    }
    /**
     * 读取烤盘温度
     * area 1，2，3区
     */
    fun readKaoPanTemperature(area: Int):Int{
        when (area) {   
            1 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_1_temperature)
            }
            2 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_2_temperature)
            }
            3 -> {
                return VMModbusHelper.readHoldingRegisters(modbus_address,action_address_3_temperature)
            }
        }
        return 0
    }
    /**
     *  1区500 2区501 3区502 读取状态
     *  503 504 505 写入温度
     * area 加热1，2，3区
     * 加热温度 160
     */
    fun heating(area: Int) {
        LogUtils.d("开始加热温度数-----："+AppConfig.getAppConfig().heatingTemperature)
        //加热管开始工作
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,AppConfig.getAppConfig().heatingTemperature)
            }
        }
    }
    /**
     *
     * area 1，2，3区
     * 保温 60
     */
    fun keepWarm(area: Int) {
        LogUtils.d("开始保温温度数-----："+AppConfig.getAppConfig().keepWarmTemperature)
        LogUtils.d("开始加热温度数-----："+AppConfig.getAppConfig().heatingTemperature)
        //开始保温工作
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write, AppConfig.getAppConfig().keepWarmTemperature)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,AppConfig.getAppConfig().keepWarmTemperature)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,AppConfig.getAppConfig().keepWarmTemperature)
            }
        }
    }

    /**
     *
     * area 加热1，2，3区
     * 停止加热 0
     */
    fun stopHeating(area: Int) {
        //停止加热
        when (area) {
            1 -> {
                 writeSingleRegister(action_address_1_temperature_write,0)
            }
            2 -> {
                 writeSingleRegister(action_address_2_temperature_write,0)
            }
            3 -> {
                 writeSingleRegister(action_address_3_temperature_write,0)
            }
        }
    }
    /**
     * 暂停营业时间
     * 停止加热和保温
     */
    fun pauseBusiness() {
        //1，2，3 区停止保温
        writeSingleRegister(action_address_1_temperature_write,0)
        writeSingleRegister(action_address_2_temperature_write,0)
        writeSingleRegister(action_address_3_temperature_write,0)

    }

    /**
     * 营业时间开始
     */
    suspend fun startBusinessHours(config: AppConfigBean){
        if (config.errorStatus != 0) {
            config.onlineStatus = 3
            AppConfig.saveAppConfig(config)
            LogUtils.w("设备仍存在错误标记(errorStatus=${config.errorStatus})，禁止从维护中切换到运营中")
            NetApi.updateDeviceInfo()
            return
        }
        //修改设备状态 改为运营中
        config.apply {
            onlineStatus = 1
        }
        AppConfig.saveAppConfig(config)
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

    /**
     * 营业时间结束
     */
    suspend fun endBusinessHours(config: AppConfigBean){
        if (config.errorStatus != 0) {
            config.onlineStatus = 3
            AppConfig.saveAppConfig(config)
            LogUtils.w("设备仍存在错误标记(errorStatus=${config.errorStatus})，禁止从维护中切换到休息中")
            NetApi.updateDeviceInfo()
            return
        }
        //修改设备状态 改为休息中
        config.apply {
            onlineStatus = 2
        }
        AppConfig.saveAppConfig(config)
        //同步更新云端状态
        NetApi.updateDeviceInfo()
        //执行丢弃操作
        try {
            KaoChangAlgorithm.resetKaoPan()
        } catch (e: Exception) {
            TODO("Not yet implemented")
        }
    }

    /**
     * 读取寄存器的值
     */
    fun readHoldingRegisters(address:Int,quantity:Int):IntArray?{
        return VMModbusHelper.readHoldingRegisters(modbus_address,address,quantity)
    }



    /**
     * 写入寄存器的值
     */
    fun writeSingleRegister(address: Int,value:Int): VMModbusHelper.ModbusOperationResult{
        LogUtils.d("烤肠状态程序："+AppConfig.getAppConfig().errorStatus)
        return VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

    /**
     * 由于下位机处理频率过低需要延迟150毫秒，读取都需要加延迟150发送，每个读取方法已经加了delay
     */
    suspend fun writeSingleRegister2(address: Int,value:Int): VMModbusHelper.ModbusOperationResult{
        delay(150)
        LogUtils.d("烤肠状态程序："+AppConfig.getAppConfig().errorStatus)
        return VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

    fun actionDebug() {
        writeSingleRegister(action_debug,5555)

    }

}
