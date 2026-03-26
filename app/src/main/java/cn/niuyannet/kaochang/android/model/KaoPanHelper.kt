package cn.niuyannet.kaochang.android.model

import android.util.Log

import cn.niuyannet.kaochang.android.init.AppConfig.getAppConfig
import cn.niuyannet.kaochang.android.init.BusinessTime
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


/**
 * 烤盘数据类
 */
object KaoPanHelper {
    private const val FILE_SP_URL = "file_kaopan_helper"
    private const val KEY_KAO_PAN_LIST = "key_kao_pan_list"
    private const val KEY_KAO_PAN_BOX_LIST = "key_kao_pan_box_list"
    private const val KEY_KAO_PAN_CONFIG = "key_kao_pan_config"
    private const val KEY_TASTE_LIST = "key_taste_list" // 添加口味列表的键
    //烤制时间 烤制时间40分钟
    val bakingTime= 40*60*1000L //10*60*1000L
    //保温时间
    val holdingTime=0L
    //丢弃时间 6h丢弃
    val discardTime=6*60*60*1000L //10*60*1000L
    //口味列表
    private var tasteList= mutableListOf<Taste>()
    //烤盘
    private var kaoPanList = mutableListOf<KaoPan>()
    //烤肠箱
    private var kaoPanBoxList = mutableListOf<KaoPanBox>()

