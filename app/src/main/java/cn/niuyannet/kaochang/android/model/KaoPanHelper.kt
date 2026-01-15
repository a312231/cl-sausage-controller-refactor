package cn.niuyannet.kaochang.android.model

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.net.DataManagementAPI
import cn.niuyannet.kaochang.android.utils.LogUtils
import cn.niuyannet.kaochang.android.utils.PreferenceUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.text.isNotEmpty

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
                    // 创建烤盘对象并设置属性
                    val kaoPan = KaoPan()
                    kaoPan.id = i
                    kaoPan.positionSn = positionSn
                    kaoPan.positionRegion = region
                    kaoPan.startTime = 0L
                    kaoPan.bakingTime = bakingTime
                    kaoPan.holdingTime = holdingTime
                    kaoPan.closeTime = discardTime
                    kaoPan.taste = null
                    kaoPan.temperature = 0
                    kaoPan.status = 0
                    kaoPan.isHasSausage = false
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
        // 重置所有烤盘的状态
        for (kaoPan in kaoPanList) {
            kaoPan.startTime = 0L
            kaoPan.bakingTime = bakingTime
            kaoPan.holdingTime = holdingTime
            kaoPan.closeTime =discardTime
            kaoPan.taste = null
            kaoPan.temperature = 0
            kaoPan.status = 0
            kaoPan.isHasSausage = false
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
     * listKaoPan 烤盘
     * listPanBox 烤箱
     * list_size 烤盘工位数
     */
    fun computeKaoPamRatio(listKaoPan: List<KaoPan>, listPanBox: List<KaoPanBox>, list_size: Int): MutableMap<String, Int> {
        // 1. 统计每种口味总数量
        val flavorTypes2 = listPanBox
            .filter { it.num > 0 && it.tasteCode != null }
            .groupBy { it.tasteCode!! }
            .mapValues { (_, items) -> items.sumOf { it.num } }

        // 2. 检查是否可分配
        if (flavorTypes2.size > list_size) {
            LogUtils.d("KaoChangAlgorithm", "口味类型数量大于工位数分配失败！")
            // 返回每种口味至少1个的分配（按数量从高到低取前list_size个）
//            return flavorTypes2.entries
//                .sortedByDescending { it.value }
//                .take(list_size)
//                .associate { it.key to 1 }
//                .toMutableMap()
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

}