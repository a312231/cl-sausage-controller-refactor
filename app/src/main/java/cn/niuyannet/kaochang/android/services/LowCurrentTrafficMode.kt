package cn.niuyannet.kaochang.android.services

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


    /**
     *         低并发模式处理器
     *         如果p=9 就是初始化敌低并发模式
     *         如果p=3 并且2区没有烤肠加热【就是就是要添加烤肠】【检查是否已经加热完成，加热完成需要关闭加热和移动烤肠】
     *         如果p=5或者=6 就需要切换并发模式到高并发
     *
     *
     *
     *
     */
    suspend fun lowTrafficMode( config: AppConfigBean,kaoPanList: List<KaoPan>, listBox: List<KaoPanBox>): Int {
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, kaoPanList)
        //val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, kaoPanList)
        val list_13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, kaoPanList)
        val p1_9 = list1_6.filter { !it.isHasSausage }.size//空位数
        val p_13_15 = list_13_15.filter { !it.isHasSausage }.size
        val p = p1_9 + p_13_15
        LogUtils.d("KaoChangAlgorithm", "1区坑位 P= ${p}")
        LogUtils.d("KaoChangAlgorithm", "1区坑位 p1_6= ${p1_9}")

        if (p == 9) {
//        if (p == 12) {
            if (config.errorStatus==0){ //todo 如果没有错误才进行初始化
                initLowTrafficMode(kaoPanList, listBox)//初始化低并发
            }
        } else if (p1_9 >= 3) {//todo 改成大于等于3补烤肠动作
            LogUtils.d("KaoChangAlgorithm", "加热区空位 ${p_13_15} 检测加热状态")
            if (p_13_15 < 3) {
                checkLowHeatSausage(kaoPanList)//烤肠正在加热，检查是否已经加热完成，移动烤肠到指定的位置去
            } else if (p_13_15 == 3) {
                if (config.errorStatus==0){
                    //todo 如果没有错误才进行添加烤肠的动作
                    addLowHeatSausage(kaoPanList,listBox)//需要加烤肠了，满足少了3个空位
                    LogUtils.d("KaoChangAlgorithm", "补肠完成")
                }
            }
        }
        //检查是否已经加热结束，加热结束需要关闭加热进行保温
        checkLowHeatEnd(list1_6)
        //检查低并发是否超过保鲜时间，超过保鲜时间需要丢弃掉
        checkLowHeatDiscard(list1_6)
        if (p1_9 >= 5&&p_13_15==0) {
            LogUtils.d("KaoChangAlgorithm", "1区坑位 p_13_15= ${p_13_15} 切换并发模式到高并发")
             switchLowToHigh()//切换并发模式到高并发
            LogUtils.d("KaoChangAlgorithm", "切换后模式 ${AppConfig.getAppConfig().modeType}")
            LogUtils.d("KaoChangAlgorithm", "切换后模式 config ${config.modeType}")
        }
        //执行完成，保存状态
        GlobalScope.launch(Dispatchers.Main) {
            KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
        }
        return p
    }

    /**
     * 初始化低并发
     * 从烤肠箱子，移动烤肠到烤盘进行烤制
     * 烤肠的比例和烤肠箱的烤肠分布有关联
     */
    private suspend fun initLowTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.d("KaoChangAlgorithm", "初始化低并发")
        //1-6工位
        //val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        //1区开始预热->加热
        KaoChangOperate.heating(1)

        val flavorAllocation = KaoPanHelper.computeKaoPamRatio(list,listBox,list1_9.size)

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
        val list_13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        // 检查13-15区域的烤肠是否已经加热完成
        var stop = true //false
        var hasHeat = 0

        list_13_15.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                hasHeat += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                // 如果加热时间达到要求
                //if (heatTime >= kaoPan.bakingTime) { //大于加热的时间
                if (heatTime < kaoPan.bakingTime) { //大于加热的时间
                    stop = false //true
                }
            }
        }

        //加热都结束的就移动到保温区去
        if (stop && hasHeat > 0) {
            // 2区先保温
            KaoChangOperate.keepWarm(2)
            // 1区也保温
            KaoChangOperate.keepWarm(1)
            list_13_15.forEach{ kaoPan->
                // 获取1-6区域空位
                    val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
                    //查找空的位置
                    val emptyPosition =
                        list1_6.find { !it.isHasSausage && it.taste.tasteCode.equals(kaoPan.taste.tasteCode) }
                    emptyPosition?.let { targetPan ->
                        //移动烤盘位置到1-6空位
                        kaoPan.holdingTime=System.currentTimeMillis()//开始保温
                        kaoPan.status=2//进入保温的状态
                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                    }
            }
            //2区停止加热
            KaoChangOperate.stopHeating(2)
        }
    }

    /**
     * 低并发模式添加烤肠
     */
    private suspend fun addLowHeatSausage(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        //1-6区域内的烤肠
        //val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
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
        if (emptyPositions.size >= 3 && emptyPositions13_15.size >= 3) {
            // 开启2区加热
            KaoChangOperate.heating(2)
            // 取前3个空位的口味信息 TODO 目前是卖口味补什么口味，没有判断库存是否足够
            val neededTastes = emptyPositions.take(3).map { it.taste.tasteCode }

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
        var list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
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
        val list1_6 = TrafficModeHelper.getTrafficModePositions(1, 6, list)
        //遍历1-6区域内的烤肠
        list1_6.forEach { kaoPan ->
            //如果烤肠已经开始保温
            if (kaoPan.isHasSausage&&kaoPan.holdingTime>0) {
                //获取烤肠的保温时间
                val heatTime = System.currentTimeMillis() - kaoPan.holdingTime
                //如果保温时间超过保鲜时间
                if (heatTime > kaoPan.closeTime) {
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