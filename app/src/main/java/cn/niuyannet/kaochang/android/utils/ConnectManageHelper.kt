package cn.niuyannet.kaochang.android.utils

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object ConnectManageHelper {
    private var connectLun = true

    @OptIn(DelicateCoroutinesApi::class)
    fun lunStatus(callback: () -> Unit) {
        connectLun = true
        GlobalScope.launch(Dispatchers.IO) {
            fun runSingleCheck(includeModbus: Boolean) {
                VMMqttHelper.connect { }
                if (includeModbus) {
                    VMModbusHelper.connectModbus { _ -> }
                }
                GlobalScope.launch(Dispatchers.Main) {
                    callback.invoke()
                }
            }

            // 先立即连接 MQTT，让后台尽快感知设备上线。
            // Modbus 仍放在定时轮询里处理，避免应用刚启动时过于激进地反复探测串口。
            runSingleCheck(includeModbus = false)

            while (connectLun) {
                delay(10 * 1000L)
                if (!connectLun) {
                    break
                }
                runSingleCheck(includeModbus = true)
            }
        }
    }

    fun disConnect() {
        connectLun = false
        VMMqttHelper.unAllSubscribe()
        VMModbusHelper.isLunXun = false
        VMMqttHelper.disconnect()
        VMModbusHelper.disconnectModbusMaster()
    }
}
