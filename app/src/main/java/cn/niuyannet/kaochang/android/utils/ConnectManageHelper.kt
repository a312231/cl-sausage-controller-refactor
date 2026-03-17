package cn.niuyannet.kaochang.android.utils

import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 连接管理器
 */
object ConnectManageHelper {
    //轮询状态
    private var connectLun=true
    /**
     * 状态监听
     * 建立10秒重连机制
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun lunStatus(callback:(()->Unit)){
        GlobalScope.launch(Dispatchers.IO) {
            while (connectLun){
                delay(10*1000)
                //判断mqtt服务状态
                VMMqttHelper.connect{ }
                //判断modbus状态
                VMModbusHelper.connectModbus {md->}
                //执行其他需要更新的参数
                GlobalScope.launch(Dispatchers.Main) {
                    callback.invoke()
                }

            }
        }
     }
    /**
     * 释放所有的连接配置
     */
    fun disConnect(){
        connectLun =false
        //取消订阅
        VMMqttHelper.unAllSubscribe()
        //取消modbus状态轮询
        VMModbusHelper.isLunXun=false
        //关闭mqtt连接
        VMMqttHelper.disconnect()
        //关闭modbus连接
        VMModbusHelper.disconnectModbusMaster()
    }

}
