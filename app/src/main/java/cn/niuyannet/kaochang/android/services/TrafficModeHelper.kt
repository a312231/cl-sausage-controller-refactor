package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox

/**
 * 并发模式公共工具。
 * 负责烤盘范围筛选，以及按口味从烤肠箱中选择供给来源。
 */
object TrafficModeHelper {

    /**
     * 按 positionSn 升序返回指定范围内的烤盘。
     * 例如：
     * - 低并发前区：1~9
     * - 低并发补热区：13~18
     * - 高并发前区：1~21
     * - 高并发补热区：25~33
     */
    fun getTrafficModePositions(start: Int, end: Int, list: List<KaoPan>): List<KaoPan> {
        return list.filter { it.positionSn in start..end }.sortedBy { it.positionSn }
    }

    /**
     * 自动补肠时，优先从同口味且仍有库存的烤肠箱中选择编号最大的箱体。
     * 这样可以缩短机械臂整体取肠路径。
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