    //初始化烤盘配置
    fun init() {
        //先检测本地是否已经有了，如果本地有了就用本地保存最新的，否则就用默认值
        val savedKaoPanList = PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, KaoPan::class.java)
        if (!savedKaoPanList.isNullOrEmpty()) {
            kaoPanList = savedKaoPanList.toMutableList()
        } else {
            // 如果没有保存过，则保存默认值
            //烤盘数据初始化
            kaoPanList = mutableListOf<KaoPan>().apply {
                //1,4,7,10,13...31,2,5..32,3.6...33 且1~9为1区.10~12为过渡区.13~21为2区，22-24为过渡区，25~33为三区
                // 按照特定顺序初始化烤盘：1,4,7,10,13...31,2,5..32,3.6...33
                val positions = mutableListOf<Int>()
                // 生成位置顺序：先添加所有1,4,7...位置
                for (i in 1..31 step 3) {
                    positions.add(i)
                }

                // 再添加所有2,5,8...位置
                for (i in 2..32 step 3) {
                    positions.add(i)
                }

                // 最后添加所有3,6,9...位置
                for (i in 3..33 step 3) {
                    positions.add(i)
                }

                // 根据生成的顺序创建烤盘对象
                for (i in 0 until 33) {
                    val positionSn = positions[i]
                    // 计算区域: 1-9为1区, 10-12为过渡区, 13-21为2区, 22-24为过渡区, 25-33为3区
                    val region = when (positionSn) {
                        in 1..9 -> 1
                        in 10..12 -> 4 // 过渡区
                        in 13..21 -> 2
                        in 22..24 -> 4 // 过渡区
                        else -> 3
                    }
                    // 计算命令值
                    val cmdValueMove = positionSn // 从平台把烤肠搬运到烤盘1-33
                    val cmdValueTake = positionSn + 100 // 把烤好的烤肠取出来 101-133
                    val cmdValueDiscard = positionSn + 300 // 丢弃烤肠 301-333
                    // 获取配置实例，避免重复调用
                    val config = getAppConfig()
                    // 设置烘烤时间（默认40分钟）
                    val bakingTimeMinutes = if (config.bakingTime != null) config.bakingTime else 40
                    // 设置关闭/丢弃时间（默认6小时）
                    val discardTimeHours = if (config.discardTime != null) config.discardTime else 6

                    // 创建烤盘对象并设置属性
                    val kaoPan = KaoPan()
                    kaoPan.id = i
                    kaoPan.positionSn = positionSn
                    kaoPan.positionRegion = region
                    kaoPan.startTime = 0L
                    kaoPan.bakingTime = bakingTimeMinutes * 60L * 1000L
                    kaoPan.holdingTime = holdingTime
                    kaoPan.closeTime = discardTimeHours * 60L * 60L * 1000L
                    kaoPan.taste = null
                    kaoPan.temperature = 0
                    kaoPan.status = 0
                    kaoPan.isHasSausage = false
                    kaoPan.isHasGrilling = false
                    kaoPan.cmdValueMove = cmdValueMove
                    kaoPan.cmdValueTake = cmdValueTake
                    kaoPan.cmdStatusMove = 0
                    kaoPan.cmdStatusTake = 0
                    kaoPan.cmdValueDiscard = cmdValueDiscard
                    kaoPan.cmdStatusDiscard = 0
                    add(kaoPan)
                }
            }
            saveKaoPanList( kaoPanList)
        }
    }
    /**
     * 重置烤盘
     */
    fun resetKaoPan() {
        // 获取配置实例，避免重复调用
        val config = getAppConfig()
        // 设置烘烤时间（默认40分钟）
        val bakingTimeMinutes = if (config.bakingTime != null) config.bakingTime else 40
        // 设置关闭/丢弃时间（默认6小时）
        val discardTimeHours = if (config.discardTime != null) config.discardTime else 6
        // 重置所有烤盘的状态
        for (kaoPan in kaoPanList) {
            kaoPan.startTime = 0L
            kaoPan.bakingTime = bakingTimeMinutes * 60L * 1000L
            kaoPan.holdingTime = holdingTime
            kaoPan.closeTime = discardTimeHours * 60L * 60L * 1000L
            kaoPan.taste = null
            kaoPan.temperature = 0
            kaoPan.status = 0
            kaoPan.isHasSausage = false
            kaoPan.isHasGrilling = false
            kaoPan.cmdStatusMove = 0
            kaoPan.cmdStatusTake = 0
            kaoPan.cmdStatusDiscard = 0
        }
        // 保存更新后的烤盘列表
        saveKaoPanList(kaoPanList)
    }

    /**
     * 获取烤盘列表
     */
    fun getKaoPanList():List<KaoPan>{
        if (kaoPanList.size>0){
            return kaoPanList
        }
        this.kaoPanList= PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, KaoPan::class.java).toMutableList()
        return kaoPanList
    }

    /**
     * 获取烤肠箱列表
     */
    fun getKaoPanBoxList():List<KaoPanBox>{
        if (this.kaoPanBoxList.size>0){
            return this.kaoPanBoxList
        }
        this.kaoPanBoxList=  PreferenceUtils.getListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, KaoPanBox::class.java).toMutableList()
        return this.kaoPanBoxList
    }
    /**
     * 获取口味列表
     */
    fun getTasteList():List<Taste>{
        if (tasteList.size>0){
            return tasteList
        }
        tasteList= PreferenceUtils.getListPreference(FILE_SP_URL, KEY_TASTE_LIST, Taste::class.java).toMutableList()
        return tasteList;
    }
    /**
     * 保存烤盘列表
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun saveKaoPanList(list: List<KaoPan>) {
        GlobalScope.launch(Dispatchers.Main) {
            kaoPanList = list.toMutableList()
            PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_LIST, kaoPanList)
        }
    }


    /**
     * 保存烤箱列表
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun saveKaoPanBoxList(list: List<KaoPanBox>) {
        GlobalScope.launch(Dispatchers.Main) {
            kaoPanBoxList = list.toMutableList()
            PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_KAO_PAN_BOX_LIST, kaoPanBoxList)
        }
    }


    /**
     * 保存口味列表
     */
    fun saveTasteList(list: List<Taste>) {
        tasteList = list.toMutableList()
        PreferenceUtils.saveListPreference(FILE_SP_URL, KEY_TASTE_LIST, tasteList)
    }

    /**
     * 根据位置获取烤盘
     */
    fun getKaoPanByPosition(positionSn: Int): KaoPan? {
        return kaoPanList.find { it.positionSn == positionSn }
    }

    /**
     * 清除所有数据
     */
    fun clearAllData() {
        PreferenceUtils.deletePreference(FILE_SP_URL)
    }

    /**
     * 清空烤盘
     */
    fun clearKaoPan() {
        PreferenceUtils.deletePreference(FILE_SP_URL,KEY_KAO_PAN_LIST)
    }

    /**
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 按比例计算“理论分配值”
        val rawAllocations = sortedFlavors.associate { (flavor, ratio) ->
            flavor to (list_size * ratio)
        }

        // 5. 取整，并保证每种至少 1 个
        val allocation = rawAllocations.mapValues { (_, value) ->
            maxOf(1, value.toInt())
        }.toMutableMap()

        // 6. 修正总数，使分配总和严格等于 list_size
        var currentSum = allocation.values.sum()

        if (currentSum < list_size) {
            // 6.1 不足：按小数部分从大到小补
            val sortedByRemainder = rawAllocations
                .map { (flavor, value) ->
                    flavor to (value - value.toInt())
                }
                .sortedByDescending { it.second }

            var idx = 0
            while (currentSum < list_size) {
                val flavor = sortedByRemainder[idx % sortedByRemainder.size].first
                allocation[flavor] = allocation[flavor]!! + 1
                currentSum++
                idx++
            }

        } else if (currentSum > list_size) {
            // 6.2 超出：从分配最多的口味开始减，但不能减到 < 1
            val sortedByCount = allocation.entries
                .sortedByDescending { it.value }
                .map { it.key }

            var idx = 0
            while (currentSum > list_size) {
                val flavor = sortedByCount[idx % sortedByCount.size]
                if (allocation[flavor]!! > 1) {
                    allocation[flavor] = allocation[flavor]!! - 1
                    currentSum--
                }
                idx++
            }
        }

        return allocation
    }


    /**
     * (比例计算有问题，已弃用)
     * 低并发，高并发初烤肠初始化口味分配
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio_old(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 初始化每个口味至少1个位置
        val allocation = sortedFlavors.associate { (flavor, _) ->
            flavor to 1
        }.toMutableMap()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size - allocation.size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 应用额外分配
            extraAllocations.forEach { (flavor, extra) ->
                allocation[flavor] = allocation[flavor]!! + extra
            }

            // 处理剩余位置（由于取整可能未完全分配）
            if(totalCount>0){
                var unallocated = remainingPositions - extraAllocations.values.sum()
                var index = 0
                while (unallocated > 0) {
                    val flavor = sortedFlavors[index].first
                    allocation[flavor] = allocation[flavor]!! + 1
                    unallocated--
                    index = (index + 1) % sortedFlavors.size
                }
            }
        }

        return allocation
    }



    /**
     * 低转高并发打标签初始化
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeHighKaoPamRatio(listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
        }
        //烤箱库存总数
        val totalCount = flavorTypes2.values.sum().toFloat()

        // 3. 计算比例并排序
        val sortedFlavors = flavorTypes2.map { (flavor, count) ->
            flavor to count / totalCount
        }.sortedByDescending { (_, ratio) -> ratio }

        // 4. 初始化每个口味
        val allocation = mutableMapOf<String, Int>()

        // 5. 按比例分配剩余位置
        var remainingPositions = list_size
        if (remainingPositions > 0) {
            // 计算每个口味的额外分配
            val extraAllocations = sortedFlavors.map { (flavor, ratio) ->
                flavor to (remainingPositions * ratio).toInt()
            }.toMap()

            // 处理剩余位置（由于取整可能未完全分配）
            var unallocated = remainingPositions - extraAllocations.values.sum()
            var index = 0
            while (unallocated > 0) {
                // 循环遍历所有口味，按比例高低顺序分配
                val flavor = sortedFlavors[index % sortedFlavors.size].first
                // 增加该口味一个工位
                allocation[flavor] = (allocation[flavor] ?: 0) + 1
                unallocated--
                index++
            }
            // 将额外分配的结果合并到总分配中
            extraAllocations.forEach { (flavor, count) ->
                allocation[flavor] = (allocation[flavor] ?: 0) + count
            }
        }

        return allocation
    }



    /**
     * 是否距离烤制时间还要前半小时
     * @param currentTimeStr 当前时间字符串，格式为"HH:mm"
     * @param businessTime 营业时间配置
     * @return 是否距离烤制时间还要前半小时
     */

     fun isBeforeBusinessHours( businessTime: BusinessTime,minute : Int): Boolean {
        try {
            var currentTimeStr = getCurrentTime()
            if (businessTime == null) return false

            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val current = format.parse(currentTimeStr)

            // 获取当前是星期几（1-7，对应周一到周日）
            val calendar = Calendar.getInstance()
            // Calendar.DAY_OF_WEEK 返回 1-7，对应周日到周六，需要转换为 1-7 对应周一到周日
            val dayOfWeek = if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else calendar.get(Calendar.DAY_OF_WEEK) - 1

            // 根据营业时间模式判断
            if ("everyday".equals(businessTime.mode, ignoreCase = true) && businessTime.defaultTime != null && businessTime.defaultTime.size >= 2) {
                // 默认模式：所有天使用相同的时间范围
                val end = format.parse(businessTime.defaultTime[1])

                return isTimeInRange(current, end,minute)
            } else if ("custom".equals(businessTime.mode, ignoreCase = true) && businessTime.customTimes != null) {
                // 自定义模式：每天可以有不同的时间范围
                for (timeItem in businessTime.customTimes) {
                    // 检查是否是当前日期且已启用
                    if (timeItem.day == dayOfWeek && timeItem.enabled && timeItem.timeRange != null && timeItem.timeRange.size >= 2) {
                        val end = format.parse(timeItem.timeRange[1])
                        return isTimeInRange(current, end, minute )
                    }
                }
            }
            // 如果没有匹配的时间范围，则不在营业时间内
            return false

        } catch (e: Exception) {
            Log.e("KaoPanHeper", "时间格式解析错误: $e")
            return false
        }
    }

    private fun isTimeInRange(current: Date, end: Date, minute: Int): Boolean {

        if (current.after(end)) {
            // 当前时间已经超过结束时间，直接返回true
            return true
        }
        // 计算距离营业结束的时间差（毫秒）
        val diffInMillis = end.time - current.time
        val beforeCloseMinutesInMillis = minute.coerceAtLeast(0) * 60 * 1000L

        // 判断当前时间是否已经进入“距离营业结束 minute 分钟内”的区间
        return diffInMillis <= beforeCloseMinutesInMillis
    }

    /**
     * 获取当前时间
     * @return 当前时间字符串，格式为"HH:mm"
     */
    private fun getCurrentTime(): String {
        val currentTime = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        return dateFormat.format(currentTime.time)
    }






}
