package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.utils.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

object MaintenanceModbusRecoveryConnector {
    private val reconnectInProgress = AtomicBoolean(false)

    fun ensureConnected(
        isConnected: () -> Boolean = { VMModbusHelper.connectStatus() },
        reconnect: ((Boolean) -> Unit) -> Unit = { callback ->
            VMModbusHelper.connectModbus(callback)
        },
        log: (String) -> Unit = { message -> LogUtils.d(message) }
    ): Boolean {
        if (isConnected()) {
            return true
        }

        if (!reconnectInProgress.compareAndSet(false, true)) {
            log("【维护操作】手动恢复状态等待已有 Modbus 重连结果，避免重复发起完整串口探测")
            return isConnected()
        }

        return try {
            log("【维护操作】手动恢复状态发起 Modbus 重连探测")
            var reconnectStatus = false
            reconnect { status ->
                reconnectStatus = status
            }
            reconnectStatus
        } finally {
            reconnectInProgress.set(false)
        }
    }
}
