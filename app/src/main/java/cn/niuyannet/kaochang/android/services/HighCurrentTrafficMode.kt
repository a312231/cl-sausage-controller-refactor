package cn.niuyannet.kaochang.android.services

import android.util.Log
import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.NetApi
import cn.niuyannet.kaochang.android.utils.LogUtils
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2


/**
 * 烤盘高并发模式控制类
 */

object HighCurrentTrafficMode {

    // 坑位计数器，用于跟踪已售出的烤肠数量
    private var pitCount = 0

    // 上次填坑时间，用于高并发转低并发的判断
    private var lastFillPitTime = 0L

    // 两次p=9的时间间隔阈值，超过此值考虑转为低并发
    private val TIME_THRESHOLD_HIGH_TO_LOW = 5 * 60 * 1000L // 30分钟

    // 停止营业前的时间阈值
    private val TIME_BEFORE_CLOSE = 30 * 60 * 1000L // 30分钟

    /**
     * 过渡态，从低并发切换到高并发，从高并发切换到低并发
     * 高并发处理器
     * 1. 初始化：一区、二区预热到60℃，将21根烤肠加到1-21号工位，加热20分钟后进入保温状态
     * 2. 当卖出9根烤肠形成9个坑位时，三区预热到60℃，将9根烤肠加到25-33号工位，加热20分钟后三区断电，
     *    然后将加热完成的烤肠转移到1-21号工位的空缺处
     */
    suspend fun highTrafficMode(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>
    ) {
        LogUtils.d("KaoChangAlgorithm", "高并发检查 config.transitionMode----${config.transitionMode}")
        when (config.transitionMode) {
            0 -> {
                val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
                val p1_21 = list1_21.filter { !it.isHasSausage }.size
                val p_25_33 = list_25_33.filter { !it.isHasSausage }.size
                val p = p1_21 + p_25_33
//                LogUtils.d("KaoChangAlgorithm", "1 2区空位 ${p1_21} 检测加热状态")
//                LogUtils.d("KaoChangAlgorithm", "3区空位 ${p_25_33} 检测加热状态")

                val beforeCloseMinutes = config.timeBeforeClose.takeIf { it > 0 } ?: 30
                val businessHours =
                    KaoPanHelper.isBeforeBusinessHours(config.businessTime, beforeCloseMinutes)
                if (p == 30) {
                    // 初始化高并发
                    if (config.errorStatus==0 && !businessHours) {//todo 如果状态是正常的才开始初始化
                        initHighTrafficMode(list, listBox)
                        pitCount = 0
                    }else{

                        LogUtils.d("KaoChangAlgorithm", "营业时间前半小时或错误停止补肠..")
                    }
                } else if (p1_21 >= 9) {
                    LogUtils.d("KaoChangAlgorithm", "加热区空位 ${p_25_33} 检测加热状态")
                    // 当1-21区域有9个空位时，开始填坑
                    if (p_25_33 == 9) {
                        // 如果25-33区域也有9个空位，说明需要添加烤肠到25-33区域
                        if (config.errorStatus==0){//todo 如果状态是正常的才开始填坑算法

                            if(!businessHours){
                                addHighHeatSausage(list, listBox)
                                LogUtils.d("KaoChangAlgorithm", "补肠完成")
                                pitCount = 9
                                lastFillPitTime = System.currentTimeMillis()
                            }else{
                                LogUtils.d("KaoChangAlgorithm", "营业时间前半小时停止补肠..")
                            }

                        }
                    } else {
                        // 检查25-33区域的烤肠是否已经加热完成，如果完成则移动到1-21区域
                        LogUtils.d("KaoChangAlgorithm", "高并发检查加热")
                        // 先检查加热状态，加热完毕就转保温
                        checkHighHeatEnd(list,3)
                        // 转保温后再进行搬移，允许被购买程序阻塞
                        checkHighHeatSausage(list)
                    }
                }
                // 检查是否已经加热结束，加热结束需要关闭加热进行保温
                // 先检查1-9
                val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
                checkHighHeatEnd(list1_9,1)
                // 再检查10-21
                val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)
                checkHighHeatEnd(list10_21,2)

                // 检查高并发是否超过保鲜时间，超过保鲜时间需要丢弃掉
                checkHighHeatDiscard(list1_21)
                checkSwitchToLow()

                // 检查是否需要切换到低并发模式
//                checkSwitchToLow()
                //执行完成，保存状态
                GlobalScope.launch(Dispatchers.Main) {
                    KaoPanHelper.saveKaoPanList(KaoPanHelper.getKaoPanList())
                }
            }

            1 -> {
                //低并发切换到高并发过渡态
                lowTrafficModeTransition(config, list, listBox)

            }

            2 -> {
                //高并发转化成低并发过渡态
                highTrafficModeTransition(config, list, listBox)
            }
        }
    }

    /**
     * 低并发切换到高并发过渡态
     */
    private suspend fun lowTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>
    ) {
        // 获取1-21区域和13-15区域的烤盘
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list13_15 = TrafficModeHelper.getTrafficModePositions(13, 15, list)
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)


        // 检查13-15区域烤肠是否加热完成
        var isStop = true
        var hasHeat = 0
        list13_15.forEach { kaoPan ->
            if (kaoPan.isHasSausage && kaoPan.startTime>0) {
                hasHeat += 1
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                if (heatTime < kaoPan.bakingTime) {
                    isStop = false
                }
            }
        }
        // 检测25-33区域是否有烤肠在加热
        val hasSausageIn25_33 = list25_33.any { it.isHasSausage }
        if (!hasSausageIn25_33) {

            //口味分配
            //1：获取1-21坑位P位数量，去除已有烤肠数
            val exisTastes1_21 = list1_21.filter { !it.isHasSausage }.toSet().size  // 返回集合的大小（整数）

            //2: P坑位数重新打标签
            val flavorAllocation = KaoPanHelper.computeHighKaoPamRatio(listBox,exisTastes1_21)

            //3:将口味分配到1-21号工位
            var currentPosition = 0
            flavorAllocation.forEach { (flavor, count) ->
                var allocated = 0
                while (allocated < count && currentPosition < list1_21.size) {
                    // 跳过已有烤肠的位置
                    while (currentPosition < list1_21.size && list1_21[currentPosition].isHasSausage) {
                        currentPosition++
                    }
                    // 如果找到可用位置，分配口味
                    if (currentPosition < list1_21.size) {
                        list1_21[currentPosition].taste = Taste().apply {
                            tasteCode = flavor
                        }
                        allocated++
                        currentPosition++
                    } else {
                        // 没有更多可用位置了
                        break
                    }
                }
            }

            // 如果25-33区域没有烤肠,需要添加烤肠进行加热
            val emptyPositions25_33 = list25_33.filter { !it.isHasSausage }
            // 获取1-21区域缺少烤肠的工位口味信息
            val emptyPositions1_21 = list1_21.filter { !it.isHasSausage }
            val neededTastes = emptyPositions1_21.take(9).map { it.taste.tasteCode }

            // 三区开始预热->加热
            KaoChangOperate.heating(3)

            // 将烤肠从烤肠箱移动到25-33工位
            neededTastes.forEachIndexed { index, tasteCode ->
                // 找到对应口味的烤肠箱
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                matchingBox?.let { box ->
                    if (box.num > 0 && index < emptyPositions25_33.size) {
                        val targetPosition = emptyPositions25_33[index]
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

        if (isStop && hasHeat>0) {

            // 关闭2区加热,进入保温状态
            KaoChangOperate.stopHeating(2)
            KaoChangOperate.keepWarm(2)

            list13_15.forEach { kaoPan ->
                if (kaoPan.isHasSausage && kaoPan.startTime>0) {
                    //清空加热时间
                    kaoPan.startTime=0
                    //记录保温开始时间
                    kaoPan.holdingTime = System.currentTimeMillis()
                    //进入保温
                    kaoPan.status=2
                }
            }
        }
        val hasActiveHeatingIn13_15 = list13_15.any { it.isHasSausage && it.startTime > 0 }
        val hasStartedHighHeatSupply = list25_33.any { it.isHasSausage }
        if (hasActiveHeatingIn13_15 || !hasStartedHighHeatSupply) {
            return
        }
        // 切换到正常高并发模式
        config.apply {
            transitionMode = 0 // 取消过渡态
        }
        AppConfig.saveAppConfig(config)
        //同步更新云端状态
        NetApi.updateDeviceInfo()
    }

    /**
     * 高并发转化成低并发过渡态
     */
    private suspend fun highTrafficModeTransition(
        config: AppConfigBean,
        list: List<KaoPan>,
        listBox: List<KaoPanBox>
    ) {
        /**
         * - 在高并发循环中，当出现两次p>=9的时间间隔大于t1，或停止营运前t2，则要进入低并发状态。
         * - 无论是发生那种触发条件，都先保持高并发列表l=21不变，只不过不进行p=9就新加热烤肠的动作，且保持烤盘上的烤肠位置不变，因售卖不断减少数量。当烤盘上只剩下3根烤肠时，才进行大预设列表到小预设列表的切换，即l=21到l=6的切换。
         */
        // 检查25-33区域的烤肠是否加热完成，需要移动到1-21区域
        LogUtils.d("KaoChangAlgorithm", "高转低检查加热")
        checkHighHeatEnd(list,3)
        checkHighHeatSausage(list)
//        list_25_33.forEach { kaoPan ->
//            if (kaoPan.isHasSausage) {
//                val heatTime = System.currentTimeMillis() - kaoPan.startTime
//                if (heatTime >= kaoPan.bakingTime) {
//                    // 查找1-21区域中相同口味的空位
//                    val emptyPosition =
//                        list1_21.find { !it.isHasSausage && it.taste.tasteCode == kaoPan.taste.tasteCode }
//                    emptyPosition?.let { targetPan ->
//                        kaoPan.holdingTime = System.currentTimeMillis()
//                        kaoPan.status = 2 // 保温状态
//                        KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
//                    }
//                }
//            }
//        }

        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        val list10_21 = TrafficModeHelper.getTrafficModePositions(10, 21, list)

        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        // 获取1-21区域内有烤肠的工位数量
        val sausageCount = list1_21.count { it.isHasSausage }
        //获取25-33区域内有烤肠的工位数量
        val sausageCount_25_33 = list_25_33.count { it.isHasSausage }


        // 如果烤肠数量小于等于6，切换到低并发模式
        // if ((sausageCount_25_33 + sausageCount) <= 6) {
        if ((sausageCount) <= 6 && sausageCount_25_33 == 0) {
            // 获取1-9
            val list1_9 = TrafficModeHelper.getTrafficModePositions(1, 9, list)
            // 1-9 已有烤肠P数
            val alreadySausages1_9 = list1_9.filter {it.isHasSausage }
            // 如果是剩余烤肠全在1区不进行搬移操作。
            if(alreadySausages1_9.size < sausageCount){
                // 获取10-21区域内剩余的烤肠,1-9剩余烤肠不用搬，只需搬10-21 的烤肠改为低并发
                val remainingSausages = list10_21.filter { it.isHasSausage }
                // 遍历剩余的烤肠，尝试放置到对应口味的预设工位
                val remainingSausages1_9 = list1_9.filter {!it.isHasSausage }
                remainingSausages.forEachIndexed {index, kaoPan ->
                    //按循环搬移到1-9区域
                    KaoChangOperate.moveKaoPanToKaoPan(kaoPan, remainingSausages1_9[index])
                }
            }

            // 1. 先统计 烤盘上已有的每种口味数量
            val existingCountMap = list1_9
                .filter { it.isHasSausage && it.taste?.tasteCode != null }
                .groupBy { it.taste!!.tasteCode }
                .mapValues { (_, kaoPans) -> kaoPans.size }
            // 2. 计算“还需要补多少”

            // 2.1 计算1-9比例分布
            val flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox, list1_9.size)
            //KaoPanHelper.computeKaoPamRatio(listBox, (9 - list1_9.size))
            val needAllocation = mutableMapOf<String, Int>()
            flavorAllocation.forEach { (flavor, targetCount) ->
                val existing = existingCountMap.getOrDefault(flavor, 0)
                var need = targetCount - existing
                if (need <= 0) { // need <= 0 说明该口味已经超额，直接不再放
                    need = 0
                }
                needAllocation[flavor] = need
            }

            // 3. 只往空位打标签
            // 3.1 先拿到所有可用空位
            val emptyPans = list1_9.filter { !it.isHasSausage }
            var emptyIndex = 0
            // 3.2 打标签
            needAllocation.forEach { (flavor, needCount) ->
                var allocated = 0
                while (allocated < needCount && emptyIndex < emptyPans.size) {
                    val kaoPan = emptyPans[emptyIndex]
                    kaoPan.taste = Taste().apply {
                        tasteCode = flavor
                    }
                    allocated++
                    emptyIndex++
                }
            }

            //2:二区停止保温
            KaoChangOperate.stopHeating(2)

            // 更新配置为低并发模式
            config.apply {
                transitionMode = 0 // 切换回正常模式
                modeType = 1//真正意义的切换到低并发模式
            }
            AppConfig.saveAppConfig(config)

            //同步更新云端状态
            NetApi.updateDeviceInfo()
        }
        // 检查高并发是否超过保鲜时间，超过保鲜时间需要丢弃掉
        checkHighHeatDiscard(list1_21)
    }

    /**
     * 初始化高并发模式
     * 一区、二区预热到60℃，将21根烤肠加到1-21号工位，加热20分钟后进入保温状态
     */
    private suspend fun initHighTrafficMode(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        LogUtils.d("KaoChangAlgorithm", "初始化高并发")
        // 如果所有烤肠箱都为空，直接停止
        val allBoxEmpty = listBox.all { it.num <= 0 }
        if (allBoxEmpty) {
            LogUtils.w("KaoChangAlgorithm", "烤肠箱为空，停止加热")
            KaoChangOperate.stopHeating(1)
            KaoChangOperate.stopHeating(2)
            return
        }
        // 获取1-21工位
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        // 一区、二区开始预热->加热
        KaoChangOperate.heating(1)
        KaoChangOperate.heating(2)

        //计算烤肠口味分配数量
        var flavorAllocation = KaoPanHelper.computeKaoPamRatio(listBox,list1_21.size);

        // 将口味分配到具体烤盘位置
        var currentPosition = 0
        flavorAllocation.forEach { (flavor, count) ->
            repeat(count) {
                if (currentPosition < list1_21.size) {
                    // 设置烤盘口味
                    list1_21[currentPosition].taste = Taste().apply {
                        tasteCode = flavor
                    }
                    currentPosition++
                }
            }
        }

        // 开始从烤肠箱搬运烤肠到烤盘上进行烤制,并且烤肠箱num数量-1
        list1_21.forEach { kaoPan ->
            // 找到对应口味的烤肠箱
            val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, kaoPan.taste?.tasteCode)
            matchingBox?.let { box ->
                if (box.num > 0) {
                    // 控制设备进行搬运
                    KaoChangOperate.moveSausageToKaoPan(kaoPan, box)
                }
            }
        }
    }

    /**
     * 添加烤肠到25-33区域
     * 当卖出9根烤肠形成9个坑位时，三区预热到60℃，将9根烤肠加到25-33号工位
     */
    private suspend fun addHighHeatSausage(list: List<KaoPan>, listBox: List<KaoPanBox>) {
        // 获取1-21区域内的烤肠
        val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
        // 获取25-33区域的烤肠
        val list25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)

        // 检查25-33区域是否有烤肠
        val hasSausageIn25_33 = list25_33.any { it.isHasSausage }
        if (hasSausageIn25_33) {
            return  // 如果25-33区域有烤肠，直接返回不执行添加操作
        }

        // 获取1-21区域缺少烤肠的工位
        val emptyPositions = list1_21.filter { !it.isHasSausage }
        // 获取25-33区域的空工位
        val emptyPositions25_33 = list25_33.filter { !it.isHasSausage }

        // 确保有足够的空位
        if (emptyPositions.size >= 9 && emptyPositions25_33.size >= 9) {
            // 三区开始预热->加热
            KaoChangOperate.heating(3)
            // 取前9个空位的口味信息
            val neededTastes = emptyPositions.take(9).map { it.taste.tasteCode }

            // 将烤肠从烤肠箱移动到25-33工位
            neededTastes.forEachIndexed { index, tasteCode ->
                // 找到对应口味的烤肠箱
                val matchingBox = TrafficModeHelper.findSupplyBoxByTaste(listBox, tasteCode)
                matchingBox?.let { box ->
                    if (box.num > 0 && index < emptyPositions25_33.size) {
                        val targetPosition = emptyPositions25_33[index]
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
     * 检查25-33区域的烤肠是否已经加热完成，如果完成则移动到1-21区域
     */
    private suspend fun checkHighHeatSausage(list: List<KaoPan>) {
        val list_25_33 = TrafficModeHelper.getTrafficModePositions(25, 33, list)
        // 检查25-33区域的烤肠是否已经加热完成
        var stop = true //false
        var hasHeat = 0

        list_25_33.forEach { kaoPan ->
//            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
//                hasHeat += 1
//                val heatTime = System.currentTimeMillis() - kaoPan.startTime
//                if (heatTime < kaoPan.bakingTime) { //小于加热的时间
//                    stop = false
//                }
//            }
            if (kaoPan.isHasSausage) {
                hasHeat += 1
                if (kaoPan.status==1) {
                    stop = false
                }
            }
        }
        if (stop && hasHeat > 0) {
        //if (stop) {
            LogUtils.d("KaoChangAlgorithm", "高并发检查加热 满足保温时间")
            // 3区先保温
            KaoChangOperate.keepWarm(3)
            // 2区先保温
            KaoChangOperate.keepWarm(2)
            // 1区也保温
            KaoChangOperate.keepWarm(1)
//            list_25_33.forEach {kaoPan ->
//                if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
//                    kaoPan.holdingTime = System.currentTimeMillis() // 开始保温
//                    kaoPan.status = 2 // 进入保温的状态
//                    kaoPan.startTime=0;
//                }
//            }

            list_25_33.forEach {kaoPan ->
                if (kaoPan.isHasSausage) {
                    // 获取1-21区域空位
                    val list1_21 = TrafficModeHelper.getTrafficModePositions(1, 21, list)
                    // 查找空的位置
                    if (kaoPan.taste != null) {
                        val tasteCode = kaoPan.taste.tasteCode
                        // 查找空的位置
                        val emptyPosition = list1_21.find {
                            !it.isHasSausage &&
                                    it.taste?.tasteCode?.equals(tasteCode) == true
                        }
                        emptyPosition?.let { targetPan ->
                            // 移动烤盘位置到1-21空位
                            KaoChangOperate.moveKaoPanToKaoPan(kaoPan, targetPan)
                        }
                    }
                }
            }
            // 三区停止加热
            KaoChangOperate.stopHeating(3)
            // 重置坑位计数
            pitCount = 0
        }
    }

    /**
     * 检查高并发模式下的烤肠是否加热完成
     */
    private fun checkHighHeatEnd(list: List<KaoPan>, area: Int) {
        // 如果1-21或加热区域内有烤肠，检查是否加热时间到了，加热时间到了就结束加热进入保温的状态
        var stop = true //false
        var hasHeat = 0
        list.forEach { kaoPan ->
            // 如果烤肠已经开始加热
            if (kaoPan.isHasSausage && kaoPan.startTime > 0) {
                // 记录处于加热态而非保温态
                hasHeat += 1
                // 获取烤肠的加热时间
                val heatTime = System.currentTimeMillis() - kaoPan.startTime
                // 如果加热时间达到要求
                if (heatTime < kaoPan.bakingTime) {
                    stop = false
//                    //清空加热时间
//                    kaoPan.startTime=0
//                    // 记录保温开始时间
//                    kaoPan.holdingTime = System.currentTimeMillis()
//                    // 进入保温
//                    kaoPan.status = 2
                }
            }
        }
        if (stop && (hasHeat > 0)) {
            // 结束加热进入保温状态
            KaoChangOperate.stopHeating(area)
            //KaoChangOperate.stopHeating(2)
            KaoChangOperate.keepWarm(area)
            //KaoChangOperate.keepWarm(2)

            // 更新烤肠状态
            list.forEach { kaoPan ->
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
     * 检查高并发模式下的烤肠是否过期丢弃
     */
    private suspend fun checkHighHeatDiscard(list: List<KaoPan>) {
        // 遍历1-21区域内的烤肠
        list.forEach { kaoPan ->
            // 如果烤肠已经开始加热
            if (kaoPan.isHasSausage && kaoPan.holdingTime > 0) {
                // 获取烤肠的保温时间
                val holdTime = System.currentTimeMillis() - kaoPan.holdingTime
                // 如果保温时间超过保鲜时间
                if (holdTime > kaoPan.closeTime && kaoPan.holdingTime > 0) {
                    LogUtils.d("KaoChangAlgorithm", "丢弃时间 ${kaoPan.closeTime}")
                    // 丢弃烤肠
                    KaoChangOperate.discardSausage(kaoPan)
                }
            }
        }
    }

    /**
     * 检查是否需要切换到低并发模式
     * 如果两次填坑时间间隔超过阈值，或者距离营业结束时间不足阈值，切换到低并发模式
     */
    private fun checkSwitchToLow() {
        if (lastFillPitTime > 0) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastFill = currentTime - lastFillPitTime

            // 如果距离上次填坑时间超过阈值，切换到低并发模式
            val timeThresholdLow = AppConfig.getAppConfig().timeThresholdLow * 60 * 1000L
            if (timeSinceLastFill > timeThresholdLow) {
                switchHighToLow()
                return
            }

            // 如果距离营业结束时间不足阈值，切换到低并发模式
            val businessTime = AppConfig.getAppConfig().businessTime
            if (businessTime != null) {
                val remainingTimeUntilClose = getRemainingTimeUntilClose(businessTime)
                if (remainingTimeUntilClose > 0 && remainingTimeUntilClose < TIME_BEFORE_CLOSE) {
                    switchHighToLow()
                }
            }
        }
    }

    /**
     * 获取距离营业结束时间的剩余毫秒数
     * @param businessTime 营业时间配置
     * @return 距离营业结束的毫秒数，如果无法计算则返回-1
     */
    private fun getRemainingTimeUntilClose(businessTime: BusinessTime): Long {
        try {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val calendar = Calendar.getInstance()
            val currentTimeMillis = calendar.timeInMillis

            // 获取当前是星期几（1-7，对应周一到周日）
            val dayOfWeek =
                if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(
                    Calendar.DAY_OF_WEEK
                ) - 1

            // 获取今天的结束时间
            val endTimeStr = when {
                "everyday".equals(
                    businessTime.mode,
                    ignoreCase = true
                ) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2 -> {
                    // 默认模式：所有天使用相同的时间范围
                    businessTime.defaultTime[1]
                }

                "custom".equals(
                    businessTime.mode,
                    ignoreCase = true
                ) && businessTime.customTimes != null -> {
                    // 自定义模式：查找当天的时间范围
                    val todayTime =
                        businessTime.customTimes.find { it.day == dayOfWeek && it.enabled }
                    if (todayTime != null && todayTime.timeRange != null && todayTime.timeRange.size >= 2) {
                        todayTime.timeRange[1]
                    } else {
                        return -1 // 没有找到当天的结束时间
                    }
                }

                else -> return -1 // 无效的营业时间配置
            }

            // 解析结束时间
            val endTime = format.parse(endTimeStr)
            if (endTime != null) {
                // 设置结束时间的日期部分为今天
                val endCalendar = Calendar.getInstance()
                endCalendar.time = endTime

                // 保留原始小时和分钟
                val hour = endCalendar.get(Calendar.HOUR_OF_DAY)
                val minute = endCalendar.get(Calendar.MINUTE)

                // 重置为今天的日期
                endCalendar.timeInMillis = calendar.timeInMillis
                endCalendar.set(Calendar.HOUR_OF_DAY, hour)
                endCalendar.set(Calendar.MINUTE, minute)
                endCalendar.set(Calendar.SECOND, 0)
                endCalendar.set(Calendar.MILLISECOND, 0)

                // 如果结束时间已经过了，可能是跨天营业，将结束时间设为明天
                if (endCalendar.timeInMillis < currentTimeMillis) {
                    endCalendar.add(Calendar.DAY_OF_MONTH, 1)
                }

                // 计算剩余时间
                return endCalendar.timeInMillis - currentTimeMillis
            }

            return -1 // 无法解析结束时间
        } catch (e: Exception) {
            Log.e("HighCurrentTrafficMode", "计算营业结束时间出错: ${e.message}")
            return -1
        }
    }

    /**
     * 切换高并发到低并发
     */
    private fun switchHighToLow() {
        Log.e("HighCurrentTrafficMode", "并发切换-- 切换高并发转低并发--- 两次补肠时间间隔----${TIME_THRESHOLD_HIGH_TO_LOW}")
        AppConfig.getAppConfig().apply {
            transitionMode = 2
        }
        AppConfig.saveAppConfig(AppConfig.getAppConfig())
        //同步更新云端状态
        NetApi.updateDeviceInfo()
        // 重置坑位计数和填坑时间
        pitCount = 0
        lastFillPitTime = 0L
    }
}
