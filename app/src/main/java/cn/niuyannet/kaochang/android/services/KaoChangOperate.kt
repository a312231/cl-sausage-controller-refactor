package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.modbus.VMModbusHelper
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.mqtt.SendServerHelper
import cn.niuyannet.kaochang.android.mqtt.VMMqttHelper
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.ui.MainActivity
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.SauceDetectionUitls
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.math.log

/**
 * 烤盘操作类
 *  *  【补烤肠到烤盘】从平台把烤肠搬运到烤盘1-33
     * 【用户下单取烤肠】把烤好的烤肠取出来 101-133
     * 【补烤肠】从烤肠箱取出来烤肠 201-212
     * 【超过设置的时间】丢弃烤肠 301-333
     * 动作组地址：200  （无符号16位整型，动作组号）
     * 动作状态地址：201  （无符号16位整型，0为正常，1为运动中，其余为错误码）
     *  202 烤盘搬移 （无符号8位整型，起始位置）（无符号8位整型，起始位置）
     *  203 烤盘搬移执行结果 （无符号16位整型，0为正常，1为运动中，其余为错误码）
 */
object KaoChangOperate {
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

    /**
     * 从烤肠箱把烤肠搬运到烤盘1-33
     * kaoPan 烤盘的位置
     * kaoPanBox  烤箱
     */
    suspend fun moveSausageToKaoPan(kaoPan: KaoPan, kaoPanBox: KaoPanBox):Boolean {
        if (AppConfig.getAppConfig().errorStatus>0){
            LogUtils.d("KaoChangAlgorithm", "程序处于报错状态，不进行补肠操作")
            return false
        }

        var appState = AppConfig.getAppConfig().moveStatus
        // 运动状态 为0是说明机械臂闲置
        while (appState!=0) {
            delay(2000)
            appState = AppConfig.getAppConfig().moveStatus
            LogUtils.d("KaoChangAlgorithm", "补充烤肠 等待售卖完成 ${appState}")
        }
        // 机械臂状态改为补肠
        AppConfig.getAppConfig().moveStatus = 1
        AppConfig.saveAppConfig(AppConfig.getAppConfig())

        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运开始")
        // 从烤肠箱取出来
         writeSingleRegister2(action_address,kaoPanBox.cmdValueTake)

        //轮询读取命令执行的状态，直至为正常为0
        var result=1
        while (result==1){  
            //delay(5000)
            delay(2000) //
            result= readHoldingRegisters(action_address_status,1)?.get(0) ?: 1//todo 默认值改成1
            //烤盘搬移执行结果 （无符号16位整型，0为正常，1为运动中，其余为错误码）
        }
        if(result!=0 && result!=1){
            //下位机错误
            LogUtils.d("下位机异常！从烤肠箱把烤肠搬运到烤盘1-33-----返回结果result------${result}")
        }
        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运从烤肠箱搬运到平台完成${result}")
        kaoPanBox.cmdStatusTake=result

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

        //从平台把烤肠搬运到烤盘
        delay(200)
        writeSingleRegister2(action_address,kaoPan.cmdValueMove)
        var result2=1
        //读取命令执行的状态
        while (result2==1){
            delay(2000)
            result2=readHoldingRegisters(action_address_status,1)?.get(0) ?: 1//todo 默认值改成1
        }
        LogUtils.d("KaoChangAlgorithm", "从烤肠箱${kaoPanBox.positionSn}把烤肠搬运到烤盘${kaoPan.positionSn}搬运从平台搬运到烤盘完成${result2}")

        kaoPan.cmdStatusMove=result2
        if (result==0&&result2==0){
            kaoPan.isHasSausage=true
        }
        // 减少烤肠箱中的数量
        kaoPanBox.num--
        kaoPan.taste.apply {
            productId=kaoPanBox.productId.toInt()
            productName=kaoPanBox.tasteName
            tasteName=kaoPanBox.tasteName
        }
        //清空旧位置信息
        kaoPan.status=1//开始加热烤制
        // 记录开始时间
        kaoPan.startTime = System.currentTimeMillis()

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
        return result==0&&result2==0
    }

