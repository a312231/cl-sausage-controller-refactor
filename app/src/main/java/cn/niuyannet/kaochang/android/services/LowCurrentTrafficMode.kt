package cn.niuyannet.kaochang.android.services

import android.R
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


/**
 * 烤盘低并发模式控制类
 */

object LowCurrentTrafficMode {
    //控制温度关闭
    var TEMPERATURE_OFF = 0 //0写入 ,1 是关闭
    /**
     * 低并发模式处理器
     *     如果p=9 就是初始化敌低并发模式
     *     如果p=3 并且2区没有烤肠加热【就是就是要添加烤肠】【检查是否已经加热完成，加热完成需要关闭加热和移动烤肠】
     *     如果p=5或者=6 就需要切换并发模式到高并发
     */
    suspend fun lowTrafficMode( config: AppConfigBean,kaoPanList: List<KaoPan>, listBox: List<KaoPanBox>): Int {
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        //val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, kaoPanList)

        val list_13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, kaoPanList)
        val list_16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, kaoPanList)

        val p1_9 = list1_6.filter { !it.isHasSausage }.size//空位数

        val p_13_18 = list_13_18.filter { !it.isHasSausage }.size

        val p_13_15 = list_13_15.filter { !it.isHasSausage }.size
        val p_16_18 = list_16_18.filter { !it.isHasSausage }.size

        val p = p1_9 + p_13_18
        LogUtils.d("KaoChangAlgorithm", "1区坑位 P= ${p}")
        LogUtils.d("KaoChangAlgorithm", "1区坑位 p1_9= ${p1_9}")

        var supP1_9 = lowConcurrencyIntestinalSupplementation(p1_9,p_13_15,p_16_18)
        LogUtils.d("KaoChangAlgorithm", "supP1_9-补肠返回 supP1_9= ${supP1_9}")
        //营业时间前半小时停止补肠
        val businessHours =
            KaoPanHelper.isBeforeBusinessHours(config.businessTime,30)
        //一区9 加 二区6
        if (p == (list1_6.size + list_13_18.size)) {
            if (config.errorStatus==0 && !businessHours){ //todo 如果没有错误才进行初始化
                initLowTrafficMode(kaoPanList, listBox)//初始化低并发
            }
        } //else if (p1_9 >= list_13_15.size) {//todo 改成大于等于3补烤肠动作
        //else {
        else if (p1_9 >= list_13_15.size || p_13_18 < list_13_18.size) {
            LogUtils.d("KaoChangAlgorithm", "加热区空位 ${p_13_18} 检测加热状态")
            if(p_16_18 < list_16_18.size){
                checkLowHeatSausage16_18(kaoPanList)//烤肠正在加热，检查是否已经加热完成，移动烤肠到指定的位置去
            }

            if (p_13_15 < list_13_15.size) {
                checkLowHeatSausage(kaoPanList)//烤肠正在加热，检查是否已经加热完成，移动烤肠到指定的位置去
            }

            if (supP1_9 == 1) {
                if (config.errorStatus==0  && !businessHours){
                    TEMPERATURE_OFF =0;
                    //todo 如果没有错误才进行添加烤肠的动作
                    addLowHeatSausage(kaoPanList,listBox,list_13_15.size)//需要加烤肠了，满足少了3个空位
                    LogUtils.d("KaoChangAlgorithm", "13_15补肠完成")
                }
            }else if (supP1_9 == 2) {
                if (config.errorStatus==0  && !businessHours){
                    TEMPERATURE_OFF =0;
                    //todo 如果没有错误才进行添加烤肠的动作
                    addLowHeatSausage16_18(kaoPanList,listBox,list_16_18.size)//需要加烤肠了，满足少了3个空位
                    LogUtils.d("KaoChangAlgorithm", "16-18补肠完成")
                }
            }
        }

        if(supP1_9 == 3 && TEMPERATURE_OFF!=1){
            TEMPERATURE_OFF=1
            LogUtils.d("KaoChangAlgorithm", "2区停止加热-p_13_18")
            KaoChangOperate.stopHeating(2)
        }
        //检查是否已经加热结束，加热结束需要关闭加热进行保温
        checkLowHeatEnd(list1_6)
        //检查低并发是否超过保鲜时间，超过保鲜时间需要丢弃掉
        checkLowHeatDiscard(list1_6)
        if (p1_9 >= 7 && p_13_18==0) {
            LogUtils.d("KaoChangAlgorithm", "1区坑位 p_13_15= ${p_13_15} 切换并发模式到高并发")
            switchLowToHigh()//切换并发模式到高并发
            LogUtils.d("KaoChangAlgorithm", "切换后模式 ${AppConfig.getAppConfig().modeType}")
            // LogUtils.d("KaoChangAlgorithm", "切换后模式 config ${config.modeType}")
        }
        //执行完成，保存状态
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        return p
    }

    /**
     * 低并发补肠
     * p_1_9 空位数
     * 13-15 空位数
     * list_16_18 p_1_9 空位数
     * return 0 不补肠，1补肠13-16,2补肠16-18,3:停止加热
     */
    private  fun lowConcurrencyIntestinalSupplementation(p_1_9: Int, p_13_15:Int, p_16_18:Int): Int{
        val p_13_18 = (p_13_15 + p_16_18);
        if(p_1_9 >= 3){
            //补肠是13-15还是16-18
            if(p_13_15 == 3 && p_16_18 == 3 ){
                return 1;
            }
            else if(p_13_15 == 3 && p_1_9 >= 6 && p_16_18 == 0 ){
                return 1;
            }
            else if(p_16_18 == 3 && p_1_9 >= 6  && p_13_15 == 0){
                return 2;
            }
        }
        else if(p_13_18 == 6){
            return 3;
        }
        return 0;
    }

    /**
     * 初始化低并发
     * 从烤肠箱子，移动烤肠到烤盘进行烤制
     * 烤肠的比例和烤肠箱的烤肠分布有关联
     */
    private suspend fun initLowTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.d("KaoChangAlgorithm", "初始化低并发")
        // 如果所有烤肠箱都为空，直接停止
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w("KaoChangAlgorithm", "烤肠箱为空，停止加热")
            KaoChangOperate.stopHeating(1)
            return
        }
        //1-6工位
        //val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        //1区开始预热->加热
        KaoChangOperate.heating(1)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox,list1_9.size)

        // 将口味分配到具体烤盘位置
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_9.size) {
                    // 设置烤盘口味
                    list1_9[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }
        //开始从烤肠箱搬运烤肠到烤盘上进行烤制,并且烤肠箱num数量-1
        list1_9.forEach { kaoPan ->
//            //容错，因为taste被清空了
//            if(kaoPan.taste==null){
//                kaoPan.taste= Taste()
//            }
            //var tag = 0
            // 找到对应口味的烤肠箱
            val matchingBox = listBox.find { it.tasteCode == kaoPan.taste?.tasteCode && it.num > 0 }
            matchingBox?.let { box ->
                if (box.num > 0) {
                    //tag+=1
                    //LogUtils.d("KaoChangAlgorithm", "搬运烤肠 ${tag}")
                    //控制设备进行搬运
                    KaoChangOperate.moveSausageToKaoPan(kaoPan, box)
                    //LogUtils.d("KaoChangAlgorithm", "搬运烤肠结束 ${tag}")
                }
            }
        }

    }

    /**
     * 检查加热的13-15烤肠是否已经完成，已经完成就移动1-6指定空缺的位置去
     */
    private suspend fun checkLowHeatSausage(list: List<KaoPan>) {
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        var p_13_18 = list_13_18.filter { it.isHasSausage }.size

        val list_13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        // 检查13-15区域的烤肠是否已经加热完成
        var stop = true
        var hasHeat =0
        list_13_15.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                hasHeat += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                // 如果加热时间达到要求
                if (heatTime < kaoPan.bakingTime) { //大于加热的时间
                    stop = false
                }
            }
        }

        //加热都结束的就移动到保温区去
        if (stop && hasHeat > 0) {
            // 2区先保温 TODO 检查16-18 是否烤制完毕
            //KaoChangOperate.keepWarm(2)
            // 1区也保温
            KaoChangOperate.keepWarm(1)
//            //设置保温
//            list_13_15.forEach{ kaoPan->
//                if (kaoPan.isHasSausage&& kaoPan.startTime>0) {
//                    //清空加热时间
//                    kaoPan.startTime=0
//                    //记录保温开始时间
//                    kaoPan.holdingTime = System.currentTimeMillis()
//                    //进入保温
//                    kaoPan.status=2
//                }
//            }
            LogUtils.d("KaoChangAlgorithm", "13-15 烤制区搬移到保温区开始")
            list_13_15.forEach{ kaoPan->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    // 获取1-6区域空位
                    val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                    //查找空的位置 TODO：如果没有相同口味，则找不同口味的空位
                    val emptyPosition =
                        list1_6.find { !it.isHasSausage && it.taste.tasteCode.equals(kaoPan.taste.tasteCode) }
                    emptyPosition?.let { targetPan ->
                        //清空加热时间
                        kaoPan.startTime = 0
                        //记录保温开始时间
                        kaoPan.holdingTime = System.currentTimeMillis()
                        //进入保温
                        kaoPan.status = 2
                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    }
                }
            }
            if(p_13_18==0){
                //2区停止加热
                LogUtils.d("KaoChangAlgorithm", "---2区停止加热-p_13_18")
                KaoChangOperate.stopHeating(2)
            }
        }
    }

    /**
     * 检查加热的16-18烤肠是否已经完成，已经完成就移动1-9指定空缺的位置去
     */
    private suspend fun checkLowHeatSausage16_18(list: List<KaoPan>) {
        val list_13_18 = TrafficModeHelper.getTrafficModePositions(13, 18, list)
        var p_13_18 = list_13_18.filter { it.isHasSausage }.size
        val list_16_18 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        // 检查13-15区域的烤肠是否已经加热完成
        var stop = true //false
        var hasHeat = 0

        list_16_18.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                hasHeat += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                // 如果加热时间达到要求
                //if (heatTime >= kaoPan.bakingTime) { //大于加热的时间
                if (heatTime < kaoPan.bakingTime) { //大于加热的时间
                    stop = false
                }
            }
        }

        //加热都结束的就移动到保温区去
        if (stop && hasHeat > 0) {
            // 2区先保温
           // KaoChangOperate.keepWarm(2)
            // 1区也保温
            KaoChangOperate.keepWarm(1)
            LogUtils.d("KaoChangAlgorithm", "16-18 烤制区搬移到保温区开始")
            list_16_18.forEach{ kaoPan->
                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                    // 获取1-6区域空位
                    val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                    //查找空的位置 TODO：如果没有相同口味，则找不同口味的空位
                    val emptyPosition =
                        list1_6.find { !it.isHasSausage && it.taste.tasteCode.equals(kaoPan.taste.tasteCode) }
                    emptyPosition?.let { targetPan ->
                        //移动烤盘位置到1-6空位

                        //清空加热时间
                        kaoPan.startTime=0
                        //开始保温
                        kaoPan.holdingTime=System.currentTimeMillis()
                        //进入保温的状态
                        kaoPan.status=2
                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    }
                }
            }
            if(p_13_18==0){
                //2区停止加热
                LogUtils.d("KaoChangAlgorithm", "2区停止加热-p_13_18")
                KaoChangOperate.stopHeating(2)
            }

        }
    }


    /**
     * 低并发模式添加烤肠
     * p_num 空位数
     */
    private suspend fun addLowHeatSausage(list: List<KaoPan>, listBox: List<KaoPanBox> ,p_num: Int) {
        //1-6区域内的烤肠
        //val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        //13-15区域的烤肠
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        //检查13-15区域是否有烤肠
        val hasSausageIn13_15 = list13_15.any { it.isHasSausage }
        if (hasSausageIn13_15) {
            return  //如果13-15区域有烤肠，直接返回不执行添加操作
        }
        // 获取1-6区域缺少烤肠的工位
        val emptyPositions = list1_6.filter { !it.isHasSausage }
        // 获取13-15区域的空工位
        val emptyPositions13_15 = list13_15.filter { !it.isHasSausage }

        // 确保有足够的空位
        if (emptyPositions.size >= p_num && emptyPositions13_15.size >= p_num) {
            // 开启2区加热
            KaoChangOperate.heating(2)
            // 取前3个空位的口味信息 TODO 目前是卖口味补什么口味，没有判断库存是否足够
            val neededTastes = emptyPositions.take(p_num).map { it.taste.tasteCode }

            // 将烤肠从烤肠箱移动到13-15工位
            neededTastes.forEachIndexed { index, tasteCode ->
                // 找到对应口味的烤肠箱
                val matchingBox = listBox.find { it.tasteCode == tasteCode && it.num > 0 }
                matchingBox?.let { box ->
                    if (box.num > 0) {
                        val targetPosition = emptyPositions13_15[index]
                        // 设置目标工位的口味
                        targetPosition.taste = Taste().apply {
                            this.tasteCode = tasteCode
                        }
                        // 控制设备进行搬运
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, box)
                    }
                }
            }
        }

    }

    /**
     * 低并发模式添加烤肠
     *  p_num 空位数
     */
    private suspend fun addLowHeatSausage16_18(list: List<KaoPan>, listBox: List<KaoPanBox> ,p_num: Int) {
        //1-6区域内的烤肠
        //val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        //13-15区域的烤肠
        val list13_15 = TrafficModeHelper.getTrafficModePositions(16, 18, list)
        //检查13-15区域是否有烤肠
        val hasSausageIn13_15 = list13_15.any { it.isHasSausage }
        if (hasSausageIn13_15) {
            return  //如果13-15区域有烤肠，直接返回不执行添加操作
        }
        // 获取1-6区域缺少烤肠的工位
        val emptyPositions = list1_6.filter { !it.isHasSausage }
        // 获取13-15区域的空工位
        val emptyPositions13_15 = list13_15.filter { !it.isHasSausage }

        // 确保有足够的空位
        if (emptyPositions.size >= p_num && emptyPositions13_15.size >= p_num) {
            // 开启2区加热
            KaoChangOperate.heating(2)
            // 取前3个空位的口味信息 TODO 目前是卖口味补什么口味，没有判断库存是否足够
            val neededTastes = emptyPositions.take(p_num).map { it.taste.tasteCode }

            // 将烤肠从烤肠箱移动到13-15工位
            neededTastes.forEachIndexed { index, tasteCode ->
                // 找到对应口味的烤肠箱
                val matchingBox = listBox.find { it.tasteCode == tasteCode && it.num > 0 }
                matchingBox?.let { box ->
                    if (box.num > 0) {
                        val targetPosition = emptyPositions13_15[index]
                        // 设置目标工位的口味
                        targetPosition.taste = Taste().apply {
                            this.tasteCode = tasteCode
                        }
                        // 控制设备进行搬运
                        KaoChangOperate.moveSausageToKaoPan(targetPosition, box)
                    }
                }
            }
        }

    }

    /**
     * 检查低并发模式下的烤肠是否加热完成
     */
    private fun checkLowHeatEnd(list: List<KaoPan>) {
        //1-6区域内的烤肠
        var list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        //如果1-6区域内的烤肠 如果有烤肠，检查是否所有肠加热时间到了，加热时间到了就结束加热进入保温的状态
        var stop_1_6 = true
        var hasHeat = 0
        list1_6.forEach { kaoPan ->
            //如果烤肠已经开始加热
            if (kaoPan.isHasSausage&& kaoPan.startTime>0) {
                // 记录处于加热态而非保温态
                hasHeat += 1
                //获取烤肠的加热时间
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                //如果加热时间达到要求
                if (heatTime < kaoPan.bakingTime) {
                    stop_1_6 = false
//                    //清空加热时间
//                    kaoPan.startTime=0
//                    //记录保温开始时间
//                    kaoPan.holdingTime = System.currentTimeMillis()
//                    //进入保温
//                    kaoPan.status=2

                }
            }
        }
     if((stop_1_6) && (hasHeat > 0)){
         //结束加热进入保温状态
         KaoChangOperate.stopHeating(1)
         KaoChangOperate.keepWarm(1)
         // 更新烤肠状态
         list1_6.forEach { kaoPan ->
             //如果烤肠已经开始加热
             if (kaoPan.isHasSausage&& kaoPan.startTime>0) {
                 //清空加热时间
                 kaoPan.startTime=0
                 //记录保温开始时间
                 kaoPan.holdingTime = System.currentTimeMillis()
                 //进入保温
                 kaoPan.status=2
             }
         }
     }
  }

    /**
     * 检查低并发模式下的烤肠是否过期丢弃
     */
    private suspend fun checkLowHeatDiscard(list: List<KaoPan>) {
        //1-6区域内的烤肠
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
        //遍历1-6区域内的烤肠
        list1_6.forEach { kaoPan ->
            //如果烤肠已经开始保温
            if (kaoPan.isHasSausage&&kaoPan.holdingTime>0) {
                //获取烤肠的保温时间
                val heatTime = System.currentTimeMillis() - kaoPan.holdingTime
                //如果保温时间超过保鲜时间
                if (heatTime > kaoPan.closeTime) {
                    LogUtils.d("KaoChangAlgorithm", "丢弃时间 ${kaoPan.closeTime}")
                    //丢弃烤肠
                    KaoChangOperate.discardSausage(kaoPan)
                }
            }
        }
    }
    /**
     * 切换低并发到高并发->直接修状态，并且进入过渡态
     */
    private fun switchLowToHigh() {
        AppConfig.getAppConfig().apply {
            modeType=2
            transitionMode=1
        }
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        //同步到云端去
        NetApi.updateDeviceInfo()
    }
}