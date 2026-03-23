package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

/**
 * 运营模式工具类
 */

object TrafficModeHelper {

    /**
     * 根据positionSn排序，低并发获取1-6范围的烤盘数据 高并发获取烤盘1-21工位
     */
     fun getTrafficModePositions(start:Int,end:Int,list: List<KaoPan>): List<KaoPan> {
        return list.filter { it.positionSn in start..end }.sortedBy { it.positionSn }
    }


    /**
     * 根据烤肠箱生成高并发口味列表
     */
    fun generateHighTrafficFlavorList(boxCount: Int): List<Int> {
        // 获取所有烤肠箱的口味ID列表
        val flavorList = mutableListOf<Int>()

        return flavorList
    }

    /**
     * 自动补肠时优先选择同口味中编号最大的烤肠箱，缩短机械臂整体取肠路径。
     */
    fun findSupplyBoxByTaste(listBox: List<KaoPanBox>, tasteCode: String?): KaoPanBox? {
        if (tasteCode.isNullOrBlank()) {
            return null
        }
        return listBox
            .filter { it.tasteCode == tasteCode && it.num > 0 }
            .maxByOrNull { it.positionSn }
    }
}
