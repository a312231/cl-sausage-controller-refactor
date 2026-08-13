package cn.niuyannet.kaochang.android.services.reset

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.services.EmergencyResetAppRestarter
import cn.niuyannet.kaochang.android.services.AppProcessIdentity
import cn.niuyannet.kaochang.android.services.KaoChangOperate
import cn.niuyannet.kaochang.android.utils.HomeUiRefreshBridge
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.OrderStateManager

object ResetStartupCloseoutManager {
    private val store = ResetCloseoutStore.sharedPreferences()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = ResetStartupCloseoutCoordinator(
        store = store,
        nowMs = System::currentTimeMillis,
        processBoundaryCrossed = { record -> record.originProcessId != AppProcessIdentity.currentId },
        recoverPrepared = { record -> recoverPrepared(record) },
        closeLocalResources = {
            VMModbusHelper.blockMechanicalWritesForPendingEmergencyReset()
            KaoChangOperate.closeMechanicalStateAfterLowerBoardReset()
        },
        closeServerOrder = { record, callback -> NetApi.manualResetCloseout(record, callback) },
        persistOrderTerminal = { orderNo, finished ->
            if (finished) {
                OrderStateManager.markAsFinishedDurable(orderNo)
            } else {
                OrderStateManager.markAsFailedDurable(orderNo)
            }
        },
        scheduleFinalize = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
        finalizeAndUnblock = { record -> finalizeAndUnblock(record) }
    )

    fun resumeIfNeeded(context: Context): Boolean {
        val record = try {
            store.load()
        } catch (error: Throwable) {
            VMModbusHelper.blockMechanicalWritesForPendingEmergencyReset()
            LogUtils.e("【下位机复位】复位收口证据损坏，保持机械写禁用：${error.message}")
            return true
        } ?: return false
        if (!ResetCloseoutPolicy.blocksOrders(record) && record.phase != ResetCloseoutPhase.SERVER_CLOSED) {
            return false
        }
        VMModbusHelper.blockMechanicalWritesForPendingEmergencyReset()
        EmergencyResetAppRestarter.cancelPendingRestart(context)
        coordinator.resume()
        return true
    }

    private fun recoverPrepared(record: ResetCloseoutRecord): Boolean {
        VMModbusHelper.blockMechanicalWritesForPendingEmergencyReset()
        val config = AppConfig.getAppConfig()
        val localGateRecovered = runCatching {
            check(
                AppConfig.saveLowerBoardResetPreparedDurable(
                    resetRecordKey = ResetCloseoutStore.KEY_RESET_CLOSEOUT_RECORD,
                    resetRecordJson = com.alibaba.fastjson.JSON.toJSONString(record),
                    onlineStatus = if (record.kind == ResetKind.EMERGENCY) 3 else record.previousOnlineStatus,
                    isEnable = if (record.kind == ResetKind.EMERGENCY) 0 else config.isEnable,
                    orderBlockReason = LowerBoardResetCoordinator.ORDER_BLOCK_REASON,
                    homeQrHidden = cn.niuyannet.kaochang.android.init.AppConfigBean.HOME_QR_HIDDEN_HIDDEN,
                    scanBlockStatus = cn.niuyannet.kaochang.android.init.AppConfigBean.SCAN_BLOCK_STATUS_BLOCKED
                )
            )
        }.isSuccess
        if (localGateRecovered) {
            HomeUiRefreshBridge.requestRefresh("lower_board_reset:recover_prepared")
            DataManagementAPI.uploadDeviceInfo(source = "lower_board_reset_recover_prepared")
        }
        return localGateRecovered
    }

    private fun finalizeAndUnblock(record: ResetCloseoutRecord): Boolean {
        val kind = requireNotNull(record.kind)
        val config = AppConfig.getAppConfig()
        val stableOnlineStatus = ResetCloseoutPolicy.resolveStableOnlineStatus(
            kind = kind,
            previousOnlineStatus = record.previousOnlineStatus,
            errorStatus = config.errorStatus
        )
        return runCatching {
            check(
                AppConfig.saveLowerBoardResetFinalizationDurable(
                    resetRecordKey = ResetCloseoutStore.KEY_RESET_CLOSEOUT_RECORD,
                    onlineStatus = stableOnlineStatus,
                    orderBlockReason = record.previousOrderBlockReason,
                    homeQrHidden = record.previousHomeQrHidden,
                    scanBlockStatus = record.previousScanBlockStatus
                )
            ) { "复位最终状态原子落盘失败" }
        }
            .onSuccess {
                VMModbusHelper.clearEmergencyResetMechanicalWriteBlock()
                HomeUiRefreshBridge.requestRefresh("lower_board_reset:server_closed")
                DataManagementAPI.uploadDeviceInfo(source = "lower_board_reset_server_closed")
            }
            .onFailure { error ->
                LogUtils.e("【下位机复位】恢复复位前稳定状态失败：${error.message}")
            }
            .isSuccess
    }
}
