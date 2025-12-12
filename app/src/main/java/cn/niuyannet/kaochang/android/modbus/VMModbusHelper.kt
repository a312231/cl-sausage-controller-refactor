package cn.niuyannet.kaochang.android.modbus

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.invertor.modbus.Modbus
import com.invertor.modbus.ModbusMaster
import com.invertor.modbus.ModbusMasterFactory
import com.invertor.modbus.serial.SerialParameters
import com.invertor.modbus.serial.SerialPort
import com.invertor.modbus.serial.SerialPortFactoryJSSC
import com.invertor.modbus.serial.SerialUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * modbus 操作工具类
 */
object VMModbusHelper {
    var masterTcp: ModbusMaster?=null
    var isLunXun=true
    // 用于调试
    var isDebug = false //true //
    /**
     * 连接modbus
     */
    fun connectModbus(callback: (status: Boolean) -> Unit){
        if (isDebug) {
            return
        }

        try {
            if (connectStatus()) {
                callback.invoke(true)
                return
            }
            val appConfigBean= AppConfig.getAppConfig()
            val serialParameters= SerialParameters()
            serialParameters.device= appConfigBean.modbusAddress   // 串口地址
            serialParameters.parity = SerialPort.Parity.NONE// 设定成无奇偶校验
            serialParameters.dataBits = 8// 设定成数据位是8位
            serialParameters.stopBits = 1// 设定为1个停止位
            if (appConfigBean.modbusBaudRate == 9600) {
                serialParameters.setBaudRate(SerialPort.BaudRate.BAUD_RATE_9600)//波特率
            }else if (appConfigBean.modbusBaudRate == 115200){
                serialParameters.setBaudRate(SerialPort.BaudRate.BAUD_RATE_115200)//波特率
            }
            Modbus.setLogLevel(Modbus.LogLevel.LEVEL_DEBUG)
            SerialUtils.setSerialPortFactory(SerialPortFactoryJSSC())
            masterTcp = ModbusMasterFactory.createModbusMasterRTU(serialParameters)
            masterTcp?.connect()
            callback.invoke(true)
        } catch (e: Exception) {
            e.printStackTrace()
            callback.invoke(false)
        }
    }

    /**
     * 连接状态检测
     */
     fun connectStatus():Boolean{
        if (isDebug) {
            return true
        }
        try {
            if (masterTcp ==null){
                return false
            }
            masterTcp?.readHoldingRegisters(1,0,10)
            return true
        }catch (e:Exception){
            e.printStackTrace()
            Log.i("@@@","连接已经断开")
            return false
        }
    }
    /**
     * 读线圈
     * 默认是false
     */
    fun readCoils( serverAddress:Int, startAddress:Int):Boolean{
        return masterTcp?.readCoils(serverAddress,startAddress,1)?.get(0) ?: false
    }
    /**
     * 写线圈
     */
    fun writeSingleCoil( serverAddress:Int, startAddress:Int,flag:Boolean){
         masterTcp?.writeSingleCoil(serverAddress,startAddress,flag)
    }
    /**
     * 读寄存器
     */
    fun readHoldingRegisters(serverAddress:Int, startAddress:Int):Int{
    if (isDebug) {
        return 0
    }
      try {
          return masterTcp?.readHoldingRegisters(serverAddress,startAddress,1)?.get(0) ?: 1//todo 默认值改成1
      }catch (e:Exception){
          e.printStackTrace()
          LogUtils.d("KaoChangAlgorithm", "读取寄存器出错：${e.message}")
          return 1//todo 默认值改成1
      }
    }

    /**
     * 读取寄存器
     * quantity 多个
     */
    fun readHoldingRegisters(serverAddress:Int, startAddress:Int,quantity:Int): IntArray? {
        if (isDebug) {
            val arr = IntArray(quantity)
            return arr
        }
        try {
            return masterTcp?.readHoldingRegisters(serverAddress,startAddress,quantity)
        }catch (e:Exception){
            e.printStackTrace()
            LogUtils.d("KaoChangAlgorithm", "读取多个寄存器出错：${e.message}")
        }
        return null
    }
    /**
     * 写寄存器
     */
    fun writeSingleRegister(serverAddress:Int, startAddress:Int,value:Int){
        if (isDebug) {
            return
        }
       try {
           masterTcp?.writeSingleRegister(serverAddress,startAddress,value)
       }catch (e:Exception){
           e.printStackTrace()
           LogUtils.d("KaoChangAlgorithm", "写入寄存器出错：${e.message}")
       }
    }
    /**
     * 取消连接modbus
     */
    fun disConnectTcpMaster(){
        masterTcp?.disconnect()
        masterTcp=null
    }

    /**
     * 轮询寄存器的状态
     */
    fun setLunXunCallBack(address: Int,callback: (value: Int) -> Unit) {
       GlobalScope.launch(Dispatchers.IO) {
           while (isLunXun){
               var value=-1
               try {
                   value = if (masterTcp?.readCoils(1,address,1)?.get(0) == true){
                       1
                   }else{
                       0
                   }
                   delay(50)
               }catch (e:Exception){
                   e.printStackTrace()
                   delay(2000)
               }
               callback?.invoke(value)
           }
       }
    }
}