    /**
     * 把烤肠从烤盘移动到烤盘另外的位置
     * kaoPan 烤盘
     * newKaoPan 搬运到的位置
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

        // 将起始位置左移8位，然后与目标位置进行或运算
        val value = (sourcePosition shl 8) or targetPosition
         writeSingleRegister2(action_address_move,value)
        //轮询读取命令执行的状态，直至为正常为0
        var result=1
        while (result==1){
            delay(2000)
            result=VMModbusHelper.readHoldingRegisters(modbus_address,action_address_move_result)
            //烤盘搬移执行结果 （无符号16位整型，0为正常，1为运动中，其余为错误码）
        }
        if(result!=0 && result!=1){
            //下位机错误
            LogUtils.d("下位机异常！把烤肠从烤盘移动到烤盘另外的位置-----返回结果result------${result}")
        }
        kaoPan.cmdStatusMove=result
        if (result==0){
            //移动新的位置
            newKaoPan.isHasSausage=true
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
     * 用户下单，取出烤肠到售卖台
     * kaoPan 烤盘
     */
    suspend fun takeSausage(kaoPan: KaoPan) :Boolean {
        //取出烤肠
         writeSingleRegister2(action_address,kaoPan.cmdValueTake)
        //轮询读取命令执行的状态，直至为正常为0
        var result=1
        while (result==1){
            delay(5000)
            result=VMModbusHelper.readHoldingRegisters(modbus_address,action_address_status)
            //烤盘搬移执行结果 （无符号16位整型，0为正常，1为运动中，其余为错误码）
        }
        if(result!=0 && result!=1){
            //下位机错误
            LogUtils.d("下位机异常！用户下单-----返回结果result------${result}")
        }
        // 搬移完成后 等待一定时间再进行视觉检测
        delay(3000)
        val sausageInfo=SauceDetectionUitls.captureAndDetect(1)
        if (sausageInfo==null){ // 拍照失败
            //writeSingleRegister2(action_address_shoumaitai,0)
            delay(3000)
            writeSingleRegister2(action_address_shoumaitai,1)
            AppConfig.getAppConfig().errorStatus=2//未识别到烤肠报错
            GlobalScope.launch(Dispatchers.Main) {
                AppConfig.saveAppConfig(AppConfig.getAppConfig())
                DataManagementAPI.uploadDeviceInfo()
            }
            LogUtils.d("售卖台第一次拍照失败")
        }else if (sausageInfo.code == 2){ // 识别到烤肠，再确认一次客户是否没取走
            delay(3000)
            val sausageInfo_2 = SauceDetectionUitls.captureAndDetect(1)
            if (sausageInfo_2 == null) {
                //writeSingleRegister2(action_address_shoumaitai,0)
                AppConfig.getAppConfig().errorStatus=2//未识别到烤肠报错
                GlobalScope.launch(Dispatchers.Main) {
                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
                    DataManagementAPI.uploadDeviceInfo()
                }
                LogUtils.d("售卖台第二次拍照失败")
            }else if (sausageInfo_2.code == 2){
                // writeSingleRegister2(action_address_shoumaitai,sausageInfo.code)
                // 暂时发1，等待下位机修改
                writeSingleRegister2(action_address_shoumaitai,1)
                LogUtils.d("客户未及时取肠，执行丢弃...")
//                AppConfig.getAppConfig().errorStatus=2//未识别到烤肠报错
//                GlobalScope.launch(Dispatchers.Main) {
//                    AppConfig.saveAppConfig(AppConfig.getAppConfig())
//                    DataManagementAPI.uploadDeviceInfo()
//                }
            }
            else {
                //TODO: 下位机执行烤肠丢弃动作，丢弃完成后上位机判断是否丢弃完成

                // writeSingleRegister2(action_address_shoumaitai,sausageInfo.code)
                // 暂时发1，等待下位机修改
                writeSingleRegister2(action_address_shoumaitai,1)
                LogUtils.d("客户已经把肠取走")
            }
        } else { // 未识别到 顾客取走了
            writeSingleRegister2(action_address_shoumaitai,1)
            LogUtils.d("客户已经把肠取走")
        }
        kaoPan.cmdStatusTake=result
        if (result==0){
            kaoPan.isHasSausage=false
            kaoPan.startTime = 0
            kaoPan.status=0
        }
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        return result==0
    }

    /**
     * 超过保温时间丢弃烤肠
     * kaoPan 烤盘
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

         writeSingleRegister2(action_address,kaoPan.cmdValueDiscard)
        //轮询读取命令执行的状态，直至为正常为0
        var result=1
        while (result==1){
            delay(10*1000)
            result=VMModbusHelper.readHoldingRegisters(modbus_address,action_address_status)
            //烤盘搬移执行结果 （无符号16位整型，0为正常，1为运动中，其余为错误码）
        }
        if(result!=0 && result!=1){
            //下位机错误
            LogUtils.d("下位机异常！超过保温时间丢弃烤肠-----返回结果result------${result}")
        }
        kaoPan.cmdStatusDiscard=result
        if (result==0){
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
        return result==0
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
        writeSingleRegister(action_address_1_temperature_write,0)
        writeSingleRegister(action_address_2_temperature_write,0)
        writeSingleRegister(action_address_3_temperature_write,0)
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
    fun writeSingleRegister(address: Int,value:Int){
        LogUtils.d("烤肠状态程序："+AppConfig.getAppConfig().errorStatus)
        VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

    /**
     * 由于下位机处理频率过低需要延迟150毫秒，读取都需要加延迟150发送，每个读取方法已经加了delay
     */
    suspend fun writeSingleRegister2(address: Int,value:Int){
        delay(150)
        LogUtils.d("烤肠状态程序："+AppConfig.getAppConfig().errorStatus)
        VMModbusHelper.writeSingleRegister(modbus_address,address,value)

    }

}